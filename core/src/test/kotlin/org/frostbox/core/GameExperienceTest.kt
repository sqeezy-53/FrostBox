package org.frostbox.core
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.*

class GameExperienceTest {
    @Test fun fourByThreeFitOnWideScreen() {
        val g=ViewportTransform(800,600,2400,1080,ScalingMode.FIT)
        assertEquals(480f,g.left,.01f);assertEquals(0f,g.top,.01f)
        assertEquals(1.8f,g.scaleX,.01f);assertEquals(g.scaleX,g.scaleY,0f)
        assertNull(g.map(479f,500f));assertEquals(400 to 300,g.map(1200f,540f))
        assertEquals(0 to 599,g.map(-1f,2000f,true))
    }
    @Test fun fillCropsSourceAndKeepsInverseMapping() {
        val g=ViewportTransform(800,600,2400,1080,ScalingMode.FILL)
        assertEquals(-360f,g.top,.01f);assertEquals(120f,g.cropTop,.01f);assertEquals(480f,g.cropBottom,.01f)
        assertEquals(0 to 120,g.map(0f,0f));assertEquals(400 to 300,g.map(1200f,540f))
        assertNull(g.map(-1f,500f));assertNull(g.map(Float.NaN,0f))
    }
    @Test fun stretchIsAnisotropicAndAutoMatchesFit() {
        val g=ViewportTransform(800,600,2400,1080,ScalingMode.STRETCH)
        assertEquals(3f,g.scaleX,.01f);assertEquals(1.8f,g.scaleY,.01f);assertEquals(400 to 300,g.map(1200f,540f))
        val fit=ViewportTransform(800,600,1080,2400,ScalingMode.FIT)
        val auto=ViewportTransform(800,600,1080,2400,ScalingMode.AUTO)
        assertEquals(fit.map(540f,1200f),auto.map(540f,1200f));assertEquals(fit.top,auto.top,0f)
    }
    @Test fun transformsRoundTripAcrossRotationAndAspectRatios() {
        for((w,h) in listOf(800 to 600,1280 to 720,480 to 800))for((vw,vh) in listOf(2400 to 1080,1080 to 2400,1200 to 900))for(mode in ScalingMode.entries) {
            val g=ViewportTransform(w,h,vw,vh,mode)
            for(x in listOf(w/4,w/2,w*3/4))for(y in listOf(h/4,h/2,h*3/4)) {
                val px=g.left+x*g.scaleX;val py=g.top+y*g.scaleY
                if(px in 0f..<vw.toFloat() && py in 0f..<vh.toFloat()) {
                    val actual=g.map(px,py)!!;assertTrue(kotlin.math.abs(actual.first-x)<=1 && kotlin.math.abs(actual.second-y)<=1)
                }
            }
        }
    }
    @Test fun legacyProfileDefaultsAreAdditive() {
        val p=Json.decodeFromString<GameProfile>("""{"id":"00000000-0000-0000-0000-000000000001","name":"Legacy","architecture":"x86","sha256":"test"}""")
        p.validate();assertEquals(GameExperience(),p.experience)
        assertEquals(SettingApplication.LIVE,gameSettingApplication["scaling"])
        assertEquals(SettingApplication.NEXT_LAUNCH,gameSettingApplication["resolution"])
    }
    @Test fun invalidGainAndHandleFailClosed() {
        for(value in listOf(GameExperience(audioGain=Float.NaN),GameExperience(audioGain=1.1f),GameExperience(panelEdge="unknown"))) {
            assertThrows(IllegalArgumentException::class.java){value.validate()}
        }
        assertThrows(IllegalArgumentException::class.java){ViewportTransform(0,1,1,1,ScalingMode.FIT)}
    }
}
