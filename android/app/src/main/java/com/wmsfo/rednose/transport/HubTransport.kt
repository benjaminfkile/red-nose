package com.wmsfo.rednose.transport

import com.google.gson.JsonElement

// The minimal surface the socket loop needs from a hub connection.  The production
// implementation lives in HubClient and wraps the SignalR Java client's
// `com.microsoft.signalr.HubConnection`.
interface HubTransport {
    fun onChannelEvent(handler: (JsonElement) -> Unit)
    fun onClosed(handler: (Throwable?) -> Unit)
    // Both suspend calls throw on failure; the loop wraps them in a 10 s timeout.
    suspend fun start()
    suspend fun invoke(method: String, vararg args: Any?)
    // Fire-and-forget: the callbacks fire on whatever thread the client uses.
    fun invokeFireAndForget(method: String, args: Array<Any?>, onSuccess: () -> Unit, onError: (Throwable) -> Unit)
    fun stop()
}
