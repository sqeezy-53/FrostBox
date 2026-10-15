package org.frostbox.app.data

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

data class StorageInventory(val bytes: Map<String,Long>,val availableBytes: Long,val unreadable: Int)
/** Read-only inventory; does not follow symlinks and counts each private regular file once. */
suspend fun inspectStorage(context: Context): StorageInventory=withContext(Dispatchers.IO) {
    val totals=linkedMapOf<String,Long>();var errors=0
    val root=context.filesDir.toPath()
    Files.walkFileTree(root,object: SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path,attrs: BasicFileAttributes): FileVisitResult {
            if(attrs.isRegularFile) {
                val rel=root.relativize(file).toString();val name=file.fileName.toString()
                val kind=when {
                    rel.contains("/prefix/")->"prefixes"
                    name.startsWith("cover-")->"covers"
                    name.endsWith(".dxvk-cache") || rel.contains("/dxvk-cache/")->"shader"
                    name.endsWith(".log") || rel.contains("/logs/") || name=="run.json"->"logs"
                    rel.startsWith("games/")->"games"
                    rel.startsWith("wine") || rel.startsWith("runtime") || rel.startsWith("dxvk/")->"runtime"
                    else->"other"
                }
                totals[kind]=(totals[kind] ?: 0)+attrs.size()
            }
            return FileVisitResult.CONTINUE
        }
        override fun visitFileFailed(file: Path,exc: java.io.IOException): FileVisitResult {errors++;return FileVisitResult.CONTINUE}
    })
    StorageInventory(totals,StatFs(context.filesDir.path).availableBytes,errors)
}
