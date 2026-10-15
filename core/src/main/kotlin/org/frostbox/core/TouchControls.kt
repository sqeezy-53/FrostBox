package org.frostbox.core
import kotlinx.serialization.Serializable

@Serializable data class TouchElement(val id: String,val x: Float,val y: Float,val scale: Float=1f,val visible: Boolean=true)
fun defaultTouchElements()=listOf(TouchElement("L",.06f,.58f),TouchElement("R",.55f,.58f),TouchElement("A",.9f,.8f),TouchElement("B",.97f,.55f),TouchElement("X",.8f,.55f),TouchElement("Y",.9f,.3f),TouchElement("LB",.03f,.04f),TouchElement("LT",.15f,.04f),TouchElement("RB",.97f,.04f),TouchElement("RT",.85f,.04f),TouchElement("Start",.48f,.04f))
@Serializable data class TouchProfile(val enabled: Boolean=false,val mode: String="keyboard",val opacity: Float=.55f,val elements: List<TouchElement> = defaultTouchElements()) {
    fun validate() {
        require(mode in setOf("keyboard","xinput"));require(opacity.isFinite() && opacity in .1f..1f)
        require(elements.size<=16 && elements.map {it.id}.distinct().size==elements.size)
        require(elements.all {e->e.id in defaultTouchElements().map {it.id} && e.x.isFinite() && e.y.isFinite() && e.x in 0f..1f && e.y in 0f..1f && e.scale.isFinite() && e.scale in .5f..2f})
    }
}
/** Explicit keyboard preset: left WASD, right arrows, ABXY Space/E/Q/R, shoulders Shift/Ctrl.
 * XInput mode uses the same normalized state; it is never routed into both backends.
 */
fun touchKeyboardKeys(p: GamepadSnapshot): Set<Int> = buildSet {
    if(p.leftX<-.35f)add(65);if(p.leftX>.35f)add(68);if(p.leftY<-.35f)add(87);if(p.leftY>.35f)add(83)
    if(p.rightX<-.35f)add(37);if(p.rightX>.35f)add(39);if(p.rightY<-.35f)add(38);if(p.rightY>.35f)add(40)
    for((mask,key) in listOf(4096 to 32,8192 to 69,16384 to 81,32768 to 82,256 to 16,512 to 17,16 to 27))if(p.buttons and mask!=0)add(key)
    if(p.leftTrigger>.5f)add(90);if(p.rightTrigger>.5f)add(88)
}
