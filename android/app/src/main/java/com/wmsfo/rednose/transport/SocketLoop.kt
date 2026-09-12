package com.wmsfo.rednose.transport

import com.microsoft.signalr.HubConnection
import com.wmsfo.rednose.location.LatestFix
import com.wmsfo.rednose.location.toPayload
import com.wmsfo.rednose.log.BeaconJson
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

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

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                stats.socketState = if (attempt == 0) "connecting" else "reconnecting"
                val conn = hubBuild(enrollment.hubUrl)
                try {
                    conn.on("ChannelEvent", ::onEnvelope, JsonElement::class.java)
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
        val payload = BeaconJson.encodeToString(fix.toPayload())
        return try {
            withTimeout(10_000) {
                conn.invoke("SendToChannel", channel, "location", payload).await()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun onEnvelope(env: JsonElement) {
        val obj = try { env.jsonObject } catch (_: Throwable) { return }
        val event = obj["event"]?.jsonPrimitive?.contentOrNull ?: return
        when (event) {
            "joined" -> stats.socketState = "connected"
            "channelEvicted" -> {
                val reason = obj["reason"]?.jsonPrimitive?.contentOrNull
                    ?: (obj["data"]?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull)
                onEviction(reason)
            }
            else -> Unit
        }
    }

    private fun onEviction(reason: String?) {
        when (reason) {
            "auth_expired" -> {
                val conn = connection ?: return
                // Re-invoke JoinPrivateChannel immediately and kick the send loop; on
                // failure this closes the connection and takes the failure branch.
                try {
                    conn.invoke("JoinPrivateChannel",
                        enrollment.ingestChannel, enrollment.key).subscribe(
                        { sendLoop.kick() },
                        { closedSignal.trySend(it) },
                    )
                } catch (t: Throwable) {
                    closedSignal.trySend(t)
                }
            }
            "service_removed" -> {
                // The socket loop's normal 5 s retry is achieved by driving the
                // connection closed here; the outer loop will apply backoff.
                closedSignal.trySend(RuntimeException("service_removed"))
            }
            else -> Unit
        }
    }

    private fun Exception.isJoinDenied(): Boolean {
        val m = message?.lowercase() ?: return false
        return m.contains("denied") || m.contains("join denied") || m.contains("forbidden")
    }
}
