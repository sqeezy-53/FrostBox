// SPDX-License-Identifier: MIT
package org.frostbox.app.runtime

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.*
import org.frostbox.app.data.ManagedFiles
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/** Immutable, checksum-verified WoW64 overlay shared by Library and diagnostics.
 * The qualified x64 ZIP and existing prefixes are never modified. */
internal object WineWow64Package {
    private fun sha(f: File)=f.inputStream().use {MessageDigest.getInstance("SHA-256").let {d->
        val b=ByteArray(65536);while(true){val n=it.read(b);if(n<0)break;d.update(b,0,n)}
        d.digest().joinToString(""){"%02x".format(it)}
    }}
    fun manifest(context: Context)=JSONObject(context.assets.open("wine-wow64/manifest.json").bufferedReader().use {it.readText()})
    fun prefixIdentity(context: Context): String {
        val m=manifest(context)
        return MessageDigest.getInstance("SHA-256").digest((m.getString("baseSha256")+m.getString("archiveSha256")).toByteArray()).joinToString(""){"%02x".format(it)}
    }
    suspend fun prepare(context: Context,source: File,cancelled: AtomicBoolean,graphics: Boolean=false): File {
        val coroutine=currentCoroutineContext()
        val m=manifest(context)
        check(m.getInt("schema")==1 && m.getString("wine")=="11.19")
        check(File(source,".package-sha256").readText()==m.getString("baseSha256"))
        val patch=if(graphics)JSONObject(context.assets.open("wine-wow64/vulkan-overlay.json").bufferedReader().use{it.readText()}) else null
        val parent=File(context.filesDir,"horizon-wow64");ManagedFiles.noLink(parent);check(parent.isDirectory||parent.mkdirs())
        return ManagedFiles.locked(listOf(parent)) {
            val archive=m.getString("archiveSha256");require(archive.matches(Regex("[0-9a-f]{64}")))
            val key=MessageDigest.getInstance("SHA-256").digest((archive+source.canonicalPath+(patch?.getString("win32uSha256") ?: "")).toByteArray()).joinToString(""){"%02x".format(it)}
            val root=File(parent,key);ManagedFiles.noLink(root)
            val files=m.getJSONObject("files");val names=files.keys().asSequence().toList()
            require(names.size<=2048 && names.all {it.matches(Regex("x86_64/lib/wine/i386-windows/[A-Za-z0-9_.-]+"))})
            val total=m.getLong("unpackedBytes");require(total in 1..(1024L*1024*1024))
            if(!File(root,"wow64-ready.json").isFile) {
                val stage=File(parent,"$key.partial");ManagedFiles.noLink(stage);check(stage.isDirectory||stage.mkdirs())
                check(StatFs(parent.path).availableBytes>total+2L*1024*1024*1024){"Insufficient space for isolated WoW64 runtime"}
                Files.walk(source.toPath()).use {entries->entries.forEach {entry->
                    coroutine.ensureActive();check(!cancelled.get()){"WoW64 preparation cancelled"}
                    check(!Files.isSymbolicLink(entry));val target=stage.toPath().resolve(source.toPath().relativize(entry))
                    if(Files.isDirectory(entry))Files.createDirectories(target)
                    else if(!Files.exists(target))Files.copy(entry,target)
                }}
                val digest=MessageDigest.getInstance("SHA-256");var bytes=0L;val seen=mutableSetOf<String>()
                java.security.DigestInputStream(context.assets.open("wine-wow64/overlay.zip"),digest).use {input->
                    ZipInputStream(input).use {zip->
                        while(true){val e=zip.nextEntry?:break;require(!e.isDirectory && e.name in names && seen.add(e.name))
                            val f=ManagedFiles.resolve(stage,e.name);check(f.parentFile!!.isDirectory||f.parentFile!!.mkdirs())
                            f.outputStream().use {output->val b=ByteArray(65536);while(true){
                                coroutine.ensureActive();check(!cancelled.get()){"WoW64 extraction cancelled"}
                                val n=zip.read(b);if(n<0)break;bytes+=n;check(bytes<=total);output.write(b,0,n)
                            }}
                            check(f.length()==files.getJSONObject(e.name).getLong("size") && sha(f)==files.getJSONObject(e.name).getString("sha256"))
                        }
                        while(input.read()!=-1){}
                    }
                }
                check(bytes==total && seen.size==names.size && digest.digest().joinToString(""){"%02x".format(it)}==archive)
                if(patch!=null) {
                    val unix=File(stage,"x86_64/lib/wine/x86_64-unix")
                    val module=File(unix,"win32u.so");context.assets.open("wine-wow64/win32u.so").use{input->module.outputStream().use {input.copyTo(it)}}
                    check(sha(module)==patch.getString("win32uSha256"))
                    val wsi=JSONObject(context.assets.open("wine-wsi/manifest.json").bufferedReader().use{it.readText()})
                    val loader=File(unix,"libfrostvk.so");context.assets.open("wine-wsi/libfrostvk.so").use{input->loader.outputStream().use{input.copyTo(it)}}
                    check(sha(loader)==wsi.getJSONObject("files").getString("libfrostvk.so"))
                    ManagedFiles.write(File(stage,"wow64-vulkan-overlay.json"),patch)
                }
                ManagedFiles.write(File(stage,"wow64-ready.json"),m)
                check(!root.exists() && stage.renameTo(root))
            }
            for(name in listOf("ntdll.dll","kernel32.dll","kernelbase.dll")) {
                val path="x86_64/lib/wine/i386-windows/$name";check(sha(File(root,path))==files.getJSONObject(path).getString("sha256"))
            }
            if(patch!=null)check(sha(File(root,"x86_64/lib/wine/x86_64-unix/win32u.so"))==patch.getString("win32uSha256"))
            root
        }
    }
}
