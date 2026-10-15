package org.frostbox.app.input

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window

/** Explicit launcher/tester/game routing. Ordinary keyboard/mouse events stay untouched. */
class ControllerWindowCallback(private val delegate: Window.Callback,private val monitor: ControllerMonitor,
    private val game: ()->Boolean,private val back: ()->Unit): Window.Callback by delegate {
    private var nextNavigation=0L
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        monitor.key(event)
        val pad=monitor.state.value.devices.any {it.id==event.deviceId}
        if(pad){
            if(monitor.testing || game())return true
            if(event.keyCode==KeyEvent.KEYCODE_BUTTON_B){if(event.action==KeyEvent.ACTION_UP)back();return true}
            if(event.keyCode==KeyEvent.KEYCODE_BUTTON_A)return delegate.dispatchKeyEvent(KeyEvent(event.downTime,event.eventTime,event.action,KeyEvent.KEYCODE_DPAD_CENTER,event.repeatCount,event.metaState,event.deviceId,event.scanCode,event.flags,android.view.InputDevice.SOURCE_DPAD))
        }
        return delegate.dispatchKeyEvent(event)
    }
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        monitor.motion(event)
        val info=monitor.state.value.devices.firstOrNull {it.id==event.deviceId}
        if(info!=null){
            if(monitor.testing || game())return true
            val p=monitor.state.value.pads.firstOrNull {it.slot==info.slot}
            val now=android.os.SystemClock.uptimeMillis()
            if(p!=null && now>=nextNavigation && maxOf(kotlin.math.abs(p.leftX),kotlin.math.abs(p.leftY))>.65f){
                nextNavigation=now+180
                val key=if(kotlin.math.abs(p.leftX)>kotlin.math.abs(p.leftY)) {if(p.leftX>0)KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT} else {if(p.leftY>0)KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP}
                delegate.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,key));delegate.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,key));return true
            }
        }
        return delegate.dispatchGenericMotionEvent(event)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean){if(!hasFocus)monitor.reset("Focus lost");delegate.onWindowFocusChanged(hasFocus)}
}
