package com.wmsfo.rednose.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.wmsfo.rednose.store.SecureStore

// BOOT_COMPLETED runs in :beacon (red-nose.md 5.1).  Reads the store; when an
// enrollment exists, starts the foreground service.  Background location is granted
// at provisioning, so the location foreground service is allowed to start from boot.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        val enrollment = try { SecureStore(context).load() } catch (_: Throwable) { null } ?: return
        val start = Intent(context, BeaconService::class.java).also {
            it.action = BeaconService.ACTION_BOOT_START
        }
        ContextCompat.startForegroundService(context, start)
    }
}
