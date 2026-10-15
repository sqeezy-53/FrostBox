package org.frostbox.app.runtime

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ImmutableRuntimeFilesTest {
    @Test fun immutablePayloadSharesButMetadataDoesNot() {
        val root=Files.createTempDirectory("runtime-sharing").toFile()
        try {
            val source=root.resolve("source.dll").apply { writeText("qualified") }
            val linked=root.resolve("linked")
            ImmutableRuntimeFiles.copy(root,source.toPath(),linked.toPath(),"x86_64/lib/wine/x86_64-windows/kernel32.dll")
            assertTrue(Files.isSymbolicLink(linked.toPath()))
            assertEquals("qualified",linked.readText())
            val metadata=root.resolve("marker")
            ImmutableRuntimeFiles.copy(root,source.toPath(),metadata.toPath(),"ready")
            assertFalse(Files.isSameFile(source.toPath(),metadata.toPath()))
            val replacement=root.resolve("replacement").apply { writeText("overlay") }
            assertTrue(replacement.renameTo(linked))
            assertEquals("qualified",source.readText())
            assertEquals("overlay",linked.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun prefixGameAndTraversalPathsNeverShare() {
        for(path in listOf("games/id/prefix/drive_c/kernel32.dll","prefix/system.reg","x86_64/../prefix/user.reg",
            "x86_64/lib/wine/x86_64-unix/../user.reg","wow64-ready.json","licenses/a.txt"))
            assertFalse(path,ImmutableRuntimeFiles.shareable(path))
    }
}
