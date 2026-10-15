package org.frostbox.app.library

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.frostbox.app.MainActivity
import org.frostbox.app.data.ProfileRepository
import org.frostbox.app.ui.*
import org.frostbox.core.GameProfile
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Production menus/dialogs + repository under the app UID, with a separate QA DataStore.
 * Bulk operations NEVER target the real user archive; no EXE is executed. */
@RunWith(AndroidJUnit4::class)
class SafeLibraryDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var repo: ProfileRepository
    private lateinit var scope: CoroutineScope
    private var active by mutableStateOf(false)
    private var addClicks=0
    private lateinit var a: GameProfile
    private lateinit var b: GameProfile
    @Before fun setup() {
        scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val store=PreferenceDataStoreFactory.create(scope=scope,produceFile={File(context.filesDir,"record-qa/${UUID.randomUUID()}.preferences_pb")})
        repo=ProfileRepository(context,store)
        a=GameProfile(UUID.randomUUID().toString(),"Only QA A",architecture="x86-64",sha256="test")
        b=a.copy(id=UUID.randomUUID().toString(),name="Only QA B",fps=45,arguments=listOf("retain me"))
        runBlocking {repo.save(a);repo.save(b)}
        for(p in listOf(a,b))for(path in listOf("game.exe","prefix/system.reg","prefix/user.reg","content/resource.bin","dxvk-cache.bin"))
            File(context.filesDir,"games/${p.id}/$path").apply {parentFile!!.mkdirs();writeText("QA sentinel:$path")}
        ui.activityRule.scenario.onActivity {activity ->activity.setContent {
            val profiles by repo.profiles.collectAsState(initial=emptyList())
            var remove by remember {mutableStateOf<String?>(null)}
            var clear by remember {mutableStateOf(false)}
            var busy by remember {mutableStateOf(false)}
            val actions=rememberCoroutineScope()
            MaterialTheme {Column {
                LibraryListControls(profiles.size,busy,{addClicks++},{clear=true})
                profiles.forEach {p -> GameRecordMenu(p.id,busy,{remove=p.id})}
                if(remove!=null || clear)LibraryRemovalDialog(clear,profiles.size,active,busy,{
                    val id=remove;val all=clear;busy=true;remove=null;clear=false
                    actions.launch {try {if(all)repo.clearEntries {!active} else repo.removeEntry(checkNotNull(id)) {!active}}finally {busy=false}}
                },{remove=null;clear=false})
            }}
        }}
        ui.waitUntil(10000){ui.onAllNodesWithTag("game-actions-${a.id}").fetchSemanticsNodes().isNotEmpty()}
    }
    @After fun close() { scope.cancel() }
    private fun requestOne() {ui.onNodeWithTag("game-actions-${a.id}").performClick();ui.onNodeWithTag("remove-entry-${a.id}").performClick()}
    private fun assertFiles() {
        for(p in listOf(a,b))for(path in listOf("game.exe","prefix/system.reg","prefix/user.reg","content/resource.bin","dxvk-cache.bin"))
            assertEquals("QA sentinel:$path",File(context.filesDir,"games/${p.id}/$path").readText())
    }
    @Test fun cancelBackThenRemoveOnePreservesOtherProfile() {
        requestOne();ui.onNodeWithText("Удалить игру?").assertIsDisplayed()
        ui.onNodeWithTag("library-removal-cancel").performClick();assertEquals(listOf(a,b),runBlocking {repo.profiles.first()})
        requestOne();ui.waitForIdle()
        if(InstrumentationRegistry.getArguments().getString("frostbox.manualBack")=="true")
            InstrumentationRegistry.getInstrumentation().sendStatus(2,android.os.Bundle().apply {putString("frostbox.manual","BACK_DIALOG")})
        else assertTrue("System Back injection refused; use explicit manualBack device run",UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack())
        ui.waitForIdle()
        ui.waitUntil(120000){ui.onAllNodesWithTag("library-removal-confirm").fetchSemanticsNodes().isEmpty()}
        assertEquals(listOf(a,b),runBlocking {repo.profiles.first()})
        requestOne();ui.onNodeWithTag("library-removal-confirm").performClick()
        ui.waitUntil(10000){runBlocking {repo.profiles.first()}==listOf(b)}
        ui.onNodeWithTag("game-actions-${a.id}").assertDoesNotExist();assertFiles()
    }
    @Test fun cancelBulkBlockActiveThenClearShowsEmptyAndAdd() {
        ui.onNodeWithTag("clear-library").performClick()
        ui.onNodeWithText("Записей в библиотеке: 2",substring=true).assertIsDisplayed()
        ui.onNodeWithTag("library-removal-cancel").performClick();assertEquals(listOf(a,b),runBlocking {repo.profiles.first()})
        ui.runOnIdle {active=true}
        requestOne();ui.onNodeWithText("Сначала остановите игру").assertIsDisplayed();ui.onNodeWithTag("library-removal-confirm").assertDoesNotExist()
        ui.onNodeWithTag("library-removal-cancel").performClick()
        ui.onNodeWithTag("clear-library").performClick();ui.onNodeWithTag("library-removal-confirm").assertDoesNotExist()
        ui.onNodeWithTag("library-removal-cancel").performClick();assertEquals(listOf(a,b),runBlocking {repo.profiles.first()})
        ui.runOnIdle {active=false}
        ui.onNodeWithTag("clear-library").performClick();ui.onNodeWithTag("library-removal-confirm").performClick()
        ui.waitUntil(10000){ui.onAllNodesWithTag("library-empty").fetchSemanticsNodes().isNotEmpty()}
        assertTrue(runBlocking {repo.profiles.first().isEmpty()});assertFiles()
        ui.onNodeWithTag("clear-library").assertDoesNotExist();ui.onNodeWithTag("add-game").performClick();assertEquals(1,addClicks)
    }
}
