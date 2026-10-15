package org.frostbox.app.runtime

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RuntimeStorageDeviceTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val relative="x86_64/lib/wine/x86_64-windows/ntdll.dll"
    private fun scratch(block: suspend (Context,File,File)->Unit)=runBlocking {
        val home=File(context.cacheDir,"storage-test-${UUID.randomUUID()}").apply { check(mkdir()) }
        val isolated=object: ContextWrapper(context) { override fun getFilesDir()=home }
        try {
            val base=File(home,"runtime-tests/wine-userspace").apply { check(mkdirs()) }
            File(base,".package-sha256").writeText("a".repeat(64))
            val first=File(base,relative).apply { check(parentFile!!.mkdirs());writeBytes(ByteArray(8192){37}) }
            val overlay=File(home,"display/${"b".repeat(64)}").apply { check(mkdirs()) }
            File(overlay,"ready").writeText("b".repeat(64))
            val second=File(overlay,relative).apply { check(parentFile!!.mkdirs());first.copyTo(this);check(setLastModified(first.lastModified())) }
            block(isolated,first,second)
        } finally { org.frostbox.app.data.ManagedFiles.removeTree(home) }
    }

    @Test fun sharesIdenticalPayloadPreservesPrefixAndRejectsBusyLease()=scratch { c,first,second ->
        val user=File(c.filesDir,"games/test/prefix/system.reg").apply { check(parentFile!!.mkdirs());writeText("user registry") }
        val userIdentity=Os.lstat(user.path).st_ino
        RuntimeStorageLease.acquire(c).use {
            assertTrue(runCatching { RuntimeFileSharing.inspect(c) }.isFailure)
        }
        val plan=RuntimeFileSharing.inspect(c)
        assertEquals(2,plan.replacements.size);assertTrue(plan.expectedReclaim>0)
        RuntimeFileSharing.apply(c,plan)
        assertTrue(Files.isSameFile(first.toPath(),second.toPath()))
        assertEquals("user registry",user.readText());assertEquals(userIdentity,Os.lstat(user.path).st_ino)
        assertTrue(RuntimeFileSharing.inspect(c).replacements.isEmpty())
    }

    @Test fun rejectsStalePlanBeforeMutation()=scratch { c,first,second ->
        val plan=RuntimeFileSharing.inspect(c)
        val newFile=File(second.parentFile,"replacement").apply { writeText("changed") }
        check(newFile.renameTo(second))
        assertTrue(runCatching { RuntimeFileSharing.apply(c,plan) }.isFailure)
        assertEquals("changed",second.readText());assertEquals(8192,first.length().toInt())
    }

    @Test fun rejectsRuntimeSymlinks()=scratch { c,first,second ->
        check(second.delete());Os.symlink(first.path,second.path)
        assertTrue(runCatching { RuntimeFileSharing.inspect(c) }.isFailure)
    }

    @Test fun createsSharedDataForNewViewAndKeepsSource()=scratch { c,first,_ ->
        val target=File(c.filesDir,"new-view/ntdll.dll").apply { check(parentFile!!.mkdirs()) }
        ImmutableRuntimeFiles.copy(c.filesDir,first.toPath(),target.toPath(),relative)
        assertTrue(Files.isSymbolicLink(target.toPath()))
        assertFalse(Files.isSymbolicLink(first.toPath()))
        assertArrayEquals(first.readBytes(),target.readBytes())
        assertTrue(ImmutableRuntimeFiles.resolve(c.filesDir,target.toPath(),relative).toFile().isFile)
    }

    @Test fun recoversOnlyVerifiedUnpublishedReference()=scratch { c,first,_ ->
        val reference=File(c.filesDir,"new-view/ntdll.dll").apply { check(parentFile!!.mkdirs()) }
        ImmutableRuntimeFiles.copy(c.filesDir,first.toPath(),reference.toPath(),relative)
        val objectPath=ImmutableRuntimeFiles.resolve(c.filesDir,reference.toPath(),relative)
        val orphan=first.toPath().resolveSibling(".frost-share-${UUID.randomUUID()}")
        Files.createSymbolicLink(orphan,orphan.parent.toFile().canonicalFile.toPath().relativize(objectPath))
        val plan=RuntimeFileSharing.inspect(c)
        assertTrue(Files.exists(orphan,java.nio.file.LinkOption.NOFOLLOW_LINKS))
        RuntimeFileSharing.apply(c,plan)
        assertFalse(Files.exists(orphan,java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isRegularFile(objectPath));assertEquals(8192,first.length().toInt())
    }

    // Explicit maintenance QA only. It never removes a runtime root, prefix or game file.
    @Test fun installedRuntimeSharingDryRunOrApply()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Explicit maintenance QA required",args.getString("storageQa")=="true")
        fun protectedFingerprint(): String {
            val digest=MessageDigest.getInstance("SHA-256")
            for(root in listOf(File(context.filesDir,"games"),File(context.filesDir,"datastore"))) {
                if(!root.exists())continue
                Files.walk(root.toPath()).use { paths -> paths.sorted().forEach { p ->
                    val s=Os.lstat(p.toString())
                    digest.update("${p.toString().removePrefix(context.filesDir.path)}:${s.st_ino}:${s.st_size}:${s.st_mtime}:${s.st_mode}\n".toByteArray())
                } }
            }
            return digest.digest().joinToString(""){"%02x".format(it)}
        }
        val before=protectedFingerprint()
        val plan=RuntimeFileSharing.inspect(context)
        val evidence=File(context.filesDir,"storage-sharing").apply { check(isDirectory||mkdirs()) }
        File(evidence,"plan.json").writeText(plan.json().toString(2))
        val apply=args.getString("applyStorageSharing")=="true"
        val result=if(apply)RuntimeFileSharing.apply(context,plan) else org.json.JSONObject().put("dryRun",true)
        val after=protectedFingerprint()
        assertEquals("Protected game/prefix/profile tree changed",before,after)
        result.put("protectedTreeUnchanged",true).put("runtimeRoots",plan.roots)
            .put("examinedFiles",plan.examined).put("replacementPaths",plan.replacements.size)
            .put("expectedReclaimedAllocatedBytes",plan.expectedReclaim)
        File(evidence,if(apply)"result.json" else "dry-run.json").writeText(result.toString(2))
        instrumentation.sendStatus(2,Bundle().apply { putString("frostbox.storage",result.toString()) })
    }
}
