package org.frostbox.app.gpu

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.activity.compose.BackHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject

class VulkanSurface(context: Context,private val session: VulkanSession): SurfaceView(context),SurfaceHolder.Callback {
    init {holder.setFormat(PixelFormat.RGBA_8888);holder.addCallback(this);tag="vulkan-surface";contentDescription="Native Vulkan triangle"}
    override fun surfaceCreated(holder: SurfaceHolder) { /* surfaceChanged provides positive dimensions */ }
    override fun surfaceChanged(holder: SurfaceHolder,format: Int,width: Int,height: Int) {session.attach(holder.surface,width,height)}
    override fun surfaceDestroyed(holder: SurfaceHolder) {session.attach(null,0,0)}
}
@Composable fun VulkanScreen(session: VulkanSession) {
    BackHandler(onBack=session::hide)
    // A fixed 160dp report consumes the entire landscape viewport on a phone.
    // Keep a positive drawing area after rotation; the report remains scrollable.
    val reportHeight=if(LocalConfiguration.current.orientation==Configuration.ORIENTATION_LANDSCAPE)48.dp else 160.dp
    val raw by session.report.collectAsStateWithLifecycle()
    val caps by session.capabilities.collectAsStateWithLifecycle()
    val saved by session.saved.collectAsStateWithLifecycle()
    val report=runCatching {JSONObject(raw)}.getOrDefault(JSONObject())
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Vulkan Diagnostics · native Android",style=MaterialTheme.typography.titleLarge)
        Text("Experimental GPU triangle. Windows Vulkan and DXVK are not connected.")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(onClick=session::probe,modifier=Modifier.testTag("gpu-probe")){Text("Probe")}
            TextButton(onClick=session::start,modifier=Modifier.testTag("gpu-start")){Text("Start")}
            TextButton(onClick=session::stop,modifier=Modifier.testTag("gpu-stop")){Text("Stop")}
            TextButton(onClick=session::saveReport,modifier=Modifier.testTag("gpu-save")){Text("Save JSON")}
            TextButton(onClick=session::hide,modifier=Modifier.testTag("gpu-back")){Text("Library")}
        }
        Text("${report.optString("state")} · ${report.optLong("presentedFrames")} present calls · ${report.optInt("width")}×${report.optInt("height")}",modifier=Modifier.testTag("gpu-state"))
        if(report.optString("error").isNotBlank())Text(report.optString("error"),color=MaterialTheme.colorScheme.error)
        AndroidView(factory={VulkanSurface(it,session)},modifier=Modifier.weight(1f).fillMaxWidth().testTag("gpu-surface"))
        Column(Modifier.heightIn(max=reportHeight).verticalScroll(rememberScrollState())) {
            if(saved.isNotBlank())Text(saved)
            Text("CPU submit wall: ${report.optDouble("cpuRecordSubmitWallMsMean",0.0)} ms · GPU: ${report.opt("gpuExecutionMsMean")} ms · pacing: ${report.optDouble("presentCallIntervalMsMean",0.0)} ms · thermal: ${report.opt("thermalStatus")}")
            Text("Frames count accepted vkQueuePresentKHR calls, not scanout/DXVK FPS. PSS: ${report.opt("processPssKiB")} KiB.")
            Text(runCatching {JSONObject(caps).toString(2)}.getOrDefault(caps),modifier=Modifier.testTag("gpu-capabilities"))
        }
    }
}
