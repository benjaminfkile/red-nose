package com.wmsfo.rednose.checklist

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.PowerManager
import android.provider.Settings
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.wmsfo.rednose.service.BeaconService
import com.wmsfo.rednose.service.Checklist
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// Evaluates every row of red-nose.md section 13 from system APIs (contract:
// "the checklist reads every row from a system API, nothing inferred").  A
// green row means the API said so.  The su -c id probe runs on a background
// thread so the beacon dispatcher never blocks (red-nose.md 7.1) and read()
// returns the cached answer.
class ChecklistProbe(
    private val context: Context,
    private val rootProbeTimeoutMs: Long = 2000L,
) {
    private val rootCache: AtomicReference<Boolean?> = AtomicReference(null)
    private val rootProbeInFlight = AtomicBoolean(false)
    private val rootExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rednose-checklist-root").apply { isDaemon = true }
    }

    fun read(): Checklist {
        kickRootProbe()
        return Checklist(
            fineLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            backgroundLocation = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            preciseLocation = preciseLocation(),
            notifications = notifications(),
            batteryOptimizationExempt = batteryOptimizationExempt(),
            locationServicesOn = locationServicesOn(),
            playServices = playServicesAvailable(),
            phoneState = granted(Manifest.permission.READ_PHONE_STATE),
            systemApp = systemApp(),
            rootAvailable = rootCache.get() == true,
            airplaneModeOff = airplaneModeOff(),
            mobileDataOn = mobileDataOn(),
            batterySaverOff = batterySaverOff(),
            serviceRunning = BeaconService.isRunning,
        )
    }

    private fun granted(name: String): Boolean =
        context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED

    private fun preciseLocation(): Boolean {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        // "fine, not coarse only" (red-nose.md 13): fine granted, not merely coarse.
        return fine && !(coarse && !fine)
    }

    private fun notifications(): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.areNotificationsEnabled()) return false
        // POST_NOTIFICATIONS is a runtime permission on 33+ (red-nose.md 13).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (!granted("android.permission.POST_NOTIFICATIONS")) return false
        }
        // The channel must exist and not be blocked.  The service ensures the
        // channel; a beacon-suppressed channel is still visible to the app.
        val ch = nm.getNotificationChannel("beacon")
        return ch == null || ch.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun batteryOptimizationExempt(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun locationServicesOn(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isLocationEnabled
    }

    private fun playServicesAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    private fun systemApp(): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

    private fun airplaneModeOff(): Boolean = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 0
    } catch (_: Throwable) { false }

    private fun mobileDataOn(): Boolean = try {
        Settings.Global.getInt(context.contentResolver, "mobile_data", 1) == 1
    } catch (_: Throwable) { false }

    private fun batterySaverOff(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isPowerSaveMode
    }

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
}
