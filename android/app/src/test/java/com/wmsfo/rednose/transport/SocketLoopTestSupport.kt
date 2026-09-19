package com.wmsfo.rednose.transport

import com.google.gson.JsonElement
import com.wmsfo.rednose.location.LatestFix
import com.wmsfo.rednose.log.RingLog
import com.wmsfo.rednose.store.Enrollment
import kotlinx.coroutines.channels.Channel

// The shared test seam every SocketLoop unit test builds through: an in-process
// FakeHub that stands in for the SignalR client, a minimal SendLoop the loop only
// kicks, and a buildLoop factory that wires them together against a fixed
// enrollment.  Kept internal so SocketLoopTest and ConformanceTest reach it
// without exposing anything outside the module.

internal val TEST_ENROLLMENT: Enrollment = Enrollment(
    apiBaseUrl = "https://example.invalid",
    hubUrl = "https://example.invalid/hub",
    ingestChannel = "wmsfo-api-dev:ingest",
    beaconId = 42L,
    name = "test-beacon",
    key = "test-key-longer-than-twelve-characters",
)

internal class FakeHub : HubTransport {
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

// A minimal SendLoop for SocketLoop's dependency: SocketLoop only calls
// sendLoop.kick(), which drops onto a conflated internal channel.  The send loop
// is never started, so its dispatcher never runs.
internal fun dummySendLoop(log: RingLog): SendLoop {
    val state = object : SendLoop.State {
        override var latestFix: LatestFix? = null
        override var lastDeliveredSeqLocal: Long? = null
        override var lastReceiptLatencyMs: Long? = null
        override var lastSendError: String? = null
        override var attempt: Int = 0
        override var inFlight: Boolean = false
        override var socketState: String = "connected"
        override var liveEventId: Long? = null
        override var ingestChannel: String = TEST_ENROLLMENT.ingestChannel
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

internal fun buildLoop(
    log: RingLog,
    hubBuild: (String) -> HubTransport,
    stats: TransportStats = TransportStats(),
    retryNow: Channel<Unit> = Channel(capacity = Channel.CONFLATED),
    backoff: (Int) -> Long = { Backoff.delayMs(it).toLong() },
    enrollment: Enrollment = TEST_ENROLLMENT,
): SocketLoop = SocketLoop(
    enrollment = enrollment,
    stats = stats,
    sendLoop = dummySendLoop(log),
    retryNow = retryNow,
    log = log,
    hubBuild = hubBuild,
    backoff = backoff,
)
