package org.frostbox.core
import org.junit.Test
import org.junit.Assert.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
class TouchControlsTest {
 @Test fun profileRoundTripAndBounds(){val p=TouchProfile(enabled=true);p.validate();assertEquals(p,Json.decodeFromString<TouchProfile>(Json.encodeToString(p)));assertThrows(IllegalArgumentException::class.java){p.copy(elements=listOf(TouchElement("L",Float.NaN,0f))).validate()}}
 @Test fun keyboardPresetReleasesAndDoesNotDoubleRoute(){val p=GamepadSnapshot(0,"touch",true,buttons=4096,leftY=-1f,rightX=1f,leftTrigger=1f);assertEquals(setOf(87,39,32,90),touchKeyboardKeys(p));assertTrue(touchKeyboardKeys(GamepadSnapshot(0,"touch")).isEmpty())}
}
