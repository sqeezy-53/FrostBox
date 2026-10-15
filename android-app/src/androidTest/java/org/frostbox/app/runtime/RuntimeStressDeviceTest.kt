package org.frostbox.app.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Run only after all four basic device tests pass. 20 independent process launches. */
@RunWith(AndroidJUnit4::class)
class RuntimeStressDeviceTest {
    @Test fun repeatedInterpreterAndDynarec() {
        val harness = RuntimeDeviceTest()
        repeat(10) {
            harness.checkMode(1)
            harness.checkMode(2)
        }
    }
}
