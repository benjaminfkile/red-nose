package com.wmsfo.rednose.transport

import com.wmsfo.rednose.telemetry.TransportGroup

// The debug.transport half of the heartbeat body (contracts 4.2; red-nose.md 8).
// Every field is mutated only on the beacon dispatcher.  `socketState` feeds
// the typed `health` group of the heartbeat and stays on this class so the
// TelemetryCollector reads a consistent value.
class TransportStats {
    @Volatile var socketState: String = "disconnected"
    @Volatile var reconnectCount: Int = 0
    @Volatile var rejoinCount: Int = 0
    @Volatile var httpFallbackSeconds: Int = 0
    @Volatile var lastReceiptLatencyMs: Long? = null
    @Volatile var sendsFailedSinceBoot: Int = 0

    fun snapshot(): TransportGroup = TransportGroup(
        reconnectCount = reconnectCount,
        rejoinCount = rejoinCount,
        httpFallbackSeconds = httpFallbackSeconds,
        lastReceiptLatencyMs = lastReceiptLatencyMs,
        sendsFailedSinceBoot = sendsFailedSinceBoot,
    )
}
