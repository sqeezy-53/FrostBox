package org.frostbox.app.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.os.Bundle
import android.os.Process
import org.json.JSONObject

/** Must execute on ARM64 Android as the app UID. Building this test APK is not a pass. */
@RunWith(AndroidJUnit4::class)
class RuntimeDeviceTest {
    internal fun checkMode(mode: Int) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if(mode == 1 || mode == 2) assertTrue("Box64 not bundled: use -PwithBox64=true", File(context.applicationInfo.nativeLibraryDir,"libbox64.so").isFile)
        lateinit var client: RuntimeProbeClient
        instrumentation.runOnMainSync { client=RuntimeProbeClient(context); client.start(mode) }
        try {
            if(mode == 3) {
                withTimeout(10000) { client.state.first { it.active && it.report.startsWith("Running") } }
                instrumentation.runOnMainSync { client.cancel() }
            }
            val done=withTimeout(if (mode >= 5) 120000 else 20000) { client.state.first { !it.active } }
            assertTrue("No structured result: ${done.report}", done.report.contains('\n'))
            val result = JSONObject(done.report.substringAfter('\n'))
            instrumentation.sendStatus(2, Bundle().apply {
                putString("frostbox.runtime.result", result.put("testUid", Process.myUid()).put("testPid", Process.myPid()).toString())
            })
            assertEquals("Runtime must use the app UID", Process.myUid(), result.getInt("runtimeUid"))
            assertNotEquals("Runtime must be a separate process", Process.myPid(), result.getInt("runtimePid"))
            assertFalse("Child must be reaped", File("/proc/${result.getInt("pid")}").exists())
            if(mode == 3) {
                assertTrue(done.report,done.report.contains("\"cancelled\": true"))
                assertTrue(done.report,done.report.contains("\"timedOut\": false"))
            } else {
                assertTrue(done.report,done.report.startsWith("Built-in test PASSED"))
                if (mode in 6..7) {
                    val shutdown = result.getJSONObject("wineShutdown")
                    val kill = shutdown.getJSONObject("kill")
                    assertTrue(kill.toString(), kill.getInt("exitCode") in 0..1 && !kill.getBoolean("timedOut") && kill.getInt("signal") == 0 && kill.getInt("execErrno") == 0)
                    val wait = shutdown.getJSONObject("wait")
                    assertEquals("Wine server must finish", 0, wait.getInt("exitCode"))
                    assertFalse(wait.toString(), wait.getBoolean("timedOut"))
                }
                if (mode == 2) assertTrue("No emitted dynarec block: $result", result.getString("stderr").contains("Emitting "))
            }
        } finally { instrumentation.runOnMainSync { client.close() } }
    }
    @Test fun nativeProcess() = checkMode(0)
    @Test fun box64Interpreter() = checkMode(1)
    @Test fun box64Dynarec() = checkMode(2)
    @Test fun cancellation() = checkMode(3)
}
