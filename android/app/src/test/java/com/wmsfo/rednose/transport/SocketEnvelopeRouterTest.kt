package com.wmsfo.rednose.transport

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.wmsfo.rednose.log.RingLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// The SignalR Java client deserializes handler arguments with Gson: a kotlinx
// serialization sealed JsonElement was silently dropping every envelope before
// the loop ever saw it (contracts 2.3, 9.2).  The router walks a Gson tree and
// dispatches to the two side-effect callbacks the socket loop wires in.
class SocketEnvelopeRouterTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var log: RingLog
    private lateinit var stats: TransportStats
    private var rejoinCalls = 0
    private var serviceRemovedCalls = 0

    @Before fun setUp() {
        log = RingLog(tmp.root, 8_192)
        stats = TransportStats()
        rejoinCalls = 0
        serviceRemovedCalls = 0
    }

    private fun router(): SocketEnvelopeRouter = SocketEnvelopeRouter(
        stats = stats,
        log = log,
        ingestChannel = "wmsfo-api-dev:ingest",
        onAuthExpired = { rejoinCalls++ },
        onServiceRemoved = { serviceRemovedCalls++ },
    )

    @Test fun joined_marks_socket_state_connected() {
        stats.socketState = "connecting"
        val env = JsonObject().apply {
            addProperty("channel", "wmsfo-api-dev:ingest")
            addProperty("event", "joined")
        }
        router().onEnvelope(env)
        assertEquals("connected", stats.socketState)
        assertEquals(0, rejoinCalls)
    }

    @Test fun channel_evicted_auth_expired_triggers_rejoin() {
        val env = JsonObject().apply {
            addProperty("channel", "wmsfo-api-dev:ingest")
            addProperty("event", "channelEvicted")
            addProperty("reason", "auth_expired")
        }
        router().onEnvelope(env)
        assertEquals(1, rejoinCalls)
        assertEquals(0, serviceRemovedCalls)
        val recent = log.recent(50)
        assertTrue("expected an eviction log line, got: $recent",
            recent.any { it.contains("socket evicted auth_expired") })
    }

    @Test fun channel_evicted_reason_can_ride_in_data() {
        val env = JsonObject().apply {
            addProperty("channel", "wmsfo-api-dev:ingest")
            addProperty("event", "channelEvicted")
            add("data", JsonObject().apply { addProperty("reason", "auth_expired") })
        }
        router().onEnvelope(env)
        assertEquals(1, rejoinCalls)
    }

    @Test fun channel_evicted_service_removed_takes_close_branch() {
        val env = JsonObject().apply {
            addProperty("channel", "wmsfo-api-dev:ingest")
            addProperty("event", "channelEvicted")
            addProperty("reason", "service_removed")
        }
        router().onEnvelope(env)
        assertEquals(0, rejoinCalls)
        assertEquals(1, serviceRemovedCalls)
    }

    @Test fun a_foreign_event_is_ignored() {
        stats.socketState = "connecting"
        val env = JsonObject().apply {
            addProperty("channel", "wmsfo-api-dev:ingest")
            addProperty("event", "someOtherEvent")
        }
        router().onEnvelope(env)
        assertEquals("connecting", stats.socketState)
        assertEquals(0, rejoinCalls)
        assertEquals(0, serviceRemovedCalls)
    }

    @Test fun missing_event_field_is_ignored() {
        val env = JsonObject().apply { addProperty("channel", "x") }
        router().onEnvelope(env)
        assertEquals(0, rejoinCalls)
        assertEquals(0, serviceRemovedCalls)
    }

    @Test fun non_object_envelope_is_ignored() {
        router().onEnvelope(JsonPrimitive("nope"))
        router().onEnvelope(JsonNull.INSTANCE)
        assertEquals(0, rejoinCalls)
        assertEquals(0, serviceRemovedCalls)
    }

    @Test fun evicted_without_reason_logs_unknown_and_does_nothing() {
        val env = JsonObject().apply { addProperty("event", "channelEvicted") }
        router().onEnvelope(env)
        assertEquals(0, rejoinCalls)
        assertEquals(0, serviceRemovedCalls)
        val recent = log.recent(50)
        assertTrue(recent.any { it.contains("socket evicted unknown") })
    }
}
