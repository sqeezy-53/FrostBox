package org.frostbox.app.runtime

import android.content.*
import android.os.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class RuntimeProbeState(val active: Boolean = false, val mode: Int = 0, val report: String = "No native execution test has been run")
/** Bound IPC lifecycle; used by ViewModel, never calls blocking JNI in the UI process. */
class RuntimeProbeClient(private val context: Context) {
    private val mutable = MutableStateFlow(RuntimeProbeState())
    val state = mutable.asStateFlow()
    private var parameters = Bundle()
    private var service: Messenger? = null
    private var bound = false
    private var cancelOnConnect = false
    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == RuntimeProbeService.UPDATE) {
            val raw = message.data.getString("json") ?: "{}"
            val done = message.data.getBoolean("done")
            val result = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
            val expected = when(mutable.value.mode) { 0 -> "FROSTBOX_NATIVE_RESULT=5050\n"; 4 -> "FROSTBOX_BIONIC_RESULT=5050\n"; 5 -> "wine-11.19\n"; 6, 8 -> ""; 7 -> "FROSTBOX_PE_RESULT=5050\n"; else -> "FROSTBOX_ELF_RESULT=5050\n" }
            val legacyPassed = done && result.optInt("exitCode", -1) == (if (mutable.value.mode in listOf(5, 6, 8)) 0 else 42) && result.optString("stdout") == expected &&
                !result.optBoolean("timedOut") && !result.optBoolean("cancelled") && result.optInt("signal") == 0 && result.optInt("execErrno") == 0 && result.optString("error").isEmpty()
            val vulkanPassed = done && mutable.value.mode in listOf(11,12,15) && result.optInt("exitCode",-1)==42 &&
                result.optString("stdout").replace("\r\n","\n").contains("FROSTBOX_VULKAN_RESULT=10800\n") && !result.optBoolean("timedOut") &&
                !result.optBoolean("cancelled") && result.optInt("signal")==0 && result.optInt("execErrno")==0 && result.optString("error").isEmpty()
            val wow64Passed=done && mutable.value.mode in 19..21 && result.optBoolean("wow64Validated") && result.optJSONObject("wait")?.optInt("exitCode",-1)==0
            val passed=legacyPassed || vulkanPassed || wow64Passed
            val title = if (!done) "Running" else if (passed) "Built-in test PASSED" else "Test finished without a success verdict"
            mutable.value = mutable.value.copy(active=!done, report="$title\n${result.toString(2)}")
            if (done) disconnect()
        }
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            runCatching { service!!.send(Message.obtain(null, RuntimeProbeService.START, mutable.value.mode, 0).apply { replyTo=replies; data=Bundle(parameters) }) }
                .onFailure { failed("IPC start failed: ${it.message}") }
            if(cancelOnConnect) cancel()
        }
        override fun onServiceDisconnected(name: ComponentName) { failed("Runtime service disconnected; no completion result") }
        override fun onBindingDied(name: ComponentName) { failed("Runtime service binding died") }
        override fun onNullBinding(name: ComponentName) { failed("Runtime service returned no binder") }
    }
    fun start(mode: Int, wsiEndpoint: String? = null) {
        if (mutable.value.active) return
        require(mode in 0..8 || mode in 10..23)
        parameters=Bundle().apply { wsiEndpoint?.let { putString("wsiEndpoint", it) } }
        cancelOnConnect=false
        mutable.value=RuntimeProbeState(true, mode, "Binding separate runtime process…")
        try {
            bound=context.bindService(Intent(context, RuntimeProbeService::class.java), connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
            if (!bound) failed("Cannot bind runtime service")
        } catch (e: Exception) { failed(e.message ?: "Service binding failed") }
    }
    fun cancel() { cancelOnConnect=true; runCatching { service?.send(Message.obtain(null, RuntimeProbeService.STOP)) }.onFailure { failed("IPC stop failed") } }
    private fun failed(message: String) { mutable.value=mutable.value.copy(active=false,report=message);disconnect() }
    private fun disconnect() { if(bound) {context.unbindService(connection);bound=false};service=null }
    fun close() { disconnect(); mutable.value=mutable.value.copy(active=false) }
}
