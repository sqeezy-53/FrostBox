package org.frostbox.core

import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable data class ControllerMapping(
    val leftDeadZone: Float=.15f,val rightDeadZone: Float=.15f,val triggerDeadZone: Float=.05f,
    val invertLeftY: Boolean=false,val invertRightY: Boolean=false,val rightAxes: String="auto",val buttons: Map<Int,Int> = emptyMap()
) {
    fun validate() {
        require(listOf(leftDeadZone,rightDeadZone,triggerDeadZone).all {it.isFinite() && it in 0f..0.8f})
        require(rightAxes in setOf("auto","rx-ry","z-rz"))
        require(buttons.size<=32 && buttons.keys.all {it in 0..400} && buttons.values.all {it in PAD_BUTTONS})
    }
}
val PAD_BUTTONS=setOf(1,2,4,8,16,32,64,128,256,512,4096,8192,16384,32768)
data class GamepadSnapshot(val slot: Int,val descriptor: String,val connected: Boolean=false,val packet: Int=0,
    val buttons: Int=0,val leftX: Float=0f,val leftY: Float=0f,val rightX: Float=0f,val rightY: Float=0f,
    val leftTrigger: Float=0f,val rightTrigger: Float=0f)
fun stickAxis(raw: Float,min: Float,max: Float): Float {
    if(!raw.isFinite() || !min.isFinite() || !max.isFinite() || max<=min)return 0f
    val center=if(min<0 && max>0)0f else (min+max)/2
    return ((raw-center)/(if(raw<center)center-min else max-center)).coerceIn(-1f,1f)
}
fun triggerAxis(raw: Float,min: Float,max: Float,dead: Float): Float {
    if(!raw.isFinite() || max<=min)return 0f
    val x=((raw-min)/(max-min)).coerceIn(0f,1f)
    return ((x-dead)/(1-dead)).coerceIn(0f,1f)
}
fun radialDeadZone(x: Float,y: Float,dead: Float): Pair<Float,Float> {
    require(dead in 0f..0.8f)
    val length=sqrt(x*x+y*y)
    if(!length.isFinite() || length<=dead || length==0f)return 0f to 0f
    val scale=((min(length,1f)-dead)/(1-dead))/length
    return x*scale to y*scale
}
