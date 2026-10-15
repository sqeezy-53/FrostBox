// SPDX-License-Identifier: MIT
package org.frostbox.app.display

import android.content.*
import android.os.*
import android.view.Surface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/** Experimental Library WSI owner. GPU objects stay in :runtime; Binder carries only Surface. */
class GuestWsiSession(private val context: Context,val width: Int,val height: Int,private val failure: (String)->Unit) {
    val frames=MutableStateFlow(0)
    val error=MutableStateFlow("")
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val connected=CompletableDeferred<Messenger>()
    private val endpoint=CompletableDeferred<String>()
    private val owner=Binder()
    private val session=UUID.randomUUID().toString()
    private val pending=mutableMapOf<Long,CompletableDeferred<org.json.JSONObject>>()
    private var bound=false;private var closed=false;private var attaching=false;private var attached=false;private var requestId=0L
    private val response=Messenger(Handler(Looper.getMainLooper()){ m ->
        val r=org.json.JSONObject(m.data.getString("json") ?: "{}")
        pending.remove(r.optLong("request"))?.complete(r);true
    })
    private val connection=object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName,binder: IBinder){ android.util.Log.i("FrostBoxSurfaceIPC","Library GPU host connected");connected.complete(Messenger(binder)) }
        override fun onServiceDisconnected(name: ComponentName){ fail("GPU runtime disconnected") }
        override fun onBindingDied(name: ComponentName){ fail("GPU runtime binding died") }
        override fun onNullBinding(name: ComponentName){ fail("GPU runtime returned no Binder") }
    }
    init {
        bound=context.bindService(Intent(context,RuntimeSurfaceService::class.java),connection,Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
        check(bound){"Cannot bind GPU host"}
    }
    private fun fail(message: String) {
        if(closed)return
        error.value=message;endpoint.completeExceptionally(IllegalStateException(message))
        pending.values.forEach { it.completeExceptionally(IllegalStateException(message)) };pending.clear();failure(message)
    }
    private suspend fun request(op: Int,surface: Surface?=null): org.json.JSONObject {
        val service=withTimeout(15000){connected.await()};val id=++requestId
        val result=CompletableDeferred<org.json.JSONObject>();pending[id]=result
        try {
            service.send(Message.obtain(null,op).apply {
                replyTo=response;data=Bundle().apply {
                    putLong("request",id);putString("session",session);putBinder("owner",owner)
                    putLong("sequence",if(op==RuntimeSurfaceService.CLOSE)2 else 1);putLong("generation",if(op==RuntimeSurfaceService.CLOSE)2 else 1)
                    putInt("width",width);putInt("height",height);surface?.let { putParcelable("surface",it) }
                }
            })
            val r=withTimeout(15000){result.await()};check(r.getBoolean("accepted")){r.optString("error")};return r
        } finally {pending.remove(id)}
    }
    fun attach(value: Surface) {
        android.util.Log.i("FrostBoxSurfaceIPC","Library attach closed=$closed attaching=$attaching attached=$attached valid=${value.isValid}")
        if(closed||attaching||attached)return
        // Keep a private Java/native reference until Binder has decoded the parcel.
        val parcel=Parcel.obtain()
        val owned=try {value.writeToParcel(parcel,0);parcel.setDataPosition(0);Surface.CREATOR.createFromParcel(parcel)}finally{parcel.recycle()}
        attaching=true
        scope.launch {
            try {
                request(RuntimeSurfaceService.ATTACH,owned);attached=true
                endpoint.complete(request(RuntimeSurfaceService.GUEST_START).getString("endpoint"))
                while(!closed) {
                    val state=request(RuntimeSurfaceService.GUEST_STATUS).getJSONObject("guestWsi")
                    frames.value=state.optInt("frames")
                    if(state.optString("error").isNotEmpty()){fail(state.getString("error"));break}
                    delay(500)
                }
            } catch(e: CancellationException){throw e}
            catch(e: Exception){fail(e.message ?: e.toString())}
            finally {owned.release();attaching=false}
        }
    }
    suspend fun awaitEndpoint()=withTimeout(20000){endpoint.await()}
    fun lostSurface(){if(!closed&&attached)fail("Android Surface was destroyed; stop and restart the application")}
    fun close() {
        if(closed)return
        closed=true
        scope.launch {
            try {if(attached)request(RuntimeSurfaceService.CLOSE)}catch(_:Exception){}
            finally {if(bound){runCatching {context.unbindService(connection)};bound=false};scope.cancel()}
        }
    }
}
