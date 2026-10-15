package org.frostbox.core

/** Surface-local -> letterboxed framebuffer -> Windows screen pixels (including caption).
 * Wine performs the final screen-to-client conversion and child-control hit testing. */
data class DisplayGeometry(val frameWidth: Int,val frameHeight: Int,val surfaceWidth: Int,val surfaceHeight: Int) {
    val scale: Float get()=if(frameWidth>0 && frameHeight>0 && surfaceWidth>0 && surfaceHeight>0)
        minOf(surfaceWidth.toFloat()/frameWidth,surfaceHeight.toFloat()/frameHeight) else 0f
    val left: Float get()=(surfaceWidth-frameWidth*scale)/2
    val top: Float get()=(surfaceHeight-frameHeight*scale)/2
    fun map(x: Float,y: Float,release: Boolean=false): Pair<Int,Int>? {
        if(scale<=0 || !x.isFinite() || !y.isFinite()) return null
        val right=left+frameWidth*scale;val bottom=top+frameHeight*scale
        if(!release && (x<left || x>=right || y<top || y>=bottom)) return null
        return Pair(((x-left)/scale).toInt().coerceIn(0,frameWidth-1),((y-top)/scale).toInt().coerceIn(0,frameHeight-1))
    }
}
