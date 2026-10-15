package org.frostbox.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sign

fun normalizedAxis(value: Float, deadZone: Float): Float {
    require(deadZone in 0f..0.5f)
    if (!value.isFinite() || abs(value) <= deadZone) return 0f
    return sign(value) * ((abs(value).coerceAtMost(1f) - deadZone) / (1f - deadZone))
}
fun targetFrameNanos(fps: Int): Long? {
    require(fps == 0 || fps in 15..240)
    return if (fps == 0) null else 1_000_000_000L / fps
}
data class FrameSummary(val averageFps: Double, val meanMs: Double, val onePercentLow: Double, val longFrames: Int)
fun summarizeFrames(milliseconds: List<Double>, targetMs: Double): FrameSummary? {
    require(targetMs.isFinite() && targetMs > 0)
    if (milliseconds.isEmpty()) return null
    require(milliseconds.all { it.isFinite() && it > 0 })
    val slow = milliseconds.sortedDescending().take(ceil(milliseconds.size * 0.01).toInt().coerceAtLeast(1))
    return FrameSummary(1000 / milliseconds.average(), milliseconds.average(), 1000 / slow.average(), milliseconds.count { it > targetMs * 1.5 })
}

/** Advisory only until a verified runtime limiter exists. One instance per session. */
class ThermalPolicy {
    private var smoothed: Double? = null
    private var constrained = false
    private var coolSince: Long? = null
    fun recommend(baseFps: Int, status: Int?, headroom: Double?, seconds: Long): Int {
        require(baseFps == 0 || baseFps in 15..240)
        if (headroom != null && headroom.isFinite() && headroom >= 0) smoothed = smoothed?.let { it * 0.75 + headroom * 0.25 } ?: headroom
        // Critical Android status is never hidden by smoothing. Missing data cannot mean cool.
        val hot = (status ?: -1) >= 3 || (headroom != null && (smoothed ?: 0.0) >= 0.9)
        val cool = status != null && status <= 1 && (headroom == null || ((smoothed ?: 1.0) < 0.65))
        if (hot) { constrained = true; coolSince = null }
        else if (constrained && cool) {
            if (coolSince == null) coolSince = seconds
            if (seconds - coolSince!! >= 30) { constrained = false; coolSince = null }
        } else coolSince = null
        return if (constrained) { if (baseFps == 0) 30 else minOf(baseFps, 30) } else baseFps
    }
}
