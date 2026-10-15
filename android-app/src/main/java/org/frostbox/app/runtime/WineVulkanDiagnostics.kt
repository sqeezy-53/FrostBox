// SPDX-License-Identifier: MIT
package org.frostbox.app.runtime

import android.content.Context
import android.os.Process
import android.os.StatFs
import kotlinx.coroutines.*
import org.frostbox.app.data.ManagedFiles
import org.frostbox.app.display.DisplayPackage
import org.frostbox.app.display.DisplaySession
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit built-in headless tests only; never changes a qualified runtime or imported profile. */
internal object WineVulkanDiagnostics {
    private fun sha(file: File)=file.inputStream().use { input ->
        val d=MessageDigest.getInstance("SHA-256");val bytes=ByteArray(65536)
        while(true){val n=input.read(bytes);if(n<0)break;d.update(bytes,0,n)}
        d.digest().joinToString(""){"%02x".format(it)}
    }
    internal suspend fun view(context: Context,base: File,wsi: Boolean = false,input: Boolean = false,keyboard: Boolean = false,audio: Boolean = false,audioWow64: Boolean = false): File {
        val asset=if(audio) (if(audioWow64) "wine-audio-wow64" else "wine-audio") else if(keyboard) "wine-keyboard" else if(input) "wine-input" else if(wsi) "wine-wsi" else "wine-vulkan"
        val manifestText=context.assets.open("$asset/manifest.json").bufferedReader().use{it.readText()}
        val manifest=JSONObject(manifestText)
        check(manifest.getInt("schema")==1 && manifest.getInt("driverInterface")==48)
        val files=if(wsi || input || keyboard || audio) manifest.getJSONObject("files") else JSONObject().put("win32u.so",manifest.getString("win32uSha256"))
        val names=files.keys().asSequence().toList()
        require(names.all { it in (if(audio) listOf("wineoss.so") else if(keyboard) listOf("winefrost.so") else if(input) listOf("winebus.so") else listOf("win32u.so","winefrost.so","libfrostvk.so")) && files.getString(it).matches(Regex("[0-9a-f]{64}")) })
        val key=MessageDigest.getInstance("SHA-256").digest((manifestText+base.canonicalPath).toByteArray()).joinToString(""){"%02x".format(it)}
        val parent=File(context.filesDir,asset);ManagedFiles.noLink(parent);check(parent.isDirectory || parent.mkdirs())
        return ManagedFiles.locked(listOf(parent)) {
            val root=File(parent,"$key-v3");ManagedFiles.noLink(root)
            val unix="x86_64/lib/wine/x86_64-unix"
            if(File(root,"ready").isFile && names.all { sha(File(root,"$unix/$it"))==files.getString(it) })return@locked root
            val stage=File(parent,"$key-v3.partial");ManagedFiles.noLink(stage);check(stage.isDirectory || stage.mkdirs())
            Files.walk(base.toPath()).use { entries -> entries.forEach { source ->
                val relative=base.toPath().relativize(source)
                if(relative.toString().isNotEmpty()) {
                    check(!Files.isSymbolicLink(source));val target=stage.toPath().resolve(relative)
                    if(Files.isDirectory(source))Files.createDirectories(target)
                    else if(relative.toString()!="ready" && !Files.exists(target)) {
                        check(StatFs(parent.path).availableBytes>Files.size(source)+32L*1024*1024)
                        val part=target.resolveSibling(target.fileName.toString()+".partial");ManagedFiles.noLink(part.toFile())
                        Files.newInputStream(source).use { input -> Files.newOutputStream(part).use { input.copyTo(it) } }
                        if(Files.isExecutable(source))check(part.toFile().setExecutable(true,true))
                        check(part.toFile().renameTo(target.toFile()));check(target.toFile().setLastModified(Files.getLastModifiedTime(source).toMillis()))
                    }
                }
            } }
            for(name in names) {
                val destination=File(stage,"$unix/$name");ManagedFiles.noLink(destination)
                val temporary=File(stage,"$name.partial");ManagedFiles.noLink(temporary)
                context.assets.open("$asset/$name").use { input -> temporary.outputStream().use { input.copyTo(it) } }
                check(temporary.length()<=16*1024*1024 && sha(temporary)==files.getString(name));check(temporary.setExecutable(true,true));check(temporary.renameTo(destination))
            }
            val ready=File(stage,"ready.partial");ManagedFiles.noLink(ready);ready.writeText(key);check(ready.renameTo(File(stage,"ready")))
            check(!root.exists()){ "Experimental overlay integrity failure; retained for diagnostics" };check(stage.renameTo(root));root
        }
    }
    internal suspend fun dxvkDirectory(context: Context,wow64: Boolean=false): File {
        val asset=if(wow64) "wine-wow64/dxvk-x86" else "dxvk"
        val manifest=JSONObject(context.assets.open("$asset/manifest.json").bufferedReader().use { it.readText() })
        check(manifest.optInt("schema",1)==1 && manifest.getString("version")=="2.7.1")
        val hashes=manifest.getJSONObject("files")
        val parent=File(context.filesDir,if(wow64)"dxvk-x86" else "dxvk");ManagedFiles.noLink(parent);check(parent.isDirectory || parent.mkdirs())
        return ManagedFiles.locked(listOf(parent)) {
            val directory=File(parent,manifest.getString("archiveSha256"));ManagedFiles.noLink(directory)
            check(directory.isDirectory || directory.mkdirs())
            for(name in (if(wow64) listOf("d3d9.dll","d3d11.dll","dxgi.dll") else listOf("d3d11.dll","dxgi.dll","LICENSE"))) {
                val destination=File(directory,name);ManagedFiles.noLink(destination)
                if(destination.isFile) check(sha(destination)==hashes.getString(name)){"DXVK integrity failure: $name"}
                else {
                    val part=File(directory,"$name.partial");ManagedFiles.noLink(part)
                    check(StatFs(parent.path).availableBytes>32L*1024*1024)
                    context.assets.open("$asset/$name").use { input -> part.outputStream().use { input.copyTo(it) } }
                    check(sha(part)==hashes.getString(name));check(part.renameTo(destination))
                }
            }
            directory
        }
    }
    internal suspend fun installDxvk(context: Context,directory: File,wow64: Boolean=false): File {
        val source=dxvkDirectory(context,wow64)
        val names=if(wow64)listOf("d3d9.dll","d3d11.dll","dxgi.dll") else listOf("d3d11.dll","dxgi.dll")
        ManagedFiles.noLink(directory)
        check(StatFs(directory.path).availableBytes>32L*1024*1024){"Insufficient storage for per-application DXVK DLLs"}
        // Wine stops at an existing builtin in system32, so WINEPATH alone is insufficient.
        // Only add to the private imported copy; never overwrite DLLs supplied by the application.
        for(name in names) {
            val target=File(directory,name);ManagedFiles.noLink(target)
            if(target.exists())check(target.isFile && sha(target)==sha(File(source,name))){"Application DLL conflicts with managed DXVK: $name"}
        }
        for(name in names) {
            val target=File(directory,name)
            if(!target.exists()) {
                val part=File(directory,"$name.frostbox-part");ManagedFiles.noLink(part)
                File(source,name).inputStream().use { input -> part.outputStream().use { input.copyTo(it) } }
                check(sha(part)==sha(File(source,name)));check(part.renameTo(target))
            }
        }
        ManagedFiles.write(File(directory,".frostbox-dxvk.json"),JSONObject().put("version","2.7.1").put("source",source.path))
        return source
    }
    suspend fun execute(context: Context,directory: File,experimental: Boolean,cancelled: AtomicBoolean,
                        wsiCapability: Boolean = false, wsiEndpoint: String? = null, d3d11: Boolean = false, update: suspend (JSONObject,Boolean)->Unit) {
        val id=File(context.filesDir,"wine-vulkan-profile-id.txt").readText().trim()
        require(UUID.fromString(id).toString()==id)
        val home=File(context.filesDir,"games/$id");ManagedFiles.noLink(home)
        check(File(home,"fixture-owner.json").isFile){"Headless tests require a diagnostic-owned profile"}
        val prefix=File(home,"prefix");ManagedFiles.noLink(prefix)
        val marker=JSONObject(File(prefix,".frostbox-prefix.json").readText())
        check(marker.getBoolean("committed") && marker.getString("profileId")==id)
        val work=File(context.filesDir,if(d3d11) "d3d11-tests/${UUID.randomUUID()}" else "wine-vulkan-tests");ManagedFiles.noLink(work);check(work.isDirectory || work.mkdirs())
        val fixture=if(d3d11) "d3d11-cube-x64.exe" else if(wsiEndpoint!=null) "windows-vulkan-triangle-x64.exe" else if(wsiCapability)"vulkan-wsi-capability-x64.exe" else "vulkan-compute-x64.exe"
        val exe=File(work,fixture);ManagedFiles.noLink(exe)
        context.assets.open("runtime/$fixture").use { input -> exe.outputStream().use{input.copyTo(it)} }
        val metadata=JSONObject(context.assets.open("runtime/$fixture.json").bufferedReader().use{it.readText()})
        check(sha(exe)==metadata.getString("sha256"))
        if(d3d11){
            val packageInfo=JSONObject(context.assets.open("dxvk/manifest.json").bufferedReader().use { it.readText() })
            val names=packageInfo.getJSONObject("files")
            for(name in listOf("d3d11.dll","dxgi.dll")) {
                val target=File(work,name);ManagedFiles.noLink(target)
                context.assets.open("dxvk/$name").use { input -> target.outputStream().use { input.copyTo(it) } }
                check(sha(target)==names.getString(name))
            }
        }
        val base=DisplayPackage.prepare(context,File(directory,"wine-userspace"))
        val root=if(experimental)view(context,base,wsiEndpoint!=null) else base
        ManagedFiles.locked(listOf(home)) {
            if(wsiEndpoint!=null) {
                // The fixture may repair impossible WindowMetrics left by the older fontless
                // console runtime. This path is restricted to fixture-owner.json profiles.
                val registry=File(prefix,"user.reg");ManagedFiles.noLink(registry)
                val backup=File(prefix,"user.reg.metrics-backup-${UUID.randomUUID()}")
                Files.copy(registry.toPath(),backup.toPath())
            }
            val bridge=NativeBridge();var handle=0L
            val display=if(wsiEndpoint!=null) DisplaySession() else null
            val displayEndpoint=display?.start()
            suspend fun run(op: String): JSONObject {
                handle=if(wsiEndpoint!=null && op=="launch") bridge.startWineWsiDiagnostic(context.applicationInfo.nativeLibraryDir,root.path,prefix.path,work.path,wsiEndpoint,checkNotNull(displayEndpoint),d3d11)
                    else bridge.startVulkanWineDiagnostic(context.applicationInfo.nativeLibraryDir,root.path,prefix.path,work.path,op)
                try {
                    var stopped=false
                    while(true) {
                        if(op in listOf("launch","wsi-capability") && cancelled.get() && !stopped){bridge.cancelDiagnostic(handle);stopped=true}
                        val result=JSONObject(bridge.pollDiagnostic(handle))
                        if(!result.getBoolean("running"))return result
                        if(op in listOf("launch","wsi-capability"))update(result.put("runtimeUid",Process.myUid()).put("runtimePid",Process.myPid()),false)
                        delay(100)
                    }
                } finally {bridge.releaseDiagnostic(handle);handle=0}
            }
            var result: JSONObject?=null
            try {result=run(if(wsiCapability)"wsi-capability" else "launch")}
            finally {withContext(NonCancellable) {
                val shutdown=JSONObject().put("kill",run("kill")).put("wait",run("wait"))
                result?.put("wineShutdown",shutdown)
                display?.dispose()
            } }
            if(d3d11) {
                val logs=JSONObject()
                for(name in listOf("d3d11-cube-x64_d3d11.log","d3d11-cube-x64_dxgi.log")) {
                    val file=File(work,name);ManagedFiles.noLink(file)
                    if(file.isFile)logs.put(name,file.inputStream().use { it.readLogPrefix(131072) })
                }
                result?.put("dxvkLogs",logs)?.put("dxvkVersion","2.7.1")
            }
            update(checkNotNull(result).put("runtimeUid",Process.myUid()).put("runtimePid",Process.myPid())
                .put("androidApi",android.os.Build.VERSION.SDK_INT).put("mode",if(d3d11)18 else if(wsiEndpoint!=null)17 else if(wsiCapability)16 else if(experimental)15 else 14)
                .put("wineVulkanOverlay",experimental).put("bootstrap",false).put("prefixId",id),true)
        }
    }
}
