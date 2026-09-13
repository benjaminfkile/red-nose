package com.wmsfo.rednose.transport

import com.google.gson.JsonElement
import com.microsoft.signalr.HubConnection
import com.wmsfo.rednose.location.LatestFix
import com.wmsfo.rednose.location.toPayload
import com.wmsfo.rednose.log.RingLog
import com.wmsfo.rednose.store.Enrollment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

// The socket loop of red-nose.md 7.4 / contracts 9.2.  One HubConnection at a time.
// Never blocks the dispatcher (await() from kotlinx-coroutines-rx3, never blockingAwait).
class SocketLoop(
    private val enrollment: Enrollment,
    private val stats: TransportStats,
    private val sendLoop: SendLoop,
    private val connectivity: Connectivity,
    private val log: RingLog,
    private val hubBuild: (String) -> HubConnection = HubClient::build,
    private val backoff: (Int) -> Long = { Backoff.delayMs(it).toLong() },
) : SendLoop.HubSender {

    @Volatile private var connection: HubConnection? = null
    @Volatile private var attempt: Int = 0
    private val closedSignal: Channel<Throwable?> = Channel(capacity = Channel.CONFLATED)
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
                val conn = hubBuild(enrollment.hubUrl)
                try {
                    // The Java SignalR client deserializes handler arguments with Gson;
                    // registering the kotlinx.serialization sealed JsonElement dropped
                    // every envelope silently.  Gson builds `com.google.gson.JsonElement`.
                    conn.on("ChannelEvent", router::onEnvelope, JsonElement::class.java)
                    conn.onClosed { cause -> closedSignal.trySend(cause) }
                    withTimeout(10_000) { conn.start().await() }
                    // The hub methods return void. The Completable overload completes on the
                    // server's completion message; the Single<T> overload never does (it cannot
                    // emit a null result), so it would time out on every join and send.
                    withTimeout(10_000) {
                        conn.invoke("JoinPrivateChannel",
                            enrollment.ingestChannel, enrollment.key).await()
                    }
                    attempt = 0
                    stats.socketState = "connected"
                    connection = conn
                    sendLoop.kick()
                    val cause = closedSignal.receive()
                    log.socket("closed", cause)
                } catch (e: Exception) {
                    log.socket("join or start failed", e)
                    if (e.isJoinDenied()) {
                        delay(10_000)
                    }
                } finally {
                    connection = null
                    try { conn.stop() } catch (_: Throwable) {}
                }
                stats.socketState = "reconnecting"
                stats.reconnectCount = stats.reconnectCount + 1
                val wait = backoff(attempt)
                attempt = attempt + 1
                withTimeoutOrNull(wait) { connectivity.retryNow.receive() }
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
                conn.invoke("SendToChannel", channel, "location", fix.toPayload()).await()
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
    // subscribe callbacks log the outcome and, on failure, drive the outer loop
    // into its close-or-failure branch (reconnecting, backoff).
    private fun rejoin() {
        stats.rejoinCount = stats.rejoinCount + 1
        val conn = connection
        if (conn == null) {
            log.socket("rejoin failed no_connection")
            closedSignal.trySend(RuntimeException("no_connection"))
            return
        }
        try {
            conn.invoke("JoinPrivateChannel",
                enrollment.ingestChannel, enrollment.key).subscribe(
                {
                    log.socket("rejoined")
                    sendLoop.kick()
                },
                { t ->
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
