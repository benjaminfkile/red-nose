package com.wmsfo.rednose.transport

import com.wmsfo.rednose.location.FixTime
import com.wmsfo.rednose.location.LatestFix
import com.wmsfo.rednose.log.RingLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.ConcurrentLinkedQueue

// The decision table of contracts 9.2 (red-nose.md 7.5).
//
// Rows verified:
// - delivered over the hub: lastDeliveredSeqLocal advances, attempt resets to 0
// - rejected over the hub: fix retained, attempt++, no HTTP fallback while socket is up
// - delivered over HTTP when the socket is down: httpFallbackSeconds accrues, attempt=0
// - rejected over HTTP: fix retained, attempt++
// - in-flight guard: a second attempt returns while one is in flight
// - fix replaced mid-send: current fix goes out on the next attempt
// - sendsFailedSinceBoot only counts failures while liveEventId is non-null
@OptIn(ExperimentalCoroutinesApi::class)
class SendLoopTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var log: RingLog

    @Before fun setUp() { log = RingLog(tmp.root, 8_192) }

    @After fun tearDown() { /* RingLog has no explicit close */ }

    private fun fix(seq: Long): LatestFix = LatestFix(
        lat = 46.87, lng = -114.0, recordedAt = FixTime.rfc3339(1_734_830_000_000L),
        seqLocal = seq,
    )

    private class FakeState : SendLoop.State {
        override var latestFix: LatestFix? = null
        override var lastDeliveredSeqLocal: Long? = null
        override var lastReceiptLatencyMs: Long? = null
        override var lastSendError: String? = null
        override var attempt: Int = 0
        override var inFlight: Boolean = false
        override var socketState: String = "connected"
        override var liveEventId: Long? = 7L
        override var ingestChannel: String = "wmsfo-api-dev:ingest"
    }

    private class FakeHub : SendLoop.HubSender {
        val calls = ConcurrentLinkedQueue<Long>()
        val rejoins = ConcurrentLinkedQueue<Long>()
        var respond: suspend (LatestFix) -> Boolean = { true }
        var onRejoin: () -> Unit = { }
        override suspend fun sendToChannel(channel: String, fix: LatestFix): Boolean {
            calls.add(fix.seqLocal)
            return respond(fix)
        }
        override fun requestRejoin() {
            rejoins.add(System.nanoTime())
            onRejoin()
        }
    }

    private class FakeRest : SendLoop.RestSender {
        val calls = ConcurrentLinkedQueue<Long>()
        var respond: suspend (LatestFix) -> SendLoop.PostResult = {
            SendLoop.PostResult(true, null, null, null)
        }
        override suspend fun postLocation(fix: LatestFix): SendLoop.PostResult {
            calls.add(fix.seqLocal)
            return respond(fix)
        }
    }

    private fun make(
        state: FakeState, hub: FakeHub, rest: FakeRest,
        stats: TransportStats = TransportStats(),
        elapsed: () -> Long = { 100L },
    ): SendLoop = SendLoop(
        state = state, stats = stats, hub = hub, rest = rest, log = log,
        backoff = { 1L }, elapsedRealtimeMs = elapsed, fixIntervalMs = 1_000L,
    )

    @Test fun delivered_over_hub_resets_attempt_and_advances_seq() = runTest {
        val stats = TransportStats()
        val state = FakeState().apply { attempt = 2; latestFix = fix(5) }
        val hub = FakeHub()
        val rest = FakeRest()
        val loop = make(state, hub, rest, stats = stats, elapsed = object : () -> Long {
            var n = 0L
            override fun invoke(): Long { n += 50L; return n }
        })

        loop.attempt()

        assertEquals(listOf(5L), hub.calls.toList())
        assertTrue(rest.calls.isEmpty())
        assertEquals(5L, state.lastDeliveredSeqLocal)
        assertEquals(0, state.attempt)
        assertNull(state.lastSendError)
        assertFalse(state.inFlight)
        // R9: hub-delivered fixes also stamp lastReceiptLatencyMs on both the
        // service state (status screen) and TransportStats (heartbeat body).
        assertEquals(50L, state.lastReceiptLatencyMs)
        assertEquals(50L, stats.lastReceiptLatencyMs)
    }

    @Test fun rejected_over_hub_does_not_fall_back_to_http() = runTest {
        val state = FakeState().apply { latestFix = fix(1); socketState = "connected" }
        val hub = FakeHub().apply { respond = { false } }
        val rest = FakeRest()
        val loop = make(state, hub, rest)

        loop.attempt()

        assertEquals(listOf(1L), hub.calls.toList())
        assertTrue("hub rejection must never fall back to HTTP while socket is up",
            rest.calls.isEmpty())
        assertNull(state.lastDeliveredSeqLocal)
        assertEquals(1, state.attempt)
        assertEquals("hub_rejected", state.lastSendError)
    }

    @Test fun delivered_over_http_accrues_fallback_seconds() = runTest {
        val stats = TransportStats()
        val state = FakeState().apply { latestFix = fix(2); socketState = "disconnected" }
        val hub = FakeHub()
        val rest = FakeRest()
        // The elapsed clock advances by 1500 ms between the two calls (start, end).
        val clock = object : () -> Long {
            var n = 0L
            override fun invoke(): Long { val cur = n; n += 1500L; return cur }
        }
        val loop = make(state, hub, rest, stats = stats, elapsed = clock)

        loop.attempt()

        assertEquals(listOf(2L), rest.calls.toList())
        assertTrue(hub.calls.isEmpty())
        assertEquals(2L, state.lastDeliveredSeqLocal)
        assertEquals(0, state.attempt)
        assertTrue("httpFallbackSeconds must accrue", stats.httpFallbackSeconds >= 1)
    }

    @Test fun rejected_over_http_keeps_fix_and_grows_attempt() = runTest {
        val state = FakeState().apply { latestFix = fix(2); socketState = "disconnected" }
        val hub = FakeHub()
        val rest = FakeRest().apply {
            respond = { SendLoop.PostResult(false, null, "no_live_event", "req-42") }
        }
        val loop = make(state, hub, rest)

        loop.attempt()

        assertEquals(listOf(2L), rest.calls.toList())
        assertTrue(hub.calls.isEmpty())
        assertNull(state.lastDeliveredSeqLocal)
        assertEquals(1, state.attempt)
        // Failed sends are logged as "<code> <requestId>" (red-nose.md 9.3).
        assertEquals("no_live_event req-42", state.lastSendError)
    }

    @Test fun already_delivered_seq_returns_without_calling() = runTest {
        val state = FakeState().apply {
            latestFix = fix(9); lastDeliveredSeqLocal = 9L
        }
        val hub = FakeHub()
        val rest = FakeRest()
        val loop = make(state, hub, rest)

        loop.attempt()

        assertTrue(hub.calls.isEmpty())
        assertTrue(rest.calls.isEmpty())
    }

    @Test fun in_flight_guard_short_circuits_second_call() = runTest {
        val state = FakeState().apply { latestFix = fix(1); inFlight = true }
        val hub = FakeHub()
        val rest = FakeRest()
        val loop = make(state, hub, rest)

        loop.attempt()

        assertTrue(hub.calls.isEmpty())
        assertTrue(rest.calls.isEmpty())
    }

    @Test fun fix_replaced_mid_send_makes_current_go_out_next() = runTest {
        val state = FakeState().apply { latestFix = fix(1) }
        val gate = CompletableDeferred<Boolean>()
        val hub = FakeHub().apply { respond = { gate.await() } }
        val rest = FakeRest()
        val loop = make(state, hub, rest)

        val running = async { loop.attempt() }
        // Under runTest, the async body only starts when this coroutine suspends;
        // yield so the attempt reads latestFix (=fix(1)) and parks on the gate
        // before the fix is replaced.
        yield()

        // Simulate a fix arriving while the send is in flight.
        state.latestFix = fix(2)
        gate.complete(true)
        running.await()

        assertEquals(1L, state.lastDeliveredSeqLocal)

        // The loop kicks itself so the next attempt goes out for the replaced fix.
        loop.attempt()

        assertEquals(listOf(1L, 2L), hub.calls.toList())
        assertEquals(2L, state.lastDeliveredSeqLocal)
        assertEquals(0, state.attempt)
    }

    @Test fun three_consecutive_hub_rejections_ask_for_rejoin_once() = runTest {
        val state = FakeState().apply { latestFix = fix(1); socketState = "connected" }
        val hub = FakeHub().apply { respond = { false } }
        val rest = FakeRest()
        val loop = make(state, hub, rest)

        // Two rejections: no rejoin requested yet.
        loop.attempt(); assertTrue(hub.rejoins.isEmpty())
        state.latestFix = fix(2); loop.attempt(); assertTrue(hub.rejoins.isEmpty())

        // Third consecutive hub_rejected while socketState == connected asks for
        // exactly one re-join (contracts 9.2).
        state.latestFix = fix(3); loop.attempt()
        assertEquals("re-join requested exactly once at the third rejection",
            1, hub.rejoins.size)

        // Two more rejections without a delivered send in between: no additional
        // re-join request, because the counter reset when it fired.
        state.latestFix = fix(4); loop.attempt()
        state.latestFix = fix(5); loop.attempt()
        assertEquals(1, hub.rejoins.size)
    }

    @Test fun hub_rejections_without_a_live_event_do_not_count_toward_rejoin() = runTest {
        val state = FakeState().apply { latestFix = fix(1); socketState = "connected"; liveEventId = null }
        val hub = FakeHub().apply { respond = { false } }
        val rest = FakeRest()
        val loop = make(state, hub, rest)
        for (i in 1L..6L) { state.latestFix = fix(i); loop.attempt() }
        assertTrue("no re-join while the heartbeat names no live event", hub.rejoins.isEmpty())
        // Once a live event is named, three rejections ask for one re-join.
        state.liveEventId = 7L
        for (i in 7L..9L) { state.latestFix = fix(i); loop.attempt() }
        assertEquals(1, hub.rejoins.size)
    }

    @Test fun delivered_send_resets_the_rejoin_counter() = runTest {
        val state = FakeState().apply { latestFix = fix(1); socketState = "connected" }
        var reject = true
        val hub = FakeHub().apply { respond = { !reject } }
        val rest = FakeRest()
        val loop = make(state, hub, rest)

        // Two rejections raise the counter to 2.
        loop.attempt()
        state.latestFix = fix(2); loop.attempt()
        // Then a delivered send: the counter resets to 0.
        reject = false
        state.latestFix = fix(3); loop.attempt()
        assertEquals(3L, state.lastDeliveredSeqLocal)

        // Two more rejections must not trigger a re-join yet.
        reject = true
        state.latestFix = fix(4); loop.attempt()
        state.latestFix = fix(5); loop.attempt()
        assertTrue("counter reset on delivery", hub.rejoins.isEmpty())

        // A third rejection in the fresh streak triggers exactly one re-join.
        state.latestFix = fix(6); loop.attempt()
        assertEquals(1, hub.rejoins.size)
    }

    @Test fun a_throwing_rejoin_does_not_crash_the_send_loop() = runTest {
        val state = FakeState().apply { latestFix = fix(1); socketState = "connected" }
        val hub = FakeHub().apply {
            respond = { false }
            onRejoin = { throw RuntimeException("no_connection") }
        }
        val rest = FakeRest()
        val loop = make(state, hub, rest)

        loop.attempt()
        state.latestFix = fix(2); loop.attempt()
        // The third rejection tries to re-join; the fake throws. The send loop
        // swallows it (the socket loop is responsible for its own failure branch).
        state.latestFix = fix(3); loop.attempt()

        assertEquals(1, hub.rejoins.size)
        assertEquals("hub_rejected", state.lastSendError)
    }

    @Test fun hub_rejected_while_socket_reconnecting_does_not_count_toward_rejoin() = runTest {
        val state = FakeState().apply { latestFix = fix(1); socketState = "reconnecting" }
        val hub = FakeHub().apply { respond = { false } }
        val rest = FakeRest().apply {
            respond = { SendLoop.PostResult(false, null, "no_live_event", null) }
        }
        val loop = make(state, hub, rest)

        // While reconnecting the send loop uses HTTP, not the hub; a hub_rejected
        // outcome cannot arise here, so no re-join is ever requested.
        repeat(5) {
            state.latestFix = fix((it + 2).toLong()); loop.attempt()
        }
        assertTrue(hub.rejoins.isEmpty())
        assertTrue("hub not touched while reconnecting", hub.calls.isEmpty())
    }

    // R11 (task 395): fixes at 250 ms, HTTP window of 1 s.

    @Test fun socket_up_four_fixes_go_over_hub_in_order() = runTest {
        val state = FakeState().apply { socketState = "connected"; latestFix = fix(1) }
        val hub = FakeHub()
        val rest = FakeRest()
        val loop = make(state, hub, rest)

        loop.attempt()
        state.latestFix = fix(2); loop.attempt()
        state.latestFix = fix(3); loop.attempt()
        state.latestFix = fix(4); loop.attempt()

        assertEquals(listOf(1L, 2L, 3L, 4L), hub.calls.toList())
        assertTrue("no HTTP posts while the socket is up", rest.calls.isEmpty())
        assertEquals(4L, state.lastDeliveredSeqLocal)
        assertEquals(0, state.attempt)
    }

    @Test fun http_second_send_waits_the_window() = runTest {
        val state = FakeState().apply { socketState = "disconnected"; latestFix = fix(1) }
        val hub = FakeHub()
        val rest = FakeRest()
        // Use the virtual clock so the window can be measured.
        val loop = SendLoop(
            state = state, stats = TransportStats(), hub = hub, rest = rest, log = log,
            backoff = { 1L }, elapsedRealtimeMs = { currentTime },
            fixIntervalMs = 250L, httpFallbackIntervalMs = 1_000L,
        )

        loop.attempt()
        val afterFirst = currentTime
        state.latestFix = fix(2)
        loop.attempt()
        val afterSecond = currentTime

        assertEquals(listOf(1L, 2L), rest.calls.toList())
        assertTrue("second HTTP send waited >= 1000 ms, was ${afterSecond - afterFirst}",
            afterSecond - afterFirst >= 1_000L)
    }

    @Test fun http_window_carries_the_newest_fix_at_window_end() = runTest {
        val state = FakeState().apply { socketState = "disconnected"; latestFix = fix(1) }
        val hub = FakeHub()
        val rest = FakeRest()
        val loop = SendLoop(
            state = state, stats = TransportStats(), hub = hub, rest = rest, log = log,
            backoff = { 1L }, elapsedRealtimeMs = { currentTime },
            fixIntervalMs = 250L, httpFallbackIntervalMs = 1_000L,
        )

        // First HTTP send at t=0 (no prior window).
        loop.attempt()
        assertEquals(listOf(1L), rest.calls.toList())

        // A second attempt enters the window and delays; during the delay a
        // stream of newer fixes replaces latestFix. When the window ends the
        // newest fix goes out (contracts 9.2, red-nose.md 7.5).
        state.latestFix = fix(2)
        val running = async { loop.attempt() }
        yield()
        advanceTimeBy(250L); state.latestFix = fix(3)
        advanceTimeBy(250L); state.latestFix = fix(4)
        advanceTimeBy(250L); state.latestFix = fix(5)
        advanceTimeBy(300L)
        running.await()

        assertEquals("HTTP window ended with the newest fix",
            listOf(1L, 5L), rest.calls.toList())
        assertEquals(5L, state.lastDeliveredSeqLocal)
    }

    @Test fun socket_up_during_window_takes_the_fix_over_the_hub() = runTest {
        val state = FakeState().apply { socketState = "disconnected"; latestFix = fix(1) }
        val hub = FakeHub()
        val rest = FakeRest()
        val loop = SendLoop(
            state = state, stats = TransportStats(), hub = hub, rest = rest, log = log,
            backoff = { 1L }, elapsedRealtimeMs = { currentTime },
            fixIntervalMs = 250L, httpFallbackIntervalMs = 1_000L,
        )

        loop.attempt()  // first HTTP send at t=0
        assertEquals(listOf(1L), rest.calls.toList())

        // Kick off the second attempt inside the window, then flip the socket
        // to connected halfway through.  When the window ends the loop re-decides
        // and takes the hub with no additional HTTP post.
        state.latestFix = fix(2)
        val running = async { loop.attempt() }
        yield()
        advanceTimeBy(500L)
        state.socketState = "connected"
        advanceTimeBy(600L)
        running.await()

        assertEquals("HTTP unchanged after the socket connects",
            listOf(1L), rest.calls.toList())
        assertEquals("second send went over the hub",
            listOf(2L), hub.calls.toList())
        assertEquals(2L, state.lastDeliveredSeqLocal)
    }

    @Test fun sends_failed_since_boot_only_counts_when_liveEventId_is_set() = runTest {
        val stats = TransportStats()
        val stateNoEvent = FakeState().apply {
            latestFix = fix(1); socketState = "disconnected"; liveEventId = null
        }
        val hub = FakeHub()
        val restReject = FakeRest().apply {
            respond = { SendLoop.PostResult(false, null, "no_live_event", null) }
        }
        val loop = make(stateNoEvent, hub, restReject, stats = stats)

        loop.attempt()
        loop.attempt()  // Second failure with liveEventId still null.

        assertEquals("counter must be zero while liveEventId is null",
            0, stats.sendsFailedSinceBoot)

        // Now teach the loop about a live event and let it fail again.
        stateNoEvent.liveEventId = 7L
        stateNoEvent.attempt = 0
        loop.attempt()

        assertEquals("failure while liveEventId is set counts",
            1, stats.sendsFailedSinceBoot)
    }
}
