package com.wmsfo.rednose.guard

import com.wmsfo.rednose.location.FixTime
import com.wmsfo.rednose.log.RingLog
import com.wmsfo.rednose.telemetry.GuardCounts
import com.wmsfo.rednose.telemetry.GuardGroup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// red-nose.md 5.5: the DeviceGuard sweep semantics, verified in the JVM with
// a fake DeviceState (mutable fields), the ring log's own lines, and virtual
// time from kotlinx-coroutines-test.  The guard only reads and reports.
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceGuardTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var log: RingLog

    @Before fun setUp() { log = RingLog(tmp.root, 64_000) }

    private class FakeDeviceState : DeviceState {
        var airplane: Boolean = false
        var location: Boolean = true
        var mobileData: Boolean = true
        var powerSave: Boolean = false
        var missing: List<String> = emptyList()
        var doze: Boolean = true

        // Every sweep reads airplane mode first; counting those reads counts
        // sweeps, and onAirplaneRead lets a test act in the middle of a sweep.
        var airplaneReads: Int = 0
        var onAirplaneRead: () -> Unit = {}
        override fun airplaneModeOn(): Boolean {
            airplaneReads++
            onAirplaneRead()
            return airplane
        }
        override fun locationEnabled(): Boolean = location
        override fun mobileDataOn(): Boolean = mobileData
        override fun powerSaveMode(): Boolean = powerSave
        override fun missingPermissions(): List<String> = missing.toList()
        override fun ignoringBatteryOptimizations(): Boolean = doze
    }

    private val items = listOf(
        "airplaneMode", "location", "mobileData", "batterySaver", "permissions", "dozeAllowlist",
    )

    private fun makeWrong(s: FakeDeviceState, item: String) {
        when (item) {
            "airplaneMode" -> { s.airplane = true }
            "location" -> { s.location = false }
            "mobileData" -> { s.mobileData = false }
            "batterySaver" -> { s.powerSave = true }
            "permissions" -> { s.missing = listOf("android.permission.ACCESS_FINE_LOCATION") }
            "dozeAllowlist" -> { s.doze = false }
            else -> error(item)
        }
    }

    private fun makeRight(s: FakeDeviceState, item: String) {
        when (item) {
            "airplaneMode" -> { s.airplane = false }
            "location" -> { s.location = true }
            "mobileData" -> { s.mobileData = true }
            "batterySaver" -> { s.powerSave = false }
            "permissions" -> { s.missing = emptyList() }
            "dozeAllowlist" -> { s.doze = true }
            else -> error(item)
        }
    }

    private fun countOf(c: GuardCounts, item: String): Int = when (item) {
        "airplaneMode" -> c.airplaneMode
        "location" -> c.location
        "mobileData" -> c.mobileData
        "batterySaver" -> c.batterySaver
        "permissions" -> c.permissions
        "dozeAllowlist" -> c.dozeAllowlist
        else -> error(item)
    }

    private fun TestScope.build(
        state: FakeDeviceState,
        onPermissionsBack: () -> Unit = { },
        sweepMs: Long = 5_000L,
    ): DeviceGuard = DeviceGuard(
        state = state,
        log = log,
        sweepMs = sweepMs,
        onPermissionsBack = onPermissionsBack,
        clockMs = { testScheduler.currentTime },
    )

    // Every guard line in the ring, oldest first, without the timestamp.
    private fun guardLines(): List<String> =
        log.recent(1_024).map { it.substringAfter(' ') }.filter { it.contains(" guard ") }

    private fun assertNoneWrong(s: GuardGroup) {
        assertEquals(emptyList<String>(), s.wrong)
        assertNull(s.wrongSince)
    }

    // ---- (a) everything right ---------------------------------------------
    @Test fun everything_right_writes_nothing() = runTest {
        val state = FakeDeviceState()
        val guard = build(state)
        guard.sweepOnce()
        guard.sweepOnce()

        assertEquals(emptyList<String>(), guardLines())
        val s = guard.stats()
        assertNoneWrong(s)
        assertNull(s.lastWrongItem)
        assertNull(s.lastWrongAt)
        assertEquals(GuardCounts(), s.seenWrong)
        for (item in items) assertEquals(0, countOf(s.seenWrong, item))
    }

    // ---- (b) and (c) each item alone, wrong then right ----------------------
    @Test fun each_item_alone_logs_wrong_once_then_ok_once() {
        for (item in items) {
            log = RingLog(tmp.newFolder(), 64_000)
            runTest {
                advanceTimeBy(1_000L)
                val state = FakeDeviceState()
                makeWrong(state, item)
                val guard = build(state)

                // (b) wrong on the first sweep after creation.
                guard.sweepOnce()
                assertEquals(item, listOf("WARN guard wrong $item"), guardLines())
                val s1 = guard.stats()
                assertEquals(item, listOf(item), s1.wrong)
                assertEquals(item, FixTime.rfc3339(1_000L), s1.wrongSince)
                assertEquals(item, item, s1.lastWrongItem)
                assertEquals(item, FixTime.rfc3339(1_000L), s1.lastWrongAt)
                assertEquals(item, 1, countOf(s1.seenWrong, item))
                for (other in items - item) assertEquals(other, 0, countOf(s1.seenWrong, other))

                // Still wrong: nothing more is written.
                advanceTimeBy(5_000L)
                guard.sweepOnce()
                assertEquals(item, 1, guardLines().size)
                assertEquals(item, s1, guard.stats())

                // (c) put right: one ok line with the elapsed virtual ms.
                advanceTimeBy(2_500L)
                makeRight(state, item)
                guard.sweepOnce()
                assertEquals(
                    item,
                    listOf("WARN guard wrong $item", "INFO guard ok $item after=7500"),
                    guardLines(),
                )
                val s2 = guard.stats()
                assertNoneWrong(s2)
                assertEquals(item, 1, countOf(s2.seenWrong, item))
                assertEquals(item, item, s2.lastWrongItem)
                assertEquals(item, FixTime.rfc3339(1_000L), s2.lastWrongAt)

                // Right again holds: nothing more.
                guard.sweepOnce()
                assertEquals(item, 2, guardLines().size)
            }
        }
    }

    @Test fun wrong_again_counts_again() = runTest {
        val state = FakeDeviceState()
        val guard = build(state)
        state.mobileData = false
        guard.sweepOnce()
        state.mobileData = true
        guard.sweepOnce()
        advanceTimeBy(3_000L)
        state.mobileData = false
        guard.sweepOnce()

        assertEquals(2, guard.stats().seenWrong.mobileData)
        assertEquals(FixTime.rfc3339(3_000L), guard.stats().wrongSince)
        assertEquals(FixTime.rfc3339(3_000L), guard.stats().lastWrongAt)
        assertEquals(2, guardLines().count { it == "WARN guard wrong mobileData" })
    }

    // ---- (d) two items wrong at once ---------------------------------------
    @Test fun two_items_wrong_list_in_sweep_order_with_the_earlier_since() = runTest {
        val state = FakeDeviceState()
        val guard = build(state)
        advanceTimeBy(1_000L)
        state.doze = false
        guard.sweepOnce()
        advanceTimeBy(4_000L)
        state.location = false
        guard.sweepOnce()

        val s = guard.stats()
        assertEquals(listOf("location", "dozeAllowlist"), s.wrong)
        assertEquals(FixTime.rfc3339(1_000L), s.wrongSince)
        assertEquals("location", s.lastWrongItem)
        assertEquals(FixTime.rfc3339(5_000L), s.lastWrongAt)

        // The older one goes right: wrongSince moves to the remaining one.
        state.doze = true
        guard.sweepOnce()
        assertEquals(listOf("location"), guard.stats().wrong)
        assertEquals(FixTime.rfc3339(5_000L), guard.stats().wrongSince)
    }

    // ---- (e) permissions back invokes the callback once ---------------------
    @Test fun permissions_back_invokes_onPermissionsBack_once() = runTest {
        val state = FakeDeviceState()
        state.missing = listOf("android.permission.ACCESS_BACKGROUND_LOCATION")
        var calls = 0
        val guard = build(state, onPermissionsBack = { calls++ })

        guard.sweepOnce()
        guard.sweepOnce()
        assertEquals(0, calls)

        state.missing = emptyList()
        guard.sweepOnce()
        assertEquals(1, calls)
        guard.sweepOnce()
        guard.sweepOnce()
        assertEquals(1, calls)
    }

    @Test fun a_throwing_onPermissionsBack_does_not_break_the_sweep() = runTest {
        val state = FakeDeviceState()
        state.missing = listOf("android.permission.READ_PHONE_STATE")
        state.doze = false
        val guard = build(state, onPermissionsBack = { throw IllegalStateException("boom") })
        guard.sweepOnce()
        state.missing = emptyList()
        state.doze = true
        guard.sweepOnce()
        assertTrue(guardLines().contains("INFO guard ok dozeAllowlist after=0"))
        assertNoneWrong(guard.stats())
    }

    // ---- (f) requests during one sweep conflate into exactly one more -------
    @Test fun requests_during_a_sweep_conflate_into_one_more() = runTest {
        val state = FakeDeviceState()
        val guard = build(state, sweepMs = 60_000L)
        var inFirstSweep = true
        state.onAirplaneRead = {
            if (inFirstSweep) {
                inFirstSweep = false
                guard.requestSweep()
                guard.requestSweep()
                guard.requestSweep()
            }
        }
        guard.startLoop(this)
        runCurrent()
        assertEquals(0, state.airplaneReads)

        guard.requestSweep()
        runCurrent()
        assertEquals(2, state.airplaneReads)

        // Nothing else until the timeout.
        advanceTimeBy(59_000L)
        runCurrent()
        assertEquals(2, state.airplaneReads)

        guard.stop()
    }

    // ---- (g) sweeps run every sweepMs without any requests ------------------
    @Test fun sweeps_run_every_sweep_interval_without_requests() = runTest {
        val state = FakeDeviceState()
        val guard = build(state, sweepMs = 5_000L)
        guard.startLoop(this)
        runCurrent()
        assertEquals(0, state.airplaneReads)

        advanceTimeBy(4_999L)
        runCurrent()
        assertEquals(0, state.airplaneReads)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, state.airplaneReads)

        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(2, state.airplaneReads)

        // A change between ticks is seen at the next tick.
        state.powerSave = true
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(3, state.airplaneReads)
        assertEquals(listOf("batterySaver"), guard.stats().wrong)
        assertEquals(FixTime.rfc3339(15_000L), guard.stats().wrongSince)

        guard.stop()
    }
}
