package com.wmsfo.rednose.telemetry

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.wmsfo.rednose.location.FixTime
import com.wmsfo.rednose.location.GnssStats
import com.wmsfo.rednose.location.LatestFix
import com.wmsfo.rednose.log.RingLog
import com.wmsfo.rednose.transport.TransportStats
import java.util.ArrayDeque

// Builds the heartbeat body every tick (red-nose.md 8).  The typed `health` core is
// filled from the probes' batteryPercent, the current fix's age, and the socket's
// state; the six debug groups ride verbatim in `debug`.  A probe that fails leaves
// its group null.  A ring of fix times over the last 60 s feeds debug.gps.fixesLastMinute.
class TelemetryCollector(
    private val context: Context,
    private val log: RingLog,
    private val stats: TransportStats,
    private val gnss: GnssStats,
    private val counters: ProcessProbe.BootCountersReader,
    private val serviceStartedElapsedRealtime: Long,
    private val appVersion: String,
    private val powerProbe: PowerProbe = PowerProbe(context, log),
    private val radioProbe: RadioProbe = RadioProbe(context, log),
    private val permissionProbe: PermissionProbe = PermissionProbe(context),
    private val processProbe: ProcessProbe =
        ProcessProbe(context, log, serviceStartedElapsedRealtime),
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
) {

    @Volatile var latestFix: LatestFix? = null
    @Volatile var provider: String = "fused"
    @Volatile var clockSkewMs: Long? = null
    private val fixTimes = ArrayDeque<Long>()

    fun onFix(fix: LatestFix, provider: String) {
        this.latestFix = fix
        this.provider = provider
        val now = elapsedMs()
        synchronized(fixTimes) {
            fixTimes.addLast(now)
            val cutoff = now - 60_000L
            while (fixTimes.isNotEmpty() && fixTimes.first < cutoff) fixTimes.removeFirst()
        }
    }

    fun onTrim(level: Int) { processProbe.lastTrimLevel = level }

    fun build(): Heartbeat {
        val power = powerProbe.read()
        val fix = latestFix
        val fixAgeS = fix?.let { ageSeconds(it) }
        val socketState = stats.socketState
        val debug = DebugGroup(
            power = power?.group,
            radio = radioProbe.read(),
            gps = gpsGroup(),
            transport = stats.snapshot(),
            process = processProbe.read(counters),
            identity = IdentityGroup(
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidVersion = Build.VERSION.RELEASE,
                appVersion = appVersion,
                clockSkewMs = clockSkewMs,
            ),
        )
        val health = HealthGroup(
            batteryPercent = power?.batteryPercent,
            lastFixAgeS = fixAgeS,
            socketState = socketState,
        )
        return Heartbeat(
            sentAt = FixTime.rfc3339(clockMs()),
            health = health,
            debug = debug,
        )
    }

    private fun gpsGroup(): GpsGroup {
        val fix = latestFix
        val fixesLastMinute: Int
        synchronized(fixTimes) {
            val now = elapsedMs()
            val cutoff = now - 60_000L
            while (fixTimes.isNotEmpty() && fixTimes.first < cutoff) fixTimes.removeFirst()
            fixesLastMinute = fixTimes.size
        }
        return GpsGroup(
            provider = provider,
            satellitesUsed = gnss.satellitesUsed,
            satellitesInView = gnss.satellitesInView,
            lastFixAccuracyM = fix?.accuracyM,
            fixesLastMinute = fixesLastMinute,
            permission = permissionProbe.read(),
        )
    }

    private fun ageSeconds(fix: LatestFix): Int {
        val nowMs = clockMs()
        val parsed = try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            fmt.parse(fix.recordedAt)?.time ?: return 0
        } catch (_: Throwable) { return 0 }
        return ((nowMs - parsed) / 1000L).toInt().coerceAtLeast(0)
    }
}
