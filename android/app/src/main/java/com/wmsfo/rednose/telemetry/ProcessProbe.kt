package com.wmsfo.rednose.telemetry

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.wmsfo.rednose.log.RingLog
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// Process/system probes: uptimes, memory pressure, battery-optimization exemption,
// notification permission, systemApp and rootAvailable
// (red-nose.md 8 and 18; contracts 4.2 process group).
// The root probe (`su -c id`) runs on a background thread so the beacon
// dispatcher never blocks (red-nose.md 7.1); read() returns the cached answer.
class ProcessProbe(
    private val context: Context,
    private val log: RingLog,
    private val serviceStartedElapsedRealtime: Long,
    private val rootProbeTimeoutMs: Long = 2000L,
    private val minuteMs: Long = 60_000L,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    @Volatile var lastTrimLevel: Int = -1
    private var lastLogAtMs: Long = 0L
    private val rootCache: AtomicReference<Boolean?> = AtomicReference(null)
    private val rootProbeInFlight = AtomicBoolean(false)
    private val rootExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rednose-root-probe").apply { isDaemon = true }
    }

    fun read(counters: BootCountersReader): ProcessGroup? = try {
        val deviceUptimeS = SystemClock.elapsedRealtime() / 1000L
        val serviceUptimeS = ((SystemClock.elapsedRealtime() - serviceStartedElapsedRealtime) / 1000L)
            .coerceAtLeast(0L)
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val exempt = pm.isIgnoringBatteryOptimizations(context.packageName)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = nm.areNotificationsEnabled()
        val ai: ApplicationInfo = context.applicationInfo
        val systemApp = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val memoryPressure = memoryPressureName(lastTrimLevel)
        kickRootProbe()
        ProcessGroup(
            deviceUptimeS = deviceUptimeS,
            serviceUptimeS = serviceUptimeS,
            serviceRestartCount = counters.serviceRestartCount,
            memoryPressure = memoryPressure,
            batteryOptimizationExempt = exempt,
            notificationPermission = notif,
            systemApp = systemApp,
            rootAvailable = rootCache.get(),
        )
    } catch (e: Throwable) {
        maybeLog("process probe failed: ${e.javaClass.simpleName}:${e.message}")
        null
    }

    private fun memoryPressureName(level: Int): String {
        if (level < 0) return "normal"
        return when {
            level >= 80 -> "critical"
            level >= 60 -> "low"
            level >= 40 -> "moderate"
            level >= 5 -> "normal"
            else -> "unknown"
        }
    }

    /** Kick off `su -c id` on the probe thread; the beacon dispatcher never blocks. */
    private fun kickRootProbe() {
        if (!rootProbeInFlight.compareAndSet(false, true)) return
        rootExecutor.submit {
            try {
                val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                val done = process.waitFor(rootProbeTimeoutMs, TimeUnit.MILLISECONDS)
                val ok = if (!done) { process.destroy(); false }
                else process.inputStream.bufferedReader().readText().contains("uid=0")
                rootCache.set(ok)
            } catch (_: Throwable) {
                rootCache.set(false)
            } finally {
                rootProbeInFlight.set(false)
            }
        }
    }

    private fun maybeLog(msg: String) {
        val now = clockMs()
        if (now - lastLogAtMs >= minuteMs) {
            log.warn(msg)
            lastLogAtMs = now
        }
    }

    interface BootCountersReader {
        val serviceRestartCount: Int
    }
}
