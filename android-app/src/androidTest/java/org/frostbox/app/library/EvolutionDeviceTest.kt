package org.frostbox.app.library

import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.frostbox.app.MainActivity
import org.frostbox.app.FrostModel
import org.frostbox.app.data.ProfileRepository
import org.frostbox.core.GamepadSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EvolutionDeviceTest {
 @get:Rule val ui=createAndroidComposeRule<MainActivity>()
 private val inst get()=InstrumentationRegistry.getInstrumentation()
 @Test fun controllerHubReleasesSocketTwentyTimes()=runBlocking {
    lateinit var model: FrostModel
    ui.activityRule.scenario.onActivity {model=ViewModelProvider(it)[FrostModel::class.java]}
    val id=java.util.UUID.randomUUID().toString();val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    try {repeat(20){val hub=org.frostbox.app.input.ControllerHub(id,model.controllers,scope);delay(30);hub.close();withTimeout(2000){hub.awaitClosed()}}}
    finally {scope.cancel()}
 }
 @Test fun catalogSearchLayoutAndStorageAreReadOnly() {
    val repo=ProfileRepository(inst.targetContext);val before=runBlocking {repo.profiles.first()}
    ui.onNodeWithTag("nav-Library").performClick()
    ui.onNodeWithTag("library-search").performScrollTo().performTextReplacement("__no_matching_game_qa__")
    ui.onNodeWithText("Ничего не найдено").assertIsDisplayed()
    ui.onNodeWithTag("library-layout").performClick();ui.onNodeWithTag("library-sort").performClick()
    ui.onNodeWithTag("library-search").performTextClearance()
    ui.onNodeWithTag("nav-Storage").performClick();ui.onNodeWithText("Обновить подсчёт").assertExists()
    ui.waitUntil(20000){ui.onAllNodesWithText("Доступно:",substring=true).fetchSemanticsNodes().isNotEmpty()}
    assertEquals(before,runBlocking {repo.profiles.first()})
 }
 @Test fun physicalControllerCoverageAndHotplug() {
    Assume.assumeTrue("Requires explicit human-operated controller test",InstrumentationRegistry.getArguments().getString("frostbox.physicalPad")=="true")
    lateinit var model: FrostModel
    ui.activityRule.scenario.onActivity {model=ViewModelProvider(it)[FrostModel::class.java]}
    ui.onNodeWithTag("nav-Controllers").performClick();ui.waitForIdle()
    ui.waitUntil(30000){model.controllers.state.value.devices.any {android.view.InputDevice.getDevice(it.id)?.isExternal==true}}
    val info=model.controllers.state.value.devices.first {android.view.InputDevice.getDevice(it.id)?.isExternal==true};val log=JSONArray();var bits=0;var lx=0f;var ly=0f;var rx=0f;var ry=0f;var lt=0f;var rt=0f
    var last: GamepadSnapshot?=null;var nextReport=0L
    val report=JSONObject().put("name",info.name).put("deviceId",info.id).put("physical",true)
    val file=File(inst.targetContext.filesDir,"controller-physical-validation.json")
    inst.sendStatus(2,Bundle().apply {putString("frostbox.manual","PAD_BUTTONS_STICKS_TRIGGERS_DPAD")})
    try {
      ui.waitUntil(300000){
        val p=model.controllers.state.value.pads.first {it.slot==info.slot}
        if(p!=last){log.put(JSONObject().put("packet",p.packet).put("buttons",p.buttons).put("lx",p.leftX).put("ly",p.leftY).put("rx",p.rightX).put("ry",p.rightY).put("lt",p.leftTrigger).put("rt",p.rightTrigger).put("connected",p.connected));last=p}
        bits=bits or p.buttons;lx=maxOf(lx,kotlin.math.abs(p.leftX));ly=maxOf(ly,kotlin.math.abs(p.leftY));rx=maxOf(rx,kotlin.math.abs(p.rightX));ry=maxOf(ry,kotlin.math.abs(p.rightY));lt=maxOf(lt,p.leftTrigger);rt=maxOf(rt,p.rightTrigger)
        val now=android.os.SystemClock.uptimeMillis()
        if(now>=nextReport){nextReport=now+5000;inst.sendStatus(2,Bundle().apply {putString("frostbox.pad.coverage","buttons=$bits lx=$lx ly=$ly rx=$rx ry=$ry lt=$lt rt=$rt")})}
        bits and 0xf00f==0xf00f && minOf(lx,ly,rx,ry)>.65f && minOf(lt,rt)>.65f
      }
      report.put("controlsPassed",true)
      inst.sendStatus(2,Bundle().apply {putString("frostbox.manual","PAD_DISCONNECT")})
      ui.waitUntil(300000){model.controllers.state.value.devices.none {it.descriptor==info.descriptor}}
      val disconnected=model.controllers.state.value.pads.first {it.slot==info.slot}
      assertFalse(disconnected.connected);assertEquals(0,disconnected.buttons);assertEquals(0f,disconnected.leftX);assertEquals(0f,disconnected.rightX);assertEquals(0f,disconnected.leftTrigger);assertEquals(0f,disconnected.rightTrigger)
      report.put("disconnectNeutral",true)
      inst.sendStatus(2,Bundle().apply {putString("frostbox.manual","PAD_RECONNECT")})
      ui.waitUntil(300000){model.controllers.state.value.devices.any {it.descriptor==info.descriptor}}
      val reconnected=model.controllers.state.value.devices.first {it.descriptor==info.descriptor}
      assertEquals(info.slot,reconnected.slot);report.put("reconnectedDeviceId",reconnected.id).put("stableSlot",true)
    } finally {report.put("buttonsSeen",bits).put("maxLx",lx).put("maxLy",ly).put("maxRx",rx).put("maxRy",ry).put("maxLt",lt).put("maxRt",rt).put("events",log);file.writeText(report.toString(2));inst.sendStatus(2,Bundle().apply {putString("frostbox.controller.result",report.toString())})}
 }
}
