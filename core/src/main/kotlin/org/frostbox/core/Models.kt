package org.frostbox.core

import kotlinx.serialization.Serializable

@Serializable enum class ThermalMode { ECO, BALANCED, PERFORMANCE, CUSTOM }
@Serializable enum class GraphicsBackend { SYSTEM_VULKAN, DXVK, WINED3D, VKD3D }
@Serializable data class GameProfile(
    val id: String, val name: String, val executable: String = "game.exe",
    val architecture: String, val sha256: String,
    val width: Int = 1280, val height: Int = 720,
    val runtimeVariant: String = "auto", val graphicsApi: String = "auto",
    val cpuBackend: String = "box64", val graphics: GraphicsBackend = GraphicsBackend.SYSTEM_VULKAN,
    val fps: Int = 40, val thermal: ThermalMode = ThermalMode.BALANCED,
    val deadZone: Float = 0.15f, val diagnostics: Boolean = true,
    val environment: String = "", val lastLaunch: Long? = null, val addedAt: Long? = null,
    val arguments: List<String> = emptyList(), val workingDirectory: String = ".",
    val winePrefixId: String? = null, val contentGeneration: String? = null, val coverFile: String? = null, val controllerMapping: ControllerMapping? = null, val touchProfile: TouchProfile = TouchProfile(),val audioEnabled: Boolean=false,val controllerMode: String="xinput",val keyboardController: KeyboardControllerProfile=KeyboardControllerProfile()
) {
    fun validate() {
        require(java.util.UUID.fromString(id).toString()==id) { "Invalid profile ID" }
        require(architecture in setOf("x86", "x86-64")) { "Unsupported PE architecture" }
        require(runtimeVariant in setOf("auto","qualified-x64","wow64-experimental")) { "Unknown Wine runtime" }
        require(runtimeVariant=="auto" || (architecture=="x86" && runtimeVariant=="wow64-experimental") || (architecture=="x86-64" && runtimeVariant=="qualified-x64")) { "Wine runtime does not match PE architecture" }
        require(graphicsApi in setOf("auto","d3d9","d3d11")) { "Unsupported graphics API" }
        require(graphics!=GraphicsBackend.DXVK || architecture=="x86" || graphicsApi!="d3d9") { "x64 D3D9 is not bundled" }
        require(name.isNotBlank() && name.length <= 100) { "Name must contain 1–100 characters" }
        require(width in 320..7680 && height in 240..4320) { "Invalid resolution" }
        require(fps == 0 || fps in 15..240) { "FPS must be 0 (unlimited) or 15–240" }
        require(deadZone.isFinite() && deadZone in 0f..0.5f) { "Invalid dead zone" }
        require(executable=="game.exe" || executable.startsWith("content/")) { "EXE must be inside imported content" }
        validateRelativePath(executable)
        require(executable.endsWith(".exe",ignoreCase=true)) { "Select an EXE" }
        require(arguments.size<=64 && arguments.sumOf { it.length }<=8192 && arguments.none { '\u0000' in it }) { "Invalid launch arguments" }
        require(workingDirectory=="." || workingDirectory=="content" || workingDirectory.startsWith("content/")) { "Working directory must be private content" }
        if(workingDirectory!=".") validateRelativePath(workingDirectory)
        winePrefixId?.let { require(java.util.UUID.fromString(it).toString()==it) { "Invalid prefix ID" } }
        controllerMapping?.validate();touchProfile.validate();keyboardController.validate();require(controllerMode in setOf("xinput","keyboard"));require(!(touchProfile.enabled && touchProfile.mode=="xinput" && controllerMode=="keyboard")){"XInput touch requires the XInput controller mode"}
        coverFile?.let {require(it.matches(Regex("cover-[a-f0-9-]+\\.jpg"))) {"Invalid cover file"}}
        parseEnvironment(environment)
    }
}

fun validateRelativePath(path: String) {
    require(path.length in 1..2048 && path.split('/').size<=32) { "Path is too long" }
    path.split('/').forEach { name ->
        require(name.isNotEmpty() && name!="." && name!=".." && name.length<=255 &&
            name.none { it<' ' || it in "\\:*?\"<>|" } && !name.endsWith(' ') && !name.endsWith('.')) { "Unsafe relative path" }
        require(!name.substringBefore('.').uppercase(java.util.Locale.ROOT).matches(Regex("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))) { "Reserved Windows name" }
    }
}

fun parseLaunchEnvironment(text: String): Map<String,String> {
    val values=parseEnvironment(text)
    val allowed=setOf("BOX64_LOG","BOX64_NOBANNER","BOX64_DYNAREC","WINEDEBUG","WINEDLLOVERRIDES")
    require(values.keys.all { it in allowed }) { "Console launcher allows only ${allowed.joinToString()}" }
    require(values.values.sumOf { it.length }<=8192)
    return values
}

fun parseEnvironment(text: String): Map<String, String> {
    require(text.length <= 8192) { "Environment is too large" }
    return text.lineSequence().filter { it.isNotBlank() }.associate { line ->
        val pair = line.split('=', limit = 2)
        require(pair.size == 2 && pair[0].matches(Regex("[A-Z_][A-Z0-9_]*"))) { "Use KEY=value, one per line" }
        require(pair[0].startsWith("BOX64_") || pair[0].startsWith("DXVK_") || pair[0] in setOf("WINEDEBUG", "WINEDLLOVERRIDES")) { "Environment key is not allowlisted: ${pair[0]}" }
        require(!pair[1].contains('\u0000')) { "NUL is forbidden" }
        pair[0] to pair[1]
    }
}

interface RuntimeController {
    fun availability(): RuntimeAvailability
    fun prepare(profile: GameProfile): LaunchResult
}
data class RuntimeAvailability(val ready: Boolean, val reason: String)
sealed interface LaunchResult { data class Unavailable(val reason: String): LaunchResult }

interface CpuBackend { val id: String; val hostAbi: String; val guestArchitectures: Set<String> }
interface GraphicsProvider { val id: String; fun incompatibilities(capabilities: Set<String>): List<String> }
interface RuntimeInputSink { fun submit(event: ControllerEvent): Boolean }
data class ControllerEvent(val device: Int, val control: String, val value: Float, val timestampNanos: Long)
