package org.frostbox.app

import android.os.Looper
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Lifecycle smoke test only; does not validate a physical display, JNI or controller. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ActivityTest {
    @Test fun composeActivityStartsAndStopsWithoutNativeHostLibrary() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
