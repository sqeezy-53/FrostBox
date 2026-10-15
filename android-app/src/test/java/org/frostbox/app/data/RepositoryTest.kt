package org.frostbox.app.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.frostbox.core.GameProfile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import android.net.Uri
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepositoryTest {
    @org.junit.Before fun storage() { org.robolectric.shadows.ShadowStatFs.registerStats(RuntimeEnvironment.getApplication().filesDir.absolutePath,1000000,1000000,1000000) }
    private fun pe(): ByteArray = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN).apply {
        putShort(0, 0x5a4d.toShort()); putInt(0x3c, 128); putInt(128, 0x4550)
        putShort(132, 0x8664.toShort()); putShort(148, 240); putShort(150, 2); putShort(152, 0x20b)
    }.array()

    @Test fun importCopiesAndHashesWithoutLaunching() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://frostbox-test/valid.exe")
        val bytes = pe()
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        val repo = ProfileRepository(app)
        val p = repo.importExe(uri)
        assertArrayEquals(bytes, File(app.filesDir, "games/${p.id}/game.exe").readBytes())
        assertEquals(64, p.sha256.length)
        assertEquals("x86-64", p.architecture)
        assertNull(p.lastLaunch)
        assertFalse(File(app.filesDir, "games/${p.id}/prefix").exists())
        assertEquals(p, repo.profiles.first().single { it.id == p.id })
        val configured=org.json.JSONObject(File(app.filesDir,"games/${p.id}/launch.json").readText())
        assertEquals(p.sha256,configured.getString("exeSha256"))
        assertEquals(".",configured.getString("workingDirectory"))
        repo.save(p.copy(arguments=listOf("--name", "a b", "$(literal)")))
        assertEquals("a b",org.json.JSONObject(File(app.filesDir,"games/${p.id}/launch.json").readText()).getJSONArray("arguments").getString(1))
        assertFalse(File(app.filesDir,"games/${p.id}/prefix").exists())
    }

    @Test fun insufficientStorageRejectsWithoutRecord() = runBlocking {
        val app=RuntimeEnvironment.getApplication()
        org.robolectric.shadows.ShadowStatFs.registerStats(app.filesDir.absolutePath,1,0,0)
        val uri=Uri.parse("content://frostbox-test/low-space.exe")
        shadowOf(app.contentResolver).registerInputStream(uri,ByteArrayInputStream(pe()))
        val repo=ProfileRepository(app);val before=repo.profiles.first()
        try { repo.importExe(uri);fail("Storage check bypassed") } catch(e: IllegalStateException) { assertTrue(e.message!!.contains("storage")) }
        assertEquals(before,repo.profiles.first())
    }

    @Test fun invalidImportRollsBackDirectoryAndProfile() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://frostbox-test/invalid.exe")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1,2,3)))
        val repo = ProfileRepository(app)
        val before = repo.profiles.first()
        val folders = File(app.filesDir, "games").list()?.toSet() ?: emptySet()
        try { repo.importExe(uri); fail("Invalid PE accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(before, repo.profiles.first())
        assertEquals(folders, File(app.filesDir, "games").list()?.toSet() ?: emptySet<String>())
    }

    @Test fun simultaneousReadersDoNotDiscardAtomicResult() = runBlocking {
        val file=File(RuntimeEnvironment.getApplication().filesDir,"concurrent-result.json")
        ManagedFiles.write(file,org.json.JSONObject().put("sequence",0))
        val reader=java.util.concurrent.Executors.newSingleThreadExecutor()
        val running=java.util.concurrent.atomic.AtomicBoolean(true)
        val task=reader.submit { while(running.get()) { check(ManagedFiles.read(file)!!.has("sequence")) } }
        try {
            for(i in 1..100) ManagedFiles.write(file,org.json.JSONObject().put("sequence",i).put("output","x".repeat(65536)))
            assertEquals(100,ManagedFiles.read(file)!!.getInt("sequence"))
        } finally { running.set(false);task.get();reader.shutdownNow() }
    }

    @Test fun saveReadAndReplaceAcrossRepositoryInstances() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val id = UUID.randomUUID().toString()
        val p = GameProfile(id, "Before", architecture="x86-64", sha256="abc")
        ProfileRepository(context).save(p)
        val second = ProfileRepository(context)
        assertEquals(p, second.profiles.first().single { it.id == id })
        second.save(p.copy(name="After", fps=45, deadZone=.25f))
        val stored = ProfileRepository(context).profiles.first().filter { it.id == id }
        assertEquals(1, stored.size)
        assertEquals("After", stored.single().name)
        assertEquals(45, stored.single().fps)
        assertEquals(.25f, stored.single().deadZone)
    }
}
