package org.frostbox.core
import org.junit.Assert.*
import org.junit.Test
class DisplayGeometryTest {
    @Test fun landscapeLetterboxAndCaption() {
        val g=DisplayGeometry(640,400,1200,600)
        assertEquals(120f,g.left,0.001f);assertEquals(0f,g.top,0.001f)
        assertEquals(Pair(0,0),g.map(120f,0f));assertEquals(Pair(320,200),g.map(600f,300f))
        assertNull(g.map(119f,200f));assertNull(g.map(1080f,200f))
        assertEquals(Pair(0,399),g.map(-10f,900f,true))
    }
    @Test fun resizePreservesWindowsCoordinates() {
        val g=DisplayGeometry(640,400,320,800)
        assertEquals(Pair(200,110),g.map(100f,g.top+55))
        assertNull(DisplayGeometry(0,400,320,800).map(0f,0f))
        assertNull(g.map(Float.NaN,0f))
    }
}
