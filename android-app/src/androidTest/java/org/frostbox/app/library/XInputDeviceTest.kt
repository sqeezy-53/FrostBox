package org.frostbox.app.library

import android.os.Bundle
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.frostbox.app.MainActivity
import org.frostbox.app.FrostModel
import org.frostbox.app.data.ProfileRepository
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

class XInputDeviceTest {
 @get:Rule val ui=createAndroidComposeRule<MainActivity>()
 @Test fun windowsAudioEndpointProbe() {
    val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
    val file=File(context.cacheDir,"launcher-qa/audio.exe");file.parentFile!!.mkdirs()
    context.assets.open("launcher/audio-x64.exe").use {input->file.outputStream().use {input.copyTo(it)}}
    val repo=ProfileRepository(context);val p=runBlocking {repo.importExe(DocumentsContract.buildDocumentUri("org.frostbox.app.documents","audio.exe"))}
    lateinit var model: FrostModel
    ui.activityRule.scenario.onActivity {model=ViewModelProvider(it)[FrostModel::class.java];model.play(p)}
    ui.waitUntil(180000){model.launcher.activeId.value==null}
    val result=JSONObject(model.launcher.sessions.value[p.id]?.result ?: "{}")
    File(context.filesDir,"audio-validation.json").writeText(result.toString(2))
    inst.sendStatus(2,Bundle().apply {putString("frostbox.audio.result",result.toString())})
    val code=result.optInt("exitCode",-1)
    assertTrue(result.toString(),code==42 || code==77)
    assertTrue(if(code==77)result.optString("stdout").contains("AUDIO_NO_ENDPOINT")else result.optString("stdout").contains("AUDIO_SOFTWARE_PASS_AUDIBILITY_UNCONFIRMED"))
 }
 @Test fun physicalAndroidControllerReachesWindowsXInput() {
    val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
    Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("frostbox.physicalPad")=="true")
    val file=File(context.cacheDir,"launcher-qa/xinput.exe");file.parentFile!!.mkdirs()
    context.assets.open("launcher/xinput-x64.exe").use {input->file.outputStream().use {input.copyTo(it)}}
    val repository=ProfileRepository(context)
    val profile=runBlocking {repository.importExe(DocumentsContract.buildDocumentUri("org.frostbox.app.documents","xinput.exe"))}
    lateinit var model: FrostModel
    ui.activityRule.scenario.onActivity {model=ViewModelProvider(it)[FrostModel::class.java];model.play(profile)}
    ui.onNodeWithTag("nav-Controllers").performClick()
    inst.sendStatus(2,Bundle().apply {putString("frostbox.manual","WINDOWS_XINPUT_PRESS_ABXY_AFTER_CONNECTED")})
    var report=JSONObject();var next=0L
    try {
        ui.waitUntil(360000){
            val session=model.launcher.sessions.value[profile.id]
            report=JSONObject(session?.result ?: "{}")
            val now=android.os.SystemClock.uptimeMillis()
            if(now>=next){next=now+5000;inst.sendStatus(2,Bundle().apply{putString("frostbox.xinput.progress",JSONObject().put("phase",report.optString("phase")).put("stdout",report.optString("stdout").takeLast(2500)).put("error",report.optString("error")).toString())})}
            session!=null && model.launcher.activeId.value==null
        }
        assertEquals(report.toString(),42,report.optInt("exitCode",-1))
        assertTrue(report.optString("stdout").contains("FROSTBOX_XINPUT_RESULT=PASS"))
    } finally {
        ui.activityRule.scenario.onActivity {if(model.launcher.activeId.value!=null)model.launcher.stop()}
        File(context.filesDir,"xinput-validation.json").writeText(report.toString(2))
        inst.sendStatus(2,Bundle().apply {putString("frostbox.xinput.result",report.toString())})
    }
 }
}
