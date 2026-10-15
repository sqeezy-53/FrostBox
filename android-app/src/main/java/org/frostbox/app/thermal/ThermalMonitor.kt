package org.frostbox.app.thermal

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow

data class ThermalSample(val seconds: Long, val batteryC: Float?, val status: Int?, val headroom: Float?, val powerSave: Boolean)
class ThermalMonitor(private val context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    val samples = flow {
        while (true) {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = battery?.takeIf { it.hasExtra(BatteryManager.EXTRA_TEMPERATURE) }
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it in -200..1000 }?.div(10f)
            val status = runCatching { power.currentThermalStatus }.getOrNull()
            val headroom = if (Build.VERSION.SDK_INT >= 30) runCatching { power.getThermalHeadroom(0) }
                .getOrNull()?.takeIf { it.isFinite() && it >= 0f } else null
            emit(ThermalSample(SystemClock.elapsedRealtime()/1000, temp, status, headroom, power.isPowerSaveMode))
            delay(10_000) // Do not poll headroom faster: unsupported/rate-limited returns NaN.
        }
    }
}
fun thermalLabel(status: Int?): String = when(status) {
    0 -> "None"; 1 -> "Light"; 2 -> "Moderate"; 3 -> "Severe"; 4 -> "Critical"; 5 -> "Emergency"; 6 -> "Shutdown"; else -> "Unavailable"
}
