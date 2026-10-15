package org.frostbox.app.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FilterInputStream

class BoundedLogsTest {
    @Test fun doesNotReadBeyondLimit() {
        val input = ByteArrayInputStream("abcdef".toByteArray())
        assertEquals("abc", input.readLogPrefix(3))
        assertEquals('d'.code, input.read())
        assertEquals("ef", input.readLogPrefix(8))
        assertEquals("", input.readLogPrefix(8))
    }

    @Test fun handlesShortReadsAndUtf8() {
        val text = "Журнал"
        val input = object : FilterInputStream(ByteArrayInputStream(text.toByteArray())) {
            override fun read(bytes: ByteArray, off: Int, len: Int): Int = super.read(bytes, off, minOf(len, 1))
        }
        assertEquals(text, input.readLogPrefix(64))
    }
}
