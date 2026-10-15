package org.frostbox.app.runtime
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
/** Candidate gates; --version is not proof of Windows execution. */
@RunWith(AndroidJUnit4::class)
class WineDeviceTest {
    @org.junit.Before fun requireOptionalPackage() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        org.junit.Assume.assumeTrue("Wine probe is optional; build with -PwithWineProbe=true",
            runCatching { context.assets.open("runtime/wine-probe.json").use { } }.isSuccess)
    }
    @Test fun version() = RuntimeDeviceTest().checkMode(5)
    @Test fun prefix() = RuntimeDeviceTest().checkMode(6)
    @Test fun windowsConsole() = RuntimeDeviceTest().checkMode(7)
    @Test fun stopServer() = RuntimeDeviceTest().checkMode(8)
}
