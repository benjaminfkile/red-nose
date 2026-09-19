package com.wmsfo.rednose.transport

import com.google.gson.JsonElement
import com.wmsfo.rednose.location.LatestFix
import com.wmsfo.rednose.log.RingLog
import com.wmsfo.rednose.store.Enrollment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// The reconnect guarantee of red-nose.md 7.4 / contracts 9.2: on any hub socket
// close, and on any Throwable that fails a build/start/join, the loop retries
// forever with the documented backoff.  Only stop() (or scope cancellation) ends
// the loop.
//
// The tests inject a FakeHub through the `hubBuild` seam so they never touch the
// real SignalR client or the network.
@OptIn(ExperimentalCoroutinesApi::class)
class SocketLoopTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var log: RingLog

    @Before fun setUp() { log = RingLog(tmp.root, 8_192) }

    @After fun tearDown() { /* RingLog has no explicit close */ }

    private val enrollment = Enrollment(
        apiBaseUrl = "https://example.invalid",
        hubUrl = "https://example.invalid/hub",
        ingestChannel = "wmsfo-api-dev:ingest",
        beaconId = 42L,
        name = "test-beacon",
        key = "test-key-longer-than-twelve-characters",
    )

    private class FakeHub : HubTransport {
        var channelEventHandler: ((JsonElement) -> Unit)? = null
        var closedHandler: ((Throwable?) -> Unit)? = null
        val invokes: MutableList<Invocation> = mutableListOf()
        var startCount: Int = 0
        var stopCount: Int = 0
        var onStart: suspend () -> Unit = { }
        var onInvoke: suspend (String, Array<out Any?>) -> Unit = { _, _ -> }
        var onFireAndForget: (String, Array<Any?>, () -> Unit, (Throwable) -> Unit) -> Unit =
            { _, _, s, _ -> s() }

        fun fireClose(cause: Throwable?) { closedHandler?.invoke(cause) }

        override fun onChannelEvent(handler: (JsonElement) -> Unit) { channelEventHandler = handler }
        override fun onClosed(handler: (Throwable?) -> Unit) { closedHandler = handler }
        override suspend fun start() { startCount++; onStart() }
        override suspend fun invoke(method: String, vararg args: Any?) {
            invokes.add(Invocation(method, args.toList()))
            onInvoke(method, args)
        }
        override fun invokeFireAndForget(
            method: String,
            args: Array<Any?>,
            onSuccess: () -> Unit,
            onError: (Throwable) -> Unit,
        ) {
            onFireAndForget(method, args, onSuccess, onError)
        }
        override fun stop() { stopCount++ }

        data class Invocation(val method: String, val args: List<Any?>)
    }

    // A minimal SendLoop instance for SocketLoop's dependency: SocketLoop only
    // calls sendLoop.kick(), which drops onto a conflated internal channel.  The
    // send loop is never started, so its dispatcher never runs.
    private fun dummySendLoop(): SendLoop {
        val state = object : SendLoop.State {
            override var latestFix: LatestFix? = null
            override var lastDeliveredSeqLocal: Long? = null
            override var lastReceiptLatencyMs: Long? = null
            override var lastSendError: String? = null
            override var attempt: Int = 0
            override var inFlight: Boolean = false
            override var socketState: String = "connected"
            override var liveEventId: Long? = null
            override var ingestChannel: String = "wmsfo-api-dev:ingest"
        }
        val hub = object : SendLoop.HubSender {
            override suspend fun sendToChannel(channel: String, fix: LatestFix): Boolean = true
            override fun requestRejoin() { }
        }
        val rest = object : SendLoop.RestSender {
            override suspend fun postLocation(fix: LatestFix): SendLoop.PostResult =
                SendLoop.PostResult(true, null, null, null)
        }
        return SendLoop(
            state = state, stats = TransportStats(), hub = hub, rest = rest, log = log,
            backoff = { 1L }, elapsedRealtimeMs = { 0L },
        )
    }

    private fun buildLoop(
        hubBuild: (String) -> HubTransport,
        stats: TransportStats = TransportStats(),
        retryNow: Channel<Unit> = Channel(capacity = Channel.CONFLATED),
        backoff: (Int) -> Long = { Backoff.delayMs(it).toLong() },
    ): SocketLoop = SocketLoop(
        enrollment = enrollment,
        stats = stats,
        sendLoop = dummySendLoop(),
        retryNow = retryNow,
        log = log,
        hubBuild = hubBuild,
        backoff = backoff,
    )

    // --- Acceptance test 1: a non-null cause reconnects. -------------------

    @Test fun a_close_with_a_non_null_cause_reconnects() = runTest {
        val hubs = mutableListOf<FakeHub>()
        val stats = TransportStats()
        val loop = buildLoop(
            hubBuild = { FakeHub().also { hubs.add(it) } },
            stats = stats,
        )
        loop.start(this)
        advanceUntilIdle()
        assertEquals("initial hub built and joined", 1, hubs.size)
        assertEquals("connected", stats.socketState)

        hubs[0].fireClose(RuntimeException("transport dropped"))
        advanceUntilIdle()

        assertEquals("second hub built after close", 2, hubs.size)
        assertEquals(2, stats.reconnectCount)
        assertEquals("connected", stats.socketState)
        loop.stop()
    }

    // --- Acceptance test 2: a null (clean) cause reconnects the same way. --

    @Test fun a_close_with_a_null_cause_reconnects_too() = runTest {
        val hubs = mutableListOf<FakeHub>()
        val stats = TransportStats()
        val loop = buildLoop(
            hubBuild = { FakeHub().also { hubs.add(it) } },
            stats = stats,
        )
        loop.start(this)
        advanceUntilIdle()

        hubs[0].fireClose(null)
        advanceUntilIdle()

        assertEquals("null cause still reconnects", 2, hubs.size)
        assertEquals(2, stats.reconnectCount)
        loop.stop()
    }

    // --- Acceptance test 3: 25 consecutive closes yield 25 reconnects. -----

    @Test fun twenty_five_closes_yield_twenty_five_reconnects() = runTest {
        val hubs = mutableListOf<FakeHub>()
        val stats = TransportStats()
        val loop = buildLoop(
            hubBuild = { FakeHub().also { hubs.add(it) } },
            stats = stats,
        )
        loop.start(this)
        advanceUntilIdle()

        repeat(25) { i ->
            hubs.last().fireClose(if (i % 2 == 0) RuntimeException("close $i") else null)
            advanceUntilIdle()
        }

        assertEquals("one initial hub plus 25 reconnects", 26, hubs.size)
        assertEquals("reconnectCount counts every successful connect", 26, stats.reconnectCount)
        // Every hub was joined exactly once.
        hubs.forEach { h ->
            assertEquals(1, h.invokes.count { it.method == "JoinPrivateChannel" })
        }
        loop.stop()
    }

    // --- Acceptance test 4: backoff follows 1 s, 2 s, 3 s, then 5 s forever
    // (red-nose.md 7.2).  Successful joins reset attempt to 0, so the growing
    // sequence is only observable when successive builds/starts fail. --------

    @Test fun backoff_follows_documented_sequence_and_repeats_the_cap() = runTest {
        val hubs = mutableListOf<FakeHub>()
        val buildTimes = mutableListOf<Long>()
        val loop = buildLoop(
            hubBuild = {
                buildTimes.add(currentTime)
                FakeHub().also { hub ->
                    hub.onStart = { throw RuntimeException("start failed permanently") }
                    hubs.add(hub)
                }
            },
        )
        loop.start(this)
        // Enough virtual time for at least seven attempts (1+2+3+5+5+5 = 21 s).
        advanceTimeBy(30_000L)
        loop.stop()
        advanceUntilIdle()

        assertTrue("at least seven build attempts, got ${buildTimes.size}", buildTimes.size >= 7)
        // Intervals between successive builds should be the backoff waits.
        val gaps = buildTimes.zipWithNext { a, b -> b - a }.take(6)
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 5_000L, 5_000L, 5_000L), gaps)
    }

    // --- Acceptance test 5: socketState + reconnectCount transitions. ------

    @Test fun socket_state_transitions_and_reconnect_count_increments() = runTest {
        val hubs = mutableListOf<FakeHub>()
        val stats = TransportStats()
        val loop = buildLoop(
            hubBuild = { FakeHub().also { hubs.add(it) } },
            stats = stats,
        )
        assertEquals("disconnected", stats.socketState)

        loop.start(this)
        advanceUntilIdle()
        assertEquals("connected", stats.socketState)
        assertEquals(1, stats.reconnectCount)

        hubs[0].fireClose(RuntimeException("first close"))
        advanceUntilIdle()
        assertEquals(2, stats.reconnectCount)
        assertEquals("connected", stats.socketState)

        hubs[1].fireClose(null)
        advanceUntilIdle()
        assertEquals(3, stats.reconnectCount)
        assertEquals("connected", stats.socketState)

        loop.stop()
        assertEquals("disconnected", stats.socketState)
    }

    // --- Acceptance test 6: every reconnect re-invokes JoinPrivateChannel. -

    @Test fun join_private_channel_re_invoked_after_every_reconnect() = runTest {
        val hubs = mutableListOf<FakeHub>()
        val loop = buildLoop(
            hubBuild = { FakeHub().also { hubs.add(it) } },
        )
        loop.start(this)
        advanceUntilIdle()

        repeat(5) {
            hubs.last().fireClose(RuntimeException("close $it"))
            advanceUntilIdle()
        }

        assertEquals(6, hubs.size)
        hubs.forEachIndexed { i, hub ->
            val joins = hub.invokes.filter { it.method == "JoinPrivateChannel" }
            assertEquals("hub #$i joined exactly once", 1, joins.size)
            assertEquals("hub #$i join used the ingest channel",
                listOf(enrollment.ingestChannel, enrollment.key), joins[0].args)
        }
        loop.stop()
    }

    // --- Acceptance test 7: a close signalled BEFORE the loop reaches
    // closed.receive() does not wedge (conflated-channel early-close race). --

    @Test fun close_signalled_before_receive_still_reconnects() = runTest {
        val hubs = mutableListOf<FakeHub>()
        val stats = TransportStats()
        val loop = buildLoop(
            hubBuild = {
                val hub = FakeHub()
                if (hubs.isEmpty()) {
                    hub.onInvoke = { method, _ ->
                        // Fire the close DURING JoinPrivateChannel, before the
                        // loop reaches closed.receive().  A conflated channel
                        // must hold the signal so receive() consumes it rather
                        // than blocking forever.
                        if (method == "JoinPrivateChannel") {
                            hub.fireClose(RuntimeException("close before receive"))
                        }
                    }
                }
                hubs.add(hub)
                hub
            },
            stats = stats,
        )
        loop.start(this)
        advanceUntilIdle()

        assertTrue("loop recovered from early close, hubs=${hubs.size}", hubs.size >= 2)
        assertTrue("at least one reconnect counted", stats.reconnectCount >= 1)
        loop.stop()
    }

    // --- Acceptance test 8: stop() terminates the loop; no more reconnects.

    @Test fun stop_terminates_the_loop_and_no_more_reconnects_occur() = runTest {
        val hubs = mutableListOf<FakeHub>()
        val stats = TransportStats()
        val loop = buildLoop(
            hubBuild = { FakeHub().also { hubs.add(it) } },
            stats = stats,
        )
        loop.start(this)
        advanceUntilIdle()
        assertEquals(1, hubs.size)

        hubs[0].fireClose(RuntimeException("bye"))
        advanceUntilIdle()
        val hubsBeforeStop = hubs.size

        loop.stop()
        // Any further virtual time must not produce another hub.
        advanceTimeBy(60_000L)
        advanceUntilIdle()

        assertEquals("stop halted the loop", hubsBeforeStop, hubs.size)
        assertEquals("disconnected", stats.socketState)
    }

    // --- Guarantee: an Error (non-Exception Throwable) from start() does not
    // kill the loop.  The old `catch (e: Exception)` would have let it escape. -

    @Test fun a_non_exception_throwable_from_start_does_not_kill_the_loop() = runTest {
        val hubs = mutableListOf<FakeHub>()
        val loop = buildLoop(
            hubBuild = {
                val hub = FakeHub()
                if (hubs.isEmpty()) {
                    hub.onStart = { throw OutOfMemoryError("simulated") }
                }
                hubs.add(hub)
                hub
            },
        )
        loop.start(this)
        advanceUntilIdle()

        assertTrue("loop survived an Error from start(), built ${hubs.size} hubs",
            hubs.size >= 2)
        loop.stop()
    }

    // --- Guarantee: hubBuild throwing does not kill the loop. --------------

    @Test fun hub_build_throwing_does_not_kill_the_loop() = runTest {
        val hubs = mutableListOf<FakeHub>()
        var buildFailures = 3
        val loop = buildLoop(
            hubBuild = {
                if (buildFailures > 0) {
                    buildFailures--
                    throw IllegalStateException("simulated builder failure")
                }
                FakeHub().also { hubs.add(it) }
            },
        )
        loop.start(this)
        advanceTimeBy(30_000L)
        advanceUntilIdle()

        assertTrue("loop recovered after 3 build failures, hubs=${hubs.size}",
            hubs.isNotEmpty())
        loop.stop()
    }

    // --- Guarantee: closedHandler is registered BEFORE start(), so a close
    // that arrives during start() is captured, not missed. -------------------

    @Test fun close_arriving_during_start_is_captured_not_missed() = runTest {
        val hubs = mutableListOf<FakeHub>()
        val stats = TransportStats()
        val loop = buildLoop(
            hubBuild = {
                val hub = FakeHub()
                if (hubs.isEmpty()) {
                    hub.onStart = {
                        // The close handler has already been registered by the
                        // loop before start() is called.  Fire it now so the
                        // trySend lands in the conflated buffer for the loop
                        // to read once start() and join() complete.
                        hub.fireClose(RuntimeException("close during start"))
                    }
                }
                hubs.add(hub)
                hub
            },
            stats = stats,
        )
        loop.start(this)
        advanceUntilIdle()

        assertTrue("loop reconnected after close during start", hubs.size >= 2)
        assertTrue("reconnect counted at least once", stats.reconnectCount >= 1)
        loop.stop()
    }

    // --- Guarantee: retryNow shortcut wakes the backoff sleep. -------------
    // The reconnect happens indefinitely with or without connectivity kicks;
    // a kick just shortens the wait.  This test verifies the second reconnect
    // fires immediately when retryNow is pinged during the backoff.

    @Test fun retry_now_wakes_the_backoff_and_reconnect_happens_promptly() = runTest {
        val hubs = mutableListOf<FakeHub>()
        val retryNow = Channel<Unit>(capacity = Channel.CONFLATED)
        val loop = buildLoop(
            hubBuild = { FakeHub().also { hubs.add(it) } },
            retryNow = retryNow,
            backoff = { 60_000L },  // Long backoff; retryNow must short-circuit.
        )
        loop.start(this)
        advanceUntilIdle()
        assertEquals(1, hubs.size)

        hubs[0].fireClose(null)
        // Do NOT advance time; instead ping retryNow.
        retryNow.trySend(Unit)
        advanceUntilIdle()

        assertEquals("retryNow shortened the backoff", 2, hubs.size)
        loop.stop()
    }

    // --- Ring-log visibility: a successful connect logs one INFO line
    // carrying the ingest channel, the current reconnectCount, and the
    // attempt number that succeeded.  Distinguishes a first connect from a
    // recovery.

    @Test fun successful_connect_logs_channel_reconnect_count_and_attempt_at_info() = runTest {
        val loop = buildLoop(hubBuild = { FakeHub() })
        loop.start(this)
        advanceUntilIdle()

        val connectLines = log.recent(200).filter { it.contains("socket connected channel=") }
        assertEquals("exactly one connect line for a first connect", 1, connectLines.size)
        val line = connectLines.first()
        assertTrue("connect line at INFO, got: $line", line.contains(" INFO "))
        assertTrue("carries the ingest channel, got: $line",
            line.contains("channel=${enrollment.ingestChannel}"))
        assertTrue("carries reconnectCount=1, got: $line", line.contains("reconnectCount=1"))
        assertTrue("carries attempt=0, got: $line", line.contains("attempt=0"))
        loop.stop()
    }

    // --- Ring-log visibility: a reconnect is distinguishable from a first
    // connect via reconnectCount.

    @Test fun reconnect_is_distinguishable_from_first_connect_via_reconnect_count() = runTest {
        val hubs = mutableListOf<FakeHub>()
        val loop = buildLoop(hubBuild = { FakeHub().also { hubs.add(it) } })
        loop.start(this)
        advanceUntilIdle()
        hubs[0].fireClose(RuntimeException("drop"))
        advanceUntilIdle()

        val connectLines = log.recent(200).filter { it.contains("socket connected channel=") }
        assertEquals("one connect line per successful connect", 2, connectLines.size)
        assertTrue("first connect names reconnectCount=1, got: ${connectLines[0]}",
            connectLines[0].contains("reconnectCount=1"))
        assertTrue("second connect names reconnectCount=2, got: ${connectLines[1]}",
            connectLines[1].contains("reconnectCount=2"))
        loop.stop()
    }

    // --- Ring-log visibility: a close logs one INFO line carrying the cause
    // and the backoff delay about to be waited.

    @Test fun close_logs_the_cause_and_the_scheduled_backoff_delay() = runTest {
        val hubs = mutableListOf<FakeHub>()
        val loop = buildLoop(
            hubBuild = { FakeHub().also { hubs.add(it) } },
            backoff = { 4321L },
        )
        loop.start(this)
        advanceUntilIdle()
        hubs[0].fireClose(RuntimeException("drop-cause"))
        advanceUntilIdle()

        val closeLines = log.recent(200).filter { it.contains("socket closed; reconnecting") }
        assertEquals("exactly one close line per close", 1, closeLines.size)
        val line = closeLines.first()
        assertTrue("close line at INFO, got: $line", line.contains(" INFO "))
        assertTrue("carries the ingest channel, got: $line",
            line.contains("channel=${enrollment.ingestChannel}"))
        assertTrue("carries delayMs=4321, got: $line", line.contains("delayMs=4321"))
        assertTrue("carries attempt=0, got: $line", line.contains("attempt=0"))
        assertTrue("carries the cause message, got: $line", line.contains("drop-cause"))
        loop.stop()
    }

    // --- Confidentiality: the enrollment key never lands in any logged line.

    @Test fun no_credential_value_appears_in_any_logged_line() = runTest {
        val hubs = mutableListOf<FakeHub>()
        val loop = buildLoop(
            hubBuild = {
                val hub = FakeHub()
                if (hubs.isEmpty()) {
                    hub.onInvoke = { method, _ ->
                        if (method == "JoinPrivateChannel") {
                            throw RuntimeException("join denied")
                        }
                    }
                }
                hubs.add(hub)
                hub
            },
        )
        loop.start(this)
        // Time enough for one join failure, its backoff wait, and one successful
        // reconnect: 1s (initial backoff) + 10s (join-denied hold) is plenty.
        advanceTimeBy(30_000L)
        advanceUntilIdle()
        hubs.last().fireClose(RuntimeException("drop"))
        advanceUntilIdle()

        val leaked = log.recent(500).filter { it.contains(enrollment.key) }
        assertTrue("no line contains the enrollment key, leaked=$leaked", leaked.isEmpty())
        loop.stop()
    }

    // --- Quietness: a steady connected socket emits no repeated lines.

    @Test fun steady_connected_socket_emits_no_repeated_lines() = runTest {
        val loop = buildLoop(hubBuild = { FakeHub() })
        loop.start(this)
        advanceUntilIdle()

        val before = log.recent(500).size
        advanceTimeBy(60_000L)
        advanceUntilIdle()
        val after = log.recent(500).size
        assertEquals("no additional log lines while connected", before, after)
        loop.stop()
    }

    // --- Meaning of reconnectCount: it is the number of times the socket has
    // reached "connected" since the service started, so one recovery counts as
    // one no matter how many failed attempts the recovery took.

    @Test fun reconnect_count_is_one_after_first_connect_and_two_after_one_recovery() = runTest {
        val hubs = mutableListOf<FakeHub>()
        var startFailsLeft = 3
        val stats = TransportStats()
        val loop = buildLoop(
            hubBuild = {
                val hub = FakeHub()
                if (hubs.isNotEmpty() && startFailsLeft > 0) {
                    hub.onStart = { startFailsLeft--; throw RuntimeException("still down") }
                }
                hubs.add(hub)
                hub
            },
            stats = stats,
        )
        loop.start(this)
        advanceUntilIdle()
        assertEquals("one after the first connect", 1, stats.reconnectCount)

        hubs[0].fireClose(RuntimeException("drop"))
        // Backoff sequence 1s, 2s, 3s, 5s covers three failed attempts then
        // one successful reconnect (11 s total); 30 s of virtual time is
        // plenty.
        advanceTimeBy(30_000L)
        advanceUntilIdle()

        assertEquals("still counted once for one recovery", 2, stats.reconnectCount)
        assertEquals("recovery took three failed attempts plus one that succeeded",
            5, hubs.size)
        loop.stop()
    }

    // --- A close during start() ends the wait at once: the loop reconnects
    // after the normal backoff, not after the 10 s handshake timeout. --------

    @Test fun a_close_while_start_is_pending_reconnects_without_waiting_for_the_timeout() = runTest {
        val hubs = mutableListOf<FakeHub>()
        val block = CompletableDeferred<Unit>()
        val stats = TransportStats()
        val loop = buildLoop(
            hubBuild = {
                val hub = FakeHub()
                if (hubs.isEmpty()) {
                    hub.onStart = { block.await() }
                }
                hubs.add(hub)
                hub
            },
            stats = stats,
        )
        val t0 = currentTime
        loop.start(this)
        advanceUntilIdle()
        assertEquals("first hub built and its start() is suspended", 1, hubs.size)

        hubs[0].fireClose(RuntimeException("close during start"))
        // Advance only the first backoff; a second hub must appear.
        advanceTimeBy(1_000L)
        advanceUntilIdle()

        assertEquals("second hub built after the close during start()", 2, hubs.size)
        val elapsed = currentTime - t0
        assertTrue("fewer than 10 000 ms of virtual time passed, got $elapsed",
            elapsed < 10_000L)
        loop.stop()
    }

    // --- A close during JoinPrivateChannel ends the wait at once too. -------

    @Test fun a_close_while_the_join_is_pending_reconnects_without_waiting_for_the_timeout() = runTest {
        val hubs = mutableListOf<FakeHub>()
        val block = CompletableDeferred<Unit>()
        val stats = TransportStats()
        val loop = buildLoop(
            hubBuild = {
                val hub = FakeHub()
                if (hubs.isEmpty()) {
                    hub.onInvoke = { method, _ ->
                        if (method == "JoinPrivateChannel") block.await()
                    }
                }
                hubs.add(hub)
                hub
            },
            stats = stats,
        )
        val t0 = currentTime
        loop.start(this)
        advanceUntilIdle()
        assertEquals("first hub built, start() done, join suspended", 1, hubs.size)

        hubs[0].fireClose(RuntimeException("close during join"))
        advanceTimeBy(1_000L)
        advanceUntilIdle()

        assertEquals("second hub built after the close during the join", 2, hubs.size)
        val elapsed = currentTime - t0
        assertTrue("fewer than 10 000 ms of virtual time passed, got $elapsed",
            elapsed < 10_000L)
        loop.stop()
    }

    // --- Guarantee: an ancestor scope cancel unwinds the loop even from
    // inside the try body, and no more reconnects occur. --------------------

    @Test fun scope_cancellation_unwinds_the_loop_cleanly() = runTest {
        val hubs = mutableListOf<FakeHub>()
        val stats = TransportStats()
        val loop = buildLoop(
            hubBuild = { FakeHub().also { hubs.add(it) } },
            stats = stats,
        )
        loop.start(this)
        advanceUntilIdle()
        assertEquals(1, hubs.size)
        assertNotNull("closed handler was registered", hubs[0].closedHandler)

        loop.stop()
        advanceTimeBy(30_000L)
        advanceUntilIdle()

        assertEquals("no further hubs after stop", 1, hubs.size)
        assertEquals("disconnected", stats.socketState)
    }
}
