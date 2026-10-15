package org.frostbox.app.library

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.frostbox.app.MainActivity
import org.frostbox.app.data.ProfileRepository
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real Library navigation is read-only. All deletion tests use the isolated QA archive. */
@RunWith(AndroidJUnit4::class)
class RealLibraryRecordDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun actualDetailsSystemBackWithoutMutatingLibrary() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val repo=ProfileRepository(inst.targetContext)
        val before=runBlocking {repo.profiles.first()}
        Assume.assumeTrue("Requires an existing record for read-only navigation",before.isNotEmpty())
        val p=before.first()
        ui.onNodeWithTag("nav-Library").performClick()
        ui.waitUntil(10000){ui.onAllNodesWithTag("details-${p.id}").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("details-${p.id}").performScrollTo().performClick()
        ui.onNodeWithTag("details-back").performScrollTo().assertIsDisplayed();ui.waitForIdle()
        if(InstrumentationRegistry.getArguments().getString("frostbox.manualBack")=="true")
            inst.sendStatus(2,android.os.Bundle().apply {putString("frostbox.manual","BACK_DETAILS")})
        else assertTrue("System Back injection refused; use explicit manualBack device run",UiDevice.getInstance(inst).pressBack())
        ui.waitUntil(120000){ui.onAllNodesWithTag("details-back").fetchSemanticsNodes().isEmpty()}
        assertEquals(before,runBlocking {repo.profiles.first()})
    }
}
