package org.frostbox.app.validation

import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsProvider
import android.provider.DocumentsContract.Document as Doc
import android.provider.DocumentsContract.Root
import java.io.File
import org.frostbox.app.data.ManagedFiles

/** Debug-only, read-only SAF provider backed by dedicated QA files, not users' storage. */
class FixtureDocumentsProvider: DocumentsProvider() {
    private val base get()=File(requireNotNull(context).cacheDir,"launcher-qa").apply { mkdirs() }
    override fun onCreate()=true
    private fun file(id: String)=if(id=="root") base else ManagedFiles.resolve(base,id)
    private fun row(cursor: MatrixCursor,id: String) {
        val f=file(id);cursor.newRow().add(Doc.COLUMN_DOCUMENT_ID,id).add(Doc.COLUMN_DISPLAY_NAME,if(id=="root") "FrostBox QA" else f.name)
            .add(Doc.COLUMN_MIME_TYPE,if(f.isDirectory) Doc.MIME_TYPE_DIR else "application/octet-stream")
            .add(Doc.COLUMN_SIZE,if(f.isFile) f.length()+(if(id=="truncated/short.dat") 1 else 0) else null).add(Doc.COLUMN_FLAGS,if(f.isDirectory) Doc.FLAG_DIR_SUPPORTS_CREATE.takeIf { false } ?: 0 else 0)
    }
    override fun queryRoots(projection: Array<out String>?)=MatrixCursor(arrayOf(Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID,Root.COLUMN_TITLE,Root.COLUMN_MIME_TYPES,Root.COLUMN_FLAGS)).apply {
        addRow(arrayOf("qa","root","FrostBox QA","*/*",Root.FLAG_SUPPORTS_IS_CHILD))
    }
    private fun cursor()=MatrixCursor(arrayOf(Doc.COLUMN_DOCUMENT_ID,Doc.COLUMN_DISPLAY_NAME,Doc.COLUMN_MIME_TYPE,Doc.COLUMN_SIZE,Doc.COLUMN_FLAGS))
    override fun queryDocument(documentId: String,projection: Array<out String>?)=cursor().apply { row(this,documentId) }
    override fun queryChildDocuments(parentDocumentId: String,projection: Array<out String>?,sortOrder: String?)=cursor().apply {
        if(parentDocumentId=="bad") {
            addRow(arrayOf("invalid.exe","../escape.exe","application/octet-stream",3,0));return@apply
        }
        for(f in file(parentDocumentId).listFiles()?.sortedBy { it.name } ?: error("Directory missing")) row(this,base.toPath().relativize(f.toPath()).toString())
    }
    override fun isChildDocument(parentDocumentId: String,documentId: String)=file(documentId).canonicalPath.startsWith(file(parentDocumentId).canonicalPath+File.separator)
    override fun openDocument(documentId: String,mode: String,signal: CancellationSignal?): ParcelFileDescriptor {
        require(mode=="r");signal?.throwIfCanceled()
        if(documentId=="slow/large.dat") {
            val pipe=ParcelFileDescriptor.createPipe()
            Thread {
                runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    val buffer=ByteArray(65536)
                    repeat(128) { output.write(buffer);Thread.sleep(8) }
                } }
            }.start()
            return pipe[0]
        }
        return ParcelFileDescriptor.open(file(documentId),ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
