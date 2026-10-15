package org.frostbox.app.display

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource as tr
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.frostbox.app.R
import org.frostbox.app.input.ControllerMonitor
import org.frostbox.core.*

@Composable fun WindowsScreen(session: DisplaySession,stop: ()->Unit,gpu: GuestWsiSession?=null,
    touch: TouchProfile?=null,controllers: ControllerMonitor?=null,profile: GameProfile?=null,
    phase: String="Running",audio: String="{}",diagnostics: String="{}",change: ((GameProfile)->GameProfile)->Unit={},restart: ()->Unit={}) {
    val state by session.status.collectAsStateWithLifecycle()
    val gpuCount=if(gpu!=null){val f by gpu.frames.collectAsStateWithLifecycle();f.toLong()}else state.frames
    val activity=LocalActivity.current ?: return
    val settings=profile?.experience ?: GameExperience()
    var panel by rememberSaveable(profile?.id){mutableStateOf(false)}
    var viewport by remember {mutableStateOf<WindowsViewport?>(null)}
    var ticks by remember {mutableLongStateOf(0)}
    var count by remember {mutableLongStateOf(0)}
    var rate by remember {mutableStateOf<Float?>(null)}
    var dimensions by remember {mutableStateOf("")}
    DisposableEffect(activity) {
        val orientation=activity.requestedOrientation
        activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val insets=WindowCompat.getInsetsController(activity.window,activity.window.decorView)
        val oldBehavior=insets.systemBarsBehavior
        insets.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controllers?.blockGameInput(false);session.attach(null)
            if(!activity.isChangingConfigurations)activity.requestedOrientation=orientation
            insets.systemBarsBehavior=oldBehavior;insets.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    fun resumeGame() {viewport?.display?.gameInput(true);controllers?.blockGameInput(false);panel=false}
    DisposableEffect(panel,viewport) {
        controllers?.blockGameInput(panel);viewport?.display?.gameInput(!panel)
        onDispose { }
    }
    BackHandler {if(panel)resumeGame() else if(viewport?.display?.hideKeyboard()!=true)panel=true}
    LaunchedEffect(gpu,session) {
        var previous=0L;var time=android.os.SystemClock.elapsedRealtime()
        while(true) {
            delay(1000);ticks++
            val now=android.os.SystemClock.elapsedRealtime()
            val current=gpu?.frames?.value?.toLong() ?: session.status.value.frames
            rate=if(current>=previous) (current-previous)*1000f/(now-time) else null
            count=current;previous=current;time=now
            val v=viewport;val source=if(gpu!=null)"${gpu.width}×${gpu.height}" else "${session.status.value.width}×${session.status.value.height}"
            dimensions="${activity.getString(R.string.gx_output_buffer)}: $source\n${activity.getString(R.string.gx_viewport)}: ${v?.width ?: 0}×${v?.height ?: 0}\n${activity.getString(R.string.gx_view_bounds)}: ${v?.display?.width ?: 0}×${v?.display?.height ?: 0}\n${activity.getString(R.string.gx_backbuffer_unknown)}"
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("game-screen")) {
        // Keep this AndroidView at a stable composition position when the panel opens.
        AndroidView(factory={ctx->WindowsViewport(ctx,WindowsSurface(ctx,session,gpu)).also {viewport=it}},
            update={view->view.configure(gpu?.width ?: state.width.takeIf {it>0} ?: profile?.width ?: 1280,
                gpu?.height ?: state.height.takeIf {it>0} ?: profile?.height ?: 720,settings.scaling);view.display.gameInput(!panel)},
            modifier=Modifier.fillMaxSize().testTag("windows-surface"))
        if(!panel && settings.touchVisible && touch?.enabled==true && controllers!=null)org.frostbox.app.input.TouchControls(touch,session,controllers)
        if(!panel && settings.hud!="off") {
            val alignment=when(settings.hudCorner){"top-right"->Alignment.TopEnd;"bottom-left"->Alignment.BottomStart;"bottom-right"->Alignment.BottomEnd;else->Alignment.TopStart}
            Surface(Modifier.align(alignment).safeDrawingPadding().padding(10.dp).testTag("game-hud"),color=Color(0xC0182025),shape=MaterialTheme.shapes.small) {
                Column(Modifier.padding(8.dp)) {
                    Text(tr(if(gpu!=null)R.string.gx_hud_rate else R.string.gx_software_rate,rate?.let {"%.1f".format(it)} ?: "—"),style=MaterialTheme.typography.labelSmall)
                    if(settings.hud=="detailed") {Text(tr(if(gpu!=null)R.string.gx_present_count else R.string.gx_software_count,count),style=MaterialTheme.typography.labelSmall);Text("${profile?.architecture} · ${profile?.width}×${profile?.height} · ${profile?.graphics}",style=MaterialTheme.typography.labelSmall)}
                }
            }
        }
        if(!panel && settings.handleVisible) {
            val edge=if(settings.panelEdge=="left")Alignment.CenterStart else Alignment.CenterEnd
            val description=tr(R.string.gx_open_panel)
            FilledTonalButton({panel=true},Modifier.align(edge).safeDrawingPadding().padding(horizontal=6.dp).width(56.dp).height(64.dp).testTag("game-panel-open").semantics{contentDescription=description},contentPadding=PaddingValues(0.dp)) {Text("☰")}
        }
        if(!panel && gpuCount==0L) Surface(Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(8.dp),color=Color(0xC0182025)) {
            Column(Modifier.padding(12.dp)) {Text(tr(if(ticks<30)R.string.gx_waiting else R.string.gx_slow_start));TextButton({panel=true}){Text(tr(R.string.gx_open_panel))}}
        }
        if(panel) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.28f)).pointerInput(Unit){detectTapGestures {resumeGame()}})
            Box(Modifier.align(Alignment.CenterEnd)) {GamePanel(profile,phase,dimensions,controllers,audio,diagnostics,gpuCount,gpu!=null,change,
                resume={resumeGame()},stop=stop,restart=restart,
                keyboard={resumeGame();viewport?.display?.showKeyboard()},escape={resumeGame();session.key(27);session.key(27,true)},closeWindow={resumeGame();session.closeWindow()})}
        }
    }
}
