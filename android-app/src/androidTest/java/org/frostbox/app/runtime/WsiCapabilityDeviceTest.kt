// SPDX-License-Identifier: MIT
package org.frostbox.app.runtime

import android.os.*
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

@RunWith(AndroidJUnit4::class)
class WsiCapabilityDeviceTest {
    @Test fun currentWineDoesNotExposeWin32Surface() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val record = File(context.filesDir, "wine-vulkan-profile-id.txt")
        check(record.isFile) { "Reuse the dev.2 diagnostic prefix; run windowsCompute once if not present" }
        assertTrue(PrefixDeviceTest().operation(record.readText().trim(), "validate").getBoolean("valid"))
        lateinit var client: RuntimeProbeClient
        inst.runOnMainSync { client = RuntimeProbeClient(context); client.start(16) }
        try {
            val state = withTimeout(90000) { client.state.first { !it.active } }
            val r = JSONObject(state.report.substringAfter('\n'))
                .put("testPid", Process.myPid()).put("testUid", Process.myUid())
            File(context.filesDir, "surface-ipc-evidence").apply { mkdirs() }.resolve("wine-win32-wsi-blocked.json").writeText(r.toString(2))
            inst.sendStatus(2, Bundle().apply { putString("frostbox.surface.result", r.toString()) })
            assertEquals(r.toString(), "", r.optString("error"))
            assertEquals(Process.myUid(), r.getInt("runtimeUid")); assertNotEquals(Process.myPid(), r.getInt("runtimePid"))
            assertEquals(44, r.getInt("exitCode")); assertEquals(0, r.getInt("signal")); assertEquals(0, r.getInt("execErrno"))
            assertFalse(r.getBoolean("timedOut")); assertFalse(r.getBoolean("cancelled")); assertFalse(r.getBoolean("bootstrap"))
            val output = r.getString("stdout").replace("\r\n", "\n")
            assertTrue(output, output.contains("FROSTBOX_WSI_CAPABILITY_WINDOWS_PE"))
            assertTrue(output, output.contains("FROSTBOX_WSI_AVAILABLE WIN32=0"))
            assertTrue(output, output.contains("FROSTBOX_WSI_CREATE_INSTANCE RESULT=-7"))
            assertEquals(0, r.getJSONObject("wineShutdown").getJSONObject("wait").getInt("exitCode"))
            assertFalse(File("/proc/${r.getInt("pid")}").exists())
        } finally { inst.runOnMainSync { client.close() } }
    }
}
