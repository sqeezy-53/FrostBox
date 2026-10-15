package org.frostbox.app.runtime

import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** Every destructive scenario owns a generated diagnostic profile; never enumerates/deletes user data. */
@RunWith(AndroidJUnit4::class)
class PrefixDeviceTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val record get()=File(context.filesDir,"prefix-validation-id.txt")
    private fun emit(result: JSONObject): JSONObject {
        result.put("testUid",Process.myUid()).put("testPid",Process.myPid())
        instrumentation.sendStatus(2,Bundle().apply { putString("frostbox.wine.result",result.toString()) })
        return result
    }
    internal fun operation(id: String,op: String,expectError: Boolean=false,extras: Bundle.()->Unit = {}): JSONObject = runBlocking {
        lateinit var client: WineRuntimeClient
        instrumentation.runOnMainSync { client=WineRuntimeClient(context);client.request(op,id,extras) }
        try {
            val done=withTimeout(180000) { client.state.first { !it.active } }
            val result=emit(JSONObject(done.result))
            if(expectError) assertTrue(result.toString(),result.optString("error").isNotEmpty())
            else assertEquals(result.toString(),"",result.optString("error"))
            assertEquals(Process.myUid(),result.getInt("runtimeUid"))
            assertNotEquals(Process.myPid(),result.getInt("runtimePid"))
            result
        } finally { instrumentation.runOnMainSync { client.close() } }
    }
    private fun profile(): String {
        if(record.isFile) return record.readText().trim().also {
            assertTrue(File(context.filesDir,"games/$it/fixture-owner.json").isFile)
        }
        val id=UUID.randomUUID().toString()
        operation(id,"fixture");operation(id,"initialize");record.writeText(id)
        return id
    }
    private fun successful(id: String): JSONObject {
        val r=operation(id,"launch")
        assertEquals(r.toString(),42,r.getInt("exitCode"))
        assertEquals("FROSTBOX_PE_RESULT=5050\n",r.getString("stdout"))
        assertFalse(r.getBoolean("bootstrap"))
        assertTrue(r.getJSONObject("prefixValidation").getBoolean("valid"))
        assertEquals(0,r.getInt("signal"));assertFalse(r.getBoolean("timedOut"))
        assertFalse(File("/proc/${r.getInt("pid")}").exists())
        assertEquals(0,r.getJSONObject("wineShutdown").getJSONObject("wait").getInt("exitCode"))
        return r
    }
    @Test fun freshNormalPrefix() {
        val id=UUID.randomUUID().toString()
        operation(id,"fixture")
        val initialized=operation(id,"initialize")
        assertTrue(initialized.toString(),initialized.getBoolean("valid"))
        assertFalse(initialized.getBoolean("reused"))
        successful(id)
        record.writeText(id)
    }
    @Test fun reuseAndTwentyLaunches() {
        val id=profile();assertTrue(operation(id,"initialize").getBoolean("reused"))
        repeat(20) { iteration -> successful(id);emit(JSONObject().put("checkpoint","repeat").put("iteration",iteration+1).put("profileId",id)) }
        assertTrue(operation(id,"validate").getBoolean("valid"))
    }
    private fun interrupt(id: String,op: String,kill: Boolean) = runBlocking {
        lateinit var client: WineRuntimeClient
        instrumentation.runOnMainSync { client=WineRuntimeClient(context);client.request(op,id) }
        try {
            val running=withTimeout(180000) {
                client.state.first {
                    val j=JSONObject(it.result)
                    it.active && j.optBoolean("running") && j.optString("phase")==op &&
                        (op=="initialize" || j.optString("stdout").contains("FROSTBOX_SLEEP_READY"))
                }
            }
            val snapshot=JSONObject(running.result)
            emit(JSONObject(snapshot.toString()).put("checkpoint",if(kill) "before-runtime-kill" else "before-cancel"))
            assertEquals(Process.myUid(),snapshot.getInt("runtimeUid"))
            val pid=snapshot.getInt("runtimePid");assertNotEquals(Process.myPid(),pid)
            instrumentation.runOnMainSync { if(kill) Process.sendSignal(pid,9) else client.stopProcess() }
            val done=JSONObject(withTimeout(30000) { client.state.first { !it.active } }.result)
            emit(done.put("checkpoint",if(kill) "runtime-death-detected" else "cancel-completed"))
            if(kill) assertEquals("IPC_DISCONNECTED",done.getString("phase"))
            else { assertTrue(done.getBoolean("cancelled"));assertFalse(done.getBoolean("timedOut")) }
            snapshot
        } finally { instrumentation.runOnMainSync { client.close() } }
    }
    @Test fun cancelWindowsProcess() {
        val id=profile();operation(id,"fixture") { putBoolean("sleep",true) }
        val prior=interrupt(id,"launch",false)
        assertFalse(File("/proc/${prior.getInt("pid")}").exists())
        assertTrue(operation(id,"validate").getBoolean("valid"))
        operation(id,"fixture");successful(id)
    }
    @Test fun recoverKilledRuntime() {
        val id=profile();operation(id,"fixture") { putBoolean("sleep",true) }
        val prior=interrupt(id,"launch",true)
        assertTrue(operation(id,"recover").getBoolean("valid"))
        assertFalse(File("/proc/${prior.getInt("pid")}").exists())
        operation(id,"fixture");successful(id)
        assertTrue(operation(id,"logs").getJSONObject("lastRecovery").getBoolean("recovered"))
    }
    @Test fun resumeInterruptedWineboot() {
        val id=UUID.randomUUID().toString();operation(id,"fixture")
        interrupt(id,"initialize",true)
        assertFalse(File(context.filesDir,"games/$id/prefix").exists())
        assertTrue(File(context.filesDir,"games/$id/prefix.initializing").isDirectory)
        assertTrue(operation(id,"initialize").getBoolean("valid"));successful(id)
    }
    @Test fun crossProcessPrefixLock() {
        val id=profile()
        RandomAccessFile(File(context.filesDir,"games/$id/wine-manager.lock"),"rw").use { file ->
            file.channel.lock().use { operation(id,"initialize",expectError=true) }
        }
        successful(id)
        assertEquals(0,operation(id,"terminate").getJSONObject("shutdown").getJSONObject("wait").getInt("exitCode"))
        successful(id)
    }
    @Test fun integrityAndExplicitDeletion() {
        val id=UUID.randomUUID().toString();operation(id,"fixture");operation(id,"initialize")
        val home=File(context.filesDir,"games/$id")
        val sentinel=File(home,"keep-user-file.txt").apply { writeText("preserve") }
        val marker=File(home,"prefix/.frostbox-prefix.json")
        val original=marker.readText()
        marker.writeText(JSONObject(original).put("wineVersion","unsupported-test-version").toString())
        assertFalse(operation(id,"validate").getBoolean("valid"))
        operation(id,"initialize",expectError=true)
        assertEquals("unsupported-test-version",JSONObject(marker.readText()).getString("wineVersion"))
        marker.writeText(original)
        assertTrue(operation(id,"validate").getBoolean("valid"))
        assertTrue(File(home,"prefix/drive_c/windows/system32/kernel32.dll").delete())
        assertFalse(operation(id,"validate").getBoolean("valid"))
        operation(id,"initialize",expectError=true)
        operation(id,"delete",expectError=true)
        assertTrue(sentinel.exists())
        assertTrue(operation(id,"delete") { putString("confirmation","DELETE_PREFIX:$id") }.getBoolean("deleted"))
        assertFalse(File(home,"prefix").exists())
        assertEquals("preserve",sentinel.readText());assertTrue(File(home,"game.exe").isFile)
        assertTrue(File(home,"launch.json").isFile)
    }
    @Test fun importContentUriAndExplicitLaunch() = runBlocking {
        profile() // Prepare the verified runtime asset before exposing our own test EXE.
        val source=File(context.filesDir,"runtime-tests/wine-userspace/console-x64.exe")
        source.copyTo(File(context.cacheDir,"import-validation.exe"),overwrite=true)
        val repo=org.frostbox.app.data.ProfileRepository(context)
        val p=repo.importExe(android.net.Uri.parse("content://org.frostbox.app.validation/exe"))
        assertEquals("x86-64",p.architecture)
        val home=File(context.filesDir,"games/${p.id}")
        assertTrue(File(home,"game.exe").isFile)
        assertFalse(File(home,"prefix").exists()) // Import never auto-executes.
        assertFalse(File(home,"fixture-owner.json").exists())
        val args=listOf("--label","literal space","$(literal)",";literal")
        repo.save(p.copy(arguments=args))
        assertEquals(args,JSONObject(File(home,"launch.json").readText()).getJSONArray("arguments").let { array -> List(array.length()) { array.getString(it) } })
        successful(p.id)
        assertNull(repo.profiles.first().single { it.id==p.id }.lastLaunch)
    }
    @Test fun applicationRestartBefore() {
        val id=profile();operation(id,"fixture");successful(id)
        File(context.filesDir,"prefix-validation-restart.json").writeText(JSONObject().put("id",id).put("pid",Process.myPid()).toString())
    }
    @Test fun applicationRestartAfter() {
        val before=JSONObject(File(context.filesDir,"prefix-validation-restart.json").readText())
        assertNotEquals(before.getInt("pid"),Process.myPid())
        val id=before.getString("id");assertEquals(profile(),id)
        assertTrue(operation(id,"initialize").getBoolean("reused"));successful(id)
        emit(before.put("checkpoint","application-restart-confirmed").put("currentPid",Process.myPid()))
    }
}
