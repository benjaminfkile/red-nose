package com.wmsfo.rednose.transport

import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.TransportEnum

// SignalR Java client wrapper.  WebSockets only, negotiation skipped, keep-alive 15 s,
// server-timeout 30 s (red-nose.md 2, 7.1, 7.4; contracts 2.2, 14).
object HubClient {
    const val KEEP_ALIVE_INTERVAL_MS: Long = 15_000L
    const val SERVER_TIMEOUT_MS: Long = 30_000L

    fun build(hubUrl: String): HubConnection = HubConnectionBuilder
        .create(hubUrl)
        .withTransport(TransportEnum.WEBSOCKETS)
        .shouldSkipNegotiate(true)
        .withKeepAliveInterval(KEEP_ALIVE_INTERVAL_MS)
        .withServerTimeout(SERVER_TIMEOUT_MS)
        .build()
}
