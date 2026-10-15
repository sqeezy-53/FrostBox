package org.frostbox.core

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class CoreTest {
    @Test fun profilesRoundTrip() {
        val p = GameProfile("12345678-1234-1234-1234-123456789abc", "Game", architecture="x86", sha256="abc", fps=45)
        p.validate()
        assertEquals(p, Json.decodeFromString<GameProfile>(Json.encodeToString(p)))
    }
    @Test fun rejectUnsafeLaunchConfiguration() {
        val p=GameProfile("12345678-1234-1234-1234-123456789abc", "Game", architecture="x86-64", sha256="abc")
        p.copy(arguments=listOf("space value","$(literal)",";literal")).validate()
        assertThrows(IllegalArgumentException::class.java) { p.copy(arguments=listOf("bad\u0000arg")).validate() }
        assertThrows(IllegalArgumentException::class.java) { p.copy(arguments=List(65) { "x" }).validate() }
        assertThrows(IllegalArgumentException::class.java) { p.copy(workingDirectory="../other").validate() }
        assertThrows(IllegalArgumentException::class.java) { p.copy(id="------------------------------------").validate() }
    }
    @Test fun importedPathsAndRuntimeEnvironment() {
        validateRelativePath("content/game/data/雪.txt")
        for(path in listOf("../x","content/../x","/absolute","content/a:b","content/CON.txt","content/a\\b","content/name.")) {
            assertThrows(IllegalArgumentException::class.java) { validateRelativePath(path) }
        }
        assertEquals("0",parseLaunchEnvironment("BOX64_DYNAREC=0")["BOX64_DYNAREC"])
        assertThrows(IllegalArgumentException::class.java) { parseLaunchEnvironment("BOX64_PATH=/external") }
    }
    @Test fun runtimeMustMatchPeArchitecture() {
        val x86=GameProfile("12345678-1234-1234-1234-123456789abc","x86",architecture="x86",sha256="abc")
        x86.copy(runtimeVariant="wow64-experimental",graphics=GraphicsBackend.DXVK,graphicsApi="d3d9").validate()
        assertThrows(IllegalArgumentException::class.java) {x86.copy(runtimeVariant="qualified-x64").validate()}
        assertThrows(IllegalArgumentException::class.java) {x86.copy(architecture="x86-64",runtimeVariant="wow64-experimental").validate()}
        assertThrows(IllegalArgumentException::class.java) {x86.copy(architecture="x86-64",graphics=GraphicsBackend.DXVK,graphicsApi="d3d9").validate()}
        val old=Json.encodeToString(x86).replace("\"graphicsApi\":\"auto\",", "")
        assertEquals("auto",Json.decodeFromString<GameProfile>(old).runtimeVariant)
    }
    @Test fun pacingAndStatistics() {
        assertEquals(25_000_000L, targetFrameNanos(40))
        assertNull(targetFrameNanos(0))
        val result = summarizeFrames(List(99) { 10.0 } + 100.0, 10.0)!!
        assertEquals(10.0, result.onePercentLow, 0.001)
        assertEquals(1, result.longFrames)
        assertEquals(100000.0 / 1090.0, result.averageFps, 0.001)
        assertNull(summarizeFrames(emptyList(), 16.67))
    }
    @Test fun thermalHysteresis() {
        val p = ThermalPolicy()
        assertEquals(30, p.recommend(60, 3, null, 0))
        assertEquals(30, p.recommend(60, 0, null, 1))
        assertEquals(30, p.recommend(60, 0, null, 30))
        assertEquals(60, p.recommend(60, 0, null, 31))
    }
    @Test fun absentSensorDoesNotReleaseConstraint() {
        val p = ThermalPolicy()
        p.recommend(60, 4, null, 0)
        assertEquals(30, p.recommend(60, null, null, 100))
        assertEquals(30, p.recommend(60, 1, Double.NaN, 101))
    }
    @Test fun axes() {
        assertEquals(0f, normalizedAxis(.1f, .15f))
        assertEquals(-1f, normalizedAxis(-1f, .15f))
        assertEquals(0f, normalizedAxis(Float.NaN, .15f))
    }
    @Test(expected=IllegalArgumentException::class) fun rejectEnvironmentInjection() { parseEnvironment("LD_PRELOAD=/tmp/bad") }
    @Test fun environmentIsData() { assertEquals("a=b", parseEnvironment("DXVK_TEST=a=b")["DXVK_TEST"]) }
    @Test fun validAndMalformedPe() {
        val f = Files.createTempFile("frostbox-pe", ".exe").toFile()
        try {
            val b = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(0, 0x5a4d.toShort()); b.putInt(0x3c, 128); b.putInt(128, 0x4550)
            b.putShort(132, 0x8664.toShort()); b.putShort(148, 240); b.putShort(150, 2); b.putShort(152, 0x20b)
            f.writeBytes(b.array()); assertEquals("x86-64", PeInspector.inspect(f))
            b.putShort(220,2);f.writeBytes(b.array());assertTrue(PeInspector.isGui(f))
            b.putShort(220,3);f.writeBytes(b.array());assertFalse(PeInspector.isGui(f))
            b.putShort(148,2);f.writeBytes(b.array())
            assertThrows(IllegalArgumentException::class.java) { PeInspector.isGui(f) }
            b.putInt(0x3c, -1); f.writeBytes(b.array())
            assertThrows(IllegalArgumentException::class.java) { PeInspector.inspect(f) }
        } finally { f.delete() }
    }
}
