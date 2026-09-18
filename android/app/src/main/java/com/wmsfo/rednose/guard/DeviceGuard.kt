package com.wmsfo.rednose.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.wmsfo.rednose.location.FixTime
import com.wmsfo.rednose.log.RingLog
import com.wmsfo.rednose.telemetry.GuardGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

// red-nose.md 5.5.  Watches for platform-side changes to the settings the
// beacon needs, and every sweepMs as a backstop for changes that announce
// nothing.  On a sweep every item is walked in order; an item in the wrong
// state gets its root commands, and is then read again.  Restore counts one;
// still wrong is a failure retried on the next sweep, forever.  Sweeps never
// overlap; a request arriving during a sweep causes exactly one more sweep
// after it.
class DeviceGuard(
    private val packageName: String,
    private val state: DeviceState,
    private val shell: RootShell,
    private val log: RingLog,
    private val sweepMs: Long,
    private val onPermissionsRestored: () -> Unit = {},
    private val clockMs: () -> Long = System::currentTimeMillis,
    // Some switches apply asynchronously (svc data enable): after the commands
    // the item is read again every settlePollMs for up to settleMs before the
    // attempt counts as a failure.
    private val settleMs: Long = 2_000L,
    private val settlePollMs: Long = 100L,
) {
    // The item names of red-nose.md 5.5, in the exact order they are walked.
    private val itemOrder: List<String> = listOf(
        ITEM_AIRPLANE, ITEM_LOCATION, ITEM_MOBILE_DATA, ITEM_BATTERY_SAVER,
        ITEM_PERMISSIONS, ITEM_DOZE_ALLOWLIST,
    )

    // Counters and last-* fields.  Updated only on the beacon coroutine that
    // runs the sweeps.
    private val counters: MutableMap<String, Int> = itemOrder.associateWith { 0 }.toMutableMap()
    @Volatile private var lastRestoredItem: String? = null
    @Volatile private var lastRestoredAt: String? = null
    @Volatile private var lastError: String? = null
    private val lastWarnAtMs: MutableMap<String, Long> = mutableMapOf()

    private val requestChannel: Channel<Unit> = Channel(capacity = Channel.CONFLATED)
    private var loopJob: Job? = null

    // Observers registered at start().
    private var airplaneObserver: ContentObserver? = null
    private var mobileDataObserver: ContentObserver? = null
    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null

    fun requestSweep() {
        requestChannel.trySend(Unit)
    }

    // Runs one sweep on the caller's coroutine.  Used by BeaconService at
    // creation and by DeviceGuardTest.
    suspend fun sweepOnce() {
        for (item in itemOrder) {
            sweepItem(item)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun startLoop(scope: CoroutineScope) {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                select<Unit> {
                    requestChannel.onReceive { }
                    onTimeout(sweepMs) { }
                }
                sweepOnce()
            }
        }
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        val cr = context.contentResolver
        val handler = Handler(Looper.getMainLooper())

        airplaneObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { requestSweep() }
        }.also {
            try {
                cr.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON),
                    false, it,
                )
            } catch (_: Throwable) {}
        }
        mobileDataObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { requestSweep() }
        }.also {
            for (uri in MobileData.observedUris()) {
                try { cr.registerContentObserver(uri, false, it) } catch (_: Throwable) {}
            }
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { requestSweep() }
        }
        val filter = IntentFilter().apply {
            addAction(LocationManager.MODE_CHANGED_ACTION)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        }
        try {
            ContextCompat.registerReceiver(
                context, receiver!!, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (_: Throwable) {}
    }

    fun stop() {
        val ctx = appContext
        val cr = ctx?.contentResolver
        try { airplaneObserver?.let { cr?.unregisterContentObserver(it) } } catch (_: Throwable) {}
        try { mobileDataObserver?.let { cr?.unregisterContentObserver(it) } } catch (_: Throwable) {}
        try { receiver?.let { ctx?.unregisterReceiver(it) } } catch (_: Throwable) {}
        airplaneObserver = null
        mobileDataObserver = null
        receiver = null
        loopJob?.cancel()
        loopJob = null
    }

    fun stats(): GuardGroup = GuardGroup(
        airplaneModeRestores = counters[ITEM_AIRPLANE] ?: 0,
        locationRestores = counters[ITEM_LOCATION] ?: 0,
        mobileDataRestores = counters[ITEM_MOBILE_DATA] ?: 0,
        batterySaverRestores = counters[ITEM_BATTERY_SAVER] ?: 0,
        permissionRestores = counters[ITEM_PERMISSIONS] ?: 0,
        dozeAllowlistRestores = counters[ITEM_DOZE_ALLOWLIST] ?: 0,
        lastRestoredItem = lastRestoredItem,
        lastRestoredAt = lastRestoredAt,
        lastError = lastError,
    )

    private suspend fun sweepItem(item: String) {
        if (inNeededState(item)) return
        val startedAtMs = clockMs()
        val results = runCommandsFor(item)
        var waitedMs = 0L
        while (!inNeededState(item) && waitedMs < settleMs) {
            delay(settlePollMs)
            waitedMs += settlePollMs
        }
        if (inNeededState(item)) {
            counters[item] = (counters[item] ?: 0) + 1
            val took = clockMs() - startedAtMs
            lastRestoredItem = item
            lastRestoredAt = FixTime.rfc3339(clockMs())
            log.info("guard restored $item took=$took")
            if (item == ITEM_PERMISSIONS) {
                try { onPermissionsRestored() } catch (_: Throwable) {}
            }
        } else {
            val text = failureText(results)
            lastError = "$item: $text"
            val now = clockMs()
            val last = lastWarnAtMs[item]
            if (last == null || now - last >= 60_000L) {
                log.warn("guard restore failed $item err=$lastError")
                lastWarnAtMs[item] = now
            }
        }
    }

    private fun failureText(results: List<RootResult>): String {
        if (results.isEmpty()) return "still wrong"
        val last = results.last()
        if (last.exitCode == null) return "timeout"
        val out = last.output
        if (out.isEmpty()) return "still wrong"
        return if (out.length > 200) out.substring(0, 200) else out
    }

    private fun inNeededState(item: String): Boolean = when (item) {
        ITEM_AIRPLANE -> !state.airplaneModeOn()
        ITEM_LOCATION -> state.locationEnabled()
        ITEM_MOBILE_DATA -> state.mobileDataOn()
        ITEM_BATTERY_SAVER -> !state.powerSaveMode()
        ITEM_PERMISSIONS -> state.missingPermissions().isEmpty()
        ITEM_DOZE_ALLOWLIST -> state.ignoringBatteryOptimizations()
        else -> true
    }

    private suspend fun runCommandsFor(item: String): List<RootResult> = when (item) {
        ITEM_AIRPLANE -> listOf(shell.run("cmd connectivity airplane-mode disable"))
        ITEM_LOCATION -> listOf(shell.run("cmd location set-location-enabled true"))
        ITEM_MOBILE_DATA -> listOf(shell.run("svc data enable"))
        ITEM_BATTERY_SAVER -> listOf(shell.run("cmd power set-mode 0"))
        ITEM_PERMISSIONS -> buildList {
            for (perm in state.missingPermissions()) {
                add(shell.run("pm grant $packageName $perm"))
            }
            add(shell.run("appops set $packageName FINE_LOCATION allow"))
            add(shell.run("appops set $packageName COARSE_LOCATION allow"))
        }
        ITEM_DOZE_ALLOWLIST -> listOf(shell.run("dumpsys deviceidle whitelist +$packageName"))
        else -> emptyList()
    }

    companion object {
        const val ITEM_AIRPLANE = "airplaneMode"
        const val ITEM_LOCATION = "location"
        const val ITEM_MOBILE_DATA = "mobileData"
        const val ITEM_BATTERY_SAVER = "batterySaver"
        const val ITEM_PERMISSIONS = "permissions"
        const val ITEM_DOZE_ALLOWLIST = "dozeAllowlist"
    }
}
