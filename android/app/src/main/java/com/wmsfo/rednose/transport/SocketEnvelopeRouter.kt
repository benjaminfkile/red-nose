package com.wmsfo.rednose.transport

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.wmsfo.rednose.log.RingLog

// Routes the `ChannelEvent` envelopes SignalR delivers into the socket loop.
// The type parameter registered with `HubConnection.on` is `com.google.gson.JsonElement`
// because the Java client deserializes handler arguments with Gson; a sealed
// kotlinx.serialization tree dropped every envelope before it reached the loop.
// The router owns no state of its own; it walks the Gson tree and hands off to
// the two side-effect callbacks the socket loop wires in.
class SocketEnvelopeRouter(
    private val stats: TransportStats,
    private val log: RingLog,
    private val onAuthExpired: () -> Unit,
    private val onServiceRemoved: () -> Unit,
) {
    fun onEnvelope(env: JsonElement) {
        if (env !is JsonObject) return
        val event = stringField(env, "event") ?: return
        when (event) {
            "joined" -> stats.socketState = "connected"
            "channelEvicted" -> {
                val reason = stringField(env, "reason")
                    ?: (env.get("data") as? JsonObject)?.let { stringField(it, "reason") }
                log.socket("evicted ${reason ?: "unknown"}")
                when (reason) {
                    "auth_expired" -> onAuthExpired()
                    "service_removed" -> onServiceRemoved()
                    else -> Unit
                }
            }
            else -> Unit
        }
    }

    private fun stringField(obj: JsonObject, key: String): String? {
        val el = obj.get(key) ?: return null
        if (!el.isJsonPrimitive) return null
        val prim = el.asJsonPrimitive
        return if (prim.isString) prim.asString else null
    }
}
