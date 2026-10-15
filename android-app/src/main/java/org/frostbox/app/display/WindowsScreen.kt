package org.frostbox.app.display

import androidx.activity.compose.LocalActivity

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowInsets
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable fun WindowsScreen(session: DisplaySession,stop: ()->Unit,gpu: GuestWsiSession? = null,touch: org.frostbox.core.TouchProfile?=null,controllers: org.frostbox.app.input.ControllerMonitor?=null) {
    val state by session.status.collectAsStateWithLifecycle()
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        val orientation=activity.requestedOrientation
        activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if(android.os.Build.VERSION.SDK_INT>=30) activity.window.insetsController?.hide(WindowInsets.Type.systemBars())
        onDispose {
            session.attach(null)
            if(!activity.isChangingConfigurations)activity.requestedOrientation=orientation
            if(android.os.Build.VERSION.SDK_INT>=30) activity.window.insetsController?.show(WindowInsets.Type.systemBars())
        }
    }
    var touchVisible by remember(touch) {mutableStateOf(touch?.enabled==true)}
    var surface by remember { mutableStateOf<WindowsSurface?>(null) }
    BackHandler { if(surface?.hideKeyboard()!=true) { session.key(27);session.key(27,true) } }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(if(gpu!=null) "Windows GPU · ${gpu.width}×${gpu.height}" else if(state.frames==0L) "Waiting for Wine window…" else "Windows · ${state.width}×${state.height}",modifier=Modifier.weight(1f))
            if(touch?.enabled==true)TextButton(onClick={touchVisible=!touchVisible}){Text("Управление")}
            TextButton(onClick={surface?.showKeyboard()},modifier=Modifier.testTag("display-keyboard")) { Text("Keyboard") }
            TextButton(onClick={session.key(27);session.key(27,true)},modifier=Modifier.testTag("display-escape")) { Text("Esc") }
            TextButton(onClick=session::closeWindow,modifier=Modifier.testTag("display-close")) { Text("Close window") }
            TextButton(onClick=stop,modifier=Modifier.testTag("display-stop")) { Text("Stop") }
        }
        if(state.error.isNotEmpty()) Text(state.error,color=MaterialTheme.colorScheme.error)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if(gpu==null || androidx.compose.ui.platform.LocalConfiguration.current.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE) AndroidView(factory={WindowsSurface(it,session,gpu).also { view->surface=view }},modifier=Modifier.fillMaxSize().testTag("windows-surface"))
            if(touchVisible && touch!=null && controllers!=null)org.frostbox.app.input.TouchControls(touch,session,controllers)
        }
    }
}
