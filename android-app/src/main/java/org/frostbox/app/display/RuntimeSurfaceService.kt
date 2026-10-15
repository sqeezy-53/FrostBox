// SPDX-License-Identifier: MIT
package org.frostbox.app.display

import android.app.Service
import android.content.Intent
import android.os.*
import android.view.Surface
import org.json.JSONObject
import java.util.UUID
import java.io.File
import java.security.MessageDigest

/** Experimental Surface ownership and opt-in Linux x64 image diagnostic. No Wine WSI. */
class RuntimeSurfaceService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private lateinit var messenger: Messenger
    private var session = ""
    private var generation = 0L
    private var sequence = 0L
    private var handle = 0L // Opaque registry ID, never included in IPC replies.
    private var owner: IBinder? = null
    private var death: IBinder.DeathRecipient? = null
    private var acquired = 0L
    private var released = 0L
    private val retired = LinkedHashSet<String>()

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("FrostBoxSurfaceOwner").apply { start() }
        handler = Handler(worker.looper) { message -> receive(message); true }
        messenger = Messenger(handler)
    }
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onUnbind(intent: Intent): Boolean { handler.post { retireSession() }; return false }
    override fun onDestroy() {
        // FIFO: all queued requests finish before the final release and looper shutdown.
        handler.post { retireSession(); worker.quitSafely() }
        super.onDestroy()
    }
    private fun retireWindow() {
        if (handle != 0L) { RuntimeSurfaceBridge.release(handle); handle = 0; released++ }
    }
    private fun retireSession() {
        retireWindow()
        if (session.isNotEmpty()) {
            retired.add(session)
        }
        death?.let { callback -> owner?.let { runCatching { it.unlinkToDeath(callback, 0) } } }
        death = null; owner = null; session = ""; generation = 0; sequence = 0
    }
    private fun report(accepted: Boolean, error: String = ""): JSONObject {
        val state = JSONObject().put("accepted", accepted).put("error", error)
            .put("session", session).put("generation", generation).put("sequence", sequence)
            .put("runtimeUid", Process.myUid()).put("runtimePid", Process.myPid())
            .put("openFdCount", File("/proc/self/fd").list()?.size ?: -1)
            .put("acquired", acquired).put("released", released).put("windowOwned", handle != 0L)
            .put("wineSurfaceConnected", false).put("presentedFrames", 0)
        if (handle != 0L) state.put("nativeWindow", JSONObject(RuntimeSurfaceBridge.describe(handle)))
        return state
    }
    private fun receive(message: Message) {
        var surface: Surface? = null
        var result: JSONObject
        try {
            // Messenger records the sender UID before dispatching to this worker thread.
            require(message.sendingUid == Process.myUid()) { "Different application UID" }
            val data = message.data.apply { classLoader = Surface::class.java.classLoader }
            @Suppress("DEPRECATION")
            surface = if (Build.VERSION.SDK_INT >= 33) data.getParcelable("surface", Surface::class.java)
                else data.getParcelable("surface")
            when (message.what) {
                SNAPSHOT -> result = report(true)
                LOADER_DIAGNOSTIC -> result = report(true).put("loader", JSONObject(
                    RuntimeSurfaceBridge.loaderDiagnostic(applicationInfo.nativeLibraryDir)))
                GUEST_START, GUEST_STATUS -> {
                    require(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                    require(handle != 0L && data.getString("session") == session && data.getBinder("owner") == owner)
                    require(data.getLong("generation") == generation)
                    result = report(true)
                    if (message.what == GUEST_START) result.put("endpoint", RuntimeSurfaceBridge.guestWsi(handle, "@frost-guest-${UUID.randomUUID()}"))
                    else result.put("guestWsi", JSONObject(RuntimeSurfaceBridge.guestWsi(handle, null)))
                }
                IMAGE_FRAME, IMAGE_PERSISTENT -> {
                    require(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) { "Diagnostic only" }
                    require(handle != 0L && data.getString("session") == session && data.getBinder("owner") == owner) { "Invalid image session owner" }
                    require(data.getLong("generation") == generation) { "Stale image generation" }
                    val color = data.getInt("color", -1); require(color in 0..1) { "Invalid frame" }
                    val work = File(filesDir, "wsi-image-test").apply { mkdirs() }
                    val fixture = File(work, "wsi-export-x64.elf")
                    val meta = assets.open("runtime/wsi-export-x64.elf.json").bufferedReader().use { JSONObject(it.readText()) }
                    if (!fixture.exists() || MessageDigest.getInstance("SHA-256").digest(fixture.readBytes()).joinToString("") { "%02x".format(it) } != meta.getString("sha256")) {
                        assets.open("runtime/wsi-export-x64.elf").use { input -> fixture.outputStream().use { input.copyTo(it) } }
                    }
                    val sha = MessageDigest.getInstance("SHA-256").digest(fixture.readBytes()).joinToString("") { "%02x".format(it) }
                    require(sha == meta.getString("sha256")) { "Image fixture SHA mismatch" }
                    require(fixture.setExecutable(true, true)) { "Image fixture executable bit unavailable" }
                    result = report(true).put("imageBridge", JSONObject(RuntimeSurfaceBridge.imageFrame(handle, applicationInfo.nativeLibraryDir, work.canonicalPath, color, if (message.what == IMAGE_PERSISTENT) data.getInt("total", 100) else 1)))
                }
                ATTACH, REVOKE, CLOSE -> {
                    val id = data.getString("session") ?: error("Missing session")
                    require(UUID.fromString(id).toString() == id) { "Invalid session" }
                    require(id !in retired) { "Session was retired; create a new session" }
                    val token = data.getBinder("owner") ?: error("Missing owner Binder")
                    val nextSequence = data.getLong("sequence")
                    val nextGeneration = data.getLong("generation")
                    require(nextSequence > 0 && nextGeneration > 0) { "Invalid revision" }
                    require(session.isEmpty() || session == id) { "Surface already owned by another session" }
                    require(owner == null || owner == token) { "Session owner changed" }
                    require(nextSequence > sequence && nextGeneration >= generation) { "Stale surface request" }
                    if (message.what == ATTACH) {
                        require(surface?.isValid == true) { "Invalid Surface" }
                        require(data.getInt("width") in 1..16384 && data.getInt("height") in 1..16384) { "Invalid size" }
                    }
                    if (owner == null) {
                        require(retired.size < 64) { "Session limit reached; unbind and recreate the service" }
                        val callback = IBinder.DeathRecipient { handler.post { if (owner == token) retireSession() } }
                        token.linkToDeath(callback, 0)
                        owner = token; death = callback; session = id
                    }
                    // Acquire before retiring the previous window so failure preserves valid ownership.
                    val replacement = if (message.what == ATTACH) RuntimeSurfaceBridge.acquire(checkNotNull(surface)) else 0L
                    retireWindow(); handle = replacement
                    if (replacement != 0L) acquired++
                    sequence = nextSequence; generation = nextGeneration
                    if (message.what == CLOSE) { retireSession(); result = report(true).put("sessionClosed", true) }
                    else result = report(true)
                }
                else -> error("Unknown surface operation")
            }
        } catch (e: Exception) { result = report(false, e.toString()) }
        finally {
            // This is the decoded Binder parcel's Java reference; the native owner has its own ref.
            surface?.release()
        }
        result.put("request", message.data.getLong("request"))
        android.util.Log.i("FrostBoxSurfaceIPC", result.toString())
        runCatching { message.replyTo?.send(Message.obtain(null, RESULT).apply {
            data = Bundle().apply { putString("json", result.toString()) }
        }) }
    }
    companion object {
        const val ATTACH = 1; const val REVOKE = 2; const val SNAPSHOT = 3
        const val LOADER_DIAGNOSTIC = 4; const val CLOSE = 5; const val IMAGE_FRAME = 6; const val IMAGE_PERSISTENT = 7; const val GUEST_START = 8; const val GUEST_STATUS = 9; const val RESULT = 101
    }
}

internal object RuntimeSurfaceBridge {
    init { System.loadLibrary("frostbox_surface") }
    external fun acquire(surface: Surface): Long
    external fun describe(handle: Long): String
    external fun release(handle: Long)
    external fun loaderDiagnostic(nativeDir: String): String
    external fun guestWsi(handle: Long, endpoint: String?): String
    external fun imageFrame(handle: Long, nativeDir: String, workDir: String, color: Int, total: Int): String
}
