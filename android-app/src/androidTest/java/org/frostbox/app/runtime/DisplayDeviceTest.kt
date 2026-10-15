package org.frostbox.app.runtime

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.graphics.Bitmap
import android.os.*
import android.provider.DocumentsContract
import android.view.*
import android.view.inputmethod.EditorInfo
import org.frostbox.core.DisplayGeometry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.frostbox.app.*
import org.frostbox.app.data.ProfileRepository
import org.frostbox.app.display.WindowsSurface
import org.frostbox.core.GameProfile
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Wine/GDI buffers, PixelCopy from Android Surface, ordinary in-app event dispatch.
 * No privileged system injection; physical touch and keyboard remain separate manual checks. */
@RunWith(AndroidJUnit4::class)
class DisplayDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private val repo get()=ProfileRepository(context)
    private lateinit var model: FrostModel
    @Before fun setup() {
        Assume.assumeTrue("Experimental display is not bundled",runCatching { context.assets.open("display/manifest.json").use { } }.isSuccess)
        ui.activityRule.scenario.onActivity { model=ViewModelProvider(it)[FrostModel::class.java] }
        val root=File(context.cacheDir,"launcher-qa").apply { mkdirs() }
        context.assets.open("launcher/gui-x64.exe").use { source -> File(root,"gui.exe").outputStream().use { source.copyTo(it) } }
        context.assets.open("launcher/compat-lab-x64.exe").use { source -> File(root,"lab.exe").outputStream().use { source.copyTo(it) } }
    }
    private fun panelAction(tag: String) {
        if(ui.onAllNodesWithTag("game-panel").fetchSemanticsNodes().isEmpty())ui.onNodeWithTag("game-panel-open").performClick()
        if(tag!="display-stop")ui.onNodeWithTag("panel-compatibility").performClick()
        if(tag=="display-keyboard" || tag=="display-escape")ui.onNodeWithTag("panel-controls").performClick()
        ui.onNodeWithTag(tag).performScrollTo().performClick()
        if(tag=="display-stop")ui.onNodeWithTag("panel-confirm-stop").performClick()
    }
    private fun add(file: String="gui.exe"): GameProfile {
        val before=runBlocking { repo.profiles.first().map { it.id }.toSet() }
        ui.onNodeWithTag("nav-Library").performClick();ui.onNodeWithTag("add-game").performClick()
        Intents.init()
        try {
            val uri=DocumentsContract.buildDocumentUri("org.frostbox.app.documents",file)
            Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK,Intent().setData(uri)))
            ui.onNodeWithTag("select-exe").performScrollTo().performClick()
            var profile: GameProfile?=null
            ui.waitUntil(20000) { profile=runBlocking { repo.profiles.first().firstOrNull { it.id !in before } };profile!=null }
            val configured=profile!!.copy(environment="WINEDEBUG=err+all,warn+module,+frost,+explorer,+driver")
            runBlocking { repo.saveEdited(configured) }
            ui.onNodeWithTag("back-library").performClick();return configured
        } finally { Intents.release() }
    }
    private fun start(p: GameProfile,ready: String="FROSTBOX_GUI_READY") {
        // Library now virtualizes large catalogs; an off-screen card has no node yet.
        ui.onNodeWithTag("library-search").performTextReplacement(p.name)
        val records=runBlocking {repo.profiles.first()}.filter {it.name.contains(p.name,true)}.sortedByDescending {it.lastLaunch ?: 0}
        ui.onNodeWithTag("catalog-scroll").performScrollToIndex(records.indexOfFirst {it.id==p.id})
        ui.onNodeWithTag("play-${p.id}").performScrollTo().performClick()
        try {
            ui.waitUntil(180000) { (model.display.status.value.frames>0 && model.launcher.sessions.value[p.id]?.result?.contains(ready)==true) || model.launcher.sessions.value[p.id]?.phase in listOf(LibraryLauncher.Phase.Failed,LibraryLauncher.Phase.Finished) }
            Assert.assertTrue(model.launcher.sessions.value[p.id].toString()+model.display.report(),model.display.status.value.frames>0 && model.launcher.activeId.value==p.id)
        } catch(e: Throwable) { evidence(p,"start-failed");throw e }
    }
    private fun surface(action: (WindowsSurface)->Unit) {
        ui.activityRule.scenario.onActivity { activity ->
            val v=activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<WindowsSurface>("windows-surface")
            Assert.assertNotNull(v);action(v)
        }
    }
    private fun pixels(name: String): Bitmap {
        lateinit var result: Bitmap;val done=CountDownLatch(1);var code=-1
        surface { view ->
            result=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            PixelCopy.request(view,result,{code=it;done.countDown()},Handler(Looper.getMainLooper()))
        }
        Assert.assertTrue(done.await(10,TimeUnit.SECONDS));Assert.assertEquals(PixelCopy.SUCCESS,code)
        val dir=File(context.filesDir,"display-evidence").apply { mkdirs() }
        File(dir,"$name.png").outputStream().use { result.compress(Bitmap.CompressFormat.PNG,100,it) }
        return result
    }
    private fun colorCount(bitmap: Bitmap,color: Int): Int {
        val colors=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(colors,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        return colors.count { it and 0xffffff==color }
    }
    private fun evidence(p: GameProfile,label: String): JSONObject {
        val r=JSONObject(model.launcher.sessions.value[p.id]?.result ?: "{}").put("label",label)
            .put("displayState",model.display.report()).put("testUid",Process.myUid()).put("profileId",p.id)
        val dir=File(context.filesDir,"display-evidence").apply { mkdirs() };File(dir,"$label.json").writeText(r.toString(2))
        inst.sendStatus(2,Bundle().apply { putString("frostbox.display.result",r.toString()) });return r
    }
    private fun finish(p: GameProfile,label: String,cancel: Boolean=false,lab: Boolean=false) {
        ui.waitUntil(60000) { model.launcher.activeId.value==null }
        val r=evidence(p,label)
        Assert.assertEquals(r.toString(),LibraryLauncher.Phase.Finished,model.launcher.sessions.value[p.id]?.phase)
        if(cancel) Assert.assertTrue(r.toString(),r.getBoolean("cancelled")) else {
            Assert.assertEquals(r.toString(),42,r.getInt("exitCode"))
            Assert.assertTrue(r.getString("stdout").contains(if(lab) "LAB_CLOSE" else "FROSTBOX_GUI_CLOSE"))
            if(!lab) Assert.assertFalse("Wine font rendering failed",r.getString("stdout").contains("FROSTBOX_GUI_TEXT_FAILED"))
            Assert.assertFalse("Unicode glyphs unavailable",r.getString("stdout").contains("FROSTBOX_GUI_UNICODE_FAILED"))
            if(!lab) Assert.assertTrue("Unicode rendering not reached",r.getString("stdout").contains("FROSTBOX_GUI_UNICODE_OK"))
        }
        Assert.assertTrue(r.getJSONObject("prefixValidation").getBoolean("valid"))
        Assert.assertFalse(model.display.status.value.active)
    }
    @Test fun controllerKeyboardMouseSoftwareRoute() {
        val device=android.view.InputDevice.getDeviceIds().toList().mapNotNull {android.view.InputDevice.getDevice(it)}.firstOrNull {it.isExternal && it.supportsSource(android.view.InputDevice.SOURCE_GAMEPAD)}
        Assume.assumeTrue("Requires a real device descriptor for the software-event regression",device!=null)
        val p=add().copy(controllerMode="keyboard")
        runBlocking {repo.saveEdited(p)};start(p)
        fun key(code: Int,up: Boolean){ui.activityRule.scenario.onActivity {a->val now=android.os.SystemClock.uptimeMillis();a.window.callback.dispatchKeyEvent(KeyEvent(now,now,if(up)KeyEvent.ACTION_UP else KeyEvent.ACTION_DOWN,code,0,0,device!!.id,0,0,android.view.InputDevice.SOURCE_GAMEPAD))}}
        try {
            key(KeyEvent.KEYCODE_BUTTON_A,false);Thread.sleep(400);key(KeyEvent.KEYCODE_BUTTON_A,true)
            ui.waitUntil(10000){model.launcher.sessions.value[p.id]?.result?.contains("FROSTBOX_GUI_ASYNC_DOWN")==true}
            surface {v->model.display.pointer(v.width*.25f,v.height*.4f,0)};Thread.sleep(100)
            key(KeyEvent.KEYCODE_BUTTON_R2,false);Thread.sleep(400);key(KeyEvent.KEYCODE_BUTTON_R2,true)
            ui.waitUntil(10000){model.launcher.sessions.value[p.id]?.result?.contains("FROSTBOX_GUI_MOUSE")==true}
            panelAction("display-close");finish(p,"controller-keyboard-software-route")
        } finally {if(model.launcher.activeId.value!=null)model.launcher.stop()}
    }
    @Test fun touchControlsReachWin32() {
        val p=add().copy(touchProfile=org.frostbox.core.TouchProfile(enabled=true,mode="keyboard"))
        runBlocking {repo.saveEdited(p)}
        start(p)
        try {
            ui.onNodeWithTag("touch-A").performTouchInput {down(center);advanceEventTime(100);up()}
            ui.waitUntil(10000){model.launcher.sessions.value[p.id]?.result?.contains("FROSTBOX_GUI_KEY")==true}
            val image=pixels("touch-win32-key");Assert.assertTrue(colorCount(image,0xffe030)>1000);image.recycle()
            ui.onNodeWithTag("touch-Start").performTouchInput {click()}
            finish(p,"touch-win32-close")
        } finally {if(model.launcher.activeId.value!=null)model.launcher.stop()}
    }
    @Test fun guiPixelsMouseKeyboardAndClose() {
        val p=add();start(p)
        var first: Bitmap?=null
        try {
            ui.waitUntil(20000) {
                val candidate=pixels("first-frame")
                if(colorCount(candidate,0xe03030)>1000 && colorCount(candidate,0x40b030)>1000) { first=candidate;true }
                else { candidate.recycle();false }
            }
        } catch(e: Throwable) { evidence(p,"pixels-failed");model.launcher.stop();throw e }
        Assert.assertEquals("Backing-buffer padding must not be presented",640,model.display.status.value.width)
        Assert.assertEquals("Backing-buffer padding must not be presented",400,model.display.status.value.height)
        val painted=first!!
        Assert.assertTrue("GDI red rectangle absent",colorCount(painted,0xe03030)>1000)
        Assert.assertTrue("GDI green rectangle absent",colorCount(painted,0x40b030)>1000)
        // This unique color is drawn only by real TextOutW, never by a shape/background.
        Assert.assertTrue("Wine Unicode text pixels absent",colorCount(painted,0xe080ff)>20)
        painted.recycle()
        surface { v ->
            val time=SystemClock.uptimeMillis()
            val down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,v.width*0.25f,v.height*0.4f,0)
            val up=MotionEvent.obtain(time,time+20,MotionEvent.ACTION_UP,v.width*0.25f,v.height*0.4f,0)
            v.dispatchTouchEvent(down);v.dispatchTouchEvent(up);down.recycle();up.recycle()
            v.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_A));v.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_A))
        }
        ui.waitUntil(10000) { model.launcher.sessions.value[p.id]?.result?.let { it.contains("FROSTBOX_GUI_MOUSE") && it.contains("FROSTBOX_GUI_KEY") }==true }
        val changed=pixels("after-input")
        Assert.assertTrue(colorCount(changed,0x30ffa0)>1000);Assert.assertTrue(colorCount(changed,0xffe030)>1000);changed.recycle()
        panelAction("display-close");finish(p,"gui-input-close")
    }
    @Test fun editTextRouteDiagnosis() {
        val p=add("lab.exe");start(p,"LAB_READY")
        fun logs()=JSONObject(model.launcher.sessions.value[p.id]?.result ?: "{}").optString("stdout")
        val offset=Regex("LAB_CLIENT_OFFSET=(\\d+),(\\d+)").find(logs()) ?: error("No client geometry")
        fun click(x: Int,y: Int) { surface { v ->
            val g=DisplayGeometry(640,400,v.width,v.height);val time=SystemClock.uptimeMillis()
            val px=g.left+(x+offset.groupValues[1].toInt())*g.scale
            val py=g.top+(y+offset.groupValues[2].toInt())*g.scale
            val down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,px,py,0);val up=MotionEvent.obtain(time,time+30,MotionEvent.ACTION_UP,px,py,0)
            v.dispatchTouchEvent(down);v.dispatchTouchEvent(up);down.recycle();up.recycle()
        } }
        try {
            click(380,116);ui.waitUntil(10000) { logs().contains("LAB_CHECK=1") }
            val previous=Regex("LAB_EDIT_FOCUS").findAll(logs()).count()
            click(140,164)
            ui.waitUntil(10000) { Regex("LAB_EDIT_FOCUS").findAll(logs()).count()>previous }
            model.display.text("Ж") // Unique UTF-16 unit U+0416 correlates driver and Win32 queue/procedure.
            ui.waitUntil(10000) { logs().contains("LAB_EDIT=Ж") }
        } finally { pixels("text-route-diagnosis");evidence(p,"text-route-diagnosis");model.launcher.stop() }
    }

    @Test fun editUnicodeBridgeExtended() {
        val p=add("lab.exe");start(p,"LAB_READY")
        fun logs()=JSONObject(model.launcher.sessions.value[p.id]?.result ?: "{}").optString("stdout")
        val offset=Regex("LAB_CLIENT_OFFSET=(\\d+),(\\d+)").find(logs()) ?: error("No client geometry")
        fun waitText(prefix: String,value: String) { ui.waitUntil(10000) { logs().contains("$prefix$value\n") } }
        fun click(x: Int,y: Int) { surface { v ->
            val g=DisplayGeometry(640,400,v.width,v.height);val time=SystemClock.uptimeMillis()
            val px=g.left+(x+offset.groupValues[1].toInt())*g.scale
            val py=g.top+(y+offset.groupValues[2].toInt())*g.scale
            val down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,px,py,0);val up=MotionEvent.obtain(time,time+30,MotionEvent.ACTION_UP,px,py,0)
            v.dispatchTouchEvent(down);v.dispatchTouchEvent(up);down.recycle();up.recycle()
        } }
        fun focus(x: Int,y: Int) {
            val previous=Regex("LAB_EDIT_FOCUS").findAll(logs()).count();click(x,y)
            ui.waitUntil(10000) { Regex("LAB_EDIT_FOCUS").findAll(logs()).count()>previous }
        }
        fun clear() {
            val before=Regex("LAB_CLEAR").findAll(logs()).count();click(530,164)
            ui.waitUntil(10000) { Regex("LAB_CLEAR").findAll(logs()).count()>before }
        }
        fun ink(image: Bitmap,y: Int): Int {
            val g=DisplayGeometry(640,400,image.width,image.height)
            val left=(g.left+(24+offset.groupValues[1].toInt())*g.scale).toInt()
            val top=(g.top+(y+offset.groupValues[2].toInt())*g.scale).toInt()
            val right=(g.left+(450+offset.groupValues[1].toInt())*g.scale).toInt()
            val bottom=(g.top+(y+20+offset.groupValues[2].toInt())*g.scale).toInt()
            var count=0
            for(py in top until bottom)for(px in left until right)if(image.getPixel(px,py) and 0xffffff==0)count++
            return count
        }
        try {
            listOf("Hello","Привет","FrostBox 123","ёЁ № €").forEachIndexed { i,text ->
                clear();focus(140,164)
                val empty=pixels("text-empty-$i");val baseline=ink(empty,152);empty.recycle()
                model.display.text(text);waitText("LAB_EDIT=",text)
                ui.waitUntil(10000) {
                    val image=pixels("text-bmp-$i");val visible=ink(image,152)>baseline+20;image.recycle();visible
                }
                evidence(p,"text-bmp-$i")
            }
            model.display.key(8);model.display.key(8,true);waitText("LAB_EDIT=","ёЁ № ")
            evidence(p,"text-backspace")
            focus(140,230);model.display.text("Line1");waitText("LAB_MULTI=","Line1")
            model.display.key(13);model.display.key(13,true);waitText("LAB_MULTI=","Line1\r\n")
            model.display.text("Строка2");waitText("LAB_MULTI=","Line1\r\nСтрока2")
            val multi=pixels("text-multiline");Assert.assertTrue(ink(multi,202)>20);Assert.assertTrue(ink(multi,220)>20);multi.recycle()
            evidence(p,"text-multiline")
            focus(140,164);model.display.text("OK");waitText("LAB_EDIT=","ёЁ № OK")
            ui.activityRule.scenario.recreate();ui.waitUntil(20000) { model.display.status.value.frames>=2 }
            model.display.text("!");waitText("LAB_EDIT=","ёЁ № OK!");evidence(p,"text-after-recreate")
            model.display.text("😀");waitText("LAB_EDIT=","ёЁ № OK!😀");evidence(p,"text-surrogate-pair")
            clear();waitText("LAB_CLEAR_EDIT=","");waitText("LAB_CLEAR_MULTI=","")
            Assert.assertTrue(logs().contains("LAB_CHAR_STATE=258"));Assert.assertTrue(logs().contains("LAB_QUEUE_CHAR=258"))
            Assert.assertEquals(640,model.display.status.value.width);Assert.assertEquals(400,model.display.status.value.height)
            panelAction("display-close");finish(p,"text-extended-close",lab=true)
        } catch(e: Throwable) { pixels("text-extended-failed");evidence(p,"text-extended-failed");model.launcher.stop();throw e }
    }
    @Test fun committedImeAndBack() {
        val p=add("lab.exe");start(p,"LAB_READY")
        fun logs()=JSONObject(model.launcher.sessions.value[p.id]?.result ?: "{}").optString("stdout")
        fun imeVisible(): Boolean {
            var visible=false
            surface { v -> visible=androidx.core.view.ViewCompat.getRootWindowInsets(v)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true }
            return visible
        }
        try {
            panelAction("display-keyboard");ui.waitUntil(10000) { imeVisible() }
            surface { v -> val connection=v.onCreateInputConnection(EditorInfo())
                Assert.assertTrue(connection.setComposingText("не",1)) }
            Assert.assertFalse("Local preedit must not reach Windows",logs().contains("LAB_EDIT=не"))
            surface { v -> Assert.assertTrue(v.onCreateInputConnection(EditorInfo()).commitText("IME Привет",1)) }
            ui.waitUntil(10000) { logs().contains("LAB_EDIT=IME Привет\n") }
            ui.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            ui.waitUntil(10000) { !imeVisible() }
            Assert.assertEquals(p.id,model.launcher.activeId.value)
            evidence(p,"ime-commit-back");pixels("ime-commit-back").recycle()
            panelAction("display-close");finish(p,"ime-close",lab=true)
        } catch(e: Throwable) { evidence(p,"ime-failed");model.launcher.stop();throw e }
    }
    @Test fun compatibilityLabControlsAndClose() {
        val p=add("lab.exe");start(p,"LAB_READY")
        fun logs()=JSONObject(model.launcher.sessions.value[p.id]?.result ?: "{}").optString("stdout")
        val offset=Regex("LAB_CLIENT_OFFSET=(\\d+),(\\d+)").find(logs()) ?: error("No client geometry")
        fun click(x: Int,y: Int) { surface { v ->
            val g=DisplayGeometry(640,400,v.width,v.height)
            val px=g.left+(x+offset.groupValues[1].toInt())*g.scale
            val py=g.top+(y+offset.groupValues[2].toInt())*g.scale;val time=SystemClock.uptimeMillis()
            val down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,px,py,0)
            val up=MotionEvent.obtain(time,time+20,MotionEvent.ACTION_UP,px,py,0)
            v.dispatchTouchEvent(down);v.dispatchTouchEvent(up);down.recycle();up.recycle()
        } }
        click(70,116);ui.waitUntil(10000){logs().contains("LAB_COUNT=Count: 1")}
        click(380,116);ui.waitUntil(10000){logs().contains("LAB_CHECK=1")}
        click(530,164);ui.waitUntil(10000){logs().contains("LAB_CLEAR")}
        val image=pixels("lab-controls-only");Assert.assertTrue(image.width>0);image.recycle()
        Assert.assertEquals(640,model.display.status.value.width);Assert.assertEquals(400,model.display.status.value.height)
        panelAction("display-close");finish(p,"lab-controls-only",lab=true)
        start(p,"LAB_READY");panelAction("display-stop");finish(p,"lab-repeat-stop",cancel=true,lab=true)
    }
    @Test fun compatibilityLabControlsAndUnicode() {
        val p=add("lab.exe");start(p,"LAB_READY")
        fun logs()=JSONObject(model.launcher.sessions.value[p.id]?.result ?: "{}").optString("stdout")
        fun waitLog(value: String) { try { ui.waitUntil(10000) { logs().contains(value) } }
            catch(e: Throwable) { pixels("lab-failed");evidence(p,"lab-failed");model.launcher.stop();throw e } }
        val origin=Regex("LAB_CLIENT_OFFSET=(\\d+),(\\d+)").find(logs()) ?: error("No Win32 client geometry")
        val cx=origin.groupValues[1].toInt();val cy=origin.groupValues[2].toInt()
        fun click(x: Int,y: Int) { surface { v ->
            val g=DisplayGeometry(model.display.status.value.width,model.display.status.value.height,v.width,v.height)
            val px=g.left+(x+cx)*g.scale;val py=g.top+(y+cy)*g.scale;val time=SystemClock.uptimeMillis()
            val down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,px,py,0)
            val up=MotionEvent.obtain(time,time+30,MotionEvent.ACTION_UP,px,py,0)
            v.dispatchTouchEvent(down);v.dispatchTouchEvent(up);down.recycle();up.recycle()
        } }
        Assert.assertEquals(640,model.display.status.value.width);Assert.assertEquals(400,model.display.status.value.height)
        click(70,116);waitLog("LAB_COUNT=Count: 1")
        click(380,116);waitLog("LAB_CHECK=1")
        val beforeEditFocus=Regex("LAB_EDIT_FOCUS").findAll(logs()).count()
        click(140,164)
        ui.waitUntil(10000) { Regex("LAB_EDIT_FOCUS").findAll(logs()).count()>beforeEditFocus }
        surface { v -> val connection=v.onCreateInputConnection(EditorInfo())
            Assert.assertTrue(connection.setComposingText("не",1))
            Assert.assertTrue(connection.commitText("Hello Привет ё Ё № €",1)) }
        waitLog("LAB_EDIT=Hello Привет ё Ё № €")
        surface { v -> Assert.assertTrue(v.onCreateInputConnection(EditorInfo()).deleteSurroundingText(1,0)) }
        ui.waitUntil(10000) { Regex("LAB_EDIT=([^\\n]*)\n").findAll(logs()).lastOrNull()?.groupValues?.get(1)=="Hello Привет ё Ё № " }
        surface { v -> v.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER));v.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER)) }
        waitLog("LAB_ENTER")
        val beforeMultiFocus=Regex("LAB_EDIT_FOCUS").findAll(logs()).count()
        click(140,230)
        ui.waitUntil(10000) { Regex("LAB_EDIT_FOCUS").findAll(logs()).count()>beforeMultiFocus }
        surface { v -> val connection=v.onCreateInputConnection(EditorInfo());connection.commitText("Line1",1) }
        waitLog("LAB_MULTI=Line1\n")
        surface { v -> val connection=v.onCreateInputConnection(EditorInfo());connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER));connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER))
            }
        waitLog("LAB_MULTI=Line1\r\n\n")
        surface { v -> v.onCreateInputConnection(EditorInfo()).commitText("Строка2",1) }
        waitLog("LAB_MULTI=Line1\r\nСтрока2\n")
        val image=pixels("compatibility-lab");Assert.assertTrue(image.width>0);image.recycle()
        ui.activityRule.scenario.recreate();ui.waitUntil(20000) { model.display.status.value.frames>=2 }
        click(70,116);waitLog("LAB_COUNT=Count: 2")
        click(530,164);waitLog("LAB_CLEAR")
        panelAction("display-close");finish(p,"lab-controls-unicode",lab=true)
        start(p,"LAB_READY");panelAction("display-stop");finish(p,"lab-repeat-stop",cancel=true,lab=true)
    }
    @Test fun tenGuiLaunches() {
        val p=add()
        repeat(10) { index -> start(p);val image=pixels("repeat-$index");Assert.assertTrue(colorCount(image,0xe03030)>1000);image.recycle()
            panelAction("display-close");finish(p,"repeat-$index") }
    }
    @Test fun recreateSurfaceAndStop() {
        val p=add();start(p);ui.activityRule.scenario.recreate()
        ui.waitUntil(20000) { model.display.status.value.frames>=2 }
        val image=pixels("recreated");Assert.assertTrue(colorCount(image,0xe03030)>1000);image.recycle()
        panelAction("display-stop");finish(p,"recreate-stop",true)
    }
    @Test fun runtimeDeathClosesDisplay() {
        val p=add();start(p)
        val runtime=JSONObject(model.launcher.sessions.value.getValue(p.id).result).getInt("runtimePid")
        Assert.assertNotEquals(Process.myPid(),runtime)
        Process.killProcess(runtime)
        ui.waitUntil(20000) { model.launcher.activeId.value==null }
        Assert.assertEquals(LibraryLauncher.Phase.Failed,model.launcher.sessions.value[p.id]?.phase)
        Assert.assertFalse(model.display.status.value.active);evidence(p,"runtime-death")
        start(p);panelAction("display-close");finish(p,"after-recovery")
    }
}
