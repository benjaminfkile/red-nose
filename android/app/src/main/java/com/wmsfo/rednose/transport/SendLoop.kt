package com.wmsfo.rednose.transport

import com.wmsfo.rednose.location.LatestFix
import com.wmsfo.rednose.log.RingLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

// The send loop of red-nose.md 7.5, contracts 9.2.  Runs on the beacon dispatcher
// (no locking).  A rejection is a failed send; a fix arriving during an in-flight
// send replaces LatestFix and goes out when the attempt resolves; sendsFailedSinceBoot
// counts only failures while liveEventId is non-null; when the socket is up a hub
// rejection never falls back to HTTP.
class SendLoop(
    private val state: State,
    private val stats: TransportStats,
    private val hub: HubSender,
    private val rest: RestSender,
    private val log: RingLog,
    private val backoff: (Int) -> Long = { Backoff.delayMs(it).toLong() },
    private val elapsedRealtimeMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val fixIntervalMs: Long = 250L,
    private val httpFallbackIntervalMs: Long = 1_000L,
) {
    interface State {
        var latestFix: LatestFix?
        var lastDeliveredSeqLocal: Long?
        var lastReceiptLatencyMs: Long?
        var lastSendError: String?
        var attempt: Int
        var inFlight: Boolean
        // "connected" | "connecting" | "reconnecting" | "disconnected"
        var socketState: String
        var liveEventId: Long?
        var ingestChannel: String
    }

    interface HubSender {
        /** Resolves to true iff the invoke returned 2xx (contracts 9.2). */
        suspend fun sendToChannel(channel: String, fix: LatestFix): Boolean

        /**
         * Ask the socket loop to re-invoke `JoinPrivateChannel` on its current
         * connection (contracts 9.2). Called after three consecutive `hub_rejected`
         * outcomes while `socketState == connected`; if the re-join throws, the
         * socket loop takes its failure branch and the next attempts fall back to
         * HTTP until the socket is `connected` again.
         */
        fun requestRejoin()
    }

    interface RestSender {
        suspend fun postLocation(fix: LatestFix): PostResult
    }

    data class PostResult(
        val ok: Boolean,
        val serverTime: String?,
        val errorCode: String?,
        val requestId: String?,
    )

    private val kick: Channel<Unit> = Channel(capacity = Channel.CONFLATED)
    private val retryNow: Channel<Unit> = Channel(capacity = Channel.CONFLATED)
    private var job: Job? = null

    // Contracts 9.2: three consecutive hub_rejected outcomes while socketState is
    // "connected" ask the socket loop to re-join once; the counter resets on any
    // delivered send.
    private var consecutiveHubRejections: Int = 0

    // Contracts 9.2: while the socket is down an HTTP send starts no sooner than
    // httpFallbackIntervalMs after the previous HTTP send started. Null before
    // the first HTTP send.
    private var lastHttpSendStartedAt: Long? = null

    fun kick() { kick.trySend(Unit) }
    fun retryNow() { retryNow.trySend(Unit) }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                // Wake on kick, retry, or the fix interval; whichever fires first.
                select<Unit> {
                    kick.onReceive { }
                    retryNow.onReceive { }
                    onTimeout(fixIntervalMs) { }
                }
                attempt()
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    /** Runs one attempt on the caller's coroutine; visible for tests. */
    suspend fun attempt() {
        val fix0 = state.latestFix ?: return
        if (fix0.seqLocal == state.lastDeliveredSeqLocal) return
        if (state.inFlight) return

        // Decide the door.  If HTTP and the window has not elapsed yet, wait the
        // remainder as a plain delay (a kick during the wait only replaces
        // latestFix, it does not shorten the wait) and then re-decide from the
        // top: a socket that connected in the meantime takes the fix over the
        // hub, with no window.
        if (state.socketState != "connected") {
            val started = lastHttpSendStartedAt
            if (started != null) {
                val remaining = httpFallbackIntervalMs - (elapsedRealtimeMs() - started)
                if (remaining > 0L) delay(remaining)
            }
        }

        // Re-read latestFix after any wait: a kick during the wait only replaces
        // it (red-nose.md 7.5, contracts 9.2).
        val fix = state.latestFix ?: return
        if (fix.seqLocal == state.lastDeliveredSeqLocal) return

        state.inFlight = true
        val overHub = state.socketState == "connected"
        val t0 = elapsedRealtimeMs()
        if (!overHub) lastHttpSendStartedAt = t0
        val outcome: Outcome = try {
            if (overHub) {
                val ok = hub.sendToChannel(state.ingestChannel, fix)
                if (ok) Outcome.Delivered(elapsedRealtimeMs() - t0)
                else Outcome.Failed("hub_rejected", null)
            } else {
                val res = rest.postLocation(fix)
                if (res.ok) Outcome.Delivered(elapsedRealtimeMs() - t0)
                else Outcome.Failed(res.errorCode ?: "http_error", res.requestId)
            }
        } catch (e: Exception) {
            Outcome.Failed(e.javaClass.simpleName, null)
        } finally {
            state.inFlight = false
        }

        // Compare the sent fix against the current one; if it changed, keep the
        // current one and let the next attempt carry it (red-nose.md 7.5).
        val current = state.latestFix
        val fixReplaced = current == null || current.seqLocal != fix.seqLocal

        when (outcome) {
            is Outcome.Delivered -> {
                // The API accepted this seq: mark it delivered and reset attempt.
                state.lastDeliveredSeqLocal = fix.seqLocal
                state.lastReceiptLatencyMs = outcome.latencyMs
                stats.lastReceiptLatencyMs = outcome.latencyMs
                state.attempt = 0
                state.lastSendError = null
                consecutiveHubRejections = 0
                if (!overHub) {
                    // Accrue HTTP fallback seconds on TransportStats (red-nose.md 8):
                    // the heartbeat body's transport.* group is built from this field.
                    val secs = (outcome.latencyMs / 1000L).toInt().coerceAtLeast(1)
                    stats.httpFallbackSeconds = stats.httpFallbackSeconds + secs
                }
                // A fix that arrived during send is a fresh one and should go out next.
                if (fixReplaced) kick()
            }
            is Outcome.Failed -> {
                // Only count failures while liveEventId is non-null; before that log at
                // Debug once per minute and do not increment (red-nose.md 7.5).
                if (state.liveEventId != null) {
                    stats.sendsFailedSinceBoot = stats.sendsFailedSinceBoot + 1
                }
                state.lastSendError = if (outcome.requestId != null) "${outcome.code} ${outcome.requestId}"
                    else outcome.code
                log.send("failed seq=${fix.seqLocal} door=${if (overHub) "hub" else "http"} code=${outcome.code}" +
                    (outcome.requestId?.let { " requestId=$it" } ?: ""))
                if (overHub && outcome.code == "hub_rejected" && state.socketState == "connected") {
                    // Contracts 9.2: an unannounced loss of channel membership shows
                    // up as repeated `hub_rejected` while socketState stays connected.
                    // Ask the socket loop to re-join once; if the re-join throws, the
                    // socket loop takes its failure branch and this loop's next
                    // attempts go over HTTP until the socket is connected again.
                    consecutiveHubRejections = consecutiveHubRejections + 1
                    if (consecutiveHubRejections >= 3) {
                        consecutiveHubRejections = 0
                        try { hub.requestRejoin() } catch (t: Throwable) {
                            log.socket("rejoin request failed", t)
                        }
                    }
                }
                val next = backoff(state.attempt)
                state.attempt = state.attempt + 1
                // Wait the backoff or a kick/retry, whichever comes first.
                withTimeoutOrNull(next) {
                    select<Unit> {
                        kick.onReceive { }
                        retryNow.onReceive { }
                    }
                }
            }
        }
    }

    private sealed interface Outcome {
        data class Delivered(val latencyMs: Long) : Outcome
        data class Failed(val code: String, val requestId: String?) : Outcome
    }
}
