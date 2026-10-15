package org.frostbox.app.input
import kotlinx.coroutines.*
import org.frostbox.app.display.DisplaySession
import org.frostbox.core.*
import org.json.JSONObject

/** Explicit per-game keyboard/mouse mode. Never publishes a virtual XInput device. */
class ControllerKeyboardBridge(private val monitor: ControllerMonitor,private val display: DisplaySession,
    @Volatile private var profile: KeyboardControllerProfile,scope: CoroutineScope): AutoCloseable {
    private var keys=emptySet<Int>();private var left=false;private var right=false
    private var dx=0f;private var dy=0f;private var keyEvents=0;private var mousePackets=0
    private val job=scope.launch {
        var previous=android.os.SystemClock.uptimeMillis()
        try {while(isActive) {
            val now=android.os.SystemClock.uptimeMillis();val dt=((now-previous)/1000f).coerceIn(0f,.05f);previous=now
            val pad=monitor.runtimePads().firstOrNull {it.connected} ?: GamepadSnapshot(0,"")
            val ready=display.hasInputTarget()
            val target=if(ready)controllerKeyboardKeys(pad,profile)else emptySet()
            (keys-target).forEach {display.key(it,true);keyEvents++};(target-keys).forEach {display.key(it);keyEvents++};keys=target
            val l=ready && pad.connected && pad.rightTrigger>.4f;val r=ready && pad.connected && pad.leftTrigger>.4f
            var flags=0;if(l!=left)flags=flags or if(l)2 else 4;if(r!=right)flags=flags or if(r)8 else 16;left=l;right=r
            if(ready && pad.connected){dx+=pad.rightX*profile.mouseSpeed*dt;dy+=pad.rightY*profile.mouseSpeed*dt}else{dx=0f;dy=0f}
            val x=dx.toInt();val y=dy.toInt();dx-=x;dy-=y
            if(x!=0 || y!=0 || flags!=0){display.relativePointer(x,y,flags);mousePackets++}
            delay(16)
        }} finally {release()}
    }
    private fun release(){keys.forEach {display.key(it,true)};keys=emptySet();if(left || right)display.relativePointer(0,0,(if(left)4 else 0)or(if(right)16 else 0));left=false;right=false;dx=0f;dy=0f}
    fun update(value: KeyboardControllerProfile){value.validate();profile=value}
    fun report()=JSONObject().put("mode","keyboard-mouse").put("keyEvents",keyEvents).put("mousePackets",mousePackets)
    override fun close(){job.cancel();release()}
}
