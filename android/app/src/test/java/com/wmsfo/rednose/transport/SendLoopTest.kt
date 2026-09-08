package com.wmsfo.rednose.transport

import com.wmsfo.rednose.location.FixTime
import com.wmsfo.rednose.location.LatestFix
import com.wmsfo.rednose.log.RingLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
        var respond: suspend (LatestFix) -> Boolean = { true }
        override suspend fun sendToChannel(channel: String, fix: LatestFix): Boolean {
            calls.add(fix.seqLocal)
            return respond(fix)
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
        val state = FakeState().apply { attempt = 2; latestFix = fix(5) }
        val hub = FakeHub()
        val rest = FakeRest()
        val loop = make(state, hub, rest, elapsed = object : () -> Long {
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
