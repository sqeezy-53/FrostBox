package org.frostbox.app.runtime

import android.content.*
import android.os.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Nonblocking Wine IPC. Configuration/recovery never executes on the UI thread. */
class WineRuntimeClient(private val context: Context) {
    data class State(val active: Boolean=false,val result: String="{}",val token: String="")
    private val mutable=MutableStateFlow(State())
    val state=mutable.asStateFlow()
    private var bound=false
    private var service: Messenger?=null
    private var stopPending=false
    private var token=""
    private var connection: ServiceConnection?=null
    private fun failure(reason: String,expected: String=token) {
        if(expected!=token) return
        disconnect()
        mutable.value=State(false,JSONObject().put("error",reason).put("phase","IPC_DISCONNECTED")
            .put("previous",runCatching { JSONObject(mutable.value.result) }.getOrNull()).toString(),expected)
    }
    fun request(operation: String,profileId: String,configure: Bundle.()->Unit = {}) {
        check(!mutable.value.active) { "Wine operation already active" }
        val request=Bundle().apply { putString("operation",operation);putString("profileId",profileId);configure() }
        val expected=request.getString("launcherRunId") ?: java.util.UUID.randomUUID().toString()
        token=expected;stopPending=false;mutable.value=State(true,"{}",expected)
        // Each binding/reply channel captures its identity. Late replies from a dead service are ignored.
        val replies=Messenger(Handler(Looper.getMainLooper()) { message ->
            if(message.what==RuntimeProbeService.UPDATE && expected==token) {
                val done=message.data.getBoolean("done")
                if(done) disconnect()
                mutable.value=State(!done,message.data.getString("json") ?: "{}",expected)
            };true
        })
        val binding=object: ServiceConnection {
            override fun onServiceConnected(name: ComponentName,binder: IBinder) {
                if(expected!=token) return
                service=Messenger(binder)
                runCatching { service!!.send(Message.obtain(null,RuntimeProbeService.WINE).apply { data=request;replyTo=replies }) }
                    .onFailure { failure("Wine IPC start failed: $it",expected) }
                if(stopPending) stopProcess()
            }
            override fun onServiceDisconnected(name: ComponentName) { failure("Runtime process died before completion; recovery required",expected) }
            override fun onBindingDied(name: ComponentName) { failure("Runtime binding died; recovery required",expected) }
            override fun onNullBinding(name: ComponentName) { failure("No runtime binder",expected) }
        }
        connection=binding
        try {
            bound=context.bindService(Intent(context,RuntimeProbeService::class.java),binding,Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
            if(!bound) failure("Cannot bind Wine runtime",expected)
        } catch(e: Exception) { failure(e.toString(),expected) }
    }
    fun initializePrefix(id: String)=request("initialize",id)
    fun validatePrefix(id: String)=request("validate",id)
    fun launchWindowsExecutable(id: String)=request("launch",id)
    fun terminateWineServer(id: String)=request("terminate",id)
    fun recoverRuntime(id: String)=request("recover",id)
    fun getProcessStatus(id: String)=request("status",id)
    fun getRuntimeLogs(id: String)=request("logs",id)
    fun deletePrefix(id: String,confirmation: String)=request("delete",id) { putString("confirmation",confirmation) }
    fun stopProcess() {
        stopPending=true
        runCatching { service?.send(Message.obtain(null,RuntimeProbeService.STOP)) }.onFailure { failure("Wine stop IPC failed: $it") }
    }
    private fun disconnect() { if(bound) { connection?.let { context.unbindService(it) };bound=false };service=null }
    fun close() { token="";disconnect() }
}
