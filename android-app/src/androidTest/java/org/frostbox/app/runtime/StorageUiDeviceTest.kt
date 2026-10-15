package org.frostbox.app.runtime

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.frostbox.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StorageUiDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun showsAllocatedUsageAndRealMaintenanceAction() {
        ui.onNodeWithTag("nav-Settings").performClick()
        ui.onNodeWithTag("nav-Storage").performScrollTo().performClick()
        ui.waitUntil(60000) { ui.onAllNodesWithText("Занято на диске:",substring=true).fetchSemanticsNodes().size==1 }
        ui.onNodeWithText("Найти одинаковые runtime-файлы").performScrollTo().assertIsEnabled()
        val bitmap=ui.onRoot().captureToImage().asAndroidBitmap()
        val folder=File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"storage-sharing").apply { check(isDirectory||mkdirs()) }
        File(folder,"storage-ui.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
        bitmap.recycle()
    }
}
