package org.frostbox.app.display

import android.content.Context
import android.view.ViewGroup
import org.frostbox.core.ScalingMode
import org.frostbox.core.ViewportTransform
import kotlin.math.roundToInt

/** Stable SurfaceView child. Scaling changes only host view bounds, never its GPU buffer size.
 * No TextureView, Surface replacement, guest resolution change or WSI reconfiguration. */
class WindowsViewport(context: Context, val display: WindowsSurface): ViewGroup(context) {
    var sourceWidth=1280; private set
    var sourceHeight=720; private set
    var mode=ScalingMode.AUTO; private set
    var transform: ViewportTransform?=null; private set
    init { clipChildren=true; clipToPadding=true;setBackgroundColor(android.graphics.Color.BLACK);addView(display) }
    fun configure(width: Int,height: Int,scaling: ScalingMode) {
        if(width==sourceWidth && height==sourceHeight && scaling==mode)return
        sourceWidth=width.coerceAtLeast(1);sourceHeight=height.coerceAtLeast(1);mode=scaling;requestLayout()
    }
    override fun onMeasure(widthSpec: Int,heightSpec: Int) {
        val w=MeasureSpec.getSize(widthSpec).coerceAtLeast(1);val h=MeasureSpec.getSize(heightSpec).coerceAtLeast(1)
        setMeasuredDimension(w,h)
        val t=ViewportTransform(sourceWidth,sourceHeight,w,h,mode);transform=t
        display.measure(MeasureSpec.makeMeasureSpec(t.width.roundToInt().coerceAtLeast(1),MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(t.height.roundToInt().coerceAtLeast(1),MeasureSpec.EXACTLY))
    }
    override fun onLayout(changed: Boolean,l: Int,t: Int,r: Int,b: Int) {
        val x=((width-display.measuredWidth)/2f).roundToInt();val y=((height-display.measuredHeight)/2f).roundToInt()
        display.layout(x,y,x+display.measuredWidth,y+display.measuredHeight)
    }
}
