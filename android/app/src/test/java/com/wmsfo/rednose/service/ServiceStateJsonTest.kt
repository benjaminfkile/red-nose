package com.wmsfo.rednose.service

import com.wmsfo.rednose.location.FixTime
import com.wmsfo.rednose.location.LatestFix
import com.wmsfo.rednose.log.BeaconJson
import com.wmsfo.rednose.store.EnrollmentPublic
import com.wmsfo.rednose.telemetry.Heartbeat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// red-nose.md 4.3: the state's fields exactly, including keyPrefix (never the key),
// nulls preserved, and no raw key ever appears anywhere in the JSON.
class ServiceStateJsonTest {

    private val expectedFields = setOf(
        "serviceRunning", "serviceStartedAt", "enrollment", "socketState", "revoked",
        "liveEventId", "isActive", "lastHeartbeatAcceptedAt", "lastHeartbeatError",
        "clockSkewMs", "latestFix", "lastDeliveredSeqLocal", "lastReceiptLatencyMs",
        "lastSendError", "telemetry", "replay", "replayAllowed", "checklist", "appVersion",
    )

    @Test fun every_field_of_section_4_3_is_present() {
        val json = BeaconJson.encodeToString(sampleState())
        val obj = BeaconJson.parseToJsonElement(json).jsonObject
        for (f in expectedFields) assertTrue("missing field: $f", obj.containsKey(f))
        // No stray keys were added.
        assertEquals(expectedFields, obj.keys)
    }

    @Test fun nulls_are_preserved_when_absent() {
        val json = BeaconJson.encodeToString(sampleState())
        val obj = BeaconJson.parseToJsonElement(json).jsonObject
        assertNull(obj["liveEventId"]?.jsonPrimitive?.contentOrNull)
        assertNull(obj["isActive"]?.jsonPrimitive?.contentOrNull)
        assertNull(obj["lastHeartbeatAcceptedAt"]?.jsonPrimitive?.contentOrNull)
        assertNull(obj["clockSkewMs"]?.jsonPrimitive?.contentOrNull)
        assertNull(obj["replay"]?.jsonPrimitive?.contentOrNull)
    }

    @Test fun key_never_appears_in_state_json() {
        val json = BeaconJson.encodeToString(sampleState())
        assertTrue("raw key must never appear in ServiceState",
            !json.contains("SECRET_KEY_MUST_NOT_LEAK"))
        val obj = BeaconJson.parseToJsonElement(json).jsonObject
        val enroll = obj["enrollment"]!!.jsonObject
        // The enrollment object exposes keyPrefix, not `key`.
        assertTrue(enroll.containsKey("keyPrefix"))
        assertTrue(!enroll.containsKey("key"))
        assertEquals("wbk_abcdefgh", enroll["keyPrefix"]?.jsonPrimitive?.contentOrNull)
    }

    private fun sampleState(): ServiceState = ServiceState(
        serviceRunning = true,
        serviceStartedAt = FixTime.rfc3339(1_734_830_000_000L),
        enrollment = EnrollmentPublic(
            beaconId = 7L,
            name = "Helicopter",
            // First 12 chars of the fake full key "wbk_abcdefghSECRET_KEY_MUST_NOT_LEAK".
            keyPrefix = "wbk_abcdefgh",
            apiBaseUrl = "https://api.example",
            hubUrl = "wss://gw.example/hub",
            ingestChannel = "wmsfo-api-dev:ingest",
            gpsOnlyFallback = false,
        ),
        socketState = "connected",
        revoked = false,
        latestFix = LatestFix(
            lat = 46.87, lng = -114.0,
            recordedAt = FixTime.rfc3339(1_734_830_001_000L),
            seqLocal = 3L,
        ),
        lastDeliveredSeqLocal = 3L,
        lastReceiptLatencyMs = 42L,
        telemetry = Heartbeat(sentAt = FixTime.rfc3339(1_734_830_000_500L)),
        replay = null,
        replayAllowed = true,
        checklist = Checklist(serviceRunning = true),
        appVersion = "0.1.0",
    )
}
