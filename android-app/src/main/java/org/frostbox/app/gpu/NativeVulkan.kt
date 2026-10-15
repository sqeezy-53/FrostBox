package org.frostbox.app.gpu
import android.view.Surface
/** Independent ARM64 Android backend; no Windows/Wine API is advertised here. */
object NativeVulkan {
    init { System.loadLibrary("frostbox_vulkan") }
    external fun probe(): String
    external fun mapProbe(): String
    external fun create(): Long
    external fun surface(handle: Long,surface: Surface?,width: Int,height: Int)
    external fun start(handle: Long,running: Boolean)
    external fun foreground(handle: Long,visible: Boolean)
    external fun snapshot(handle: Long): String
    external fun release(handle: Long)
}
