package org.frostbox.app.display

import android.content.Context
import android.system.Os
import org.frostbox.app.data.ManagedFiles
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Optional runtime view. Verified private objects share guest PE/font data; overlay replacement uses rename.
 * Wine searches its own dll_dir before WINEDLLPATH, so an overlay env var alone is insufficient. */
object DisplayPackage {
    private fun digest(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    suspend fun prepare(context: Context,base: File): File {
        val manifest=context.assets.open("display/manifest.json").use { it.readBytes() }
        val baseManifest=context.assets.open("runtime/wine-probe.json").use { it.readBytes() }
        val parent=File(context.filesDir,"display");ManagedFiles.noLink(parent);check(parent.isDirectory || parent.mkdirs())
        val key=digest(manifest+baseManifest)
        return ManagedFiles.locked(listOf(parent)) {
            val root=File(parent,key);ManagedFiles.noLink(root)
            val metadata=JSONObject(String(manifest))
            check(metadata.optInt("schema")==2 && metadata.optString("freetype")=="2.14.3") { "Display package requires the FreeType overlay (schema 2)" }
            val expected=metadata.getJSONObject("files")
            val names=expected.keys().asSequence().toList()
            check(names.size in 1..32)
            names.forEach { name ->
                require(name.matches(Regex("x86_64/lib/wine/x86_64-(unix|windows)/[a-z0-9.]+|share/wine/fonts/[a-z0-9_]+\\.ttf|licenses/[A-Za-z0-9.-]+\\.txt"))) { "Invalid overlay path" }
                require(expected.getString(name).matches(Regex("[0-9a-f]{64}")))
            }
            check(names.containsAll(listOf("x86_64/lib/wine/x86_64-unix/win32u.so","x86_64/lib/wine/x86_64-unix/libfreetype.so","share/wine/fonts/tahoma.ttf","share/wine/fonts/marlett.ttf"))) { "Incomplete font overlay" }
            fun valid(): Boolean = File(root,"ready").isFile && expected.keys().asSequence().all { name ->
                val file=File(root,name);org.frostbox.app.runtime.ImmutableRuntimeFiles.resolve(context.filesDir,file.toPath(),name)
                file.isFile && file.length()<=16*1024*1024 && digest(file.readBytes())==expected.getString(name)
            }
            if(root.isDirectory && valid()) return@locked root
            // Only this unpublished candidate staging tree may be discarded, never a base/prefix.
            val stage=File(parent,"$key.partial");ManagedFiles.noLink(stage)
            if(stage.exists()) ManagedFiles.removeTree(stage)
            check(stage.mkdirs())
            try {
                Files.walk(base.toPath()).use { paths -> paths.forEach { source ->
                    val rel=base.toPath().relativize(source)
                    if(rel.toString().isNotEmpty()) {
                        org.frostbox.app.runtime.ImmutableRuntimeFiles.resolve(context.filesDir,source,rel.toString())
                        val target=stage.toPath().resolve(rel).toFile()
                        if(Files.isDirectory(source)) check(target.isDirectory || target.mkdirs())
                        else org.frostbox.app.runtime.ImmutableRuntimeFiles.copy(context.filesDir,source,target.toPath(),rel.toString())
                    }
                } }
                var total=0
                names.forEach { name ->
                    val bytes=context.assets.open("display/$name").use { source ->
                        val buffer=java.io.ByteArrayOutputStream()
                        val block=ByteArray(65536)
                        while(true) {
                            val n=source.read(block);if(n<0) break
                            check(buffer.size()+n<=16*1024*1024) { "Oversized display asset" }
                            buffer.write(block,0,n)
                        }
                        buffer.toByteArray()
                    }
                    total+=bytes.size
                    check(bytes.size<=16*1024*1024 && total<=64*1024*1024 && digest(bytes)==expected.getString(name)) { "Display package hash mismatch or oversized overlay" }
                    val dest=File(stage,name)
                    check(dest.parentFile!!.isDirectory || dest.parentFile!!.mkdirs())
                    val temp=File(dest.path+".tmp");temp.writeBytes(bytes);check(temp.renameTo(dest))
                }
                File(stage,"ready").writeText(key)
                if(root.exists()) ManagedFiles.removeTree(root)
                check(stage.renameTo(root))
                root
            } catch(e: Throwable) { ManagedFiles.removeTree(stage);throw e }
        }
    }
}
