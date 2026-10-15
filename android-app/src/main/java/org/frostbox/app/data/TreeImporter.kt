package org.frostbox.app.data

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract as D
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import org.frostbox.core.validateRelativePath
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** Read-only SAF traversal; finite budgets, cancellable streaming, no provider name used as a host path unchecked. */
class TreeImporter(private val context: Context) {
    data class Entry(val uri: Uri,val relative: String,val size: Long?)
    data class Progress(val file: String="",val copiedBytes: Long=0,val totalBytes: Long?=null,val filesDone: Int=0,val filesTotal: Int=0)
    private val maxBytes=16L*1024*1024*1024
    private val reserve=64L*1024*1024
    suspend fun copy(tree: Uri,stage: File,progress: (Progress)->Unit): List<Entry> {
        require(D.isTreeUri(tree)) { "Choose a document tree" }
        val entries=mutableListOf<Entry>();val seen=mutableSetOf<String>();val ids=mutableSetOf<String>()
        suspend fun scan(id: String,parent: String,depth: Int) {
            coroutineContext.ensureActive();require(depth<=32 && ids.size<10000 && ids.add(id)) { "Tree depth/count/cycle limit" }
            val children=D.buildChildDocumentsUriUsingTree(tree,id)
            val cursor=context.contentResolver.query(children,arrayOf(D.Document.COLUMN_DOCUMENT_ID,D.Document.COLUMN_DISPLAY_NAME,D.Document.COLUMN_MIME_TYPE,D.Document.COLUMN_SIZE),null,null,null)
                ?: error("Provider cannot enumerate folder")
            cursor.use {
                while(it.moveToNext()) {
                    coroutineContext.ensureActive()
                    val child=it.getString(0) ?: error("Missing document ID")
                    val name=it.getString(1) ?: error("Missing filename")
                    require('/' !in name) { "Provider filename contains a separator" }
                    val relative=if(parent.isEmpty()) name else "$parent/$name"
                    validateRelativePath(relative)
                    require(!name.startsWith(".frostbox-")) { "Reserved import metadata name" }
                    val normalized=java.text.Normalizer.normalize(relative,java.text.Normalizer.Form.NFC).lowercase(Locale.ROOT)
                    require(seen.add(normalized) && seen.size<=10000) { "Case-insensitive filename collision or too many entries" }
                    if(it.getString(2)==D.Document.MIME_TYPE_DIR) {
                        check(ManagedFiles.resolve(stage,relative).mkdirs());scan(child,relative,depth+1)
                    } else entries+=Entry(D.buildDocumentUriUsingTree(tree,child),relative,if(it.isNull(3)) null else it.getLong(3).takeIf { n -> n>=0 })
                }
            }
        }
        scan(D.getTreeDocumentId(tree),"",0)
        require(entries.isNotEmpty()) { "Selected folder is empty" }
        val declared=entries.fold(0L) { sum,entry ->
            val size=entry.size ?: 0L
            require(size<=maxBytes-sum) { "Folder exceeds 16 GiB budget" };sum+size
        }
        require(declared in 0..maxBytes) { "Folder exceeds 16 GiB budget" }
        require(StatFs(stage.path).availableBytes>=declared+reserve) { "Not enough free space for folder plus 64 MiB reserve" }
        val total=if(entries.all { it.size!=null }) declared else null
        var bytes=0L
        entries.forEachIndexed { index,entry ->
            coroutineContext.ensureActive()
            val destination=ManagedFiles.resolve(stage,entry.relative)
            check(!destination.exists()) { "Destination conflict" }
            context.contentResolver.openInputStream(entry.uri)?.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer=ByteArray(64*1024);var fileBytes=0L;var lastProgress=0L
                    while(true) {
                        coroutineContext.ensureActive()
                        val n=input.read(buffer);if(n<0) break
                        bytes+=n;fileBytes+=n
                        require(bytes<=maxBytes) { "Folder exceeds 16 GiB budget" }
                        if(bytes-lastProgress>=1024*1024 || fileBytes==n.toLong()) {
                            check(StatFs(stage.path).availableBytes>=reserve+n) { "Storage reserve exhausted" }
                            progress(Progress(entry.relative,bytes,total,index,entries.size));lastProgress=bytes
                        }
                        output.write(buffer,0,n)
                    }
                    if(entry.size!=null) check(fileBytes==entry.size) { "Provider file changed or truncated: ${entry.relative}" }
                    output.fd.sync()
                }
            } ?: error("Provider cannot read ${entry.relative}")
            progress(Progress(entry.relative,bytes,total,index+1,entries.size))
        }
        return entries
    }
}
