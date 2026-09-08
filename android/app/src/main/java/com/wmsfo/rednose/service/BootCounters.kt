package com.wmsfo.rednose.service

import android.content.Context
import android.provider.Settings
import com.wmsfo.rednose.telemetry.ProcessProbe

// Two counters keyed by the system BOOT_COUNT setting (red-nose.md 5.4).
// A new boot value resets both.  Stored in plain SharedPreferences in :beacon.
class BootCounters(private val context: Context) : ProcessProbe.BootCountersReader {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Snapshot the current boot key and reset counters when it changed. */
    fun rollOverIfNeeded() {
        val current = currentBootKey()
        val stored = prefs.getInt(K_BOOT_KEY, -1)
        if (current != stored) {
            prefs.edit()
                .putInt(K_BOOT_KEY, current)
                .putInt(K_SERVICE_RESTART_COUNT, 0)
                .putInt(K_SENDS_FAILED_SINCE_BOOT, 0)
                .apply()
        }
    }

    /** Increments serviceRestartCount on the second and later starts since boot. */
    fun onServiceCreated() {
        rollOverIfNeeded()
        val started = prefs.getBoolean(K_FIRST_START_DONE, false)
        if (started) {
            prefs.edit()
                .putInt(K_SERVICE_RESTART_COUNT, prefs.getInt(K_SERVICE_RESTART_COUNT, 0) + 1)
                .apply()
        } else {
            prefs.edit().putBoolean(K_FIRST_START_DONE, true).apply()
        }
    }

    override val serviceRestartCount: Int
        get() = prefs.getInt(K_SERVICE_RESTART_COUNT, 0)

    var sendsFailedSinceBoot: Int
        get() = prefs.getInt(K_SENDS_FAILED_SINCE_BOOT, 0)
        set(v) { prefs.edit().putInt(K_SENDS_FAILED_SINCE_BOOT, v).apply() }

    private fun currentBootKey(): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
    } catch (_: Throwable) { 0 }

    companion object {
        private const val FILE_NAME = "rednose_boot"
        private const val K_BOOT_KEY = "bootKey"
        private const val K_SERVICE_RESTART_COUNT = "serviceRestartCount"
        private const val K_SENDS_FAILED_SINCE_BOOT = "sendsFailedSinceBoot"
        private const val K_FIRST_START_DONE = "firstStartDone"
    }
}
