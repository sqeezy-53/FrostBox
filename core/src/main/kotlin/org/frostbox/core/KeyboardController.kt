package org.frostbox.core
import kotlinx.serialization.Serializable

@Serializable data class KeyboardControllerProfile(val mouseSpeed: Float=650f,
    val buttons: Map<Int,Int> = mapOf(4096 to 32,8192 to 17,16384 to 69,32768 to 81,256 to 16,512 to 82,16 to 27,32 to 13)) {
    fun validate(){require(mouseSpeed.isFinite() && mouseSpeed in 50f..6000f);require(buttons.size<=14 && buttons.keys.all {it in PAD_BUTTONS} && buttons.values.all {it in 1..255})}
}
fun controllerKeyboardKeys(p: GamepadSnapshot,profile: KeyboardControllerProfile)=buildSet {
    if(!p.connected)return@buildSet
    if(p.leftX<-.35f || p.buttons and 4!=0)add(65);if(p.leftX>.35f || p.buttons and 8!=0)add(68)
    if(p.leftY<-.35f || p.buttons and 1!=0)add(87);if(p.leftY>.35f || p.buttons and 2!=0)add(83)
    profile.buttons.forEach {(mask,key)->if(p.buttons and mask!=0)add(key)}
}
