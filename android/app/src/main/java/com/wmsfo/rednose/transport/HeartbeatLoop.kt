package com.wmsfo.rednose.transport

import com.wmsfo.rednose.log.RingLog
import com.wmsfo.rednose.telemetry.Heartbeat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// HeartbeatLoop of red-nose.md 7.6: every REDNOSE_HEARTBEAT_INTERVAL_MS tick,
// POST /beacons/heartbeat over HTTP no matter the socket state.  A 401 sets
// revoked=true and changes nothing else; skew is
// clockSkewMs = serverTime - (tSend + (tReceive - tSend)/2).
class HeartbeatLoop(
    private val intervalMs: Long,
    private val build: suspend () -> Heartbeat,
    private val rest: RestClient,
    private val state: State,
    private val log: RingLog,
    private val parseRfc3339: (String) -> Long? = ::defaultParseRfc3339,
) {
    interface State {
        var liveEventId: Long?
        var isActive: Boolean?
        var clockSkewMs: Long?
        var revoked: Boolean
        var lastHeartbeatAcceptedAt: String?
        var lastHeartbeatError: String?
    }

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                tick()
                delay(intervalMs)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    suspend fun tick() {
        val body = build()
        val r = rest.postHeartbeat(body)
        if (r.ok) {
            state.liveEventId = r.liveEventId
            state.isActive = r.isActive
            state.lastHeartbeatAcceptedAt = r.receivedAt
            state.lastHeartbeatError = null
            val serverEpoch = r.serverTime?.let(parseRfc3339)
            state.clockSkewMs = if (serverEpoch != null) skew(serverEpoch, r.tSendMs, r.tReceiveMs) else state.clockSkewMs
        } else {
            if (r.code == 401) {
                state.revoked = true
                log.heartbeat("revoked (401)")
            } else {
                val err = r.errorCode ?: "http_${r.code}"
                state.lastHeartbeatError = if (r.requestId != null) "$err ${r.requestId}" else err
                log.heartbeat("failed code=${r.code} err=$err" + (r.requestId?.let { " requestId=$it" } ?: ""))
            }
        }
    }

    companion object {
        /**
         * `clockSkewMs = serverTime - (tSend + (tReceive - tSend) / 2)` (red-nose.md 7.6, 9.2).
         * Positive means the server is ahead of the phone.
         */
        fun skew(serverEpochMs: Long, tSendMs: Long, tReceiveMs: Long): Long {
            val midpoint = tSendMs + (tReceiveMs - tSendMs) / 2
            return serverEpochMs - midpoint
        }

        private fun defaultParseRfc3339(s: String): Long? = try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            fmt.parse(s)?.time
        } catch (_: Throwable) { null }
    }
}
