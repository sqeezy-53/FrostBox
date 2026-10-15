package org.frostbox.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.frostbox.core.GameProfile
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class LibraryRecordsTest {
    private lateinit var scope: CoroutineScope
    private lateinit var repo: ProfileRepository
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun setup() {
        scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val path=File(app.filesDir,"record-qa/${UUID.randomUUID()}.preferences_pb")
        repo=ProfileRepository(app,PreferenceDataStoreFactory.create(scope=scope,produceFile={path}))
    }
    @After fun close() { scope.cancel() }
    private suspend fun record(): GameProfile {
        val p=GameProfile(UUID.randomUUID().toString(),"QA only",architecture="x86-64",sha256="test",fps=45,
            arguments=listOf("literal argument"),environment="BOX64_LOG=0",winePrefixId=UUID.randomUUID().toString())
        repo.save(p)
        for(path in listOf("game.exe","content/resources/data.bin","prefix/user.reg","prefix/system.reg","dxvk-cache/shaders.bin","launch.json","run.json")) {
            File(app.filesDir,"games/${p.id}/$path").apply {parentFile!!.mkdirs();writeText("sentinel:$path")}
        }
        return p
    }
    private fun files()=File(app.filesDir,"games").walkTopDown().filter {it.isFile}.associate {it.relativeTo(app.filesDir).path to it.readBytes().toList()}
    @Test fun removeOnePreservesOtherSettingsAndEveryGameFile()=runBlocking {
        val a=record();val b=record();val before=files()
        repo.removeEntry(a.id)
        assertEquals(listOf(b),repo.profiles.first())
        assertEquals(before,files())
    }
    @Test fun clearOnlyRecordsPreservesPrefixesResourcesAndCache()=runBlocking {
        record();record();record();val before=files()
        repo.clearEntries()
        assertTrue(repo.profiles.first().isEmpty());assertEquals(before,files())
    }
    @Test fun activeGuardRejectsSingleAndBulkInsideTransaction()=runBlocking {
        val p=record();val before=files()
        for(clear in listOf(false,true)) {
            try {if(clear)repo.clearEntries {false} else repo.removeEntry(p.id) {false};fail("Active session must block mutation")}
            catch(_: IllegalStateException) { }
            assertEquals(listOf(p),repo.profiles.first());assertEquals(before,files())
        }
    }
    @Test fun coverImportPersistsAndInvalidImageKeepsProfile()=runBlocking {
        val p=record()
        val uri=android.net.Uri.parse("content://qa/cover.png")
        val bitmap=android.graphics.Bitmap.createBitmap(32,16,android.graphics.Bitmap.Config.ARGB_8888)
        val bytes=java.io.ByteArrayOutputStream().apply {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,this)}.toByteArray()
        org.robolectric.Shadows.shadowOf(app.contentResolver).registerInputStream(uri,java.io.ByteArrayInputStream(bytes))
        repo.importCover(p.id,uri)
        val saved=repo.profiles.first().single();saved.validate()
        assertNotNull(saved.coverFile);assertTrue(File(app.filesDir,"games/${p.id}/${saved.coverFile}").isFile)
        val bad=android.net.Uri.parse("content://qa/not-image")
        org.robolectric.Shadows.shadowOf(app.contentResolver).registerInputStream(bad,java.io.ByteArrayInputStream(byteArrayOf(1,2,3)))
        try {repo.importCover(p.id,bad);fail("Invalid image accepted")}catch(_: IllegalArgumentException){}
        assertEquals(saved,repo.profiles.first().single())
    }
    @Test fun emptyAndRepeatedOperationsRemainEmpty()=runBlocking {
        repo.clearEntries();repo.removeEntry(UUID.randomUUID().toString());repo.clearEntries()
        assertTrue(repo.profiles.first().isEmpty())
    }
}
