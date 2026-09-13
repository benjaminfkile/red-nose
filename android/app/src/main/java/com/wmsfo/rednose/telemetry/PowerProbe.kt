package com.wmsfo.rednose.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.wmsfo.rednose.log.RingLog

// Battery, charging state, temperature, and thermal status (red-nose.md 8;
// contracts 0.5 thermal names).  batteryPercent feeds the health group;
// the rest goes verbatim in debug.power.
class PowerProbe(
    private val context: Context,
    private val log: RingLog,
    private val minuteMs: Long = 60_000L,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private var lastLogAtMs: Long = 0L

    data class Reading(val batteryPercent: Int?, val group: PowerGroup)

    fun read(): Reading? = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val percent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it != Integer.MIN_VALUE }
        val charging = try { bm.isCharging } catch (_: Throwable) { null }
        val batteryStatus: Intent? =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempTenths = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val tempC = if (tempTenths != null && tempTenths != Int.MIN_VALUE) tempTenths / 10.0 else null
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            thermalName(pm.currentThermalStatus)
        } else null
        Reading(
            batteryPercent = percent,
            group = PowerGroup(
                charging = charging,
                batteryTempC = tempC,
                thermalStatus = thermal,
            ),
        )
    } catch (e: Throwable) {
        maybeLog("power probe failed: ${e.javaClass.simpleName}:${e.message}")
        null
    }

    private fun thermalName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown"
    }

    private fun maybeLog(msg: String) {
        val now = clockMs()
        if (now - lastLogAtMs >= minuteMs) {
            log.warn(msg)
            lastLogAtMs = now
        }
    }
}
