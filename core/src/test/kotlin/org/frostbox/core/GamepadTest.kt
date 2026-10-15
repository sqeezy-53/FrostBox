package org.frostbox.core
import org.junit.Test
import org.junit.Assert.*
class GamepadTest {
 @Test fun actualRangesAndNeutral(){assertEquals(-1f,stickAxis(-32768f,-32768f,32767f));assertEquals(1f,stickAxis(32767f,-32768f,32767f));assertEquals(0f,stickAxis(128f,0f,256f));assertEquals(0f,stickAxis(Float.NaN,-1f,1f))}
 @Test fun radialIsContinuousAndPreservesDirection(){assertEquals(0f to 0f,radialDeadZone(.1f,.1f,.2f));assertTrue(radialDeadZone(.20001f,0f,.2f).first<.0001f);val p=radialDeadZone(1f,1f,.2f);assertEquals(p.first,p.second);assertEquals(1f,p.first*p.first+p.second*p.second,.0001f)}
 @Test fun triggersUseTheirOwnRange(){assertEquals(0f,triggerAxis(-1f,-1f,1f,.05f));assertEquals(1f,triggerAxis(1f,-1f,1f,.05f));assertEquals(0f,triggerAxis(2f,0f,255f,.05f))}
 @Test fun invalidMappingRejected(){try{ControllerMapping(leftDeadZone=Float.NaN).validate();fail()}catch(_:IllegalArgumentException){};try{ControllerMapping(buttons=mapOf(96 to 3)).validate();fail()}catch(_:IllegalArgumentException){}}
}
