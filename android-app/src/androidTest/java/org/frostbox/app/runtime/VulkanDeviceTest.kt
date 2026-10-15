package org.frostbox.app.runtime

import android.graphics.Bitmap
import android.os.*
import android.view.PixelCopy
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.frostbox.app.FrostModel
import org.frostbox.app.MainActivity
import org.frostbox.app.gpu.VulkanSurface
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Native ARM64 Vulkan under target UID. PixelCopy verifies the actual GPU-backed Surface. */
@RunWith(AndroidJUnit4::class)
class VulkanDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var model: FrostModel
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    @Before fun setup() {ui.activityRule.scenario.onActivity {model=ViewModelProvider(it)[FrostModel::class.java]}}
    private fun report()=JSONObject(model.gpu.report.value)
    private fun evidence(label: String) {
        val r=report().put("label",label).put("testUid",Process.myUid())
        val dir=File(inst.targetContext.filesDir,"vulkan-evidence").apply {mkdirs()};File(dir,"$label.json").writeText(r.toString(2))
        inst.sendStatus(2,Bundle().apply {putString("frostbox.vulkan.result",r.toString())})
    }
    private fun open() {ui.onNodeWithTag("nav-Settings").performClick();ui.onNodeWithTag("nav-Vulkan Diagnostics").performScrollTo().performClick()}
    private fun waitFrames(count: Long=10) {
        ui.waitUntil(30000) {(report().optString("state")=="Running" && report().optLong("presentedFrames")>=count) || report().optString("state")=="Failed"}
        Assert.assertEquals(report().toString(),"Running",report().getString("state"))
        Assert.assertTrue(report().toString(),report().getBoolean("deviceCreated"));Assert.assertTrue(report().getBoolean("swapchainCreated"))
    }
    private fun pixels(label: String): Bitmap {
        val done=CountDownLatch(1);var code=-1;lateinit var bitmap: Bitmap
        ui.activityRule.scenario.onActivity {a ->
            val v=a.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<VulkanSurface>("vulkan-surface")
            Assert.assertNotNull(v);bitmap=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888)
            PixelCopy.request(v,bitmap,{code=it;done.countDown()},Handler(Looper.getMainLooper()))
        }
        Assert.assertTrue(done.await(10,TimeUnit.SECONDS));Assert.assertEquals(PixelCopy.SUCCESS,code)
        val dir=File(inst.targetContext.filesDir,"vulkan-evidence").apply {mkdirs()};File(dir,"$label.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        return bitmap
    }
    private fun verifyTriangle(bitmap: Bitmap) {
        val colors=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(colors,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val red=colors.count { val r=it shr 16 and 255;val g=it shr 8 and 255;val b=it and 255;r>120 && r>g+30 && r>b+30 }
        val green=colors.count {val r=it shr 16 and 255;val g=it shr 8 and 255;val b=it and 255;g>120 && g>r+30 && g>b+30}
        val blue=colors.count {val r=it shr 16 and 255;val g=it shr 8 and 255;val b=it and 255;b>120 && b>r+30 && b>g+30}
        Assert.assertTrue("Shader triangle RGB missing: $red/$green/$blue",red>100 && green>100 && blue>100)
        var left=bitmap.width;var right=0;var top=bitmap.height;var bottom=0
        for(y in 0 until bitmap.height)for(x in 0 until bitmap.width) {
            val pixel=colors[y*bitmap.width+x];val r=pixel shr 16 and 255;val g=pixel shr 8 and 255;val b=pixel and 255
            if(maxOf(r,g,b)>120 && maxOf(r,g,b)-minOf(r,g,b)>30) {left=minOf(left,x);right=maxOf(right,x);top=minOf(top,y);bottom=maxOf(bottom,y)}
        }
        val aspect=(right-left+1).toDouble()/(bottom-top+1)
        Assert.assertTrue("Surface transform distorted triangle: bounds ${right-left+1}x${bottom-top+1}",aspect in 0.5..2.0)
    }
    @Test fun realGpuProbe() {
        open();ui.onNodeWithTag("gpu-probe").performClick()
        ui.waitUntil(15000) {JSONObject(model.gpu.capabilities.value).has("devices") || report().optString("state")=="Failed"}
        val caps=JSONObject(model.gpu.capabilities.value);Assert.assertEquals(Process.myUid(),caps.getInt("uid"))
        val d=caps.getJSONArray("devices").getJSONObject(0);Assert.assertNotEquals(4,d.getInt("deviceType"));Assert.assertTrue(d.getBoolean("swapchain"));Assert.assertTrue(d.getJSONArray("extensions").length()>0)
        val r=JSONObject().put("capabilities",caps).put("label","headless-probe").put("testUid",Process.myUid())
        File(inst.targetContext.filesDir,"vulkan-evidence").apply {mkdirs()}.resolve("headless-probe.json").writeText(r.toString(2))
        inst.sendStatus(2,Bundle().apply {putString("frostbox.vulkan.result",r.toString())})
    }
    @Test fun realTriangleAndLifecycle() {
        open();ui.onNodeWithTag("gpu-start").performClick()
        try {
            waitFrames(30);val first=pixels("native-triangle");verifyTriangle(first)
            val before=report().getLong("presentedFrames");waitFrames(before+30)
            val second=pixels("native-triangle-moved");verifyTriangle(second)
            var changed=0;for(y in 0 until minOf(first.height,second.height) step 8)for(x in 0 until minOf(first.width,second.width) step 8)if(first.getPixel(x,y)!=second.getPixel(x,y))changed++
            Assert.assertTrue("Triangle animation absent",changed>100);first.recycle();second.recycle();evidence("native-render")
            val rotationCreations=report().getLong("swapchainCreations")
            ui.activityRule.scenario.onActivity { it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            ui.waitUntil(30000) {report().optLong("swapchainCreations")>rotationCreations && report().optString("state")=="Running"}
            val rotated=pixels("rotated");verifyTriangle(rotated);Assert.assertTrue(rotated.width>rotated.height);rotated.recycle();evidence("rotated")
            val creations=report().getLong("swapchainCreations")
            ui.activityRule.scenario.recreate()
            ui.waitUntil(30000) {report().optLong("swapchainCreations")>creations && report().optString("state")=="Running"}
            val recreated=pixels("surface-recreated");verifyTriangle(recreated);recreated.recycle();evidence("surface-recreated")
            ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            ui.waitUntil(10000) {report().optString("state")=="Paused"}
            val paused=report().getLong("presentedFrames");evidence("background-paused")
            val idle=CountDownLatch(1);Handler(Looper.getMainLooper()).postDelayed({idle.countDown()},300);Assert.assertTrue(idle.await(2,TimeUnit.SECONDS));Assert.assertEquals(paused,report().getLong("presentedFrames"))
            ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);waitFrames(paused+10);evidence("foreground-resumed")
            ui.onNodeWithTag("gpu-stop").performClick();ui.waitUntil(10000) {report().optString("state")=="Stopped"};Assert.assertFalse(report().getBoolean("deviceCreated"));evidence("stopped")
            ui.onNodeWithTag("gpu-start").performClick();waitFrames(30);val restarted=pixels("restarted");verifyTriangle(restarted);restarted.recycle()
            evidence("restarted");ui.onNodeWithTag("gpu-save").performClick()
            ui.waitUntil(10000) {runCatching {
                val saved=JSONObject(File(inst.targetContext.filesDir,"vulkan-diagnostics/render.json").readText())
                saved.getInt("pid")==Process.myPid() && saved.getString("state")=="Running" && saved.getLong("presentedFrames")>=30
            }.getOrDefault(false)}
            ui.onNodeWithTag("gpu-stop").performClick();ui.waitUntil(10000) {report().optString("state")=="Stopped"};evidence("final-stopped")
        } catch(e: Throwable) {evidence("vulkan-failed");model.gpu.stop();throw e}
    }
}
