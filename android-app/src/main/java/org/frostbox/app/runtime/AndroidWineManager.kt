package org.frostbox.app.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.StatFs
import org.frostbox.app.data.ManagedFiles
import org.frostbox.core.parseLaunchEnvironment
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import kotlinx.coroutines.*
import org.frostbox.core.PeInspector
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Storage/lifecycle orchestration only. All native execution uses the existing JNI Process registry. */
internal class AndroidWineManager(private val context: Context, private var runtime: File,
    private val cancelled: AtomicBoolean, private val update: suspend (JSONObject) -> Unit) {
    private var displayEndpoint=""
    private var displayRoot=""
    private var wsiEndpoint=""
    private var dxvkDirectory=""
    private val bridge = NativeBridge()
    private var wow64=false
    private var graphicsApi="d3d11"
    private val packageInfo = JSONObject(context.assets.open("runtime/wine-probe.json").bufferedReader().use { it.readText() })
    private val critical = listOf("ntdll.dll", "kernel32.dll", "kernelbase.dll", "wineboot.exe")
    private lateinit var home: File
    private lateinit var prefixHome: File
    private var prefixId=""
    private lateinit var journal: File
    private var activeHandle = 0L
    private var activePrefix: File? = null
    private var id = ""
    private fun sha(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if(n<0) break; digest.update(buffer,0,n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun noRedirect(file: File) {
        check(!Files.isSymbolicLink(file.toPath())) { "Redirected managed file: ${file.name}" }
    }
    private fun read(file: File): JSONObject? {
        noRedirect(file);noRedirect(File(file.path+".bak"));noRedirect(File(file.path+".new"))
        return if (!file.exists() && !File(file.path+".bak").exists()) null
            else JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
    }
    private fun atomic(file: File, value: JSONObject) {
        noRedirect(file);noRedirect(File(file.path+".bak"));noRedirect(File(file.path+".new"))
        val a=AtomicFile(file); val stream=a.startWrite()
        try { stream.write(value.toString().toByteArray()); a.finishWrite(stream) }
        catch (e: Throwable) { a.failWrite(stream); throw e }
    }
    private fun envelope() = JSONObject().put("profileId",id).put("runtimeUid",Process.myUid())
        .put("runtimePid",Process.myPid()).put("androidApi",android.os.Build.VERSION.SDK_INT)
        .put("prefixId",prefixId).put("wow64",wow64).put("runtimeVariant",if(wow64)"wow64-experimental" else "qualified-x64").put("graphicsApi",graphicsApi).put("bootstrap",false).put("wineVersion",packageInfo.getString("version"))
    private fun log(value: JSONObject) {
        val file=File(home,"runtime.ndjson");noRedirect(file);noRedirect(File(home,"runtime.previous.ndjson"))
        if(file.length()>2*1024*1024) file.renameTo(File(home,"runtime.previous.ndjson"))
        file.appendText(value.toString()+"\n")
    }
    private fun state(phase: String, prefix: File, extra: JSONObject?=null) {
        val value=envelope().put("phase",phase).put("prefix",prefix.name).put("timestamp",System.currentTimeMillis())
        if(extra!=null) value.put("process",extra)
        atomic(journal,value)
        atomic(File(prefixHome,"prefix-runtime-journal.json"),value)
    }
    private fun prefixPath(name: String): File {
        require(name=="prefix" || name=="prefix.initializing") { "Invalid managed prefix name" }
        val file=File(prefixHome,name)
        check(!Files.isSymbolicLink(file.toPath())) { "Redirected prefix forbidden" }
        return file
    }
    suspend fun execute(request: Bundle): JSONObject {
        displayEndpoint=request.getString("displayEndpoint","")
        id=request.getString("profileId") ?: error("Missing profile ID")
        require(UUID.fromString(id).toString()==id) { "Canonical profile UUID required" }
        val root=File(context.filesDir,"games")
        check(!Files.isSymbolicLink(root.toPath()))
        check(root.isDirectory || root.mkdirs())
        home=File(root,id); check(!Files.isSymbolicLink(home.toPath()))
        check(home.isDirectory || home.mkdirs())
        journal=File(home,"runtime-journal.json")
        val launchConfig=read(File(home,"launch.json"))
        val selectedExe=ManagedFiles.resolve(home,launchConfig?.optString("executable","game.exe") ?: "game.exe")
        val architecture=if(selectedExe.isFile) PeInspector.inspect(selectedExe) else "x86-64"
        check(architecture in setOf("x86","x86-64")) {"Unsupported PE architecture"}
        wow64=architecture=="x86"
        val variant=launchConfig?.optString("runtimeVariant","auto") ?: "auto"
        check(variant=="auto" || variant==if(wow64)"wow64-experimental" else "qualified-x64") {"Wine runtime does not match PE architecture"}
        graphicsApi=launchConfig?.optString("graphicsApi","auto") ?: "auto"
        if(graphicsApi=="auto") graphicsApi=if(wow64)"d3d9" else "d3d11"
        check(graphicsApi in setOf("d3d9","d3d11") && (wow64 || graphicsApi=="d3d11")) {"Graphics API not bundled for this architecture"}
        if(displayEndpoint.isNotEmpty()) {
            require(displayEndpoint.matches(Regex("frostbox-${Process.myUid()}-[a-f0-9-]{36}")))
            displayRoot=org.frostbox.app.display.DisplayPackage.prepare(context,runtime).path
        }
        wsiEndpoint=request.getString("wsiEndpoint", "")
        if(wsiEndpoint.isNotEmpty()) {
            require(displayEndpoint.isNotEmpty() && wsiEndpoint.matches(Regex("@frost-guest-[a-f0-9-]{36}")))
            check(launchConfig?.optString("graphicsBackend")=="DXVK"){"Profile did not select DXVK"}
            displayRoot=WineVulkanDiagnostics.view(context,File(displayRoot),true).path
            dxvkDirectory=WineVulkanDiagnostics.dxvkDirectory(context,wow64).path
        }
        // Apply the qualified GDI/WSI view BEFORE the WoW64 allocation patch.
        // Applying WSI again afterwards would silently replace the private patched win32u.
        if(wow64) {
            runtime=WineWow64Package.prepare(context,if(displayRoot.isEmpty())runtime else File(displayRoot),cancelled,wsiEndpoint.isNotEmpty())
            packageInfo.put("sha256",WineWow64Package.prefixIdentity(context))
            if(displayRoot.isNotEmpty())displayRoot=runtime.path
        }
        if(request.getBoolean("controllerKeyboard",false) && wsiEndpoint.isEmpty()) {
            check(displayRoot.isNotEmpty()) {"Keyboard/mouse controller requires a display"}
            displayRoot=WineVulkanDiagnostics.view(context,File(displayRoot),keyboard=true).path
        }
        if(request.getBoolean("controllerBridge",false)) {
            displayRoot=WineVulkanDiagnostics.view(context,if(displayRoot.isEmpty()) runtime else File(displayRoot),input=true).path
        }
        if(request.getBoolean("audioBridge",false)) displayRoot=WineVulkanDiagnostics.view(context,if(displayRoot.isEmpty())runtime else File(displayRoot),audio=true,audioWow64=wow64).path
        val selected=read(File(home,"launch.json"))?.optString("winePrefixId",id) ?: id
        require(UUID.fromString(selected).toString()==selected)
        prefixId=selected;prefixHome=File(root,prefixId);noRedirect(prefixHome)
        check(prefixHome.isDirectory) { "Selected prefix owner is missing" }
        val previous=read(journal)?.optString("prefixId",id) ?: id
        require(UUID.fromString(previous).toString()==previous)
        val priorHome=File(root,previous);noRedirect(priorHome)
        return ManagedFiles.locked(listOf(home,prefixHome,priorHome)) {
                try {
                    val op=request.getString("operation") ?: error("Missing Wine operation")
                    check((read(File(home,"launch.json"))?.optString("winePrefixId",id) ?: id)==prefixId) { "Launch configuration changed; retry" }
                    val own=read(journal)
                    val lease=read(File(prefixHome,"prefix-runtime-journal.json"))
                    val prior=if(own!=null && own.optString("phase") !in listOf("IDLE","READY","DELETED")) own else lease?.takeIf { it.optString("phase") !in listOf("IDLE","READY","DELETED") }
                    if(op!="recover" && prior!=null && prior.optString("phase") !in listOf("IDLE","READY","DELETED")) recoverRuntime(prior)
                    val result=when(op) {
                        "fixture" -> prepareFixture(request.getBoolean("sleep"))
                        "initialize" -> initializePrefix()
                        "validate" -> validatePrefix(prefixPath("prefix"))
                        "launch" -> launchWindowsExecutable()
                        "recover" -> { recoverRuntime(prior ?: read(journal)); validatePrefix(prefixPath("prefix")) }
                        "configure" -> configure(request)
                        "terminate" -> {
                            val prefix=prefixPath("prefix")
                            check(read(File(prefix,".frostbox-prefix.json"))?.optString("profileId")==prefixId) { "Refuse unmanaged server control" }
                            val shutdown=terminateWineServer(prefix);state("IDLE",prefix)
                            envelope().put("shutdown",shutdown).put("running",false)
                        }
                        "delete" -> deletePrefix(request.getString("confirmation"))
                        "status" -> envelope().put("journal",read(journal)).put("running",false)
                        "logs" -> envelope().put("lastRecovery",read(File(home,"last-recovery.json"))).put("logs",File(home,"runtime.ndjson").takeIf { it.isFile }?.readText()?.takeLast(32768) ?: "")
                        else -> error("Unknown Wine operation")
                    }
                    result.put("operation",op)
                    log(result); result
                } finally {
                    withContext(NonCancellable) {
                        if(activeHandle!=0L) { bridge.releaseDiagnostic(activeHandle); activeHandle=0L }
                        activePrefix?.let { runCatching { terminateWineServer(it) }.onFailure { log(envelope().put("cleanupError",it.toString())) } }
                        activePrefix=null
                    }
                }
        }
    }
    private fun prepareFixture(sleep: Boolean): JSONObject {
        val owner=File(home,"fixture-owner.json")
        // Refuse to overwrite any imported/user EXE. This operation is an explicit diagnostic request.
        check(owner.isFile || !File(home,"game.exe").exists()) { "Profile contains a user EXE" }
        val source=if(sleep) File(home,"sleep-fixture.part").also { file ->
            context.assets.open("runtime/sleep-x64.exe").use { input -> file.outputStream().use { input.copyTo(it) } }
        } else File(runtime,"console-x64.exe")
        val expected=if(sleep) JSONObject(context.assets.open("runtime/sleep-fixture.json").bufferedReader().use { it.readText() }).getString("sha256") else packageInfo.getString("fixtureSha256")
        check(sha(source)==expected)
        source.copyTo(File(home,"game.exe"),overwrite=true)
        if(sleep) check(source.delete())
        atomic(owner,envelope().put("fixture",true))
        atomic(File(home,"launch.json"),JSONObject().put("schema",1).put("exeSha256",expected)
            .put("arguments",org.json.JSONArray()).put("workingDirectory","."))
        return envelope().put("configured",true).put("running",false)
    }
    private fun configure(request: Bundle): JSONObject {
        val exe=File(home,"game.exe")
        check(PeInspector.inspect(exe) in setOf("x86","x86-64")) { "Unsupported PE architecture" }
        val digest=sha(exe);check(digest==request.getString("exeSha256")) { "Imported EXE digest mismatch" }
        val args=request.getStringArrayList("arguments") ?: arrayListOf()
        require(args.size<=64 && args.sumOf { it.length }<=8192 && args.none { '\u0000' in it }) { "Invalid arguments" }
        atomic(File(home,"launch.json"),JSONObject().put("schema",1).put("exeSha256",digest)
            .put("arguments",org.json.JSONArray(args)).put("workingDirectory","."))
        return envelope().put("configured",true)
    }
    private suspend fun initializePrefix(): JSONObject {
        val prefix=prefixPath("prefix")
        if(prefix.exists()) {
            val validation=validatePrefix(prefix)
            check(validation.getBoolean("valid")) { "Existing prefix requires repair/migration: $validation" }
            return validation.put("reused",true)
        }
        val stage=prefixPath("prefix.initializing")
        val marker=File(stage,".frostbox-prefix.json")
        check(stage.isDirectory || stage.mkdirs())
        val old=read(marker)
        if(old!=null) check(old.optString("profileId")==prefixId && old.optString("wineVersion")==packageInfo.getString("version") && old.optString("runtimeSha256")==packageInfo.getString("sha256")) { "Staged prefix needs version migration" }
        atomic(marker,envelope().put("profileId",prefixId).put("schema",1).put("runtimeSha256",packageInfo.getString("sha256")).put("committed",false))
        activePrefix=stage;state("PROVISIONING",stage)
        update(envelope().put("phase","PROVISIONING").put("running",true))
        for(path in listOf("drive_c","drive_c/windows","drive_c/windows/system32","dosdevices")) noRedirect(File(stage,path))
        val systems=if(wow64)listOf("x86_64" to "system32","i386" to "syswow64") else listOf("x86_64" to "system32")
        for((arch,systemName) in systems) {
        val system=File(stage,"drive_c/windows/$systemName");noRedirect(system);check(system.isDirectory || system.mkdirs())
        val dlls=File(runtime,"x86_64/lib/wine/$arch-windows")
        val sources=dlls.listFiles()?.sortedBy { it.name } ?: error("Missing Wine system files")
        val missing=sources.filter { it.isFile && !File(system,it.name).exists() }.sumOf { it.length() }
        check(StatFs(stage.path).availableBytes>=missing+64L*1024*1024) { "Not enough storage for Wine prefix" }
        for(source in sources) {
            currentCoroutineContext().ensureActive();check(!cancelled.get()) { "Prefix initialization cancelled" }
            if(!source.isFile) continue
            val destination=File(system,source.name)
            if(!destination.exists()) {
                val part=File(system,source.name+".frostbox-part");noRedirect(part)
                source.inputStream().use { input -> FileOutputStream(part).use { output -> input.copyTo(output);output.fd.sync() } }
                check(part.renameTo(destination))
            }
        }
        }
        val drives=File(stage,"dosdevices");check(drives.isDirectory || drives.mkdirs())
        if(!Files.exists(File(drives,"c:").toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)) Os.symlink("../drive_c",File(drives,"c:").path)
        if(!Files.exists(File(drives,"z:").toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)) Os.symlink("/",File(drives,"z:").path)
        val initialized=runProcess("initialize",stage)
        val cleanup=terminateWineServer(stage)
        check(success(initialized,0)) { "wineboot failed: $initialized" }
        check(cleanup.getJSONObject("wait").getInt("exitCode")==0) { "Wine server did not stop" }
        val validation=validatePrefix(stage,requireCommitted=false)
        check(validation.getBoolean("valid")) { "Incomplete Wine prefix: $validation" }
        atomic(marker,envelope().put("profileId",prefixId).put("schema",1).put("runtimeSha256",packageInfo.getString("sha256")).put("committed",true))
        check(stage.renameTo(prefix)) { "Cannot atomically publish prefix" }
        activePrefix=null;state("READY",prefix)
        return envelope().put("valid",true).put("reused",false).put("wineboot",initialized).put("shutdown",cleanup)
    }
    private fun validatePrefix(prefix: File, requireCommitted: Boolean=true): JSONObject {
        val failures=org.json.JSONArray();val marker=read(File(prefix,".frostbox-prefix.json"))
        if(marker==null || marker.optInt("schema")!=1) failures.put("Missing/unsupported prefix owner")
        if(marker?.optString("profileId")!=prefixId) failures.put("Prefix owner mismatch")
        if(marker?.optString("wineVersion")!=packageInfo.getString("version")) failures.put("Wine version mismatch")
        if(marker?.optString("runtimeSha256")!=packageInfo.getString("sha256")) failures.put("Wine package version mismatch")
        if(requireCommitted && marker?.optBoolean("committed")!=true) failures.put("Initialization not committed")
        for(name in listOf("system.reg","user.reg","userdef.reg")) {
            val file=File(prefix,name)
            if(!file.isFile || Files.isSymbolicLink(file.toPath()) || !file.inputStream().bufferedReader().use { it.readLine() ?: "" }.startsWith("WINE REGISTRY Version 2")) failures.put("Invalid registry $name")
        }
        for(path in listOf("drive_c","drive_c/windows","drive_c/windows/system32","dosdevices")) {
            if(Files.isSymbolicLink(File(prefix,path).toPath())) failures.put("Redirected prefix directory: $path")
        }
        if(failures.length()>0) return envelope().put("valid",false).put("failures",failures).put("running",false)
        val system=File(prefix,"drive_c/windows/system32")
        for(name in critical) {
            val file=File(system,name);val source=File(runtime,"x86_64/lib/wine/x86_64-windows/$name")
            if(!file.isFile || Files.isSymbolicLink(file.toPath()) || sha(file)!=sha(source)) failures.put("System file integrity: $name")
        }
        if(wow64) {
            val system32=File(prefix,"drive_c/windows/syswow64");noRedirect(system32)
            for(name in critical.filter {it!="wineboot.exe"}) {
                val f=File(system32,name);val source=File(runtime,"x86_64/lib/wine/i386-windows/$name")
                if(!f.isFile || Files.isSymbolicLink(f.toPath()) || sha(f)!=sha(source))failures.put("WoW64 system file integrity: $name")
            }
        }
        val c=File(prefix,"dosdevices/c:")
        if(!Files.isSymbolicLink(c.toPath()) || c.canonicalFile!=File(prefix,"drive_c").canonicalFile) failures.put("Invalid C drive")
        return envelope().put("valid",failures.length()==0).put("failures",failures).put("prefix",prefix.path).put("running",false)
    }
    private suspend fun launchWindowsExecutable(): JSONObject {
        val prefix=prefixPath("prefix")
        initializePrefix()
        val config=read(File(home,"launch.json")) ?: error("Explicit launch configuration required")
        val exe=ManagedFiles.resolve(home,config.optString("executable","game.exe"))
        check(!Files.isSymbolicLink(exe.toPath()) && PeInspector.inspect(exe)==(if(wow64)"x86" else "x86-64") && sha(exe)==config.getString("exeSha256")) { "EXE identity changed" }
        check(config.optInt("schema")==1)
        val work=ManagedFiles.resolve(home,config.getString("workingDirectory"));check(work.isDirectory) { "Working directory missing" }
        val environment=config.optJSONObject("environment") ?: JSONObject()
        val envText=environment.keys().asSequence().joinToString("\n") { "$it=${environment.getString(it)}" }
        val env=parseLaunchEnvironment(envText)
        val array=config.getJSONArray("arguments");require(array.length()<=64)
        val args=Array(array.length()) { array.getString(it) }
        require(args.sumOf { it.length }<=8192 && args.none { '\u0000' in it })
        if(wsiEndpoint.isNotEmpty())dxvkDirectory=WineVulkanDiagnostics.installDxvk(context,exe.parentFile!!,wow64).path
        val managedDxvk=File(exe.parentFile,".frostbox-dxvk.json").isFile
        val launchEnv=if(wsiEndpoint.isEmpty() && managedDxvk)env+("WINEDLLOVERRIDES" to (env["WINEDLLOVERRIDES"]?.plus(";") ?: "")+"d3d11,dxgi=b") else env
        activePrefix=prefix
        val result=runProcess("launch",prefix,exe,args,work=work,environment=launchEnv)
        val cleanup=terminateWineServer(prefix)
        if(wsiEndpoint.isNotEmpty()) {
            val logs=JSONObject()
            work.listFiles()?.filter { it.name.endsWith("_d3d9.log") || it.name.endsWith("_d3d11.log") || it.name.endsWith("_dxgi.log") }?.take(4)?.forEach { file ->
                noRedirect(file);logs.put(file.name,file.inputStream().use { it.readLogPrefix(131072) })
            }
            result.put("graphicsBackend","DXVK").put("dxvkVersion","2.7.1").put("dxvkLogs",logs)
        }
        result.put("wineShutdown",cleanup).put("prefixValidation",validatePrefix(prefix))
        if(result.getJSONObject("prefixValidation").getBoolean("valid")) state("IDLE",prefix) else state("INVALID",prefix)
        activePrefix=null;return result
    }
    private fun success(value: JSONObject, exit: Int) = value.optInt("exitCode",-1)==exit && value.optInt("signal")==0 &&
        !value.optBoolean("timedOut") && !value.optBoolean("cancelled") && value.optInt("execErrno")==0 && value.optString("error").isEmpty()
    private fun startTime(pid: Int): String? = runCatching {
        File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ')[19]
    }.getOrNull()
    private suspend fun runProcess(op: String,prefix: File,exe: File=File(home,"game.exe"),args: Array<String> = emptyArray(),cleanup: Boolean=false,work: File=home,environment: Map<String,String> = emptyMap()): JSONObject {
        val nativeOp=if(wow64 && wsiEndpoint.isNotEmpty()) when(op) {
            "initialize"->"initialize-wow64-graphics"
            "launch"->if(graphicsApi=="d3d9")"launch-wow64-d3d9" else "launch-wow64-d3d11"
            else->op
        } else op
        val handle=bridge.startWineCommand(context.applicationInfo.nativeLibraryDir,if(displayRoot.isNotEmpty()) displayRoot else runtime.path,prefix.path,work.path,nativeOp,exe.path,args,environment.keys.toTypedArray(),environment.values.toTypedArray(),if(op=="launch") displayEndpoint else "",displayRoot,if(op=="launch") wsiEndpoint else "",if(op=="launch") dxvkDirectory else "")
        activeHandle=handle
        var recorded=false;var sentStop=false
        try {
            while(true) {
                currentCoroutineContext().ensureActive()
                if(!cleanup && cancelled.get() && !sentStop) { bridge.cancelDiagnostic(handle);sentStop=true }
                val value=JSONObject(bridge.pollDiagnostic(handle))
                for(key in listOf("runtimeUid","runtimePid","androidApi","profileId","prefixId","bootstrap","wineVersion","wow64","runtimeVariant","graphicsApi")) value.put(key,envelope().get(key))
                value.put("phase",op)
                if(!cleanup && !recorded && value.getBoolean("running")) {
                    value.put("startTime",startTime(value.getInt("pid")))
                    state(if(op=="initialize") "INITIALIZING" else "RUNNING",prefix,value);recorded=true
                }
                if(!cleanup) update(value)
                if(!value.getBoolean("running")) { log(value);return value }
                delay(100)
            }
        } finally { bridge.releaseDiagnostic(handle);activeHandle=0L }
    }
    private suspend fun terminateWineServer(prefix: File): JSONObject = withContext(NonCancellable) {
        val result=JSONObject().put("kill",runProcess("kill",prefix,cleanup=true)).put("wait",runProcess("wait",prefix,cleanup=true))
        val kill=result.getJSONObject("kill");val wait=result.getJSONObject("wait")
        check(kill.optInt("exitCode",-1) in 0..1 && kill.optInt("signal")==0 && !kill.optBoolean("timedOut") && kill.optInt("execErrno")==0) { "Wine shutdown failed: $kill" }
        check(success(wait,0)) { "Wine wait failed: $wait" }
        log(envelope().put("shutdown",result));return@withContext result
    }
    private suspend fun recoverRuntime(prior: JSONObject?) {
        val selected=prefixId;val selectedHome=prefixHome
        prefixId=prior?.optString("prefixId",id) ?: prefixId
        prefixHome=File(context.filesDir,"games/$prefixId");noRedirect(prefixHome)
        val prefix=prefixPath(prior?.optString("prefix","prefix") ?: "prefix")
        val owned=prior?.optJSONObject("process")
        if(owned!=null) {
            val pid=owned.optInt("pid",-1);val born=owned.optString("startTime")
            // PID reuse guard; only our original APK Box64 process group is eligible.
            fun matches(): Boolean = pid>0 && born.isNotEmpty() && startTime(pid)==born && runCatching {
                Os.stat("/proc/$pid").st_uid==Process.myUid() && Os.readlink("/proc/$pid/exe").removeSuffix(" (deleted)").let { it.endsWith("/libbox64.so") || it.endsWith("/libbox64_vulkan.so") }
            }.getOrDefault(false)
            if(matches()) { runCatching { Os.kill(-pid,OsConstants.SIGTERM) };delay(300);if(matches()) runCatching { Os.kill(-pid,OsConstants.SIGKILL) } }
        }
        val exitInfo=if(android.os.Build.VERSION.SDK_INT>=30) context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(null,0,5).map { JSONObject().put("process",it.processName).put("reason",it.reason)
                .put("status",it.status).put("timestamp",it.timestamp).put("description",it.description) } else emptyList()
        activePrefix=prefix
        val cleanup=terminateWineServer(prefix)
        val recovery=envelope().put("recovered",true).put("prior",prior).put("exitInfo",org.json.JSONArray(exitInfo)).put("shutdown",cleanup)
        atomic(File(home,"last-recovery.json"),recovery);log(recovery)
        state("IDLE",prefix);activePrefix=null
        prefixId=selected;prefixHome=selectedHome
    }
    private suspend fun deletePrefix(confirmation: String?): JSONObject {
        require(prefixId==id) { "Only a prefix owner can delete it" }
        require(confirmation=="DELETE_PREFIX:$id") { "Explicit deletion confirmation required" }
        for(name in listOf("prefix","prefix.initializing")) {
            val prefix=prefixPath(name)
            if(!prefix.exists()) continue
            check(read(File(prefix,".frostbox-prefix.json"))?.optString("profileId")==id) { "Refuse unmanaged prefix deletion" }
            terminateWineServer(prefix)
            // Files.walk does NOT follow symlinks (including Wine's Z: -> /).
            Files.walk(prefix.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
        state("DELETED",prefixPath("prefix"));return envelope().put("deleted",true)
    }
}
