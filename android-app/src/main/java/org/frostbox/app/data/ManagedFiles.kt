package org.frostbox.app.data

import android.util.AtomicFile
import org.frostbox.core.validateRelativePath
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

internal object ManagedFiles {
    fun noLink(file: File) { check(!Files.isSymbolicLink(file.toPath())) { "Redirected private path: ${file.name}" } }
    fun resolve(home: File,path: String): File {
        noLink(home)
        if(path==".") return home
        validateRelativePath(path)
        var result=home
        for(name in path.split('/')) { result=File(result,name);noLink(result) }
        check(result.canonicalPath.startsWith(home.canonicalPath+File.separator))
        return result
    }
    // AtomicFile does not serialize readers/writers; openRead can remove an in-flight .new.
    @Synchronized fun read(file: File): JSONObject? {
        for(f in listOf(file,File(file.path+".bak"),File(file.path+".new"))) noLink(f)
        return if(file.exists() || File(file.path+".bak").exists()) JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() }) else null
    }
    @Synchronized fun write(file: File,value: JSONObject) {
        for(f in listOf(file,File(file.path+".bak"),File(file.path+".new"))) noLink(f)
        val atomic=AtomicFile(file);val output=atomic.startWrite()
        try { output.write(value.toString().toByteArray());atomic.finishWrite(output) }
        catch(e: Throwable) { atomic.failWrite(output);throw e }
    }
    fun removeTree(file: File) {
        if(!Files.exists(file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        // Never follows Wine Z: or a guest-created symlink.
        Files.walk(file.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }
    suspend fun <T> locked(homes: List<File>,block: suspend ()->T): T {
        val streams=mutableListOf<RandomAccessFile>();val locks=mutableListOf<java.nio.channels.FileLock>()
        try {
            for(home in homes.distinctBy { it.absolutePath }.sortedBy { it.absolutePath }) {
                noLink(home);check(home.isDirectory) { "Missing profile directory" }
                val file=File(home,"wine-manager.lock");noLink(file)
                val stream=RandomAccessFile(file,"rw");streams+=stream
                locks+=stream.channel.tryLock() ?: error("Profile/prefix is active; retry after it stops")
            }
            return block()
        } finally { locks.asReversed().forEach { it.close() };streams.asReversed().forEach { it.close() } }
    }
}
