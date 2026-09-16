package com.wmsfo.rednose.guard

import com.wmsfo.rednose.log.RingLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// red-nose.md 5.5: the DeviceGuard sweep semantics, verified in the JVM with
// a fake DeviceState (mutable fields), a fake RootShell (records every command
// with a per-command effect that either fixes state or not), and virtual time
// from kotlinx-coroutines-test.
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceGuardTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var log: RingLog

    @Before fun setUp() { log = RingLog(tmp.root, 8_192) }

    private class FakeDeviceState : DeviceState {
        var airplane: Boolean = false
        var location: Boolean = true
        var mobileData: Boolean = true
        var powerSave: Boolean = false
        var missing: List<String> = emptyList()
        var doze: Boolean = true

        // How many times each read method has been invoked; the sweep reads
        // once at the start of each item, and again after any commands.
        var airplaneReads: Int = 0
        override fun airplaneModeOn(): Boolean { airplaneReads++; return airplane }
        override fun locationEnabled(): Boolean = location
        override fun mobileDataOn(): Boolean = mobileData
        override fun powerSaveMode(): Boolean = powerSave
        override fun missingPermissions(): List<String> = missing.toList()
        override fun ignoringBatteryOptimizations(): Boolean = doze
    }

    private class FakeShell(
        private val effect: (String, FakeDeviceState) -> RootResult,
        private val state: FakeDeviceState,
    ) : RootShell {
        val commands: MutableList<String> = mutableListOf()
        override suspend fun run(command: String): RootResult {
            commands.add(command)
            return effect(command, state)
        }
    }

    private fun noopEffect(): (String, FakeDeviceState) -> RootResult = { _, _ ->
        RootResult(exitCode = 0, output = "")
    }

    private fun fixingEffect(): (String, FakeDeviceState) -> RootResult = { cmd, s ->
        when {
            cmd == "cmd connectivity airplane-mode disable" -> { s.airplane = false }
            cmd == "cmd location set-location-enabled true" -> { s.location = true }
            cmd == "svc data enable" -> { s.mobileData = true }
            cmd == "cmd power set-mode 0" -> { s.powerSave = false }
            cmd.startsWith("pm grant") -> {
                val perm = cmd.substringAfterLast(' ')
                s.missing = s.missing - perm
            }
            cmd.startsWith("appops set") -> { /* no-op */ }
            cmd.startsWith("dumpsys deviceidle whitelist +") -> { s.doze = true }
        }
        RootResult(exitCode = 0, output = "")
    }

    private fun build(
        state: FakeDeviceState,
        shell: RootShell,
        onPermissionsRestored: () -> Unit = { },
        sweepMs: Long = 5_000L,
        clock: () -> Long = { 0L },
    ): DeviceGuard = DeviceGuard(
        packageName = "com.wmsfo.rednose",
        state = state,
        shell = shell,
        log = log,
        sweepMs = sweepMs,
        onPermissionsRestored = onPermissionsRestored,
        clockMs = clock,
    )

    // Count how many times a substring appears in the log's tail.
    private fun warnLineCount(needle: String): Int {
        val tail = log.recent(1_024)
        return tail.count { it.contains(needle) && it.contains("WARN") }
    }

    // ---- (a) --------------------------------------------------------------
    @Test fun everything_correct_no_commands_run() = runTest {
        val state = FakeDeviceState()
        val shell = FakeShell(fixingEffect(), state)
        val guard = build(state, shell)
        guard.sweepOnce()
        assertEquals(emptyList<String>(), shell.commands)
        val s = guard.stats()
        assertEquals(0, s.airplaneModeRestores)
        assertEquals(0, s.locationRestores)
        assertEquals(0, s.mobileDataRestores)
        assertEquals(0, s.batterySaverRestores)
        assertEquals(0, s.permissionRestores)
        assertEquals(0, s.dozeAllowlistRestores)
    }

    // ---- (b) each item alone -------------------------------------------------
    @Test fun airplane_alone_runs_exactly_its_command() = runTest {
        val state = FakeDeviceState().apply { airplane = true }
        val shell = FakeShell(fixingEffect(), state)
        val guard = build(state, shell, clock = { 1_000L })
        guard.sweepOnce()
        assertEquals(listOf("cmd connectivity airplane-mode disable"), shell.commands)
        assertEquals(1, guard.stats().airplaneModeRestores)
        assertEquals("airplaneMode", guard.stats().lastRestoredItem)
        assertNotNull(guard.stats().lastRestoredAt)
        // INFO line was written.
        val tail = log.recent(64)
        assertTrue(tail.any { it.contains("guard restored airplaneMode") && it.contains("INFO") })
    }

    @Test fun location_alone_runs_exactly_its_command() = runTest {
        val state = FakeDeviceState().apply { location = false }
        val shell = FakeShell(fixingEffect(), state)
        val guard = build(state, shell)
        guard.sweepOnce()
        assertEquals(listOf("cmd location set-location-enabled true"), shell.commands)
        assertEquals(1, guard.stats().locationRestores)
    }

    @Test fun mobile_data_alone_runs_exactly_its_command() = runTest {
        val state = FakeDeviceState().apply { mobileData = false }
        val shell = FakeShell(fixingEffect(), state)
        val guard = build(state, shell)
        guard.sweepOnce()
        assertEquals(listOf("svc data enable"), shell.commands)
        assertEquals(1, guard.stats().mobileDataRestores)
    }

    @Test fun battery_saver_alone_runs_exactly_its_command() = runTest {
        val state = FakeDeviceState().apply { powerSave = true }
        val shell = FakeShell(fixingEffect(), state)
        val guard = build(state, shell)
        guard.sweepOnce()
        assertEquals(listOf("cmd power set-mode 0"), shell.commands)
        assertEquals(1, guard.stats().batterySaverRestores)
    }

    @Test fun doze_allowlist_alone_runs_exactly_its_command() = runTest {
        val state = FakeDeviceState().apply { doze = false }
        val shell = FakeShell(fixingEffect(), state)
        val guard = build(state, shell)
        guard.sweepOnce()
        assertEquals(
            listOf("dumpsys deviceidle whitelist +com.wmsfo.rednose"),
            shell.commands,
        )
        assertEquals(1, guard.stats().dozeAllowlistRestores)
    }

    // ---- (c) two missing permissions -----------------------------------------
    @Test fun two_missing_permissions_grant_them_then_appops_once() = runTest {
        val state = FakeDeviceState().apply {
            missing = listOf(
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_BACKGROUND_LOCATION",
            )
        }
        val shell = FakeShell(fixingEffect(), state)
        var callbackCount = 0
        val guard = build(state, shell, onPermissionsRestored = { callbackCount++ })
        guard.sweepOnce()
        assertEquals(
            listOf(
                "pm grant com.wmsfo.rednose android.permission.ACCESS_FINE_LOCATION",
                "pm grant com.wmsfo.rednose android.permission.ACCESS_BACKGROUND_LOCATION",
                "appops set com.wmsfo.rednose FINE_LOCATION allow",
                "appops set com.wmsfo.rednose COARSE_LOCATION allow",
            ),
            shell.commands,
        )
        assertEquals(1, callbackCount)
        assertEquals(1, guard.stats().permissionRestores)
    }

    // ---- (d) a command that does not fix the state --------------------------
    @Test fun failure_retried_every_sweep_warn_rate_limited_per_item() = runTest {
        val state = FakeDeviceState().apply { airplane = true }
        val shell = FakeShell(noopEffect(), state)
        var t = 0L
        val guard = build(state, shell, sweepMs = 5_000L, clock = { t })

        // First-minute sweeps at 10 s apart, six of them.
        for (i in 0 until 6) {
            t = i * 10_000L
            guard.sweepOnce()
        }
        assertEquals(6, shell.commands.size)
        assertEquals(0, guard.stats().airplaneModeRestores)
        val err0 = guard.stats().lastError
        assertNotNull(err0)
        assertTrue(err0!!.startsWith("airplaneMode: "))
        assertEquals(1, warnLineCount("guard restore failed airplaneMode"))

        // Another sweep after 60 s: a second WARN emits.
        t = 70_000L
        guard.sweepOnce()
        assertEquals(2, warnLineCount("guard restore failed airplaneMode"))
    }

    // ---- a switch that applies asynchronously still counts as a restore -------
    @Test fun asynchronous_switch_counts_as_restored_within_the_settle_window() = runTest {
        val state = FakeDeviceState().apply { mobileData = false }
        var readsAfterCommand = -1
        val slowState = object : DeviceState by state {
            override fun mobileDataOn(): Boolean {
                if (readsAfterCommand >= 0) {
                    readsAfterCommand++
                    if (readsAfterCommand >= 5) state.mobileData = true
                }
                return state.mobileData
            }
        }
        val shell = object : RootShell {
            override suspend fun run(command: String): RootResult {
                if (command == "svc data enable") readsAfterCommand = 0
                return RootResult(exitCode = 0, output = "")
            }
        }
        val guard = DeviceGuard(
            packageName = "com.wmsfo.rednose", state = slowState, shell = shell,
            log = log, sweepMs = 5_000L,
        )
        guard.sweepOnce()
        assertEquals(1, guard.stats().mobileDataRestores)
        assertEquals(null, guard.stats().lastError)
        assertEquals(0, warnLineCount("guard restore failed mobileData"))
    }

    // ---- (e) timeout gives "timeout" ----------------------------------------
    @Test fun timeout_is_a_failure_with_timeout_text() = runTest {
        val state = FakeDeviceState().apply { location = false }
        val timeoutShell = object : RootShell {
            override suspend fun run(command: String): RootResult =
                RootResult(exitCode = null, output = "")
        }
        val guard = build(state, timeoutShell)
        guard.sweepOnce()
        assertEquals(0, guard.stats().locationRestores)
        assertEquals("location: timeout", guard.stats().lastError)
    }

    // ---- (f) three requests during one sweep conflate into exactly one -----
    @Test fun three_requests_during_one_sweep_yield_exactly_one_more() = runTest {
        // A shell that yields once inside its command; call requestSweep three
        // times while the sweep is in flight.
        val state = FakeDeviceState().apply { airplane = true }
        val calls = intArrayOf(0)
        val yieldingShell = object : RootShell {
            override suspend fun run(command: String): RootResult {
                calls[0]++
                // Fix airplane so the next sweep sees it right.
                state.airplane = false
                delay(1_000L)
                return RootResult(exitCode = 0, output = "")
            }
        }
        val guard = build(state, yieldingShell, sweepMs = 60_000L)
        guard.startLoop(this)
        runCurrent()   // loop reaches the select and suspends

        // Kick off the first sweep; the shell runs, sets airplane=false, then
        // parks in its delay(1000).  runCurrent stops there.
        guard.requestSweep()
        runCurrent()

        // Three more requests conflate to exactly one pending item on the
        // channel; the running sweep is still parked in its delay.
        guard.requestSweep()
        guard.requestSweep()
        guard.requestSweep()
        runCurrent()

        // Fire the delay: the first sweep completes and the loop re-enters
        // select, consumes the one conflated request, and runs one more sweep.
        // That second sweep reads the state as correct and runs no commands,
        // so calls[0] stays at 1.
        advanceTimeBy(1_100L)
        runCurrent()

        assertEquals(1, calls[0])
        assertEquals(1, guard.stats().airplaneModeRestores)

        guard.stop()
    }

    // ---- (g) sweeps run every sweepMs without any requests ------------------
    @Test fun sweeps_run_every_sweep_interval_without_requests() = runTest {
        val state = FakeDeviceState()
        val shell = FakeShell(fixingEffect(), state)
        val guard = build(state, shell, sweepMs = 5_000L)
        guard.startLoop(this)
        val before = state.airplaneReads
        advanceTimeBy(15_500L)
        // At t=5000, 10000, 15000 the loop woke and ran a sweep; each sweep
        // reads airplaneModeOn once for that item.
        assertTrue(
            "expected at least 3 sweeps' reads, got ${state.airplaneReads - before}",
            state.airplaneReads - before >= 3,
        )
        guard.stop()
    }
}
