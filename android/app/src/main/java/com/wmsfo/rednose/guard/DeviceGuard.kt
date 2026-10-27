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
import com.wmsfo.rednose.telemetry.GuardCounts
import com.wmsfo.rednose.telemetry.GuardGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

// red-nose.md 5.5.  Watches the settings the beacon needs for platform-side
// changes, and every sweepMs as a backstop for changes that announce nothing.
// It reports and never puts anything back: a sweep reads every item in order,
// and an item that changes between right and wrong writes one ring-log line
// and updates the heartbeat's guard group.  Nothing is written while a state
// holds.  Sweeps never overlap; a request arriving during a sweep causes
// exactly one more sweep after it.
class DeviceGuard(
    private val state: DeviceState,
    private val log: RingLog,
    private val sweepMs: Long,
    private val onPermissionsBack: () -> Unit = {},
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    // The item names of red-nose.md 5.5, in the exact order they are walked.
    private val itemOrder: List<String> = listOf(
        ITEM_AIRPLANE, ITEM_LOCATION, ITEM_MOBILE_DATA, ITEM_BATTERY_SAVER,
        ITEM_PERMISSIONS, ITEM_DOZE_ALLOWLIST,
    )

    // Per-item state, counters, and last-* fields.  Written only on the beacon
    // coroutine that runs the sweeps; stats() reads them from other threads.
    // Every item starts as right, so an item wrong at creation logs once.
    @Volatile private var wrongNow: Map<String, Boolean> = itemOrder.associateWith { false }
    @Volatile private var wrongSinceMs: Map<String, Long> = emptyMap()
    @Volatile private var seenWrong: Map<String, Int> = itemOrder.associateWith { 0 }
    @Volatile private var lastWrongItem: String? = null
    @Volatile private var lastWrongAt: String? = null

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
        // Everything registers on the application context, the one stop()
        // unregisters on; a service context would leak the receiver at destroy.
        val app = context.applicationContext
        appContext = app
        val cr = app.contentResolver
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
                app, receiver!!, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
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

    fun stats(): GuardGroup {
        val wrong = wrongNow
        val since = wrongSinceMs
        val counts = seenWrong
        val wrongItems = itemOrder.filter { wrong[it] == true }
        val oldest = wrongItems.mapNotNull { since[it] }.minOrNull()
        return GuardGroup(
            wrong = wrongItems,
            wrongSince = oldest?.let { FixTime.rfc3339(it) },
            lastWrongItem = lastWrongItem,
            lastWrongAt = lastWrongAt,
            seenWrong = GuardCounts(
                airplaneMode = counts[ITEM_AIRPLANE] ?: 0,
                location = counts[ITEM_LOCATION] ?: 0,
                mobileData = counts[ITEM_MOBILE_DATA] ?: 0,
                batterySaver = counts[ITEM_BATTERY_SAVER] ?: 0,
                permissions = counts[ITEM_PERMISSIONS] ?: 0,
                dozeAllowlist = counts[ITEM_DOZE_ALLOWLIST] ?: 0,
            ),
        )
    }

    private fun sweepItem(item: String) {
        val isWrong = !inNeededState(item)
        val wasWrong = wrongNow[item] == true
        if (isWrong == wasWrong) return
        val now = clockMs()
        if (isWrong) {
            seenWrong = seenWrong + (item to (seenWrong[item] ?: 0) + 1)
            wrongSinceMs = wrongSinceMs + (item to now)
            lastWrongItem = item
            lastWrongAt = FixTime.rfc3339(now)
            wrongNow = wrongNow + (item to true)
            log.warn("guard wrong $item")
        } else {
            val after = now - (wrongSinceMs[item] ?: now)
            wrongSinceMs = wrongSinceMs - item
            wrongNow = wrongNow + (item to false)
            log.info("guard ok $item after=$after")
            if (item == ITEM_PERMISSIONS) {
                try { onPermissionsBack() } catch (_: Throwable) {}
            }
        }
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

    companion object {
        const val ITEM_AIRPLANE = "airplaneMode"
        const val ITEM_LOCATION = "location"
        const val ITEM_MOBILE_DATA = "mobileData"
        const val ITEM_BATTERY_SAVER = "batterySaver"
        const val ITEM_PERMISSIONS = "permissions"
        const val ITEM_DOZE_ALLOWLIST = "dozeAllowlist"
    }
}
