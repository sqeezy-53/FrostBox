package org.frostbox.app.runtime
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class WineStressDeviceTest {
    @org.junit.Before fun requireOptionalPackage() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        org.junit.Assume.assumeTrue("Wine probe is optional; build with -PwithWineProbe=true",
            runCatching { context.assets.open("runtime/wine-probe.json").use { } }.isSuccess)
    }
    @Test fun twentyWindowsExecutions() { repeat(20) { RuntimeDeviceTest().checkMode(7) } }
}
