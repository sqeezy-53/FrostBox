package org.frostbox.app.library

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.frostbox.app.MainActivity
import org.frostbox.app.FrostModel
import org.frostbox.app.data.ProfileRepository
import org.junit.Test
import org.junit.Rule
import org.junit.Assert.*
import java.io.File

/** Real user library, read-only navigation; no game launch or system input injection. */
class HorizonUiDeviceTest {
 @get:Rule val ui=createAndroidComposeRule<MainActivity>()
 private fun snap(name: String){ui.waitForIdle();android.os.SystemClock.sleep(500);val i=InstrumentationRegistry.getInstrumentation();val dir=File(i.targetContext.filesDir,"horizon-ui-evidence").apply{mkdirs()};val b=i.uiAutomation.takeScreenshot();checkNotNull(b);File(dir,"$name.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
 @Test fun realLibraryHomeDetailsBackAndLandscape(){
  val i=InstrumentationRegistry.getInstrumentation();val repo=ProfileRepository(i.targetContext)
  val before=runBlocking{repo.profiles.first()};assertTrue("Existing library required",before.isNotEmpty())
  ui.onNodeWithTag("nav-Home").assertIsSelected();ui.waitUntil(20000){ui.onAllNodesWithTag("home-hero").fetchSemanticsNodes().isNotEmpty()};snap("home-portrait")
  ui.onNodeWithTag("nav-Library").performClick()
  val p=before.firstOrNull {it.name.contains("KARLSON",true)} ?: before.first()
  ui.onNodeWithTag("library-search").performTextReplacement(p.name)
  ui.onNodeWithTag("library-search").performImeAction()
  ui.waitUntil(10000){ui.onAllNodesWithTag("details-${p.id}").fetchSemanticsNodes().isNotEmpty()};snap("library-search")
  ui.onNodeWithTag("details-${p.id}").performScrollTo().performClick();snap("game-details")
  ui.onNodeWithTag("settings-${p.id}").performScrollTo().performClick();snap("game-settings")
  ui.activityRule.scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()};ui.onNodeWithTag("details-back").assertExists()
  ui.activityRule.scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()};ui.onNodeWithTag("library-search").assertTextContains(p.name)
  ui.onNodeWithTag("nav-Controllers").performClick();snap("controllers")
  ui.onNodeWithTag("nav-Settings").performClick();snap("settings")
  ui.onNodeWithTag("nav-Home").performClick();ui.onNodeWithTag("nav-Home").assertIsSelected();ui.activityRule.scenario.onActivity {it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
  ui.waitUntil(20000){i.targetContext.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE};snap("home-landscape")
  ui.activityRule.scenario.onActivity {it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT};ui.waitUntil(20000){i.targetContext.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT}
  val after=runBlocking{repo.profiles.first()};assertEquals("Navigation must not edit profiles",before.toSet(),after.toSet())
 }
}
