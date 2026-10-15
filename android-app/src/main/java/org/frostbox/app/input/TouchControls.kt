package org.frostbox.app.input

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.frostbox.core.*
import org.frostbox.app.display.DisplaySession

@Composable fun TouchProfileEditor(value: TouchProfile,inputAvailable: Boolean,onChange: (TouchProfile)->Unit) {
    Row {Checkbox(value.enabled,{onChange(value.copy(enabled=it))});Text("Экранное управление")}
    if(!value.enabled)return
    Row {FilterChip(value.mode=="keyboard",{onChange(value.copy(mode="keyboard"))},label={Text("Клавиши Win32")})
        FilterChip(value.mode=="xinput",{onChange(value.copy(mode="xinput"))},enabled=inputAvailable,label={Text("XInput")})}
    Text("Клавиши: L → WASD, R → стрелки, ABXY → Space/E/Q/R, LB/RB → Shift/Ctrl, LT/RT → Z/X, Start → Esc")
    Text("Экранный XInput занимает слот 1 вместо физического геймпада, пока overlay виден. Клавиатурный режим не отправляет XInput.")
    Text("Прозрачность");Slider(value.opacity,{onChange(value.copy(opacity=it))},valueRange=.1f..1f)
    var selected by remember {mutableStateOf("L")}
    Row {value.elements.chunked(4).forEach {column->Column {column.forEach {e->TextButton(onClick={selected=e.id}){Text(e.id)}}}}}
    value.elements.firstOrNull {it.id==selected}?.let {e->
        fun update(v: TouchElement)=onChange(value.copy(elements=value.elements.map {if(it.id==selected)v else it}))
        Row {Checkbox(e.visible,{update(e.copy(visible=it))});Text("Показывать ${e.id}")}
        Text("Положение по горизонтали");Slider(e.x,{update(e.copy(x=it))})
        Text("Положение по вертикали");Slider(e.y,{update(e.copy(y=it))})
        Text("Размер");Slider(e.scale,{update(e.copy(scale=it))},valueRange=.5f..2f)
    }
}
@Composable fun TouchControls(profile: TouchProfile,session: DisplaySession,monitor: ControllerMonitor) {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var pad by remember {mutableStateOf(GamepadSnapshot(0,"FrostBox touch",true))}
    var keys by remember {mutableStateOf(emptySet<Int>())}
    fun emit(next: GamepadSnapshot){
        pad=next.copy(packet=pad.packet+1)
        if(profile.mode=="xinput")monitor.touch(pad)
        else {val target=touchKeyboardKeys(pad);(keys-target).forEach {session.key(it,true)};(target-keys).forEach {session.key(it)};keys=target}
    }
    fun release(){keys.forEach {session.key(it,true)};keys=emptySet();pad=GamepadSnapshot(0,"FrostBox touch",true);if(profile.mode=="xinput")monitor.touch(pad)}
    DisposableEffect(profile.mode,lifecycle) {
        if(profile.mode=="xinput")monitor.touch(pad)
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_PAUSE)release()}
        lifecycle.addObserver(observer)
        onDispose{release();monitor.touch(null);lifecycle.removeObserver(observer)}
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w=maxWidth;val h=maxHeight
        profile.elements.filter {it.visible}.forEach {e->key(e.id) {
            val stick=e.id in listOf("L","R");val size=(if(stick)100 else 48).dp*e.scale
            val color=MaterialTheme.colorScheme.primaryContainer.copy(alpha=profile.opacity)
            Box(Modifier.offset(x=(w-size).coerceAtLeast(0.dp)*e.x,y=(h-size).coerceAtLeast(0.dp)*e.y).size(size).background(color,CircleShape).testTag("touch-${e.id}")
                .pointerInput(e.id,profile.mode){
                    if(stick)detectDragGestures(onDragStart={p->val (x,y)=radialDeadZone((p.x/this.size.width*2-1).coerceIn(-1f,1f),(p.y/this.size.height*2-1).coerceIn(-1f,1f),.12f);emit(if(e.id=="L")pad.copy(leftX=x,leftY=y)else pad.copy(rightX=x,rightY=y))},
                        onDragEnd={emit(if(e.id=="L")pad.copy(leftX=0f,leftY=0f)else pad.copy(rightX=0f,rightY=0f))},
                        onDragCancel={emit(if(e.id=="L")pad.copy(leftX=0f,leftY=0f)else pad.copy(rightX=0f,rightY=0f))}){change,_->
                            change.consume();val (x,y)=radialDeadZone((change.position.x/this.size.width*2-1).coerceIn(-1f,1f),(change.position.y/this.size.height*2-1).coerceIn(-1f,1f),.12f)
                            emit(if(e.id=="L")pad.copy(leftX=x,leftY=y)else pad.copy(rightX=x,rightY=y))
                        }
                    else awaitEachGesture {
                        val down=awaitFirstDown();down.consume()
                        val mask=mapOf("A" to 4096,"B" to 8192,"X" to 16384,"Y" to 32768,"LB" to 256,"RB" to 512,"Start" to 16)[e.id] ?: 0
                        fun press(on: Boolean){emit(when(e.id){"LT"->pad.copy(leftTrigger=if(on)1f else 0f);"RT"->pad.copy(rightTrigger=if(on)1f else 0f);else->pad.copy(buttons=if(on)pad.buttons or mask else pad.buttons and mask.inv())})}
                        try {press(true);waitForUpOrCancellation()?.consume()}finally{press(false)}
                    }
                },contentAlignment=Alignment.Center){Text(e.id)}
        }}
    }
}
