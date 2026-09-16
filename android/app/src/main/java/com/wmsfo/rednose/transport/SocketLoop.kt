package com.wmsfo.rednose.transport

import com.wmsfo.rednose.location.LatestFix
import com.wmsfo.rednose.location.toPayload
import com.wmsfo.rednose.log.RingLog
import com.wmsfo.rednose.store.Enrollment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

// The socket loop of red-nose.md 7.4 / contracts 9.2.  One HubTransport at a time.
// The loop reconnects indefinitely: every branch that ends an iteration (a close
// received, a build or start or join failure, any Throwable escaping the try) is
// caught inside the `while (isActive)` body so cancellation is the only way out.
class SocketLoop(
    private val enrollment: Enrollment,
    private val stats: TransportStats,
    private val sendLoop: SendLoop,
    private val retryNow: ReceiveChannel<Unit>,
    private val log: RingLog,
    private val hubBuild: (String) -> HubTransport = HubClient::build,
    private val backoff: (Int) -> Long = { Backoff.delayMs(it).toLong() },
) : SendLoop.HubSender {

    @Volatile private var connection: HubTransport? = null
    @Volatile private var attempt: Int = 0
    // One close channel per connection (red-nose.md 7.4): the loop's own stop()
    // of a finished connection fires onClosed too, and a channel shared across
    // connections would hand that stale close to the next one the moment it joined.
    @Volatile private var closedSignal: Channel<Throwable?> = Channel(capacity = Channel.CONFLATED)
    private var job: Job? = null

    private val router = SocketEnvelopeRouter(
        stats = stats,
        log = log,
        onAuthExpired = ::rejoin,
        onServiceRemoved = { closedSignal.trySend(RuntimeException("service_removed")) },
    )

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                stats.socketState = if (attempt == 0) "connecting" else "reconnecting"
                var conn: HubTransport? = null
                val closed = Channel<Throwable?>(capacity = Channel.CONFLATED)
                closedSignal = closed
                try {
                    // hubBuild runs inside the try so a build failure (bad URL
                    // or resource issue in the SignalR builder) does not kill
                    // the loop.
                    conn = hubBuild(enrollment.hubUrl)
                    conn.onChannelEvent(router::onEnvelope)
                    conn.onClosed { cause -> closed.trySend(cause) }
                    withTimeout(10_000) { conn.start() }
                    withTimeout(10_000) {
                        conn.invoke("JoinPrivateChannel",
                            enrollment.ingestChannel, enrollment.key)
                    }
                    attempt = 0
                    stats.socketState = "connected"
                    connection = conn
                    sendLoop.kick()
                    val cause = closed.receive()
                    log.socket("closed", cause)
                } catch (t: Throwable) {
                    // Catches Throwable so an Error or non-Exception Throwable
                    // cannot escape and kill the coroutine (red-nose.md 7.4).
                    // ensureActive rethrows only if the outer job itself is
                    // being cancelled (stop() or scope teardown); a
                    // TimeoutCancellationException from withTimeout leaves the
                    // outer job active, so the loop falls through and backs off
                    // like any other iteration failure.
                    coroutineContext.ensureActive()
                    log.socket("join or start failed", t)
                    if (t is Exception && t.isJoinDenied()) {
                        delay(10_000)
                    }
                } finally {
                    connection = null
                    try { conn?.stop() } catch (_: Throwable) {}
                }
                stats.socketState = "reconnecting"
                stats.reconnectCount = stats.reconnectCount + 1
                val wait = backoff(attempt)
                attempt = attempt + 1
                withTimeoutOrNull(wait) { retryNow.receive() }
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        try { connection?.stop() } catch (_: Throwable) {}
        connection = null
        stats.socketState = "disconnected"
    }

    override suspend fun sendToChannel(channel: String, fix: LatestFix): Boolean {
        val conn = connection ?: return false
        // The payload goes as an object, serialized by the client into the invocation
        // arguments, so the gateway forwards `data` as the location body itself. A
        // pre-encoded string would arrive as a JSON string and fail the API's validation.
        return try {
            withTimeout(10_000) {
                conn.invoke("SendToChannel", channel, "location", fix.toPayload())
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    // Send-loop hook: three consecutive hub_rejected outcomes while socketState is
    // "connected" ask for a re-join on the same path as `auth_expired` (contracts 9.2).
    override fun requestRejoin() {
        rejoin()
    }

    // Re-invoke JoinPrivateChannel on the current connection. Fire-and-forget: the
    // callbacks log the outcome and, on failure, drive the outer loop into its
    // close-or-failure branch (reconnecting, backoff).
    private fun rejoin() {
        stats.rejoinCount = stats.rejoinCount + 1
        val conn = connection
        if (conn == null) {
            log.socket("rejoin failed no_connection")
            closedSignal.trySend(RuntimeException("no_connection"))
            return
        }
        try {
            conn.invokeFireAndForget(
                method = "JoinPrivateChannel",
                args = arrayOf(enrollment.ingestChannel, enrollment.key),
                onSuccess = {
                    log.socket("rejoined")
                    sendLoop.kick()
                },
                onError = { t ->
                    log.socket("rejoin failed", t)
                    closedSignal.trySend(t)
                },
            )
        } catch (t: Throwable) {
            log.socket("rejoin failed", t)
            closedSignal.trySend(t)
        }
    }

    private fun Exception.isJoinDenied(): Boolean {
        val m = message?.lowercase() ?: return false
        return m.contains("denied") || m.contains("join denied") || m.contains("forbidden")
    }
}
