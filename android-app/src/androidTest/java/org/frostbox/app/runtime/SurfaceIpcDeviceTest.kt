// SPDX-License-Identifier: MIT
package org.frostbox.app.runtime

import android.content.*
import android.graphics.Bitmap
import android.graphics.Color
import android.os.*
import android.view.*
import android.widget.FrameLayout
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.frostbox.app.MainActivity
import org.frostbox.app.display.RuntimeSurfaceService
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real Binder + SurfaceView + runtime-local ANativeWindow. Does not claim Windows GPU rendering. */
@RunWith(AndroidJUnit4::class)
class SurfaceIpcDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private var view: SurfaceView? = null
    private fun evidence(label: String, result: JSONObject) {
        val r = result.put("label", label).put("testPid", Process.myPid()).put("testUid", Process.myUid())
        File(inst.targetContext.filesDir, "surface-ipc-evidence").apply { mkdirs() }.resolve("$label.json").writeText(r.toString(2))
        inst.sendStatus(2, Bundle().apply { putString("frostbox.surface.result", r.toString()) })
    }
    private fun createSurface(width: Int = 320, height: Int = 240): Surface {
        val ready = CountDownLatch(1)
        ui.activityRule.scenario.onActivity { activity ->
            val root = activity.findViewById<FrameLayout>(android.R.id.content)
            view?.let { old -> (old.parent as? android.view.ViewGroup)?.removeView(old) }
            view = SurfaceView(activity).apply {
                holder.setFixedSize(width, height)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {}
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) { if (w == width && h == height) ready.countDown() }
                    override fun surfaceDestroyed(holder: SurfaceHolder) {}
                })
                root.addView(this, FrameLayout.LayoutParams(width, height))
            }
        }
        assertTrue("SurfaceView creation timed out", ready.await(10, TimeUnit.SECONDS))
        return checkNotNull(view).holder.surface
    }
    @After fun removeView() { ui.activityRule.scenario.onActivity { view?.let { v -> (v.parent as? android.view.ViewGroup)?.removeView(v) }; view = null } }
    private inner class Client : AutoCloseable {
        val replies = LinkedBlockingQueue<JSONObject>()
        val connected = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        var service: Messenger? = null
        val owner = Binder()
        val session = UUID.randomUUID().toString()
        var requestId = 0L
        val response = Messenger(Handler(Looper.getMainLooper()) { msg -> replies.put(JSONObject(msg.data.getString("json")!!)); true })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { service = Messenger(binder); connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { service = null; disconnected.countDown() }
            override fun onBindingDied(name: ComponentName) { disconnected.countDown() }
        }
        init {
            inst.runOnMainSync { assertTrue(inst.targetContext.bindService(Intent(inst.targetContext, RuntimeSurfaceService::class.java), connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)) }
            assertTrue(connected.await(10, TimeUnit.SECONDS))
        }
        fun request(op: Int, seq: Long = 1, generation: Long = 1, surface: Surface? = null, token: IBinder = owner, color: Int = 0, total: Int = 100): JSONObject {
            val id = ++requestId
            checkNotNull(service).send(Message.obtain(null, op).apply {
                replyTo = response
                data = Bundle().apply {
                    putLong("request", id); putString("session", session); putBinder("owner", token)
                    putLong("sequence", seq); putLong("generation", generation)
                    putInt("color", color); putInt("total", total); putInt("width", 320); putInt("height", 240); surface?.let { putParcelable("surface", it) }
                }
            })
            val r = replies.poll(30, TimeUnit.SECONDS) ?: error("Surface IPC timeout")
            assertEquals(id, r.getLong("request")); assertEquals(Process.myUid(), r.getInt("runtimeUid"))
            assertNotEquals(Process.myPid(), r.getInt("runtimePid"))
            assertEquals(0, r.getInt("presentedFrames")); assertFalse(r.getBoolean("wineSurfaceConnected"))
            return r
        }
        override fun close() { inst.runOnMainSync { inst.targetContext.unbindService(connection) }; service = null }
    }
    private fun accepted(r: JSONObject) { assertTrue(r.toString(), r.getBoolean("accepted")); assertEquals("", r.getString("error")) }
    @Test fun parcelableSurfaceOwnershipAndLifecycle() {
        val surface = createSurface()
        Client().use { c ->
            val first = c.request(RuntimeSurfaceService.ATTACH, surface = surface); accepted(first)
            assertTrue(first.getBoolean("windowOwned")); assertTrue(surface.isValid)
            assertEquals(320, first.getJSONObject("nativeWindow").getInt("width"))
            assertEquals(240, first.getJSONObject("nativeWindow").getInt("height"))
            assertEquals(first.getInt("runtimePid"), first.getJSONObject("nativeWindow").getInt("ownerPid"))
            assertFalse(c.request(RuntimeSurfaceService.ATTACH, surface = surface).getBoolean("accepted"))
            assertFalse(c.request(RuntimeSurfaceService.REVOKE, 2, token = Binder()).getBoolean("accepted"))
            val revoked = c.request(RuntimeSurfaceService.REVOKE, 2, 2); accepted(revoked)
            assertFalse(revoked.getBoolean("windowOwned")); assertEquals(revoked.getLong("acquired"), revoked.getLong("released"))
            assertFalse(c.request(RuntimeSurfaceService.ATTACH, 3, 1, surface).getBoolean("accepted"))
            val next = c.request(RuntimeSurfaceService.ATTACH, 3, 3, surface); accepted(next)
            // Activity destroys its Surface; runtime explicitly revokes its independently acquired reference.
            ui.activityRule.scenario.recreate()
            val retired = c.request(RuntimeSurfaceService.REVOKE, 4, 4); accepted(retired)
            val recreated = createSurface(400, 300)
            val fresh = c.request(RuntimeSurfaceService.ATTACH, 5, 5, recreated); accepted(fresh)
            assertEquals(400, fresh.getJSONObject("nativeWindow").getInt("width"))
            assertEquals(300, fresh.getJSONObject("nativeWindow").getInt("height"))
            evidence("surface-created-resized-recreated", fresh)
            val closed = c.request(RuntimeSurfaceService.CLOSE, 6, 6); accepted(closed)
            assertFalse(closed.getBoolean("windowOwned")); assertEquals(closed.getLong("acquired"), closed.getLong("released"))
            assertFalse(c.request(RuntimeSurfaceService.ATTACH, 7, 7, recreated).getBoolean("accepted"))
            evidence("surface-closed-no-stale-reattach", closed)
        }
    }
    @Test fun runtimeDeathAndFreshSurface() {
        val surface = createSurface(); var oldPid = 0
        Client().use { c ->
            val first = c.request(RuntimeSurfaceService.ATTACH, surface = surface); accepted(first)
            oldPid = first.getInt("runtimePid"); Process.killProcess(oldPid)
            assertTrue("Service death not delivered", c.disconnected.await(10, TimeUnit.SECONDS))
            evidence("runtime-death-observed", first.put("serviceDisconnected", true))
        }
        Client().use { c ->
            val fresh = c.request(RuntimeSurfaceService.ATTACH, surface = surface); accepted(fresh)
            assertNotEquals(oldPid, fresh.getInt("runtimePid")); assertEquals(1L, fresh.getLong("acquired"))
            val closed = c.request(RuntimeSurfaceService.CLOSE, 2, 2); accepted(closed)
            assertEquals(closed.getLong("acquired"), closed.getLong("released"))
            evidence("runtime-restarted-new-owner", closed)
        }
    }
    @Test fun currentBox64HasNoExportedMain() {
        Client().use { c ->
            val r = c.request(RuntimeSurfaceService.LOADER_DIAGNOSTIC); accepted(r)
            val loader = r.getJSONObject("loader")
            assertTrue(loader.toString(), loader.getBoolean("libraryLoadable"))
            assertFalse(loader.toString(), loader.getBoolean("mainExported"))
            assertFalse(loader.getBoolean("entryInvoked"))
            assertTrue(loader.toString(), loader.getString("error").contains("main"))
            evidence("box64-loadable-main-not-exported", r)
        }
    }

    private fun imagePixels(label: String): Bitmap {
        val ready=CountDownLatch(1);var status=-1
        val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
        ui.activityRule.scenario.onActivity { PixelCopy.request(checkNotNull(view),bitmap,{ status=it;ready.countDown() },Handler(Looper.getMainLooper())) }
        assertTrue(ready.await(10,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,status)
        File(inst.targetContext.filesDir,"surface-ipc-evidence").apply { mkdirs() }.resolve("$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        return bitmap
    }

    @Test fun windowsTriangleThroughGuestWsi() {
        val surface=createSurface()
        Client().use { c ->
            accepted(c.request(RuntimeSurfaceService.ATTACH,surface=surface))
            val start=c.request(RuntimeSurfaceService.GUEST_START);accepted(start);evidence("windows-wsi-host-start",start)
            lateinit var probe: RuntimeProbeClient
            inst.runOnMainSync { probe=RuntimeProbeClient(inst.targetContext);probe.start(17,start.getString("endpoint")) }
            val hashes=mutableSetOf<Int>();var lastFrames=0;val deadline=SystemClock.elapsedRealtime()+120000
            try {
                while(probe.state.value.active && SystemClock.elapsedRealtime()<deadline) {
                    val status=c.request(RuntimeSurfaceService.GUEST_STATUS);accepted(status)
                    val state=status.getJSONObject("guestWsi");val frames=state.optInt("frames")
                    if(frames>lastFrames) {
                        val bitmap=imagePixels("windows-triangle-$frames")
                        val pixels=IntArray(320*240);bitmap.getPixels(pixels,0,320,0,0,320,240);hashes.add(pixels.contentHashCode());bitmap.recycle();lastFrames=frames
                    }
                    Thread.sleep(80)
                }
                val report=probe.state.value.report
                val result=runCatching { JSONObject(report.substringAfter('\n')) }.getOrElse { JSONObject().put("error",report) }
                val status=c.request(RuntimeSurfaceService.GUEST_STATUS)
                evidence("windows-triangle-result",result.put("hostWsi",status).put("distinctPixelCopies",hashes.size))
                assertFalse("Windows fixture timeout",probe.state.value.active)
                assertEquals(result.toString(),42,result.optInt("exitCode",-1));assertEquals(0,result.getInt("signal"))
                assertFalse(result.getBoolean("timedOut"));assertFalse(result.getBoolean("cancelled"))
                assertTrue(result.getString("stdout"),result.getString("stdout").contains("DRAWS=120"))
                assertEquals(120,status.getJSONObject("guestWsi").getInt("frames"))
                assertTrue("PixelCopy did not prove changing frames",hashes.size>=2)
                assertEquals(0,result.getJSONObject("wineShutdown").getJSONObject("wait").getInt("exitCode"))
            } finally {
                inst.runOnMainSync { if(probe.state.value.active)probe.cancel() }
                val end=SystemClock.elapsedRealtime()+10000;while(probe.state.value.active&&SystemClock.elapsedRealtime()<end)Thread.sleep(50)
                inst.runOnMainSync { probe.close() }
                accepted(c.request(RuntimeSurfaceService.CLOSE,2,2))
            }
        }
    }
    @Test fun d3d11CubeThroughDxvk() {
        val surface=createSurface()
        Client().use { c ->
            accepted(c.request(RuntimeSurfaceService.ATTACH,surface=surface))
            val start=c.request(RuntimeSurfaceService.GUEST_START);accepted(start);evidence("d3d11-host-start",start)
            lateinit var probe: RuntimeProbeClient
            inst.runOnMainSync { probe=RuntimeProbeClient(inst.targetContext);probe.start(18,start.getString("endpoint")) }
            val hashes=mutableSetOf<Int>();var lastFrames=0;val deadline=SystemClock.elapsedRealtime()+120000
            try {
                while(probe.state.value.active && SystemClock.elapsedRealtime()<deadline) {
                    val status=c.request(RuntimeSurfaceService.GUEST_STATUS);accepted(status)
                    val state=status.getJSONObject("guestWsi");val frames=state.optInt("frames")
                    if(frames>lastFrames) {
                        val bitmap=imagePixels("d3d11-cube-$frames")
                        val pixels=IntArray(320*240);bitmap.getPixels(pixels,0,320,0,0,320,240);hashes.add(pixels.contentHashCode());bitmap.recycle();lastFrames=frames
                    }
                    Thread.sleep(80)
                }
                val report=probe.state.value.report
                val result=runCatching { JSONObject(report.substringAfter('\n')) }.getOrElse { JSONObject().put("error",report) }
                val status=c.request(RuntimeSurfaceService.GUEST_STATUS)
                evidence("d3d11-cube-result",result.put("hostWsi",status).put("distinctPixelCopies",hashes.size))
                assertFalse("Windows fixture timeout",probe.state.value.active)
                assertEquals(result.toString(),42,result.optInt("exitCode",-1));assertEquals(0,result.getInt("signal"))
                assertFalse(result.getBoolean("timedOut"));assertFalse(result.getBoolean("cancelled"))
                assertTrue(result.getString("stdout"),result.getString("stdout").contains("DRAWS=120"))
                assertTrue(status.toString(),status.getJSONObject("guestWsi").getInt("frames")>=120)
                val logs=result.getJSONObject("dxvkLogs").toString()
                assertTrue(logs,logs.contains("DXVK") && logs.contains("2.7.1") && logs.contains("Adreno"))
                assertTrue("PixelCopy did not prove changing frames",hashes.size>=2)
                assertEquals(0,result.getJSONObject("wineShutdown").getJSONObject("wait").getInt("exitCode"))
            } finally {
                inst.runOnMainSync { if(probe.state.value.active)probe.cancel() }
                val end=SystemClock.elapsedRealtime()+10000;while(probe.state.value.active&&SystemClock.elapsedRealtime()<end)Thread.sleep(50)
                inst.runOnMainSync { probe.close() }
                accepted(c.request(RuntimeSurfaceService.CLOSE,2,2))
            }
        }
    }
    @Test fun x86D3d9ThroughDxvk() {
        val surface=createSurface()
        Client().use {c->
            accepted(c.request(RuntimeSurfaceService.ATTACH,surface=surface))
            val start=c.request(RuntimeSurfaceService.GUEST_START);accepted(start)
            lateinit var probe: RuntimeProbeClient
            inst.runOnMainSync {probe=RuntimeProbeClient(inst.targetContext);probe.start(21,start.getString("endpoint"))}
            val hashes=mutableSetOf<Int>();var frames=0;val deadline=SystemClock.elapsedRealtime()+180000
            try {
                while(probe.state.value.active && SystemClock.elapsedRealtime()<deadline) {
                    val state=c.request(RuntimeSurfaceService.GUEST_STATUS).getJSONObject("guestWsi")
                    if(state.optInt("frames")>frames) {
                        frames=state.getInt("frames");val bitmap=imagePixels("d3d9-x86-$frames")
                        val pixels=IntArray(320*240);bitmap.getPixels(pixels,0,320,0,0,320,240);hashes.add(pixels.contentHashCode());bitmap.recycle()
                    }
                    Thread.sleep(80)
                }
                val r=JSONObject(probe.state.value.report.substringAfter('\n',"{}"))
                val state=c.request(RuntimeSurfaceService.GUEST_STATUS).getJSONObject("guestWsi")
                evidence("d3d9-x86-result",r.put("hostWsi",state).put("distinctPixelCopies",hashes.size))
                assertFalse("x86 D3D9 timeout",probe.state.value.active);assertTrue(r.toString(),r.getBoolean("wow64Validated"))
                assertEquals(42,r.getJSONArray("runs").getJSONObject(0).getInt("exitCode"))
                assertTrue(state.toString(),state.getInt("frames")>=120);assertTrue("Changing GPU pixels",hashes.size>=2)
                assertTrue(r.getJSONObject("dxvkLogs").toString().contains("Adreno"))
                assertEquals(0,r.getJSONObject("wait").getInt("exitCode"))
            } finally {
                inst.runOnMainSync {if(probe.state.value.active)probe.cancel()}
                val end=SystemClock.elapsedRealtime()+15000;while(probe.state.value.active&&SystemClock.elapsedRealtime()<end)Thread.sleep(50)
                inst.runOnMainSync {probe.close()};accepted(c.request(RuntimeSurfaceService.CLOSE,2,2))
            }
        }
    }
    @Test fun separateBox64ImageGpuPresent() {
        val surface=createSurface()
        Client().use { c ->
            accepted(c.request(RuntimeSurfaceService.ATTACH,surface=surface))
            for(color in 0..1) {
                val r=c.request(RuntimeSurfaceService.IMAGE_FRAME,color=color)
                evidence("external-image-frame-$color",r);accepted(r)
                val b=r.getJSONObject("imageBridge");assertFalse(b.getBoolean("windowsPresentation"))
                assertTrue(b.getBoolean("sameDeviceUuid"));assertEquals(color+1,b.getInt("presentedFrames"))
                val child=b.getJSONObject("child");assertEquals(42,child.getInt("exitCode"));assertNotEquals(r.getInt("runtimePid"),child.getInt("pid"))
                assertEquals(0L,child.getLong("droppedOut"));assertEquals(0L,child.getLong("droppedErr"));assertFalse(child.getBoolean("running"));assertFalse(child.getBoolean("timedOut"));assertFalse(child.getBoolean("cancelled"));
                assertTrue(child.getString("stdout").contains("FROST_WSI_GUEST=X86_64"));assertTrue(child.getString("stdout").contains("Adreno"));assertTrue(child.getString("stdout").contains("FROST_WSI_IMAGE_EXPORTED FRAME=$color"))
                val bitmap=imagePixels("external-image-frame-$color")
                val pixel=bitmap.getPixel(160,120)
                assertTrue("Guest color missing: $pixel",if(color==0) Color.red(pixel)>240&&Color.green(pixel)<15&&Color.blue(pixel)<15 else Color.green(pixel)>240&&Color.red(pixel)<15&&Color.blue(pixel)<15)
                evidence("external-image-pixelcopy-$color",JSONObject().put("pixelCopy",true).put("color",color).put("pixel",pixel).put("windowsPresentation",false))
                bitmap.recycle()
            }
            val closed=c.request(RuntimeSurfaceService.CLOSE,2,2);accepted(closed)
            assertEquals(closed.getLong("acquired"),closed.getLong("released"));evidence("external-image-closed",closed)
        }
    }
    @Test fun persistentBox64HundredFrames() {
        val surface=createSurface()
        Client().use { c ->
            accepted(c.request(RuntimeSurfaceService.ATTACH,surface=surface))
            var pid=0
            for(frame in 0 until 100) {
                val r=c.request(RuntimeSurfaceService.IMAGE_PERSISTENT)
                accepted(r)
                val b=r.getJSONObject("imageBridge");val child=b.getJSONObject("child")
                if(frame==0)pid=child.getInt("pid")
                assertEquals(pid,child.getInt("pid"));assertNotEquals(r.getInt("runtimePid"),pid)
                assertEquals(frame+1,b.getInt("sessionFrame"));assertEquals(frame+1,b.getInt("presentedFrames"))
                assertEquals(2,b.getInt("poolSize"));assertEquals(if(frame==0)1 else 2,b.getInt("imports"))
                assertEquals(0L,child.getLong("droppedOut"));assertEquals(0L,child.getLong("droppedErr"))
                assertFalse(b.getBoolean("windowsPresentation"));assertTrue(b.getBoolean("sameDeviceUuid"))
                if(frame in listOf(0,1,48,49,98,99)) {
                    val pixels=imagePixels("persistent-$frame");val pixel=pixels.getPixel(160,120)
                    assertTrue("Incorrect guest frame $frame: $pixel",if(frame%2==0) Color.red(pixel)>240&&Color.green(pixel)<15 else Color.green(pixel)>240&&Color.red(pixel)<15)
                    pixels.recycle();evidence("persistent-$frame",r.put("pixelCopy",true).put("pixel",pixel))
                }
                if(frame==99) {
                    assertEquals(42,child.getInt("exitCode"));assertFalse(child.getBoolean("running"))
                    assertEquals(100,b.getInt("syncImports"));assertTrue(b.getInt("realSyncFds")>0)
                    assertTrue(child.getString("stdout").contains("FROST_WSI_PERSISTENT_FRAMES=100 POOL=2"))
                    assertEquals(1,Regex("CALL=vkCreateInstance").findAll(child.getString("stdout")).count())
                    assertEquals(1,Regex("CALL=vkCreateDevice").findAll(child.getString("stdout")).count())
                    assertEquals(2,Regex("CALL=vkAllocateMemory").findAll(child.getString("stdout")).count())
                } else assertTrue(child.getBoolean("running"))
            }
            val close=c.request(RuntimeSurfaceService.CLOSE,2,2);accepted(close);evidence("persistent-closed",close)
        }
    }
    @Test fun persistentStopResizeAndGuestDeath() {
        val surface=createSurface()
        Client().use { c ->
            accepted(c.request(RuntimeSurfaceService.ATTACH,surface=surface))
            val initial=c.request(RuntimeSurfaceService.IMAGE_PERSISTENT,total=10);accepted(initial)
            val firstPid=initial.getJSONObject("imageBridge").getJSONObject("child").getInt("pid")
            val revoked=c.request(RuntimeSurfaceService.REVOKE,2,2);accepted(revoked);evidence("persistent-stop",revoked)
            // This check occurs in the same app UID; only our recorded guest PID is inspected.
            assertFalse("Guest survived Stop",File("/proc/$firstPid").exists())
            val resized=createSurface(400,300)
            accepted(c.request(RuntimeSurfaceService.ATTACH,3,3,resized))
            val next=c.request(RuntimeSurfaceService.IMAGE_PERSISTENT,generation=3,total=10);accepted(next)
            val secondPid=next.getJSONObject("imageBridge").getJSONObject("child").getInt("pid")
            assertNotEquals(firstPid,secondPid);Process.killProcess(secondPid)
            Thread.sleep(100)
            val dead=c.request(RuntimeSurfaceService.IMAGE_PERSISTENT,generation=3,total=10)
            assertFalse("Dead guest reported success",dead.getBoolean("accepted"));evidence("persistent-guest-death",dead)
            accepted(c.request(RuntimeSurfaceService.REVOKE,4,4))
            accepted(c.request(RuntimeSurfaceService.ATTACH,5,5,resized))
            for(frame in 0..1)accepted(c.request(RuntimeSurfaceService.IMAGE_PERSISTENT,generation=5,total=2))
            val closed=c.request(RuntimeSurfaceService.CLOSE,6,6);accepted(closed)
            assertEquals(closed.getLong("acquired"),closed.getLong("released"));evidence("persistent-recovered",closed)
        }
    }
    @Test fun persistentRepeatedSessionsAndFdBounds() {
        val surface=createSurface()
        Client().use { c ->
            var fdBaseline=0;val pids=mutableSetOf<Int>()
            for(cycle in 0..3) {
                val revision=1L+cycle*2
                accepted(c.request(RuntimeSurfaceService.ATTACH,revision,revision,surface))
                var last: JSONObject?=null
                for(frame in 0 until 100) {
                    val r=c.request(RuntimeSurfaceService.IMAGE_PERSISTENT,generation=revision)
                    accepted(r);last=r
                }
                val bridge=checkNotNull(last).getJSONObject("imageBridge");val child=bridge.getJSONObject("child")
                assertEquals(42,child.getInt("exitCode"));assertEquals(100,bridge.getInt("presentedFrames"))
                assertEquals(2,bridge.getInt("imports"));assertEquals(100,bridge.getInt("syncImports"))
                assertTrue(pids.add(child.getInt("pid")))
                val close=c.request(RuntimeSurfaceService.REVOKE,revision+1,revision+1);accepted(close)
                val fdCount=close.getInt("openFdCount");assertTrue(fdCount>0)
                if(cycle==0)fdBaseline=fdCount else assertTrue("FD growth $fdBaseline -> $fdCount",fdCount<=fdBaseline+3)
                evidence("persistent-cycle-$cycle",checkNotNull(last).put("fdsAfterStop",fdCount))
            }
            accepted(c.request(RuntimeSurfaceService.CLOSE,9,9))
        }
    }
    @Test fun persistentRuntimeDeathRecovery() {
        val surface=createSurface();var guestPid=0;var runtimePid=0
        Client().use { c ->
            accepted(c.request(RuntimeSurfaceService.ATTACH,surface=surface))
            val r=c.request(RuntimeSurfaceService.IMAGE_PERSISTENT,total=10);accepted(r)
            guestPid=r.getJSONObject("imageBridge").getJSONObject("child").getInt("pid");runtimePid=r.getInt("runtimePid")
            Process.killProcess(runtimePid);assertTrue(c.disconnected.await(10,TimeUnit.SECONDS))
            // EOF on the private socket terminates the orphaned diagnostic; no global process kill.
            val until=SystemClock.elapsedRealtime()+10000
            while(File("/proc/$guestPid").exists()&&SystemClock.elapsedRealtime()<until)Thread.sleep(50)
            assertFalse("Guest left after runtime death",File("/proc/$guestPid").exists())
            evidence("persistent-runtime-death",r.put("guestGone",true))
        }
        // A dead Vulkan producer can leave the old BufferQueue connected. A new Surface generation is required.
        val replacement=createSurface()
        Client().use { c ->
            val r=c.request(RuntimeSurfaceService.ATTACH,surface=replacement);accepted(r);assertNotEquals(runtimePid,r.getInt("runtimePid"))
            for(frame in 0..1)accepted(c.request(RuntimeSurfaceService.IMAGE_PERSISTENT,total=2))
            val closed=c.request(RuntimeSurfaceService.CLOSE,2,2);accepted(closed);evidence("persistent-runtime-recovered",closed)
        }
    }
    @Test fun surfaceParcelCannotBeFlattenedForUnixSocket() {
        val surface = createSurface()
        val parcel = Parcel.obtain()
        try {
            surface.writeToParcel(parcel, 0)
            var error = ""
            try { parcel.marshall(); fail("Binder Surface must not become raw socket bytes") }
            catch (expected: RuntimeException) { error = expected.toString() }
            assertTrue(error, error.contains("objects"))
            evidence("surface-parcel-raw-marshalling-rejected", JSONObject().put("rawMarshallRejected", true).put("error", error)
                .put("windowsPresentation", false).put("testUid", Process.myUid()))
            assertTrue(surface.isValid)
        } finally { parcel.recycle() }
    }
}
