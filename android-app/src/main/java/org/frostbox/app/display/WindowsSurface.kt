package org.frostbox.app.display

import android.content.Context
import android.graphics.PixelFormat
import android.text.InputType
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.*
import android.view.inputmethod.*

/** Ordinary app input. IME commits UTF-16; preedit stays local until commit/finish. */
class WindowsSurface(context: Context,private val session: DisplaySession,private val gpu: GuestWsiSession? = null): SurfaceView(context),SurfaceHolder.Callback {
    private val pressed=mutableSetOf<Int>()
    private var pointerId=-1;private var pointerX=0f;private var pointerY=0f
    private var pointerRelease=0
    private var composition=""
    init { holder.setFormat(PixelFormat.RGBA_8888);gpu?.let { holder.setFixedSize(it.width,it.height) };holder.addCallback(this);isFocusableInTouchMode=true;isFocusable=true;contentDescription="Windows display";tag="windows-surface" }
    override fun surfaceCreated(holder: SurfaceHolder) { if(gpu!=null)gpu.attach(holder.surface) else session.attach(holder.surface,width,height);requestFocus();session.focus(hasWindowFocus()) }
    override fun surfaceChanged(holder: SurfaceHolder,format: Int,width: Int,height: Int) { if(gpu!=null)gpu.attach(holder.surface) else session.attach(holder.surface,width,height) }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { super.onSizeChanged(w,h,oldw,oldh);if(gpu!=null)session.inputViewport(w,h) }
    override fun surfaceDestroyed(holder: SurfaceHolder) { releaseInput();if(gpu!=null)gpu.lostSurface() else session.attach(null) }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus);if(!hasWindowFocus) { composition="";releaseInput() };session.focus(hasWindowFocus) }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        requestFocus()
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId=event.getPointerId(0);pointerX=event.x;pointerY=event.y
                val secondary=event.buttonState and MotionEvent.BUTTON_SECONDARY!=0
                pointerRelease=if(secondary) 16 else 4
                session.pointer(pointerX,pointerY,if(secondary) 8 else 2)
            }
            MotionEvent.ACTION_MOVE -> {
                val index=event.findPointerIndex(pointerId)
                if(index>=0){pointerX=event.getX(index);pointerY=event.getY(index);session.pointer(pointerX,pointerY,0)}
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP -> {
                if(event.getPointerId(event.actionIndex)==pointerId) {
                    pointerX=event.getX(event.actionIndex);pointerY=event.getY(event.actionIndex)
                    releasePointer();performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> releasePointer()
        };return true
    }
    private fun releasePointer() {
        // An unmatched right-button UP opens Wine Edit's context menu even on an ordinary tap.
        if(pointerRelease!=0) session.pointer(pointerX,pointerY,pointerRelease)
        pointerRelease=0;pointerId=-1
    }
    private fun releaseInput() { pressed.forEach { session.key(it,true) };pressed.clear();releasePointer() }
    override fun onDetachedFromWindow() { composition="";releaseInput();super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick();return true }
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if(event.actionMasked==MotionEvent.ACTION_HOVER_MOVE) { session.pointer(event.x,event.y,0);return true }
        return super.onGenericMotionEvent(event)
    }
    fun showKeyboard() { requestFocus();(context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this,InputMethodManager.SHOW_IMPLICIT) }
    fun hideKeyboard(): Boolean {
        val visible=ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime())==true
        if(visible)(context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken,0)
        return visible
    }
    override fun onCheckIsTextEditor()=true // Committed text; preedit is local, not a Wine IME implementation.
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions=EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object: BaseInputConnection(this,false) {
            override fun commitText(text: CharSequence?,newCursorPosition: Int): Boolean {
                if(text==null || text.length>2048 || text.contains('\u0000'))return false
                composition="";session.text(text);return true
            }
            override fun setComposingText(text: CharSequence?,newCursorPosition: Int): Boolean {
                if(text==null || text.length>2048 || text.contains('\u0000'))return false
                composition=text.toString();return true // No inline Windows composition/selection claim.
            }
            override fun finishComposingText(): Boolean { if(composition.isNotEmpty())session.text(composition);composition="";return true }
            override fun deleteSurroundingText(beforeLength: Int,afterLength: Int): Boolean {
                if(beforeLength !in 0..128 || afterLength !in 0..128)return false
                if(composition.isNotEmpty()) { composition=composition.dropLast(beforeLength.coerceAtMost(composition.length));return afterLength==0 }
                repeat(beforeLength){session.key(8);session.key(8,true)}
                repeat(afterLength){session.key(46);session.key(46,true)};return true
            }
            override fun sendKeyEvent(event: KeyEvent)=dispatchKeyEvent(event)
            override fun performEditorAction(actionCode: Int): Boolean {session.key(13);session.key(13,true);return true}
        }
    }
    private fun virtualKey(code: Int): Int? = when(code) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 65+code-KeyEvent.KEYCODE_A
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> 48+code-KeyEvent.KEYCODE_0
        KeyEvent.KEYCODE_ENTER -> 13;KeyEvent.KEYCODE_SPACE -> 32;KeyEvent.KEYCODE_TAB -> 9
        KeyEvent.KEYCODE_DEL -> 8;KeyEvent.KEYCODE_FORWARD_DEL -> 46
        KeyEvent.KEYCODE_MOVE_HOME -> 36;KeyEvent.KEYCODE_MOVE_END -> 35
        KeyEvent.KEYCODE_ESCAPE,KeyEvent.KEYCODE_BACK -> 27
        KeyEvent.KEYCODE_DPAD_LEFT -> 37;KeyEvent.KEYCODE_DPAD_UP -> 38;KeyEvent.KEYCODE_DPAD_RIGHT -> 39;KeyEvent.KEYCODE_DPAD_DOWN -> 40
        KeyEvent.KEYCODE_SHIFT_LEFT,KeyEvent.KEYCODE_SHIFT_RIGHT -> 16
        KeyEvent.KEYCODE_CTRL_LEFT,KeyEvent.KEYCODE_CTRL_RIGHT -> 17
        KeyEvent.KEYCODE_ALT_LEFT,KeyEvent.KEYCODE_ALT_RIGHT -> 18
        else -> null
    }
    override fun onKeyDown(keyCode: Int,event: KeyEvent): Boolean { virtualKey(keyCode)?.let { pressed.add(it);session.key(it);return true };return super.onKeyDown(keyCode,event) }
    override fun onKeyUp(keyCode: Int,event: KeyEvent): Boolean { virtualKey(keyCode)?.let { pressed.remove(it);session.key(it,true);return true };return super.onKeyUp(keyCode,event) }
}
