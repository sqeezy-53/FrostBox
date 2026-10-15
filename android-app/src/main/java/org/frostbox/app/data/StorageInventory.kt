package org.frostbox.app.data

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import android.system.Os

data class StorageInventory(val bytes: Map<String,Long>,val availableBytes: Long,val unreadable: Int,val logicalBytes: Long=0)
/** Allocated blocks including directories, each inode once. Never follows symlinks. */
suspend fun inspectStorage(context: Context): StorageInventory=withContext(Dispatchers.IO) {
    val totals=linkedMapOf<String,Long>();var errors=0;var logical=0L
    val seen=mutableSetOf<Pair<Long,Long>>()
    val root=context.filesDir.toPath()
    fun count(file: Path) {
        try {
            val stat=Os.lstat(file.toString())
            if(!seen.add(stat.st_dev to stat.st_ino))return
            val rel=root.relativize(file).toString();val name=file.fileName.toString()
            val kind=when {
                rel.contains("/prefix/") || rel.endsWith("/prefix") || rel.contains("/wine-prefix/")->"prefixes"
                name.startsWith("cover-")->"covers"
                name.endsWith(".dxvk-cache") || rel.contains("/dxvk-cache/")->"shader"
                name.endsWith(".log") || rel.contains("/logs/") || name=="run.json" || rel.contains("evidence") || rel.startsWith("storage-sharing/")->"logs"
                rel.startsWith("games/")->"games"
                rel.contains(".partial/") || rel.endsWith(".partial")->"temporary"
                rel.startsWith("wine") || rel.startsWith("runtime") || rel.startsWith("dxvk") || rel.startsWith("display/") || rel.startsWith("horizon-wow64/")->"runtime"
                else->"other"
            }
            totals[kind]=(totals[kind] ?: 0)+stat.st_blocks*512
        } catch(e: Exception) { errors++ }
    }
    Files.walkFileTree(root,object: SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path,attrs: BasicFileAttributes): FileVisitResult {count(dir);return FileVisitResult.CONTINUE}
        override fun visitFile(file: Path,attrs: BasicFileAttributes): FileVisitResult {
            if(attrs.isRegularFile)logical+=attrs.size()
            count(file)
            return FileVisitResult.CONTINUE
        }
        override fun visitFileFailed(file: Path,exc: java.io.IOException): FileVisitResult {errors++;return FileVisitResult.CONTINUE}
    })
    StorageInventory(totals,StatFs(context.filesDir.path).availableBytes,errors,logical)
}
