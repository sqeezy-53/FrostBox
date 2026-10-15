package org.frostbox.app.runtime

import org.frostbox.app.data.ManagedFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Android forbids app hardlinks. Share only guest PE/font data via verified private objects.
 * Unix ELF/bin files stay regular: their loader/relocation semantics are unchanged. */
internal object ImmutableRuntimeFiles {
    fun shareable(relative: String): Boolean =
        relative.matches(Regex("x86_64/lib/wine/(x86_64-windows|i386-windows)/[A-Za-z0-9_.-]+\\.(dll|exe)")) ||
        relative.matches(Regex("share/wine/fonts/[A-Za-z0-9_.-]+\\.ttf"))

    fun hash(file: File): String = file.inputStream().use { input ->
        val digest=MessageDigest.getInstance("SHA-256");val block=ByteArray(262144)
        while(true) {val n=input.read(block);if(n<0)break;digest.update(block,0,n)}
        digest.digest().joinToString(""){"%02x".format(it)}
    }
    private fun store(privateRoot: File): File {
        val root=privateRoot.canonicalFile
        ManagedFiles.noLink(root)
        return ManagedFiles.resolve(root,"runtime-objects/v1")
    }
    fun objectFile(privateRoot: File,source: File,sha: String,mode: Int,modified: Long): File {
        require(sha.matches(Regex("[0-9a-f]{64}")))
        require(source.extension in listOf("dll","exe","ttf"))
        return File(store(privateRoot),"$sha-${Integer.toOctalString(mode and 511)}-$modified.${source.extension}")
    }
    fun resolve(privateRoot: File,source: Path,relative: String): Path {
        if(!Files.isSymbolicLink(source))return source
        check(shareable(relative)) {"Unexpected runtime link"}
        val dest=source.parent.toFile().canonicalFile.toPath().resolve(Files.readSymbolicLink(source)).normalize().toFile()
        val objects=store(privateRoot)
        check(dest.parentFile.canonicalFile==objects.canonicalFile && dest.parentFile==objects) {"Runtime link escapes private object store"}
        check(dest.name.matches(Regex("[0-9a-f]{64}-[0-7]{1,3}-[0-9]+\\.(dll|exe|ttf)")))
        ManagedFiles.noLink(dest)
        check(dest.isFile && hash(dest)==dest.name.substringBefore('-')) {"Shared runtime object is missing/corrupt"}
        return dest.toPath()
    }
    fun ensureObject(privateRoot: File,source: Path,relative: String,sha: String,mode: Int,modified: Long): File {
        val bytes=resolve(privateRoot,source,relative).toFile()
        val dest=objectFile(privateRoot,source.toFile(),sha,mode,modified)
        check(dest.parentFile!!.isDirectory||dest.parentFile!!.mkdirs())
        ManagedFiles.noLink(dest)
        if(dest.exists()) {check(hash(dest)==sha);return dest}
        val temp=File(dest.parentFile,".object-${UUID.randomUUID()}")
        try {
            Files.copy(bytes.toPath(),temp.toPath(),StandardCopyOption.COPY_ATTRIBUTES)
            check(hash(temp)==sha)
            check(temp.renameTo(dest))
        } finally { Files.deleteIfExists(temp.toPath()) }
        return dest
    }
    fun replaceWithReference(target: Path,objectFile: File) {
        val temp=target.resolveSibling(".frost-share-${UUID.randomUUID()}")
        try {
            Files.createSymbolicLink(temp,temp.parent.toFile().canonicalFile.toPath().relativize(objectFile.toPath()))
            check(temp.toFile().renameTo(target.toFile())) {"Cannot publish shared runtime reference"}
        } finally {Files.deleteIfExists(temp)}
    }
    fun copy(privateRoot: File,source: Path,target: Path,relative: String) {
        ManagedFiles.noLink(target.toFile());check(!Files.exists(target))
        val resolved=resolve(privateRoot,source,relative)
        if(shareable(relative)) {
            val attrs=Files.readAttributes(resolved,"unix:mode,lastModifiedTime")
            val sha=hash(resolved.toFile())
            val modified=(attrs["lastModifiedTime"] as java.nio.file.attribute.FileTime).toMillis()/1000
            val obj=ensureObject(privateRoot,source,relative,sha,attrs["mode"] as Int,modified)
            replaceWithReference(target,obj)
        } else Files.copy(resolved,target)
    }
}
