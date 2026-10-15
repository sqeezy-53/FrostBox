package org.frostbox.app.runtime

import android.app.Service
import android.content.Intent
import android.os.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Non-exported, bound :runtime process. Accepts diagnostics and managed profile IDs; never arbitrary host paths. */
class RuntimeProbeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val cancelled = AtomicBoolean(false)
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            START -> {
                val reply = message.replyTo
                val mode = message.arg1
                if (reply != null && job?.isActive != true) {
                    cancelled.set(false)
                    val parameters = Bundle(message.data)
                    job = scope.launch { execute(mode, reply, parameters) }
                } else if (reply != null) {
                    runCatching { send(reply, JSONObject().put("error", "Runtime test already active").toString(), true) }
                }
            }
            WINE -> {
                val reply=message.replyTo
                val request=Bundle(message.data)
                if(reply!=null && job?.isActive!=true) {
                    cancelled.set(false)
                    job=scope.launch {
                        try {
                            val directory=File(filesDir,"runtime-tests").apply { check(isDirectory || mkdirs()) }
                            prepareWine(directory)
                            val manager=AndroidWineManager(this@RuntimeProbeService,File(directory,"wine-userspace"),cancelled) {
                                send(reply,it.toString(),false)
                            }
                            val result=manager.execute(request)
                            android.util.Log.i("FrostBoxWine",result.toString())
                            send(reply,result.toString(),true)
                        } catch(e: CancellationException) { throw e }
                        catch(e: Throwable) {
                            val result=JSONObject().put("error",e.toString()).put("runtimeUid",Process.myUid()).put("runtimePid",Process.myPid())
                            android.util.Log.e("FrostBoxWine",result.toString())
                            runCatching { send(reply,result.toString(),true) }
                        }
                    }
                } else if(reply!=null) runCatching { send(reply,JSONObject().put("error","Runtime busy").toString(),true) }
            }
            STOP -> cancelled.set(true)
        }
        true
    })
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onUnbind(intent: Intent): Boolean { job?.cancel(); return false }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private fun send(reply: Messenger, text: String, done: Boolean) {
        reply.send(Message.obtain(null, UPDATE).apply { data = Bundle().apply { putString("json", text); putBoolean("done", done) } })
    }
    private suspend fun execute(mode: Int, reply: Messenger, parameters: Bundle) {
        var bridge: NativeBridge? = null
        var handle = 0L
        var wineCleaned = false
        try {
            require(mode in 0..8 || mode in 10..23) { "Unknown built-in test" }
            if(mode==22) {
                val r=JSONObject(org.frostbox.app.gpu.NativeVulkan.mapProbe()).put("runtimePid",Process.myPid()).put("runtimeUid",Process.myUid())
                send(reply,r.toString(),true);return
            }
            val directory = File(filesDir, "runtime-tests").apply { check(isDirectory || mkdirs()) }
            if(mode in 19..21 || mode==23) {
                check(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {"WoW64 candidate is diagnostic only"}
                prepareWine(directory)
                WineWow64Diagnostics.execute(this,File(directory,"wine-userspace"),cancelled,if(mode==20)"vulkan" else if(mode==21)"d3d9" else null,parameters.getString("wsiEndpoint"),sleep=mode==23) {result,done->send(reply,result.toString(),done)}
                return
            }
            if(mode in 14..18) {
                prepareWine(directory)
                WineVulkanDiagnostics.execute(this, directory, mode>=15, cancelled, mode==16, if(mode>=17) parameters.getString("wsiEndpoint") else null, mode==18) { result, done -> send(reply,result.toString(),done) }
                return
            }
            if (mode == 1 || mode == 2 || mode == 4 || mode in 10..13) {
                val guest = if(mode>=10) "vulkan-compute-x64.elf" else if (mode == 4) "bionic-probe.elf" else "compute-x64.elf"
                val meta = if(mode>=10) "vulkan-compute-x64.elf.json" else if (mode == 4) "bionic-fixture.json" else "fixture.json"
                val bytes = assets.open("runtime/$guest").use { it.readBytes() }
                val metadata = JSONObject(assets.open("runtime/$meta").bufferedReader().use { it.readText() })
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                check(sha == metadata.getString("sha256")) { "Packaged guest checksum mismatch" }
                val target = File(directory, guest)
                val temporary = File(directory, "compute.part")
                temporary.writeBytes(bytes)
                check(temporary.renameTo(target)) { "Cannot prepare guest fixture" }
                // Box64 checks S_IXUSR even though this ELF is parsed as guest data, never execve'd.
                check(target.setExecutable(true, true)) { "Cannot mark guest ELF for Box64" }
            }
            if (mode in 5..8) prepareWine(directory)
            bridge = NativeBridge()
            handle = bridge.startDiagnostic(mode, applicationInfo.nativeLibraryDir, directory.absolutePath)
            var stopSent = false
            while (currentCoroutineContext().isActive) {
                if (cancelled.get() && !stopSent) { bridge.cancelDiagnostic(handle); stopSent = true }
                val result = JSONObject(bridge.pollDiagnostic(handle))
                    .put("runtimeUid", Process.myUid())
                    .put("runtimePid", Process.myPid())
                    .put("androidApi", Build.VERSION.SDK_INT)
                    .put("mode", mode)
                val running = result.getBoolean("running")
                // Release before notifying completion: the next request must not inherit a native handle.
                if (!running) { bridge.releaseDiagnostic(handle); handle = 0L }
                if (!running && mode in 6..7) {
                    result.put("wineShutdown", shutdownWine(bridge, applicationInfo.nativeLibraryDir, directory.absolutePath))
                    wineCleaned = true
                    result.put("prefixRegistryPresent", listOf("system.reg", "user.reg", "userdef.reg").all { File(directory, "wine-prefix/$it").isFile })
                }
                if (!running) android.util.Log.i("FrostBoxRuntime", result.toString())
                send(reply, result.toString(), !running)
                if (!running) break
                delay(100)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Throwable) {
            // Linkage errors and native-reported execution failures are visible to the caller.
            runCatching { send(reply, JSONObject().put("error", e.toString()).put("runtimeUid",Process.myUid()).put("runtimePid",Process.myPid()).toString(), true) }
        } finally {
            if (handle != 0L) runCatching { bridge?.releaseDiagnostic(handle) }
            if (mode in 6..7 && bridge != null && !wineCleaned) runCatching {
                shutdownWine(bridge, applicationInfo.nativeLibraryDir, File(filesDir, "runtime-tests").absolutePath)
            }
        }
    }
    private suspend fun shutdownWine(bridge: NativeBridge, libraries: String, directory: String): JSONObject = withContext(NonCancellable) {
        val result = JSONObject()
        for ((mode, label) in listOf(9 to "kill", 8 to "wait")) {
            val handle = bridge.startDiagnostic(mode, libraries, directory)
            try {
                while (true) {
                    val snapshot = JSONObject(bridge.pollDiagnostic(handle))
                    if (!snapshot.getBoolean("running")) { result.put(label, snapshot); break }
                    delay(100)
                }
            } finally { bridge.releaseDiagnostic(handle) }
        }
        result
    }
    internal fun prepareWine(directory: File) {
        val metadata = JSONObject(assets.open("runtime/wine-probe.json").bufferedReader().use { it.readText() })
        val expected = metadata.getString("sha256")
        val root = File(directory, "wine-userspace")
        val marker = File(root, ".package-sha256")
        if (marker.isFile && marker.readText() == expected) return
        check(!root.exists() || root.deleteRecursively())
        check(root.mkdirs())
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        java.security.DigestInputStream(assets.open("runtime/wine-probe.zip"), digest).use { input ->
            java.util.zip.ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val file = File(root, entry.name).canonicalFile
                    check(file.path.startsWith(root.canonicalPath + File.separator)) { "Unsafe bundled Wine path" }
                    check(!entry.isDirectory) { "Unexpected directory entry" }
                    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                    file.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            total += n
                            check(total <= 1024L * 1024 * 1024) { "Wine package exceeds extraction limit" }
                            output.write(buffer, 0, n)
                        }
                    }
                    check(file.setExecutable(true, true)) // Guest ELF metadata, not host execution.
                }
                // Consume ZIP central directory so SHA covers the complete asset.
                while (input.read() != -1) { }
            }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == expected) { "Wine package checksum mismatch" }
        marker.writeText(expected)
    }
    companion object { const val WINE = 4; const val START = 1; const val STOP = 2; const val UPDATE = 3 }
}
