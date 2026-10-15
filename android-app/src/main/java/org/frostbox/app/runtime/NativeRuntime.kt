package org.frostbox.app.runtime

import org.frostbox.core.*

class NativeBridge {
    external fun probe(): String
    external fun readiness(): String
    external fun startDiagnostic(mode: Int, nativeDir: String, workDir: String): Long
    external fun startWineWsiDiagnostic(nativeDir: String, root: String, prefix: String, work: String, endpoint: String, display: String, d3d11: Boolean): Long
    external fun startVulkanWineDiagnostic(nativeDir: String, root: String, prefix: String, work: String, operation: String): Long
    external fun startWineCommand(nativeDir: String, root: String, prefix: String, work: String, operation: String, exe: String, arguments: Array<String>, environmentKeys: Array<String>, environmentValues: Array<String>, displayEndpoint: String="", displayRoot: String="", wsiEndpoint: String="", dxvkDirectory: String=""): Long
    external fun pollDiagnostic(handle: Long): String
    external fun cancelDiagnostic(handle: Long)
    external fun releaseDiagnostic(handle: Long)
    companion object { init { System.loadLibrary("frostbox") } }
}
class NativeRuntime: RuntimeController {
    private val bridge = runCatching { NativeBridge() }
    fun diagnostic(): String = bridge.fold({ b -> runCatching { b.probe() }.getOrElse { "JNI probe error: $it" } }, { "Native library unavailable: $it" })
    override fun availability(): RuntimeAvailability = RuntimeAvailability(false,
        bridge.fold({ b -> runCatching { b.readiness() }.getOrElse { "JNI error: $it" } }, { "JNI load error: $it" }))
    override fun prepare(profile: GameProfile): LaunchResult { profile.validate(); return LaunchResult.Unavailable(availability().reason) }
}
