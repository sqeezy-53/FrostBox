package org.frostbox.app.library
import android.provider.DocumentsContract
import android.os.Bundle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.frostbox.app.*
import org.frostbox.app.data.ProfileRepository
import org.frostbox.core.GameProfile
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Internal test-only stimulus; no Android system injection or physical input assertion. */
class RemoteInputAudioDeviceTest {
 @get:Rule val ui=createAndroidComposeRule<MainActivity>()
 private val inst get()=InstrumentationRegistry.getInstrumentation()
 private lateinit var model: FrostModel
 @Before fun setup(){ui.activityRule.scenario.onActivity{model=ViewModelProvider(it)[FrostModel::class.java]}}
 @After fun stop(){ui.activityRule.scenario.onActivity{if(model.launcher.activeId.value!=null)model.launcher.stop()}}
 private fun profile(asset: String,audio: Boolean=false): GameProfile=runBlocking {
  val context=inst.targetContext;val name="remote-${java.util.UUID.randomUUID()}.exe";val f=File(context.cacheDir,"launcher-qa/$name");f.parentFile!!.mkdirs()
  context.assets.open("launcher/$asset").use {input->f.outputStream().use {input.copyTo(it)}}
  ProfileRepository(context).importExe(DocumentsContract.buildDocumentUri("org.frostbox.app.documents",name)).copy(controllerMode="keyboard",audioEnabled=audio,environment="WINEDEBUG=err+all,+rawinput,+frost")
 }
 private fun logs(p: GameProfile)=JSONObject(model.launcher.sessions.value[p.id]?.result ?: "{}").optString("stdout")
 private fun done(p: GameProfile,label: String): JSONObject {
  ui.waitUntil(90000){model.launcher.activeId.value==null}
  val r=JSONObject(model.launcher.sessions.value[p.id]?.result ?: "{}").put("label",label).put("stimulus","internal-test-only").put("physicalInputTested",false).put("audibilityVerified",false)
  File(inst.targetContext.filesDir,"remote-$label.json").writeText(r.toString(2));inst.sendStatus(2,Bundle().apply {putString("frostbox.remote.result",r.toString())});return r
 }
 @Test fun cameraSensitivityUiSaveAndRecreate() {
  val p=profile("raw-input-x64.exe");val repository=ProfileRepository(inst.targetContext)
  runBlocking {repository.save(p)}
  ui.onNodeWithTag("nav-Library").performClick()
  ui.waitUntil(20000){ui.onAllNodesWithTag("catalog-scroll").fetchSemanticsNodes().isNotEmpty()}
  ui.onNodeWithTag("catalog-scroll").performScrollToNode(hasTestTag("details-${p.id}"))
  ui.onNodeWithTag("details-${p.id}").performScrollTo().performClick()
  ui.onNodeWithTag("settings-${p.id}").performScrollTo().performClick()
  ui.onNodeWithTag("camera-sensitivity").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(2700f)}
  ui.onNodeWithTag("save-profile").performScrollTo().performClick()
  ui.waitUntil(10000){runBlocking{repository.profiles.first().single{it.id==p.id}.keyboardController.mouseSpeed==2700f}}
  ui.activityRule.scenario.recreate()
  assertEquals(2700f,runBlocking{ProfileRepository(inst.targetContext).profiles.first().single{it.id==p.id}.keyboardController.mouseSpeed})
  inst.sendStatus(2,Bundle().apply {putString("frostbox.sensitivity.result",JSONObject().put("profileId",p.id).put("mouseSpeed",2700).put("uiSaved",true).put("activityRecreated",true).toString())})
 }
 @Test fun rawKeyboardAndRelativeMouseInternalBridge() {
  val p=profile("raw-input-x64.exe")
  ui.activityRule.scenario.onActivity{model.play(p)}
  ui.waitUntil(180000){logs(p).contains("FROSTBOX_RAW_READY")||model.launcher.activeId.value==null};assertTrue(logs(p),logs(p).contains("FROSTBOX_RAW_READY"))
  for(vk in listOf(87,65,83,68,32)){
   ui.activityRule.scenario.onActivity{model.display.key(vk)};Thread.sleep(120)
   ui.activityRule.scenario.onActivity{model.display.key(vk,true)};Thread.sleep(100)
  }
  ui.activityRule.scenario.onActivity{model.display.relativePointer(37,-19)};Thread.sleep(120)
  // Movement alone must reach WM_INPUT; later button packets must not flush it for us.
  ui.waitUntil(10000){logs(p).contains("RAW_MOUSE dx=37 dy=-19")}
  for(flags in listOf(2,4,8,16)){ui.activityRule.scenario.onActivity{model.display.relativePointer(0,0,flags)};Thread.sleep(100)}
  ui.waitUntil(10000){logs(p).contains("RAW_MOUSE dx=37 dy=-19")}
  ui.activityRule.scenario.onActivity{model.display.closeWindow()}
  val r=done(p,"raw-input");assertEquals(r.toString(),42,r.optInt("exitCode",-1));val out=r.optString("stdout")
  assertTrue(out,out.contains("RAW_REGISTER=1"));assertTrue(out,out.contains("RAW_KEY vk=87 scan=17"));assertFalse(out,out.contains("RAW_KEY vk=87 scan=0"));assertTrue(out,out.contains("RAW_BUFFER_ERRORS=0"));assertTrue("Foreground/focus mismatch: $out",Regex("FOCUS hwnd=([1-9]\\d*) fg=\\1 focus=\\1 active=\\1").containsMatchIn(out));assertTrue("Raw device enumeration/handle lookup incomplete: $out",out.contains("RAW_DEVICE_INFO_ERRORS=0"))
 }
 @Test fun wasapiPcmToActualAndroidOutput() {
  Assume.assumeTrue("Audio device test must be explicitly selected",InstrumentationRegistry.getArguments().getString("frostbox.audioQa")=="true")
  val p=profile("wasapi-audio-x64.exe",audio=true);ui.activityRule.scenario.onActivity{model.play(p)}
  val r=done(p,"audio");assertEquals(r.toString(),42,r.optInt("exitCode",-1));assertTrue(r.optString("stdout").contains("AUDIO_PCM_FRAMES=9600"))
  val bridge=r.getJSONObject("audioBridge");assertEquals("android-audiotrack",bridge.optString("outputKind"));assertEquals(9600L,bridge.optLong("totalReceivedFrames"));assertTrue(bridge.toString(),bridge.optLong("totalConsumedFrames")>=9600);assertTrue(bridge.optBoolean("cleanup"))
 }
}
