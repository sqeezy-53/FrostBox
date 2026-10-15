package org.frostbox.app.display

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource as tr
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.frostbox.app.R
import org.frostbox.app.input.ControllerMonitor
import org.frostbox.core.*

@Composable internal fun GamePanel(profile: GameProfile?,phase: String,dimensions: String,
    controllers: ControllerMonitor?,audio: String,diagnostics: String,settingsError: String,frames: Long,isGpu: Boolean,
    change: ((GameProfile)->GameProfile)->Unit,resume: ()->Unit,stop: ()->Unit,restart: ()->Unit,
    keyboard: ()->Unit,escape: ()->Unit,closeWindow: ()->Unit) {
    var tab by rememberSaveable {mutableStateOf("home")}
    var confirm by rememberSaveable {mutableStateOf("")}
    val focus=remember {FocusRequester()}
    val scroll=rememberScrollState()
    LaunchedEffect(tab){scroll.scrollTo(0)}
    Surface(Modifier.fillMaxHeight().widthIn(max=540.dp).fillMaxWidth().testTag("game-panel"),
        color=MaterialTheme.colorScheme.surface,shape=androidx.compose.foundation.shape.RoundedCornerShape(topStart=24.dp,bottomStart=24.dp),tonalElevation=6.dp) {
        Column(Modifier.padding(16.dp).safeDrawingPadding()) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(profile?.name ?: "Windows",style=MaterialTheme.typography.titleLarge,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text("Wine · Box64 · ${profile?.architecture ?: "—"} · $phase",style=MaterialTheme.typography.labelSmall)
                }
                Button(resume,Modifier.focusRequester(focus).testTag("panel-resume")){Text(tr(R.string.gx_resume))}
            }
            Text(tr(R.string.gx_live_notice),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("home" to R.string.gx_home,"display" to R.string.gx_display,"controls" to R.string.gx_controls,
                    "audio" to R.string.gx_audio,"performance" to R.string.gx_performance,"compatibility" to R.string.gx_compatibility).forEach {(id,label)->
                    FilterChip(tab==id,{tab=id},label={Text(tr(label))},modifier=Modifier.testTag("panel-$id"))
                }
            }
            if(settingsError.isNotEmpty())Text(settingsError.take(512),color=MaterialTheme.colorScheme.error)
            Column(Modifier.weight(1f).verticalScroll(scroll),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                when(tab) {
                    "home" -> {
                        Text(tr(R.string.gx_status,phase))
                        Text(profile?.let {if(it.graphics==GraphicsBackend.DXVK)"DXVK · ${it.graphicsApi}" else "Wine GDI"} ?: "Wine GDI")
                        Text(tr(if(isGpu)R.string.gx_present_count else R.string.gx_software_count,frames))
                        Button({confirm="stop"},Modifier.fillMaxWidth().testTag("display-stop")){Text(tr(R.string.gx_stop))}
                        if(profile!=null)OutlinedButton({confirm="restart"},Modifier.fillMaxWidth().testTag("panel-restart")){Text(tr(R.string.gx_restart))}
                    }
                    "display" -> if(profile!=null) {
                        Text(tr(R.string.gx_live),color=MaterialTheme.colorScheme.primary)
                        ScalingChoices(profile.experience.scaling) {mode ->change {it.copy(experience=it.experience.copy(scaling=mode))}}
                        Text(tr(R.string.gx_scaling_hint),style=MaterialTheme.typography.bodySmall)
                        Text(dimensions,style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("panel-dimensions"))
                        Text(tr(R.string.gx_next_resolution,profile.width,profile.height))
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            listOf(800 to 600,960 to 540,1280 to 720).forEach {(w,h)->
                                OutlinedButton({change {it.copy(width=w,height=h)}},Modifier.testTag("resolution-$w-$h")){Text("$w×$h")}
                            }
                        }
                        Text(tr(R.string.gx_resolution_hint),style=MaterialTheme.typography.bodySmall)
                        Text(tr(R.string.gx_unsupported_display),style=MaterialTheme.typography.bodySmall)
                        Row {Switch(profile.experience.handleVisible,{v->change {it.copy(experience=it.experience.copy(handleVisible=v))}});Text(tr(R.string.gx_handle))}
                        Row {listOf("left" to R.string.gx_left,"right" to R.string.gx_right).forEach {(edge,label)->FilterChip(profile.experience.panelEdge==edge,{change {it.copy(experience=it.experience.copy(panelEdge=edge))}},label={Text(tr(label))})}}
                    }
                    "controls" -> {
                        if(controllers!=null) {
                            val state by controllers.state.collectAsStateWithLifecycle()
                            Text(state.devices.joinToString {"${it.name} · #${it.slot+1}"}.ifBlank {tr(R.string.gx_disconnected)})
                            Text(tr(R.string.gx_input_mode,profile?.controllerMode ?: "—"))
                            state.pads.filter {it.connected}.forEach {p ->Text("#${p.slot+1} · buttons 0x${p.buttons.toString(16)}\nL %.2f / %.2f · R %.2f / %.2f\nLT %.2f · RT %.2f".format(p.leftX,p.leftY,p.rightX,p.rightY,p.leftTrigger,p.rightTrigger),style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("panel-pad-${p.slot}"))}
                            if(profile!=null) {
                                val mapping=profile.controllerMapping ?: state.devices.firstOrNull()?.let {controllers.configuration(it.descriptor)} ?: ControllerMapping()
                                SettingSlider(tr(R.string.gx_deadzone),mapping.rightDeadZone,0f..0.8f,"panel-deadzone") {v->change {it.copy(controllerMapping=mapping.copy(leftDeadZone=v,rightDeadZone=v))}}
                                Row {Switch(mapping.invertRightY,{v->change {it.copy(controllerMapping=mapping.copy(invertRightY=v))}});Text(tr(R.string.gx_invert_y))}
                                if(profile.controllerMode=="keyboard") SettingSlider(tr(R.string.gx_sensitivity),profile.keyboardController.mouseSpeed,50f..6000f,"panel-sensitivity") {v->change {it.copy(keyboardController=it.keyboardController.copy(mouseSpeed=v))}}
                                else Text(tr(R.string.gx_native_sensitivity),style=MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row {OutlinedButton(keyboard,Modifier.testTag("display-keyboard")){Text(tr(R.string.gx_keyboard))};OutlinedButton(escape,Modifier.testTag("display-escape")){Text("Esc")}}
                        if(profile!=null) {
                            Row {Switch(profile.experience.touchVisible,{v->change {it.copy(experience=it.experience.copy(touchVisible=v))}});Text(tr(R.string.gx_touch_visible))}
                            Text(tr(R.string.gx_touch_hint),style=MaterialTheme.typography.bodySmall)
                            org.frostbox.app.input.TouchProfileEditor(profile.touchProfile,profile.controllerMode=="xinput") {v->change {it.copy(touchProfile=v)}}
                        }
                    }
                    "audio" -> {
                        Text(tr(R.string.gx_audio_backend))
                        Text(tr(R.string.gx_audio_enabled,profile?.audioEnabled==true))
                        if(profile!=null && profile.audioEnabled) {
                            Row {Switch(profile.experience.muted,{v->change {it.copy(experience=it.experience.copy(muted=v))}},Modifier.testTag("panel-mute"));Text(tr(R.string.gx_mute))}
                            SettingSlider(tr(R.string.gx_volume),profile.experience.audioGain,0f..1f,"panel-volume") {v->change {it.copy(experience=it.experience.copy(audioGain=v))}}
                        }
                        val report=remember(audio){runCatching {org.json.JSONObject(audio)}.getOrDefault(org.json.JSONObject())}
                        Text(tr(R.string.gx_audio_stats,report.optBoolean("outputInitialized"),report.optLong("totalReceivedFrames"),report.optLong("totalConsumedFrames")),style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("panel-audio-state"))
                        if(report.optString("error").isNotEmpty())Text(report.optString("error"),color=MaterialTheme.colorScheme.error)
                        Text(tr(R.string.gx_audio_hint),style=MaterialTheme.typography.bodySmall)
                    }
                    "performance" -> if(profile!=null) {
                        Text(tr(R.string.gx_live))
                        listOf("off" to R.string.gx_off,"minimal" to R.string.gx_minimal,"detailed" to R.string.gx_detailed).forEach {(mode,label)->FilterChip(profile.experience.hud==mode,{change {it.copy(experience=it.experience.copy(hud=mode))}},label={Text(tr(label))},modifier=Modifier.testTag("hud-$mode"))}
                        Row(Modifier.horizontalScroll(rememberScrollState())) {listOf("top-left","top-right","bottom-left","bottom-right").forEach {corner->FilterChip(profile.experience.hudCorner==corner,{change {it.copy(experience=it.experience.copy(hudCorner=corner))}},label={Text(corner)})}}
                        Text(tr(R.string.gx_hud_hint),style=MaterialTheme.typography.bodySmall)
                    }
                    "compatibility" -> {
                        Text(tr(R.string.gx_compat_hint))
                        val report=remember(diagnostics){runCatching{org.json.JSONObject(diagnostics)}.getOrDefault(org.json.JSONObject())}
                        Text("PID ${report.optInt("pid",-1)} · ${report.optString("phase")} · ${report.optLong("elapsedMs")} ms",style=MaterialTheme.typography.bodySmall)
                        if(report.optString("error").isNotEmpty())Text(report.optString("error").take(1024),color=MaterialTheme.colorScheme.error)
                        Text(report.optString("stdout").takeLast(4096),style=MaterialTheme.typography.bodySmall)
                        Text(report.optString("stderr").takeLast(4096),style=MaterialTheme.typography.bodySmall)
                        OutlinedButton(closeWindow,Modifier.testTag("display-close")){Text(tr(R.string.gx_close_window))}
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit){focus.requestFocus()}
    if(confirm.isNotEmpty()) AlertDialog(onDismissRequest={confirm=""},title={Text(tr(if(confirm=="restart")R.string.gx_restart else R.string.gx_stop))},
        text={Text(tr(R.string.gx_stop_warning))},confirmButton={TextButton({val restartRequested=confirm=="restart";confirm="";if(restartRequested)restart() else stop()},Modifier.testTag("panel-confirm-stop")){Text(tr(R.string.gx_confirm))}},
        dismissButton={TextButton({confirm=""}){Text(tr(R.string.gx_cancel))}})
}

@Composable internal fun ScalingChoices(selected: ScalingMode,change: (ScalingMode)->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {ScalingMode.entries.forEach {mode->
        FilterChip(selected==mode,{change(mode)},label={Text(mode.name.lowercase().replaceFirstChar {it.uppercase()})},modifier=Modifier.testTag("scale-${mode.name}"))
    }}
}
@Composable private fun SettingSlider(label: String,value: Float,range: ClosedFloatingPointRange<Float>,tag: String,change: (Float)->Unit) {
    var draft by remember(value){mutableFloatStateOf(value)}
    Text("$label · ${if(range.endInclusive>1)draft.toInt().toString() else "${(draft*100).toInt()}%"}")
    Slider(draft,{draft=it},valueRange=range,onValueChangeFinished={change(draft)},modifier=Modifier.testTag(tag))
}
