package org.frostbox.core
import org.junit.Test
import org.junit.Assert.*
class KeyboardControllerTest {
 @Test fun cameraSensitivityRangeAndPersistence(){
  val p=KeyboardControllerProfile(mouseSpeed=1800f);p.validate()
  val json=kotlinx.serialization.json.Json
  assertEquals(p,json.decodeFromString(KeyboardControllerProfile.serializer(),json.encodeToString(KeyboardControllerProfile.serializer(),p)))
  KeyboardControllerProfile(mouseSpeed=6000f).validate()
  assertThrows(IllegalArgumentException::class.java){KeyboardControllerProfile(mouseSpeed=6001f).validate()}
 }
 @Test fun gameKeyboardAndCustomButtons(){val p=GamepadSnapshot(0,"physical",true,buttons=4096 or 8192 or 4,leftY=-1f);assertEquals(setOf(65,87,32,17),controllerKeyboardKeys(p,KeyboardControllerProfile()));assertEquals(setOf(65,87,69,17),controllerKeyboardKeys(p,KeyboardControllerProfile(buttons=mapOf(4096 to 69,8192 to 17))))}
 @Test fun disconnectedReleasesAllKeys(){assertTrue(controllerKeyboardKeys(GamepadSnapshot(0,"physical",false,buttons=4096,leftX=1f),KeyboardControllerProfile()).isEmpty())}
 @Test fun profileRejectsInvalidValues(){assertThrows(IllegalArgumentException::class.java){KeyboardControllerProfile(mouseSpeed=Float.NaN).validate()};assertThrows(IllegalArgumentException::class.java){KeyboardControllerProfile(buttons=mapOf(123 to 999)).validate()}}
}
