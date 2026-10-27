package com.wmsfo.rednose.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.wmsfo.rednose.store.SecureStore

// Runs in :beacon (red-nose.md 5.1), at boot and after a package update.  Reads
// the store; when an enrollment exists, starts the foreground service.  The
// broadcasts are exempt from the background foreground-service start
// restrictions, and the app is on the battery-optimization allowlist besides.
// Background location is granted at provisioning, so the location foreground
// service is allowed to start from here.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in START_ACTIONS) return
        val enrollment = try { SecureStore(context).load() } catch (_: Throwable) { null }
        if (!shouldStart(intent.action, enrollment != null)) return
        val start = Intent(context, BeaconService::class.java).also {
            it.action = BeaconService.ACTION_BOOT_START
        }
        ContextCompat.startForegroundService(context, start)
    }

    companion object {
        // Boot, direct-boot boot, and a package update (adb install -r, 14.3).
        internal val START_ACTIONS: Set<String> = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )

        internal fun shouldStart(action: String?, hasEnrollment: Boolean): Boolean =
            hasEnrollment && action in START_ACTIONS
    }
}
