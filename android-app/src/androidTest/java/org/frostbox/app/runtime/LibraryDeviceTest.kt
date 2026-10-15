package org.frostbox.app.runtime

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.DocumentsContract as D
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.frostbox.app.MainActivity
import org.frostbox.app.data.ProfileRepository
import org.frostbox.core.GameProfile
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

/** Real Compose clicks + real SAF provider IO + real Wine/Box64 on app UID. Only picker result is controlled. */
@RunWith(AndroidJUnit4::class)
class LibraryDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private val repository get()=ProfileRepository(context)
    private val authority="org.frostbox.app.documents"
    private fun doc(path: String)=D.buildDocumentUri(authority,path)
    private fun tree(path: String)=D.buildTreeDocumentUri(authority,path)
    @Before fun setup() {
        val root=File(context.cacheDir,"launcher-qa").apply { mkdirs() }
        fun asset(name: String,target: String) {
            val f=File(root,target);f.parentFile!!.mkdirs()
            context.assets.open("launcher/$name").use { input -> f.outputStream().use { input.copyTo(it) } }
        }
        asset("console-x64.exe","console.exe");asset("sleep-x64.exe","sleep.exe")
        asset("dependency-x64.exe","game/dependency.exe");asset("frostmath.dll","game/frostmath.dll")
        File(root,"game/data").mkdirs();File(root,"game/data/resource.txt").writeText("original resource")
        File(root,"game/data/雪.txt").writeText("unicode resource")
        File(root,"invalid.exe").writeText("not PE")
        File(root,"bad").mkdirs();File(root,"slow").mkdirs()
        File(root,"collision").mkdirs();File(root,"collision/A.dat").writeText("A");File(root,"collision/a.dat").writeText("a")
        File(root,"truncated").mkdirs();File(root,"truncated/short.dat").writeText("x")
        java.io.RandomAccessFile(File(root,"slow/large.dat"),"rw").use { it.setLength(8L*1024*1024) }
    }
    private fun click(tag: String) { if(ui.onAllNodesWithTag("catalog-scroll").fetchSemanticsNodes().isNotEmpty() && ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty())ui.onNodeWithTag("catalog-scroll").performScrollToNode(hasTestTag(tag));ui.waitUntil(20000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() };ui.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun waitProfile(before: Set<String>): GameProfile {
        var found: GameProfile?=null
        ui.waitUntil(20000) { found=runBlocking { repository.profiles.first().firstOrNull { it.id !in before } };found!=null }
        return found!!
    }
    private fun add(path: String): GameProfile {
        val before=runBlocking { repository.profiles.first().map { it.id }.toSet() }
        ui.onNodeWithTag("nav-Library").performClick();ui.onNodeWithTag("add-game").performClick()
        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK,Intent().setData(doc(path))))
            click("select-exe");Intents.intended(hasAction(Intent.ACTION_OPEN_DOCUMENT))
            val p=waitProfile(before)
            Assert.assertNull(p.lastLaunch)
            ui.onNodeWithTag("back-library").performClick();return p
        } finally { Intents.release() }
    }
    private fun result(id: String,phase: String="Finished",previous: String?=null): JSONObject {
        var value: JSONObject?=null
        ui.waitUntil(180000) {
            value=runCatching { repository.readRun(id) }.getOrNull()
            value?.optString("launcherPhase")==phase && (previous==null || value?.optString("runId")!=previous)
        }
        val r=value!!
        inst.sendStatus(2,Bundle().apply { putString("frostbox.library.result",JSONObject(r.toString()).put("testUid",Process.myUid()).put("testPid",Process.myPid()).put("profileId",id).toString()) })
        return r
    }
    private fun success(p: GameProfile,stdout: String="FROSTBOX_PE_RESULT=5050\n") {
        val previous=repository.readRun(p.id)?.optString("runId")
        click("play-${p.id}");val r=result(p.id,previous=previous)
        Assert.assertEquals(r.toString(),42,r.getInt("exitCode"));Assert.assertEquals(stdout,r.getString("stdout"))
        Assert.assertEquals(Process.myUid(),r.getInt("runtimeUid"));Assert.assertNotEquals(Process.myPid(),r.getInt("runtimePid"))
        Assert.assertTrue(r.getJSONObject("prefixValidation").getBoolean("valid"));Assert.assertFalse(r.getBoolean("bootstrap"))
    }
    private fun folder(p: GameProfile,path: String) {
        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT_TREE)).respondWith(ActivityResult(Activity.RESULT_OK,Intent().setData(tree(path))))
            click("import-folder-${p.id}");ui.onNodeWithTag("choose-folder").performClick()
            Intents.intended(hasAction(Intent.ACTION_OPEN_DOCUMENT_TREE))
        } finally { Intents.release() }
    }
    private fun gpuPixels(label: String): Int {
        val bitmap=android.graphics.Bitmap.createBitmap(320,240,android.graphics.Bitmap.Config.ARGB_8888)
        val latch=java.util.concurrent.CountDownLatch(1);var status=-1
        fun find(view: android.view.View): org.frostbox.app.display.WindowsSurface? {
            if(view is org.frostbox.app.display.WindowsSurface)return view
            if(view is android.view.ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let { return it }
            return null
        }
        ui.activityRule.scenario.onActivity { activity ->
            val surface=find(activity.window.decorView)
            if(surface!=null && surface.holder.surface.isValid)android.view.PixelCopy.request(surface,bitmap,{ status=it;latch.countDown() },android.os.Handler(android.os.Looper.getMainLooper()))
            else latch.countDown()
        }
        Assert.assertTrue(latch.await(10,java.util.concurrent.TimeUnit.SECONDS))
        if(status!=android.view.PixelCopy.SUCCESS){bitmap.recycle();return 0}
        val pixels=IntArray(320*240);bitmap.getPixels(pixels,0,320,0,0,320,240)
        File(context.filesDir,"library-gpu-evidence").apply{mkdirs()}.resolve("$label.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle();return pixels.contentHashCode()
    }
    @Test fun wow64LibraryConsoleStopRecovery() {
        val root=File(context.cacheDir,"launcher-qa")
        for(name in listOf("console-x86.exe","sleep-x86.exe"))context.assets.open("wine-wow64/$name").use {input->File(root,name).outputStream().use{input.copyTo(it)}}
        val p=add("console-x86.exe")
        Assert.assertEquals("x86",p.architecture);Assert.assertNull(p.winePrefixId)
        success(p);val marker=File(context.filesDir,"games/${p.id}/prefix/.frostbox-prefix.json").readText()
        success(p);Assert.assertEquals(marker,File(context.filesDir,"games/${p.id}/prefix/.frostbox-prefix.json").readText())
        ui.activityRule.scenario.recreate();success(p)
        val sleep=add("sleep-x86.exe").copy(winePrefixId=p.id)
        runBlocking {repository.save(sleep)}
        click("play-${sleep.id}");ui.waitUntil(180000){repository.readRun(sleep.id)?.optString("launcherPhase")=="Running"}
        windowsReady(sleep);click("stop-${sleep.id}");Assert.assertTrue(result(sleep.id).getBoolean("cancelled"))
        click("play-${sleep.id}");var pid=-1
        ui.waitUntil(30000){val r=repository.readRun(sleep.id);pid=r?.optInt("runtimePid",-1) ?: -1;r?.optString("launcherPhase")=="Running" && pid>0}
        windowsReady(sleep,detailsOpen=true);Process.sendSignal(pid,9)
        Assert.assertTrue(result(sleep.id,"Failed").getString("error").contains("died"))
        ui.onNodeWithText("Recover runtime").performScrollTo().performClick()
        ui.waitUntil(30000){repository.readRun(sleep.id)?.optString("launcherPhase")=="Ready"}
        click("details-back");success(p)
        File(context.filesDir,"library-wow64-console-id.txt").writeText(p.id)
    }
    @Test fun wow64D3d9LibraryPlayRepeatAndStop() {
        val root=File(context.cacheDir,"launcher-qa")
        context.assets.open("wine-wow64/d3d9-triangle-x86.exe").use {input->File(root,"triangle-x86.exe").outputStream().use{input.copyTo(it)}}
        val original=add("triangle-x86.exe")
        click("details-${original.id}");click("settings-${original.id}");click("graphics-backend")
        ui.onNodeWithText("DXVK 2.7.1 · experimental D3D9").performClick();click("save-profile");click("details-back")
        ui.waitUntil(10000){runBlocking{repository.profiles.first().single{it.id==original.id}.graphics==org.frostbox.core.GraphicsBackend.DXVK}}
        var prefix=""
        repeat(2){run->
            val previous=repository.readRun(original.id)?.optString("runId")
            val hashes=mutableSetOf<Int>();click("play-${original.id}")
            val deadline=android.os.SystemClock.elapsedRealtime()+180000;var index=0
            while(android.os.SystemClock.elapsedRealtime()<deadline){
                ui.waitForIdle()
                val state=repository.readRun(original.id)
                if(state?.optString("runId")!=previous && state?.optString("launcherPhase") in listOf("Finished","Failed"))break
                val hash=gpuPixels("x86-d3d9-$run-${index++}");if(hash!=0)hashes.add(hash);Thread.sleep(50)
            }
            val r=result(original.id,previous=previous).put("distinctPixelCopies",hashes.size)
            inst.sendStatus(2,Bundle().apply{putString("frostbox.library.result",r.toString())})
            Assert.assertEquals(r.toString(),42,r.optInt("exitCode",-1));Assert.assertTrue(r.toString(),r.optInt("gpuFrames")>=120)
            Assert.assertTrue(r.getBoolean("wow64"));Assert.assertEquals("d3d9",r.getString("graphicsApi"))
            Assert.assertTrue(r.getJSONObject("dxvkLogs").toString().contains("Adreno"));Assert.assertTrue("Changing pixels",hashes.size>=2)
            Assert.assertTrue(r.getJSONObject("prefixValidation").getBoolean("valid"))
            if(run==0)prefix=r.getJSONObject("prefixValidation").getString("prefix") else Assert.assertEquals(prefix,r.getJSONObject("prefixValidation").getString("prefix"))
        }
        val previous=repository.readRun(original.id)?.optString("runId");click("play-${original.id}")
        ui.waitUntil(30000){ui.onAllNodesWithTag("display-stop").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("display-stop").performClick()
        val stopped=result(original.id,previous=previous)
        Assert.assertTrue(stopped.toString(),stopped.optBoolean("cancelled") || stopped.optBoolean("notStarted"))
        File(context.filesDir,"library-wow64-d3d9-id.txt").writeText(original.id)
    }
    @Test fun dxvkLibraryImportAndPlay() {
        val root=File(context.cacheDir,"launcher-qa")
        context.assets.open("runtime/d3d11-cube-x64.exe").use { input -> File(root,"cube.exe").outputStream().use { input.copyTo(it) } }
        val original=add("cube.exe")
        // Reuse the already validated diagnostic prefix; no deletion/metrics rewriting.
        val prefix=File(context.filesDir,"wine-vulkan-profile-id.txt").readText().trim()
        runBlocking { repository.save(original.copy(winePrefixId=prefix)) }
        click("details-${original.id}");click("settings-${original.id}");click("graphics-backend")
        ui.onNodeWithText("DXVK 2.7.1 · experimental D3D11").performClick();click("save-profile");click("details-back")
        ui.waitUntil(10000) { runBlocking { repository.profiles.first().single { it.id==original.id }.graphics==org.frostbox.core.GraphicsBackend.DXVK } }
        val hashes=mutableSetOf<Int>();click("play-${original.id}")
        val deadline=android.os.SystemClock.elapsedRealtime()+120000
        var index=0
        while(android.os.SystemClock.elapsedRealtime()<deadline) {
            ui.waitForIdle()
            if(repository.readRun(original.id)?.optString("launcherPhase") in listOf("Finished","Failed"))break
            val hash=gpuPixels("cube-${index++}");if(hash!=0)hashes.add(hash);Thread.sleep(80)
        }
        val terminalPhase=repository.readRun(original.id)?.optString("launcherPhase") ?: "Failed"
        val r=result(original.id,terminalPhase)
        r.put("distinctPixelCopies",hashes.size)
        inst.sendStatus(2,Bundle().apply {putString("frostbox.library.result",r.toString())})
        Assert.assertEquals(r.toString(),42,r.optInt("exitCode",-1));Assert.assertTrue(r.toString(),r.optInt("gpuFrames")>=120)
        Assert.assertTrue(r.toString(),r.getJSONObject("dxvkLogs").toString().contains("Adreno"));Assert.assertTrue("Changing cube pixels",hashes.size>=2)
        Assert.assertTrue(r.getString("stdout"),r.getString("stdout").contains("DRAWS=120"))
        File(context.filesDir,"library-dxvk-profile-id.txt").writeText(original.id)
    }
    @Test fun wow64LibraryAudio() {
        Assume.assumeTrue("Requires explicit existing experimental WoW64 prefix",File(context.filesDir,"library-mafia2-profile-id.txt").isFile)
        val id=File(context.filesDir,"library-mafia2-profile-id.txt").readText().trim()
        context.assets.open("launcher/wasapi-audio-x86.exe").use {input->File(context.cacheDir,"launcher-qa/audio-x86.exe").outputStream().use {input.copyTo(it)}}
        val p=add("audio-x86.exe").copy(winePrefixId=id,audioEnabled=true)
        runBlocking{repository.save(p)}
        click("play-${p.id}");val r=result(p.id)
        Assert.assertEquals(r.toString(),42,r.optInt("exitCode",-1))
        Assert.assertTrue(r.getString("stdout").contains("AUDIO_PCM_FRAMES=9600"))
        Assert.assertTrue(r.getJSONObject("audioBridge").getBoolean("outputInitialized"))
        Assert.assertTrue(r.getJSONObject("audioBridge").optLong("totalReceivedFrames")>=9600)
    }
    @Test fun mafia2InstallBundledPhysx() {
        requireGameQa()
        val id=File(context.filesDir,"library-mafia2-profile-id.txt").readText().trim()
        val owner=runBlocking{repository.profiles.first().single{it.id==id}}
        check(owner.architecture=="x86" && owner.winePrefixId==id)
        val msi=File(context.filesDir,"games/$id/content/3rd/PhysX_9.10.0513_SystemSoftware.msi")
        check(msi.isFile && msi.length()==26978304L){"Original bundled PhysX MSI required"}
        val tool=File(context.filesDir,"games/$id/prefix/drive_c/windows/syswow64/msiexec.exe")
        check(org.frostbox.core.PeInspector.inspect(tool)=="x86")
        tool.copyTo(File(context.cacheDir,"launcher-qa/empire-msiexec.exe"),overwrite=true)
        val original=add("empire-msiexec.exe")
        val log=File(context.filesDir,"games/$id/physx-install.log")
        fun winePath(f: File)="Z:"+f.path.replace('/','\\')
        val p=original.copy(name="QA · bundled PhysX installer",winePrefixId=id,arguments=listOf("/i",winePath(msi),"/qn","/norestart","/L*V",winePath(log)))
        runBlocking{repository.save(p)}
        click("play-${p.id}")
        var r: JSONObject?=null
        ui.waitUntil(180000){r=repository.readRun(p.id);r?.optString("launcherPhase") in listOf("Finished","Failed")}
        val report=checkNotNull(r).put("installer","Original bundled PhysX 9.10.0513 MSI").put("profileId",p.id)
        inst.sendStatus(2,Bundle().apply{putString("frostbox.library.result",report.toString())})
        org.frostbox.app.data.ManagedFiles.write(File(context.filesDir,"physx-installer-result.json"),report)
        Assert.assertTrue(report.toString(),report.optInt("exitCode",-1) in listOf(0,3010))
    }
    /** Explicit real-game QA. Uses an already user-imported profile; never imports external
     * storage through ADB or starts the game at import time. */
    @Test fun mafia2LibraryAttempt() {
        requireGameQa()
        val digest="16c79a3be483e582719e43d7b451e887e89414be81ca7ecfc0536dd8185e6c2f"
        val original=runBlocking {repository.profiles.first().single {it.sha256==digest && it.executable=="content/pc/mafia2.exe"}}
        check(original.architecture=="x86" && original.contentGeneration!=null)
        val p=original.copy(name="Mafia II (2010) · experimental",winePrefixId=original.id,runtimeVariant="auto",graphicsApi="d3d9",graphics=org.frostbox.core.GraphicsBackend.DXVK,width=800,height=600,
            environment=InstrumentationRegistry.getArguments().getString("frostbox.mafiaDebug") ?: "WINEDEBUG=err+all,warn+module,+seh,+loaddll",audioEnabled=true,controllerMode="xinput")
        runBlocking {repository.save(p)}
        File(context.filesDir,"library-mafia2-profile-id.txt").writeText(p.id)
        if(InstrumentationRegistry.getArguments().getString("frostbox.mafiaWindowed")=="true") {
            val cfg=File(context.filesDir,"games/${p.id}/prefix/drive_c/users/u0_a${Process.myUid()-10000}/AppData/Local/2K Games/Mafia II/Saves/videoconfig.cfg")
            val originalConfig=cfg.readText()
            check(originalConfig in listOf("0 0 800 600 1 0 0 1","0 0 800 600 0 0 0 1")){"Unexpected video config; refuse edit"}
            val backup=File(cfg.parentFile,"videoconfig.frostbox-before-windowed.cfg")
            if(!backup.exists())cfg.copyTo(backup)
            val file=android.util.AtomicFile(cfg);val stream=file.startWrite()
            try{stream.write("0 0 800 600 0 0 0 1".toByteArray());file.finishWrite(stream)}catch(e: Throwable){file.failWrite(stream);throw e}
        }
        val previous=repository.readRun(p.id)?.optString("runId")
        ui.onNodeWithTag("nav-Library").performClick();click("play-${p.id}")
        val start=android.os.SystemClock.elapsedRealtime();val hashes=mutableSetOf<Int>();var shots=0;var inputAttempted=false
        val seconds=InstrumentationRegistry.getArguments().getString("frostbox.gameSeconds")?.toInt() ?: 90
        require(seconds in 30..300)
        try {
            while(android.os.SystemClock.elapsedRealtime()-start<seconds*1000L){
                ui.waitForIdle();val state=repository.readRun(p.id)
                if(state?.optString("runId")!=previous && state?.optString("launcherPhase") in listOf("Finished","Failed"))break
                val hash=gpuPixels("mafia2-${shots++}");if(hash!=0)hashes.add(hash)
                if(!inputAttempted && android.os.SystemClock.elapsedRealtime()-start>20000 && InstrumentationRegistry.getArguments().getString("frostbox.skipIntro")=="true") {
                    ui.onNodeWithTag("windows-surface").performTouchInput{click(androidx.compose.ui.geometry.Offset(width*0.5f,height*0.5f))}
                    for(code in listOf(android.view.KeyEvent.KEYCODE_SPACE,android.view.KeyEvent.KEYCODE_ENTER,android.view.KeyEvent.KEYCODE_ESCAPE)){
                        ui.activityRule.scenario.onActivity{activity->
                            fun find(v: android.view.View): org.frostbox.app.display.WindowsSurface? {
                                if(v is org.frostbox.app.display.WindowsSurface)return v
                                if(v is android.view.ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it}
                                return null
                            }
                            find(activity.window.decorView)?.apply{requestFocus();dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,code));dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,code))}
                        };Thread.sleep(500)
                    };inputAttempted=true
                }
                Thread.sleep(500)
            }
        } finally {
            if(ui.onAllNodesWithTag("display-stop").fetchSemanticsNodes().isNotEmpty())ui.onNodeWithTag("display-stop").performClick()
        }
        var terminal: JSONObject?=null
        ui.waitUntil(30000){terminal=repository.readRun(p.id);terminal?.optString("runId")!=previous && terminal?.optString("launcherPhase") in listOf("Finished","Failed")}
        val r=checkNotNull(terminal).put("distinctPixelCopies",hashes.size).put("thirdPartyGame","Mafia II (2010)").put("profileId",p.id).put("gameplayVerified",false).put("syntheticIntroInputAttempted",inputAttempted)
        inst.sendStatus(2,Bundle().apply{putString("frostbox.library.result",r.toString())})
        org.frostbox.app.data.ManagedFiles.write(File(context.filesDir,"mafia2-attempt.json"),r)
        // Test passing records an honest boot attempt and cleanup, not game compatibility.
        Assert.assertFalse(r.optBoolean("running"))
    }
    private fun requireGameQa() {
        Assume.assumeTrue("Explicit staged third-party game QA only",InstrumentationRegistry.getArguments().getString("frostbox.gameQa")=="true")
    }
    @Test fun karlsonLibraryAttempt() {
        requireGameQa()
        check(File(context.cacheDir,"launcher-qa/karlson/Karlson.exe").isFile){"Stage the official Windows64 demo in dedicated QA storage first"}
        val original=add("karlson/Karlson.exe")
        click("details-${original.id}");folder(original,"karlson")
        ui.waitUntil(60000) { runBlocking { repository.profiles.first().single { it.id==original.id }.contentGeneration!=null } }
        val imported=runBlocking { repository.profiles.first().single { it.id==original.id } }
        val prefix=File(context.filesDir,"wine-vulkan-profile-id.txt").readText().trim()
        val playerLog=File(context.filesDir,"games/${original.id}/content/Player.log")
        runBlocking { repository.save(imported.copy(name="KARLSON · official demo",winePrefixId=prefix,
            graphics=org.frostbox.core.GraphicsBackend.DXVK,width=640,height=360,
            arguments=listOf("-force-d3d11","-screen-width","640","-screen-height","360","-screen-fullscreen","0","-logFile",playerLog.path))) }
        click("details-back");click("play-${original.id}")
        val deadline=android.os.SystemClock.elapsedRealtime()+90000;val hashes=mutableSetOf<Int>();var shots=0
        var inputAttempted=false
        try {
            while(android.os.SystemClock.elapsedRealtime()<deadline) {
                ui.waitForIdle()
                val state=repository.readRun(original.id)
                if(state?.optString("launcherPhase") in listOf("Finished","Failed"))break
                val hash=gpuPixels("karlson-${shots++}");if(hash!=0)hashes.add(hash)
                if(!inputAttempted && hashes.size>=3 && shots>=15) {
                    // App-local Android touch/key events, not a claim of physical mouse/gamepad validation.
                    ui.onNodeWithTag("windows-surface").performTouchInput { click(androidx.compose.ui.geometry.Offset(width*0.5f,height*0.40f)) }
                    Thread.sleep(2000)
                    ui.activityRule.scenario.onActivity { activity ->
                        activity.window.decorView.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_W))
                    }
                    Thread.sleep(1500)
                    ui.activityRule.scenario.onActivity { activity ->
                        activity.window.decorView.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_W))
                    }
                    inputAttempted=true
                }
                Thread.sleep(500)
            }
        } finally {
            if(ui.onAllNodesWithTag("display-stop").fetchSemanticsNodes().isNotEmpty())ui.onNodeWithTag("display-stop").performClick()
        }
        var terminal: JSONObject?=null
        ui.waitUntil(30000) { terminal=repository.readRun(original.id);terminal?.optString("launcherPhase") in listOf("Finished","Failed") }
        val r=checkNotNull(terminal).put("distinctPixelCopies",hashes.size).put("automatedInputAttempted",inputAttempted)
            .put("thirdPartyGame","KARLSON official free Windows64 demo").put("profileId",original.id)
        if(playerLog.isFile)r.put("unityPlayerLog",playerLog.inputStream().use { it.readNBytes(262144).toString(Charsets.UTF_8) })
        inst.sendStatus(2,Bundle().apply {putString("frostbox.library.result",r.toString())})
        File(context.filesDir,"library-karlson-profile-id.txt").writeText(original.id)
        // This records an attempted game launch. Passing asserts import/stop, not gameplay.
        Assert.assertTrue(File(context.filesDir,"games/${original.id}/content/UnityPlayer.dll").isFile)
        Assert.assertFalse(r.optBoolean("running"))
    }
    @Test fun karlsonFocusRecoveryAttempt() {
        requireGameQa()
        val id=File(context.filesDir,"library-karlson-profile-id.txt").readText().trim()
        val p=runBlocking { repository.profiles.first().single { it.id==id } }
        runBlocking {repository.save(p.copy(environment="WINEDEBUG=err+all,+frost,+dinput"))}
        ui.onNodeWithTag("nav-Library").performClick();click("play-$id")
        val start=android.os.SystemClock.elapsedRealtime();val hashes=mutableSetOf<Int>();var shots=0;var input=false
        try {
            while(android.os.SystemClock.elapsedRealtime()-start<45000) {
                ui.waitForIdle()
                if(repository.readRun(id)?.optString("launcherPhase") in listOf("Finished","Failed"))break
                val hash=gpuPixels("karlson-focus-${shots++}");if(hash!=0)hashes.add(hash)
                if(!input && android.os.SystemClock.elapsedRealtime()-start>12000) {
                    ui.onNodeWithTag("windows-surface").performTouchInput { click(androidx.compose.ui.geometry.Offset(width*0.5f,height*0.5f)) }
                    ui.activityRule.scenario.onActivity { activity ->
                        fun find(view: android.view.View): org.frostbox.app.display.WindowsSurface? {
                            if(view is org.frostbox.app.display.WindowsSurface)return view
                            if(view is android.view.ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let {return it}
                            return null
                        }
                        find(activity.window.decorView)?.apply { requestFocus();dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_ENTER));dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_ENTER)) }
                    }
                    input=true
                }
                Thread.sleep(500)
            }
        } finally {if(ui.onAllNodesWithTag("display-stop").fetchSemanticsNodes().isNotEmpty())ui.onNodeWithTag("display-stop").performClick()}
        var r: JSONObject?=null
        ui.waitUntil(30000){r=repository.readRun(id);r?.optString("launcherPhase") in listOf("Finished","Failed")}
        val report=checkNotNull(r).put("distinctPixelCopies",hashes.size).put("automatedInputAttempted",input).put("gameplay",false)
        val log=File(context.filesDir,"games/$id/content/Player.log")
        if(log.isFile)report.put("unityPlayerLog",log.inputStream().use {it.readNBytes(262144).toString(Charsets.UTF_8)})
        inst.sendStatus(2,Bundle().apply {putString("frostbox.library.result",report.toString())})
        // A successful bounded recovery/Stop attempt does not qualify game rendering.
        Assert.assertTrue(report.toString(),report.optBoolean("cancelled"));Assert.assertTrue(report.getJSONObject("prefixValidation").getBoolean("valid"))
        runBlocking {repository.save(p)}
    }
    @Test fun karlsonMenuAndBoundedStop() {
        requireGameQa()
        val id=File(context.filesDir,"library-karlson-profile-id.txt").readText().trim()
        val p=runBlocking {repository.profiles.first().single {it.id==id}}
        // Retry the saved Library profile; no import or prefix initialization repeated.
        runBlocking {repository.save(p.copy(environment="WINEDEBUG=err+all,+frost"))}
        ui.onNodeWithTag("nav-Library").performClick();click("play-$id")
        val start=android.os.SystemClock.elapsedRealtime();var shots=0;val hashes=mutableSetOf<Int>();var accepted=false
        try {
            while(android.os.SystemClock.elapsedRealtime()-start<40000) {
                ui.waitForIdle()
                if(repository.readRun(id)?.optString("launcherPhase") in listOf("Finished","Failed"))break
                val hash=gpuPixels("karlson-upright-${shots++}");if(hash!=0)hashes.add(hash)
                if(!accepted && android.os.SystemClock.elapsedRealtime()-start>10000) {
                    // Accept the pre-render Win32 selector using the existing keyboard route.
                    ui.activityRule.scenario.onActivity {activity ->
                        fun find(view: android.view.View): org.frostbox.app.display.WindowsSurface? {
                            if(view is org.frostbox.app.display.WindowsSurface)return view
                            if(view is android.view.ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let {return it}
                            return null
                        }
                        find(activity.window.decorView)?.apply {dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_ENTER));dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_ENTER))}
                    };accepted=true
                }
                Thread.sleep(500)
            }
        } finally {if(ui.onAllNodesWithTag("display-stop").fetchSemanticsNodes().isNotEmpty())ui.onNodeWithTag("display-stop").performClick()}
        var r: JSONObject?=null
        ui.waitUntil(30000){r=repository.readRun(id);r?.optString("launcherPhase") in listOf("Finished","Failed")}
        val report=checkNotNull(r).put("distinctPixelCopies",hashes.size).put("selectorEnterAttempted",accepted).put("gameplay",false)
        val log=File(context.filesDir,"games/$id/content/Player.log")
        if(log.isFile)report.put("unityPlayerLog",log.inputStream().use {it.readNBytes(262144).toString(Charsets.UTF_8)})
        inst.sendStatus(2,Bundle().apply {putString("frostbox.library.result",report.toString())})
        runBlocking {repository.save(p.copy(environment=""))}
        Assert.assertTrue(report.toString(),report.optInt("gpuFrames")>=120 && hashes.size>=2)
        Assert.assertTrue(report.toString(),report.optBoolean("cancelled"))
    }
    @Test fun karlsonGameplayAttempt() {
        requireGameQa()
        val id=File(context.filesDir,"library-karlson-profile-id.txt").readText().trim()
        val p=runBlocking {repository.profiles.first().single {it.id==id}}
        val commands=File(context.cacheDir,"titan-qa-input.json");commands.delete() // Dedicated test mailbox only.
        val inputEvents=org.json.JSONArray();var last=0
        fun input(command: JSONObject) {
            val seq=command.getInt("sequence");if(seq<=last)return
            val key=command.optInt("key",0);val hold=command.optInt("holdMs",100).coerceIn(1,3000)
            require(key in listOf(0,android.view.KeyEvent.KEYCODE_ENTER,android.view.KeyEvent.KEYCODE_W,android.view.KeyEvent.KEYCODE_A,android.view.KeyEvent.KEYCODE_D,android.view.KeyEvent.KEYCODE_S,android.view.KeyEvent.KEYCODE_SPACE,android.view.KeyEvent.KEYCODE_ESCAPE))
            val x=command.optDouble("x",0.5).toFloat();val y=command.optDouble("y",0.5).toFloat();require(x in 0f..1f && y in 0f..1f)
            fun dispatch(up: Boolean) {ui.activityRule.scenario.onActivity {activity ->
                fun find(view: android.view.View): org.frostbox.app.display.WindowsSurface? {
                    if(view is org.frostbox.app.display.WindowsSurface)return view
                    if(view is android.view.ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let{return it}
                    return null
                }
                find(activity.window.decorView)?.apply {
                    if(key!=0)dispatchKeyEvent(android.view.KeyEvent(if(up)android.view.KeyEvent.ACTION_UP else android.view.KeyEvent.ACTION_DOWN,key))
                    else {val now=android.os.SystemClock.uptimeMillis();val e=android.view.MotionEvent.obtain(now,now,if(up)android.view.MotionEvent.ACTION_UP else android.view.MotionEvent.ACTION_DOWN,width*x,height*y,0);dispatchTouchEvent(e);e.recycle()}
                }
            }}
            dispatch(false);Thread.sleep(hold.toLong());dispatch(true);last=seq;inputEvents.put(command)
        }
        ui.onNodeWithTag("nav-Library").performClick();click("play-$id")
        val start=android.os.SystemClock.elapsedRealtime();var shots=0;val hashes=mutableSetOf<Int>();var selector=false
        try {
            while(android.os.SystemClock.elapsedRealtime()-start<120000) {
                ui.waitForIdle()
                if(repository.readRun(id)?.optString("launcherPhase") in listOf("Finished","Failed"))break
                val hash=gpuPixels("karlson-gameplay-${shots++}");if(hash!=0)hashes.add(hash)
                if(!selector && android.os.SystemClock.elapsedRealtime()-start>10000){input(JSONObject().put("sequence",1).put("key",android.view.KeyEvent.KEYCODE_ENTER));selector=true}
                if(commands.isFile)runCatching {JSONObject(commands.readText())}.getOrNull()?.let {input(it)}
                Thread.sleep(500)
            }
        } finally {if(ui.onAllNodesWithTag("display-stop").fetchSemanticsNodes().isNotEmpty())ui.onNodeWithTag("display-stop").performClick()}
        var r: JSONObject?=null
        ui.waitUntil(30000){r=repository.readRun(id);r?.optString("launcherPhase") in listOf("Finished","Failed")}
        val report=checkNotNull(r).put("distinctPixelCopies",hashes.size).put("androidInputEvents",inputEvents).put("physicalInputValidated",false)
        val log=File(context.filesDir,"games/$id/content/Player.log")
        if(log.isFile)report.put("unityPlayerLog",log.inputStream().use {it.readNBytes(262144).toString(Charsets.UTF_8)})
        inst.sendStatus(2,Bundle().apply {putString("frostbox.library.result",report.toString())})
        Assert.assertTrue(report.toString(),report.optInt("gpuFrames")>=120 && hashes.size>=2)
        Assert.assertTrue(report.getJSONObject("prefixValidation").getBoolean("valid"))
    }
    @Test fun karlsonDirectGraphicsGameplay() {
        requireGameQa()
        val id=File(context.filesDir,"library-karlson-profile-id.txt").readText().trim()
        val p=runBlocking {repository.profiles.first().single {it.id==id}}
        runBlocking {repository.save(p.copy(arguments=p.arguments.filterNot {it=="-force-gfx-direct"}+"-force-gfx-direct",environment="WINEDEBUG=err+all,+frost"))}
        ui.onNodeWithTag("nav-Library").performClick();click("play-$id")
        val start=android.os.SystemClock.elapsedRealtime();var shots=0;val hashes=mutableSetOf<Int>();val actions=org.json.JSONArray();var stage=0
        fun key(code: Int,hold: Long) {
            fun send(up: Boolean) {ui.activityRule.scenario.onActivity {activity ->
                fun find(view: android.view.View): org.frostbox.app.display.WindowsSurface? {
                    if(view is org.frostbox.app.display.WindowsSurface)return view
                    if(view is android.view.ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let{return it}
                    return null
                }
                find(activity.window.decorView)?.dispatchKeyEvent(android.view.KeyEvent(if(up)android.view.KeyEvent.ACTION_UP else android.view.KeyEvent.ACTION_DOWN,code))
            }}
            send(false);Thread.sleep(hold);send(true);actions.put(JSONObject().put("key",code).put("holdMs",hold).put("elapsedMs",android.os.SystemClock.elapsedRealtime()-start))
        }
        fun tap(x: Float,y: Float){ui.onNodeWithTag("windows-surface").performTouchInput {click(androidx.compose.ui.geometry.Offset(width*x,height*y))};actions.put(JSONObject().put("x",x).put("y",y).put("elapsedMs",android.os.SystemClock.elapsedRealtime()-start))}
        try {
            while(android.os.SystemClock.elapsedRealtime()-start<45000) {
                ui.waitForIdle()
                if(repository.readRun(id)?.optString("launcherPhase") in listOf("Finished","Failed"))break
                val hash=gpuPixels("karlson-direct-${shots++}");if(hash!=0)hashes.add(hash)
                val elapsed=android.os.SystemClock.elapsedRealtime()-start
                when {
                    stage==0 && elapsed>10000 -> {key(android.view.KeyEvent.KEYCODE_ENTER,100);stage++}
                    stage==1 && elapsed>15000 && hashes.size>=2 -> {tap(0.25f,0.24f);stage++}
                    stage==2 && elapsed>18000 -> {tap(0.195f,0.31f);stage++}
                    stage==3 && elapsed>24000 -> {key(android.view.KeyEvent.KEYCODE_W,2500);stage++}
                    stage==4 && elapsed>28500 -> {key(android.view.KeyEvent.KEYCODE_SPACE,100);stage++}
                    stage==5 && elapsed>31000 -> {key(android.view.KeyEvent.KEYCODE_W,2500);stage++}
                }
                Thread.sleep(500)
            }
        } finally {if(ui.onAllNodesWithTag("display-stop").fetchSemanticsNodes().isNotEmpty())ui.onNodeWithTag("display-stop").performClick()}
        var r: JSONObject?=null
        ui.waitUntil(30000){r=repository.readRun(id);r?.optString("launcherPhase") in listOf("Finished","Failed")}
        val report=checkNotNull(r).put("distinctPixelCopies",hashes.size).put("androidInputEvents",actions).put("forceGfxDirect",true).put("physicalInputValidated",false)
        val log=File(context.filesDir,"games/$id/content/Player.log")
        if(log.isFile)report.put("unityPlayerLog",log.inputStream().use {it.readNBytes(262144).toString(Charsets.UTF_8)})
        inst.sendStatus(2,Bundle().apply {putString("frostbox.library.result",report.toString())})
        runBlocking {repository.save(p)}
        Assert.assertTrue(report.toString(),report.optInt("gpuFrames")>=120 && hashes.size>=2)
        Assert.assertTrue(report.toString(),report.optBoolean("cancelled"))
        Assert.assertTrue(report.getJSONObject("prefixValidation").getBoolean("valid"))
    }
    @Test fun saveValidatedKarlsonDirectProfile() {
        requireGameQa()
        val id=File(context.filesDir,"library-karlson-profile-id.txt").readText().trim()
        val p=runBlocking {repository.profiles.first().single {it.id==id}}
        check(p.name=="KARLSON · official demo" && p.graphics==org.frostbox.core.GraphicsBackend.DXVK)
        val updated=p.copy(arguments=p.arguments.filterNot {it=="-force-gfx-direct"}+"-force-gfx-direct",environment="")
        runBlocking {repository.save(updated)}
        val config=JSONObject(File(context.filesDir,"games/$id/launch.json").readText())
        Assert.assertTrue(config.getJSONArray("arguments").toString().contains("-force-gfx-direct"))
        inst.sendStatus(2,Bundle().apply {putString("frostbox.library.result",JSONObject().put("profileId",id).put("configurationOnly",true).put("arguments",config.getJSONArray("arguments")).toString())})
    }
    @Test fun addPlayRepeatLogsAndRecreate() {
        val p=add("console.exe");success(p)
        Assert.assertNotNull(runBlocking { repository.profiles.first().single { it.id==p.id }.lastLaunch })
        success(p);click("details-${p.id}");click("logs-${p.id}")
        ui.onNodeWithTag("runtime-stdout").assertTextContains("FROSTBOX_PE_RESULT=5050",substring=true)
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("runtime-stdout").assertTextContains("FROSTBOX_PE_RESULT=5050",substring=true)
        click("logs-back");click("details-back");success(p)
        File(context.filesDir,"library-validation-id.txt").writeText(p.id)
    }
    private fun windowsReady(p: GameProfile,detailsOpen: Boolean=false) {
        if(!detailsOpen) click("details-${p.id}")
        click("logs-${p.id}")
        ui.waitUntil(30000) { ui.onAllNodesWithText("FROSTBOX_SLEEP_READY",substring=true).fetchSemanticsNodes().isNotEmpty() }
        click("logs-back")
    }
    @Test fun stopRecreateAndRuntimeDeath() {
        val p=add("sleep.exe");click("play-${p.id}")
        ui.waitUntil(180000) { repository.readRun(p.id)?.optString("launcherPhase")=="Running" }
        ui.activityRule.scenario.recreate();windowsReady(p);click("stop-${p.id}")
        Assert.assertTrue(result(p.id).getBoolean("cancelled"))
        click("play-${p.id}");var pid=-1
        ui.waitUntil(30000) { val r=repository.readRun(p.id);pid=r?.optInt("runtimePid",-1) ?: -1;r?.optString("launcherPhase")=="Running" && pid>0 }
        windowsReady(p,detailsOpen=true)
        Assert.assertNotEquals(Process.myPid(),pid);Process.sendSignal(pid,9)
        Assert.assertTrue(result(p.id,"Failed").getString("error").contains("died"))
        ui.onNodeWithText("Recover runtime").performScrollTo().performClick()
        ui.waitUntil(30000) { repository.readRun(p.id)?.optString("launcherPhase")=="Ready" }
        click("play-${p.id}");ui.waitUntil(30000) { repository.readRun(p.id)?.optString("launcherPhase")=="Running" }
        windowsReady(p,detailsOpen=true);click("stop-${p.id}");Assert.assertTrue(result(p.id).getBoolean("cancelled"))
    }
    @Test fun adjacentDllDirectoryAndMissingDependency() {
        val p=add("game/dependency.exe")
        click("play-${p.id}");val failed=result(p.id,"Failed")
        Assert.assertNotEquals(42,failed.getInt("exitCode"));Assert.assertTrue(failed.getString("stderr").contains("frostmath.dll",true))
        click("details-${p.id}");folder(p,"game")
        ui.waitUntil(20000) { runBlocking { repository.profiles.first().single { it.id==p.id }.contentGeneration!=null } }
        val updated=runBlocking { repository.profiles.first().single { it.id==p.id } }
        Assert.assertEquals("content/dependency.exe",updated.executable);Assert.assertEquals("content",updated.workingDirectory)
        Assert.assertEquals("original resource",File(context.filesDir,"games/${p.id}/content/data/resource.txt").readText())
        Assert.assertEquals("unicode resource",File(context.filesDir,"games/${p.id}/content/data/雪.txt").readText())
        success(updated,"FROSTBOX_DLL_RESULT=5050\n")
        folder(updated,"slow")
        ui.waitUntil(20000) { ui.onAllNodesWithTag("cancel-import").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("cancel-import").performClick()
        ui.waitUntil(20000) { ui.onAllNodesWithTag("cancel-import").fetchSemanticsNodes().isEmpty() }
        Assert.assertTrue(File(context.filesDir,"games/${p.id}/content/frostmath.dll").isFile)
        Assert.assertEquals(updated.contentGeneration,runBlocking { repository.profiles.first().single { it.id==p.id }.contentGeneration })
        val owner=updated.winePrefixId ?: updated.id
        Assert.assertFalse("Shared prefix must not be copied into this profile",owner==updated.id || File(context.filesDir,"games/${updated.id}/prefix").exists())
    }
    @Test fun unsafeFolderCancellationAndInvalidExe() {
        val p=add("console.exe");click("details-${p.id}");folder(p,"bad")
        ui.waitUntil(20000) { ui.onAllNodesWithText("Provider filename contains a separator").fetchSemanticsNodes().isNotEmpty() }
        Assert.assertFalse(File(context.filesDir,"games/${p.id}/content").exists())
        folder(p,"collision");ui.waitUntil(20000) { ui.onAllNodesWithText("Case-insensitive filename collision or too many entries").fetchSemanticsNodes().isNotEmpty() }
        folder(p,"truncated");ui.waitUntil(20000) { ui.onAllNodesWithText("Provider file changed or truncated: short.dat").fetchSemanticsNodes().isNotEmpty() }
        folder(p,"slow");ui.waitUntil(20000) { ui.onAllNodesWithTag("cancel-import").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("cancel-import").performClick()
        ui.waitUntil(20000) { ui.onAllNodesWithTag("cancel-import").fetchSemanticsNodes().isEmpty() }
        Assert.assertFalse(File(context.filesDir,"games/${p.id}/content").exists());Assert.assertFalse(File(context.filesDir,"games/${p.id}/content.new").exists())
        click("details-back")
        val before=runBlocking { repository.profiles.first().size }
        ui.onNodeWithTag("nav-Library").performClick();ui.onNodeWithTag("add-game").performClick();Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK,Intent().setData(doc("invalid.exe"))))
            click("select-exe");ui.waitUntil(20000) { ui.onAllNodesWithText("File is too short for a PE executable").fetchSemanticsNodes().isNotEmpty() }
            Assert.assertEquals(before,runBlocking { repository.profiles.first().size })
        } finally { Intents.release() }
    }
    @Test fun editArgumentsEnvironmentAndRemoveRecord() {
        val p=add("console.exe");click("details-${p.id}");click("settings-${p.id}")
        ui.onNodeWithTag("edit-name").performTextReplacement("Console edited")
        ui.onNodeWithTag("edit-args").performScrollTo().performTextReplacement("[\"literal argument\",\"$(no_shell)\"]")
        ui.onNodeWithTag("edit-env").performScrollTo().performTextReplacement("BOX64_LOG=0\nBOX64_DYNAREC=1")
        click("save-profile")
        ui.waitUntil(20000) { runBlocking { repository.profiles.first().single { it.id==p.id }.name=="Console edited" } }
        val edited=runBlocking { repository.profiles.first().single { it.id==p.id } }
        Assert.assertEquals(listOf("literal argument","$(no_shell)"),edited.arguments)
        val config=JSONObject(File(context.filesDir,"games/${p.id}/launch.json").readText())
        Assert.assertEquals("$(no_shell)",config.getJSONArray("arguments").getString(1))
        Assert.assertEquals("1",config.getJSONObject("environment").getString("BOX64_DYNAREC"))
        success(edited)
        ui.onNodeWithTag("game-actions-${p.id}").performScrollTo().performClick()
        ui.onNodeWithTag("remove-entry-${p.id}").performClick()
        ui.onNodeWithTag("library-removal-confirm").performClick()
        ui.waitUntil(20000) { runBlocking { repository.profiles.first().none { it.id==p.id } } }
        Assert.assertTrue(File(context.filesDir,"games/${p.id}/game.exe").isFile)
        Assert.assertTrue(File(context.filesDir,"games/${edited.winePrefixId ?: edited.id}/prefix/system.reg").isFile)
    }
    @Ignore("HyperOS denies cross-app INJECT_EVENTS; explicit manual SAF picker acceptance remains pending")
    @Test fun systemPickerImport() {
        val before=runBlocking { repository.profiles.first().map { it.id }.toSet() }
        val device=UiDevice.getInstance(inst)
        ui.onNodeWithTag("nav-Library").performClick();ui.onNodeWithTag("add-game").performClick();click("select-exe")
        Assert.assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")),10000))
        device.dumpWindowHierarchy(File(context.filesDir,"library-picker-start.xml"))
        if(device.findObject(By.text("FrostBox QA"))==null) {
            val toolbar=device.findObject(By.res("com.google.android.documentsui","toolbar"))
            val nav=toolbar?.findObject(By.clazz("android.widget.ImageButton")) ?: error("Picker navigation not found; inspect library-picker-start.xml")
            nav.click()
        }
        requireNotNull(device.wait(Until.findObject(By.text("FrostBox QA")),10000)) { "FrostBox QA provider root absent" }.click()
        device.dumpWindowHierarchy(File(context.filesDir,"library-picker-root.xml"))
        requireNotNull(device.wait(Until.findObject(By.text("console.exe")),10000)).click()
        val p=waitProfile(before);Assert.assertNull(p.lastLaunch)
        ui.onNodeWithTag("back-library").performClick();success(p)
    }
    @Test fun landscapeRetainsLogs() {
        val p=add("console.exe");success(p)
        click("details-${p.id}");click("logs-${p.id}")
        try {
            ui.activityRule.scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            ui.waitUntil(15000) { context.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE }
            ui.onNodeWithTag("runtime-stdout").assertTextContains("FROSTBOX_PE_RESULT=5050",substring=true)
            ui.activityRule.scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            ui.waitUntil(15000) { context.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT }
            ui.onNodeWithTag("runtime-stdout").assertTextContains("FROSTBOX_PE_RESULT=5050",substring=true)
        } finally { ui.activityRule.scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } }
    }
    @Test fun cancelBeforeIpcStarts() {
        val p=add("console.exe")
        // Deterministic API lifecycle test: Play and Stop in one UI turn, before preparation can bind.
        ui.activityRule.scenario.onActivity { activity ->
            val model=androidx.lifecycle.ViewModelProvider(activity)[org.frostbox.app.FrostModel::class.java]
            model.launcher.play(p);model.launcher.stop()
        }
        val r=result(p.id)
        Assert.assertTrue(r.getBoolean("cancelled"));Assert.assertTrue(r.getBoolean("notStarted"))
        Assert.assertNull(runBlocking { repository.profiles.first().single { it.id==p.id }.lastLaunch })
        Assert.assertFalse(r.has("pid"));Assert.assertFalse(File(context.filesDir,"games/${p.id}/runtime-journal.json").exists())
    }
    @Test fun restartPersistence() {
        val id=File(context.filesDir,"library-validation-id.txt").readText()
        val p=runBlocking { repository.profiles.first().single { it.id==id } }
        Assert.assertNotNull(p.lastLaunch);success(p)
    }
}
