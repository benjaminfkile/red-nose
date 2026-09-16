package com.wmsfo.rednose.transport

import com.google.gson.JsonElement
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.TransportEnum
import kotlinx.coroutines.rx3.await

// SignalR Java client wrapper.  WebSockets only, negotiation skipped, keep-alive 15 s,
// server-timeout 30 s (red-nose.md 2, 7.1, 7.4; contracts 2.2, 14).
object HubClient {
    const val KEEP_ALIVE_INTERVAL_MS: Long = 15_000L
    const val SERVER_TIMEOUT_MS: Long = 30_000L

    fun build(hubUrl: String): HubTransport {
        val conn: HubConnection = HubConnectionBuilder
            .create(hubUrl)
            .withTransport(TransportEnum.WEBSOCKETS)
            .shouldSkipNegotiate(true)
            .withKeepAliveInterval(KEEP_ALIVE_INTERVAL_MS)
            .withServerTimeout(SERVER_TIMEOUT_MS)
            .build()
        return HubConnectionTransport(conn)
    }
}

// Adapter over `com.microsoft.signalr.HubConnection`.  The Java client's handler
// arguments are deserialized with Gson, so ChannelEvent is registered with
// `com.google.gson.JsonElement` as its argument type (red-nose.md 7.4).
private class HubConnectionTransport(private val conn: HubConnection) : HubTransport {
    override fun onChannelEvent(handler: (JsonElement) -> Unit) {
        conn.on("ChannelEvent", handler, JsonElement::class.java)
    }

    override fun onClosed(handler: (Throwable?) -> Unit) {
        conn.onClosed { cause -> handler(cause) }
    }

    override suspend fun start() {
        conn.start().await()
    }

    override suspend fun invoke(method: String, vararg args: Any?) {
        // The Completable overload completes on the server's completion message;
        // the Single<T> overload never does (it cannot emit a null result), so it
        // would time out on every void hub method.
        conn.invoke(method, *args).await()
    }

    override fun invokeFireAndForget(
        method: String,
        args: Array<Any?>,
        onSuccess: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        conn.invoke(method, *args).subscribe({ onSuccess() }, { t -> onError(t) })
    }

    override fun stop() {
        conn.stop()
    }
}
