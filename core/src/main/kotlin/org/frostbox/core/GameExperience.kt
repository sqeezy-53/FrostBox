package org.frostbox.core

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

@Serializable enum class ScalingMode { AUTO, FIT, FILL, STRETCH }
@Serializable data class GameExperience(
    val scaling: ScalingMode = ScalingMode.AUTO,
    val panelEdge: String = "right", val handleVisible: Boolean = true,
    val hud: String = "off", val hudCorner: String = "top-left",
    val audioGain: Float = 1f, val muted: Boolean = false,
    val touchVisible: Boolean = true
) {
    fun validate() {
        require(panelEdge in setOf("left", "right"))
        require(hud in setOf("off", "minimal", "detailed"))
        require(hudCorner in setOf("top-left", "top-right", "bottom-left", "bottom-right"))
        require(audioGain.isFinite() && audioGain in 0f..1f)
    }
}

enum class SettingApplication { LIVE, NEXT_LAUNCH }
val gameSettingApplication = mapOf(
    "scaling" to SettingApplication.LIVE, "audioGain" to SettingApplication.LIVE,
    "controllerMapping" to SettingApplication.LIVE, "keyboardController" to SettingApplication.LIVE,
    "touchProfile" to SettingApplication.LIVE, "experience" to SettingApplication.LIVE,
    "resolution" to SettingApplication.NEXT_LAUNCH, "controllerMode" to SettingApplication.NEXT_LAUNCH,
    "audioEnabled" to SettingApplication.NEXT_LAUNCH
)

/** Source image -> viewport. Destination can extend outside viewport in FILL.
 * Source crop and input use the exact inverse of the same transform. */
data class ViewportTransform(val sourceWidth: Int, val sourceHeight: Int,
    val viewportWidth: Int, val viewportHeight: Int, val mode: ScalingMode) {
    init { require(minOf(sourceWidth, sourceHeight, viewportWidth, viewportHeight)>0) }
    val scaleX: Float
    val scaleY: Float
    init {
        val x=viewportWidth.toFloat()/sourceWidth; val y=viewportHeight.toFloat()/sourceHeight
        val uniform=if(mode==ScalingMode.FILL) max(x,y) else min(x,y)
        scaleX=if(mode==ScalingMode.STRETCH) x else uniform
        scaleY=if(mode==ScalingMode.STRETCH) y else uniform
    }
    val width get()=sourceWidth*scaleX
    val height get()=sourceHeight*scaleY
    val left get()=(viewportWidth-width)/2
    val top get()=(viewportHeight-height)/2
    val cropLeft get()=max(0f,-left/scaleX)
    val cropTop get()=max(0f,-top/scaleY)
    val cropRight get()=min(sourceWidth.toFloat(),(viewportWidth-left)/scaleX)
    val cropBottom get()=min(sourceHeight.toFloat(),(viewportHeight-top)/scaleY)
    fun map(x: Float,y: Float,release: Boolean=false): Pair<Int,Int>? {
        if(!x.isFinite() || !y.isFinite()) return null
        if(!release && (x<max(0f,left) || y<max(0f,top) || x>=min(viewportWidth.toFloat(),left+width) || y>=min(viewportHeight.toFloat(),top+height))) return null
        return ((x-left)/scaleX).toInt().coerceIn(0,sourceWidth-1) to ((y-top)/scaleY).toInt().coerceIn(0,sourceHeight-1)
    }
}
