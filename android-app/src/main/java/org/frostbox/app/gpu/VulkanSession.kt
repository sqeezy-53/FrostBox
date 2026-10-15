package org.frostbox.app.gpu

import android.app.Application
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.view.Surface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** ViewModel-owned lifecycle. Vulkan work and resource retirement never run on the UI thread. */
class VulkanSession(private val app: Application,private val scope: CoroutineScope) {
    val visible=MutableStateFlow(false)
    private val mutable=MutableStateFlow("{\"state\":\"Stopped\",\"presentedFrames\":0}")
    val report=mutable.asStateFlow()
    val capabilities=MutableStateFlow("{}")
    val saved=MutableStateFlow("")
    private val retirement=Executors.newSingleThreadExecutor()
    @Volatile private var handle=0L
    private var poller: Job?=null
    private var foreground=true
    private var memorySample=0L
    private var pssKiB=0L
    private val dir get()=File(app.filesDir,"vulkan-diagnostics").apply { mkdirs() }
    @Synchronized private fun save(name: String,value: String) {
        val staging=File(dir,"$name.tmp");staging.writeText(value);check(staging.renameTo(File(dir,name))) { "Cannot commit Vulkan diagnostics" }
    }
    private fun fail(e: Throwable) { mutable.value=JSONObject().put("state","Failed").put("error",e.message ?: e.javaClass.simpleName).toString() }
    private fun ensure(): Long {
        if(handle==0L) {
            handle=NativeVulkan.create();NativeVulkan.foreground(handle,foreground)
            poller=scope.launch(Dispatchers.IO) {
                while(isActive) {
                    try {
                        val snapshot=JSONObject(NativeVulkan.snapshot(handle))
                        snapshot.put("androidApi",android.os.Build.VERSION.SDK_INT)
                        snapshot.put("thermalStatus",app.getSystemService(PowerManager::class.java).currentThermalStatus)
                        val now=SystemClock.elapsedRealtime()
                        if(now-memorySample>=2000) { pssKiB=Debug.getPss();memorySample=now }
                        snapshot.put("processPssKiB",pssKiB).put("memorySampleElapsedMs",memorySample)
                        snapshot.optJSONObject("capabilities")?.takeIf { it.has("devices") }?.let { capabilities.value=it.toString() }
                        mutable.value=snapshot.toString()
                    } catch(e: CancellationException) {throw e} catch(e: Exception) {fail(e)}
                    delay(250)
                }
            }
        }
        return handle
    }
    fun show() {visible.value=true}
    fun hide() {stop();visible.value=false}
    fun probe() {scope.launch(Dispatchers.IO) {try {
        val result=NativeVulkan.probe();capabilities.value=result
        val json=JSONObject(result)
        if(json.has("error"))throw IllegalStateException(json.getString("error"))
        save("capabilities.json",result);saved.value="Saved files/vulkan-diagnostics/capabilities.json"
    }catch(e: Exception){fail(e)}catch(e: LinkageError){fail(e)}}}
    fun attach(surface: Surface?,width: Int,height: Int) {try {NativeVulkan.surface(ensure(),surface,width,height)}catch(e: Exception){fail(e)}catch(e: LinkageError){fail(e)}}
    fun start() {try {NativeVulkan.start(ensure(),true)}catch(e: Exception){fail(e)}catch(e: LinkageError){fail(e)}}
    fun stop() {if(handle!=0L)NativeVulkan.start(handle,false)}
    fun foreground(value: Boolean) {foreground=value;if(handle!=0L)NativeVulkan.foreground(handle,value)}
    fun saveReport() {val value=report.value;val caps=capabilities.value;scope.launch(Dispatchers.IO){try {save("render.json",value);save("capabilities.json",caps);saved.value="Saved files/vulkan-diagnostics/render.json and capabilities.json"}catch(e: Exception){fail(e)}}}
    fun close() {
        poller?.cancel();val owned=handle;handle=0
        retirement.execute { if(owned!=0L)NativeVulkan.release(owned) };retirement.shutdown()
    }
}
