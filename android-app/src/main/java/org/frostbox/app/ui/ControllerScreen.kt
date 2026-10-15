package org.frostbox.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.frostbox.app.R
import org.frostbox.app.input.ControllerMonitor
import org.frostbox.core.ControllerMapping

@Composable fun ControllerMappingEditor(value: ControllerMapping,onChange: (ControllerMapping)->Unit) {
    Text(stringResource(R.string.pad_left_dead,value.leftDeadZone))
    Slider(value.leftDeadZone,{onChange(value.copy(leftDeadZone=it))},valueRange=0f..0.5f)
    Text(stringResource(R.string.pad_right_dead,value.rightDeadZone))
    Slider(value.rightDeadZone,{onChange(value.copy(rightDeadZone=it))},valueRange=0f..0.5f)
    Text(stringResource(R.string.pad_trigger_dead,value.triggerDeadZone))
    Slider(value.triggerDeadZone,{onChange(value.copy(triggerDeadZone=it))},valueRange=0f..0.3f)
    Row {Checkbox(value.invertLeftY,{onChange(value.copy(invertLeftY=it))});Text(stringResource(R.string.pad_invert_left))}
    Row {Checkbox(value.invertRightY,{onChange(value.copy(invertRightY=it))});Text(stringResource(R.string.pad_invert_right))}
    var axesMenu by remember {mutableStateOf(false)}
    Box {OutlinedButton(onClick={axesMenu=true}){Text(stringResource(R.string.pad_right_axes,value.rightAxes))}
        DropdownMenu(axesMenu,{axesMenu=false}) {listOf("auto","rx-ry","z-rz").forEach {axes->DropdownMenuItem(text={Text(axes)},onClick={onChange(value.copy(rightAxes=axes));axesMenu=false})}}}
    val buttons=listOf(96 to "A",97 to "B",99 to "X",100 to "Y",102 to "LB",103 to "RB",106 to "L3",107 to "R3",108 to "Start",109 to "Back",19 to "↑",20 to "↓",21 to "←",22 to "→")
    val masks=listOf(4096,8192,16384,32768,256,512,64,128,16,32,1,2,4,8)
    var editKey by remember {mutableStateOf<Int?>(null)}
    var mappingsVisible by remember {mutableStateOf(false)}
    TextButton(onClick={mappingsVisible=!mappingsVisible}){Text(stringResource(R.string.pad_mapping))}
    if(mappingsVisible)buttons.forEachIndexed {i,(key,label)->
        Box {TextButton(onClick={editKey=key}){Text("$label → ${buttons[masks.indexOf(value.buttons[key] ?: masks[i])].second}")}
            DropdownMenu(editKey==key,{editKey=null}){buttons.forEachIndexed {j,(_,target)->DropdownMenuItem(text={Text(target)},onClick={onChange(value.copy(buttons=value.buttons+(key to masks[j])));editKey=null})}}}
    }
}
@Composable private fun StickPlot(x: Float,y: Float,dead: Float) {
    val color=MaterialTheme.colorScheme.primary;val muted=MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.size(110.dp)) {
        val radius=size.minDimension/2;drawCircle(muted,radius)
        drawCircle(color.copy(alpha=.2f),radius*dead)
        drawCircle(color,6.dp.toPx(),Offset(center.x+x*radius,center.y+y*radius))
    }
}
@Composable fun ControllerScreen(monitor: ControllerMonitor) {
    val context=LocalContext.current;val state by monitor.state.collectAsStateWithLifecycle()
    DisposableEffect(monitor){monitor.testing=true;monitor.reset("Controller tester opened");onDispose{monitor.testing=false;monitor.reset("Controller tester closed")}}
    Text(stringResource(R.string.pad_tester),style=MaterialTheme.typography.headlineSmall)
    Button(onClick={runCatching {context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))}.onFailure {android.widget.Toast.makeText(context,it.message,android.widget.Toast.LENGTH_LONG).show()}}){Text(stringResource(R.string.pad_bluetooth))}
    if(state.devices.isEmpty())Text(stringResource(R.string.pad_missing))
    state.devices.forEach {info->key(info.descriptor) {
        var config by remember(info.descriptor) {mutableStateOf(monitor.configuration(info.descriptor))}
        var edit by remember {mutableStateOf(false)}
        var saved by remember {mutableStateOf(false)}
        val pad=state.pads.firstOrNull {it.slot==info.slot}
        Card {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(info.name,style=MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.pad_slot,info.slot+1,info.axes))
            if(pad!=null){
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){StickPlot(pad.leftX,pad.leftY,config.leftDeadZone);StickPlot(pad.rightX,pad.rightY,config.rightDeadZone)}
                Text("L ${"%.2f".format(pad.leftX)}, ${"%.2f".format(pad.leftY)} · R ${"%.2f".format(pad.rightX)}, ${"%.2f".format(pad.rightY)}")
                Text("LT ${"%.2f".format(pad.leftTrigger)} · RT ${"%.2f".format(pad.rightTrigger)}")
                LinearProgressIndicator(progress={pad.leftTrigger},modifier=Modifier.fillMaxWidth());LinearProgressIndicator(progress={pad.rightTrigger},modifier=Modifier.fillMaxWidth())
                val names=listOf(1 to "↑",2 to "↓",4 to "←",8 to "→",16 to "Start",32 to "Back",64 to "L3",128 to "R3",256 to "LB",512 to "RB",4096 to "A",8192 to "B",16384 to "X",32768 to "Y")
                Text(names.filter {pad.buttons and it.first!=0}.joinToString(" · "){it.second}.ifEmpty {"—"},modifier=Modifier.testTag("pad-buttons"))
            }
            if(monitor.canVibrate(info.id))OutlinedButton(onClick={runCatching {monitor.vibrate(info.id)}.onFailure {android.widget.Toast.makeText(context,it.message,android.widget.Toast.LENGTH_LONG).show()}}){Text(stringResource(R.string.pad_rumble))}
            TextButton(onClick={edit=!edit}){Text(stringResource(R.string.pad_settings))}
            if(edit){ControllerMappingEditor(config,{config=it;saved=false})
                Row {Button(onClick={monitor.saveConfiguration(info.descriptor,config){result->saved=result.isSuccess;result.exceptionOrNull()?.let {android.widget.Toast.makeText(context,"Ошибка сохранения: ${it.message}",android.widget.Toast.LENGTH_LONG).show()}}}){Text(stringResource(R.string.pad_save))};TextButton(onClick={config=ControllerMapping();saved=false}){Text(stringResource(R.string.pad_reset))}}
                if(saved)Text(stringResource(R.string.pad_saved))
            }
        }}
    }}
    Text(state.lastEvent,modifier=Modifier.testTag("pad-event"))
    Text(stringResource(R.string.pad_boundary))
}
