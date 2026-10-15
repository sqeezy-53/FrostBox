package org.frostbox.app.library

import android.graphics.Bitmap
import android.os.*
import android.view.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.frostbox.app.*
import org.frostbox.app.data.ProfileRepository
import org.frostbox.app.display.WindowsSurface
import org.frostbox.core.TouchProfile
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in existing user-owned demo only. No downloads, removal, archive edits or game redistribution. */
class EvolutionGameDeviceTest {
 @get:Rule val ui=createAndroidComposeRule<MainActivity>()
 @Test fun saveValidatedKarlsonKeyboardProfile()=runBlocking {
    val inst=InstrumentationRegistry.getInstrumentation()
    Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("frostbox.gameQa")=="true")
    val context=inst.targetContext;val repo=ProfileRepository(context)
    val id=File(context.filesDir,"library-karlson-profile-id.txt").readText().trim()
    val original=repo.profiles.first().single {it.id==id}
    check(original.name=="KARLSON · official demo")
    val backup=File(context.filesDir,"karlson-input-before-physical-recovery.json")
    if(!backup.exists())backup.writeText(kotlinx.serialization.json.Json.encodeToString(org.frostbox.core.GameProfile.serializer(),original))
    val updated=original.copy(controllerMode="keyboard",audioEnabled=true,keyboardController=original.keyboardController.copy(mouseSpeed=1800f))
    repo.prepareLaunch(updated)
    val saved=ProfileRepository(context).profiles.first().single {it.id==id}
    assertEquals("keyboard",saved.controllerMode);assertEquals(1800f,saved.keyboardController.mouseSpeed)
    inst.sendStatus(2,Bundle().apply {putString("frostbox.profile.result",JSONObject().put("profileId",id).put("controllerMode",saved.controllerMode).put("cameraSensitivity",saved.keyboardController.mouseSpeed).put("prefixId",saved.winePrefixId).toString())})
 }
 @Test fun existingKarlsonControlledSession() {
    val inst=InstrumentationRegistry.getInstrumentation();val args=InstrumentationRegistry.getArguments()
    Assume.assumeTrue(args.getString("frostbox.gameQa")=="true")
    val context=inst.targetContext;val repo=ProfileRepository(context)
    val id=File(context.filesDir,"library-karlson-profile-id.txt").readText().trim()
    val original=runBlocking {repo.profiles.first().single {it.id==id}}
    val threaded=args.getString("frostbox.threaded")=="true"
    val profile=original.copy(environment=if(args.getString("frostbox.wsiTrace")=="true")"WINEDEBUG=-all,+frostwsi" else if(args.getString("frostbox.inputTrace")=="true")"WINEDEBUG=err+all,+frost,+rawinput,+dinput,+xinput" else if(args.getString("frostbox.audioTrace")=="true")"WINEDEBUG=err+all,+mmdevapi,+frost" else "WINEDEBUG=-all,+frost",audioEnabled=args.getString("frostbox.audioQa")=="true",controllerMode=if(args.getString("frostbox.keyboardPad")=="true")"keyboard" else original.controllerMode,arguments=original.arguments.filterNot {it=="-force-gfx-direct"}+(if(threaded)emptyList()else listOf("-force-gfx-direct")))
    lateinit var model: FrostModel
    ui.activityRule.scenario.onActivity {model=ViewModelProvider(it)[FrostModel::class.java];model.play(profile)}
    val started=SystemClock.elapsedRealtime();val duration=if(threaded)45000L else (args.getString("frostbox.durationMs")?.toLongOrNull() ?: 120000L).coerceIn(45000L,120000L)
    val folder=File(context.filesDir,"evolution-game-${if(threaded)"threaded" else "direct"}-${System.currentTimeMillis()}").apply{mkdirs()}
    var entered=false;var copies=0;var changes=0;var previous=0;var next=0L;var nextProgress=0L;val timeline=org.json.JSONArray()
    try {
        while(SystemClock.elapsedRealtime()-started<duration && model.launcher.activeId.value!=null) {
            ui.waitForIdle()
            val elapsed=SystemClock.elapsedRealtime()-started
            if(elapsed>=nextProgress){nextProgress=elapsed+5000
                val state=JSONObject(model.launcher.sessions.value[id]?.result ?: "{}");val pid=state.optInt("pid")
                val sample=JSONObject().put("elapsedMs",elapsed).put("gpuPresentedFrames",model.launcher.guestWsi.value?.frames?.value ?: 0).put("pid",pid)
                if(pid>0){runCatching {sample.put("procStat",File("/proc/$pid/stat").readText())};runCatching {sample.put("fdCount",File("/proc/$pid/fd").list()?.size ?: -1)}}
                if(args.getString("frostbox.wsiTrace")=="true") {
                    val states=JSONObject();val work=File(context.filesDir,"games/$id/${profile.workingDirectory}")
                    work.listFiles()?.filter {it.name.matches(Regex("frost-wsi-[0-9]+-[0-9]+\\.state"))}?.take(128)?.forEach {f->states.put(f.name,runCatching {f.readText().take(384)}.getOrDefault("read-error"))}
                    File(folder,"wsi-last-stages.json").writeText(states.toString(2));sample.put("wsiStages",states)
                }
                timeline.put(sample);File(folder,"timeline.json").writeText(timeline.toString(2));inst.sendStatus(2,Bundle().apply {putString("frostbox.game.progress",sample.toString())})
            }
            if(!entered && elapsed>10000){ui.activityRule.scenario.onActivity{model.display.key(13);model.display.key(13,true)};entered=true;inst.sendStatus(2,Bundle().apply {putString("frostbox.game.manual","KARLSON_READY_TRY_CONTROLLER")})}
            if(elapsed>=next){next=elapsed+10000
                var bitmap: Bitmap?=null;val done=CountDownLatch(1);var result=-1
                ui.activityRule.scenario.onActivity {activity->
                    fun find(v: View): WindowsSurface? {if(v is WindowsSurface)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null}
                    find(activity.window.decorView)?.takeIf {it.width>0 && it.height>0 && it.holder.surface.isValid}?.let {v->
                        bitmap=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888)
                        PixelCopy.request(v,bitmap!!,{result=it;done.countDown()},Handler(Looper.getMainLooper()))
                    } ?: done.countDown()
                }
                if(done.await(5,TimeUnit.SECONDS) && result==PixelCopy.SUCCESS){bitmap?.let {b->var hash=1;for(y in 0 until b.height step 20)for(x in 0 until b.width step 20)hash=hash*31+b.getPixel(x,y);if(copies>0 && hash!=previous)changes++;previous=hash;File(folder,"frame-${copies++}.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)}}}
                bitmap?.recycle()
            }
            Thread.sleep(100)
        }
    } finally {
        ui.activityRule.scenario.onActivity {if(model.launcher.activeId.value!=null)model.launcher.stop()}
        ui.waitUntil(30000){model.launcher.activeId.value==null}
        val report=JSONObject(model.launcher.sessions.value[id]?.result ?: "{}").put("threaded",threaded).put("pixelCopies",copies).put("changedCopies",changes).put("timeline",timeline).put("physicalGameplayValidated",false)
        File(folder,"report.json").writeText(report.toString(2));inst.sendStatus(2,Bundle().apply {putString("frostbox.game.result",report.toString())})
        val log=File(context.filesDir,"games/$id/content/Player.log");if(log.isFile)log.inputStream().use {input->File(folder,"Player.log").outputStream().use {out->input.copyTo(out)}}
        runBlocking {repo.prepareLaunch(original)}
    }
    if(!threaded){val report=JSONObject(model.launcher.sessions.value[id]?.result ?: "{}");assertTrue(report.toString(),report.optBoolean("cancelled"));assertTrue("No changing GPU frames",copies>=2 && changes>0);assertTrue(report.getJSONObject("prefixValidation").getBoolean("valid"))}
 }
}
