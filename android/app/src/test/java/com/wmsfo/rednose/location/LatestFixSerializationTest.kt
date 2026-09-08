package com.wmsfo.rednose.location

import com.wmsfo.rednose.log.BeaconJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// red-nose.md 7.9: absent optional fix fields serialize as JSON `null`;
// recordedAt is three fractional digits and `Z`.
class LatestFixSerializationTest {

    @Test fun recorded_at_three_fractional_digits_and_z() {
        // 1734830000123 ms = 2024-12-22T01:13:20.123Z (UTC).
        val stamp = FixTime.rfc3339(1_734_830_000_123L)
        assertEquals("2024-12-22T01:13:20.123Z", stamp)
    }

    @Test fun optional_fields_serialize_as_null_when_absent() {
        val fix = LatestFix(
            lat = 46.87, lng = -114.0,
            recordedAt = FixTime.rfc3339(1_734_830_000_123L),
            seqLocal = 42L,
        )
        val json = BeaconJson.encodeToString(fix.toPayload())
        val obj = BeaconJson.parseToJsonElement(json).jsonObject
        // Every optional field key is present with a null value (explicitNulls = true).
        assertTrue("speedMps must be present", obj.containsKey("speedMps"))
        assertTrue("altitudeM must be present", obj.containsKey("altitudeM"))
        assertTrue("headingDeg must be present", obj.containsKey("headingDeg"))
        assertTrue("accuracyM must be present", obj.containsKey("accuracyM"))
        assertNull(obj["speedMps"]?.jsonPrimitive?.contentOrNull)
        assertNull(obj["altitudeM"]?.jsonPrimitive?.contentOrNull)
        assertNull(obj["headingDeg"]?.jsonPrimitive?.contentOrNull)
        assertNull(obj["accuracyM"]?.jsonPrimitive?.contentOrNull)
        // recordedAt uses three fractional digits and Z (contracts 0.2).
        val stamp = obj["recordedAt"]?.jsonPrimitive?.contentOrNull
        assertNotNull(stamp); assertTrue(stamp!!.endsWith("Z"))
        // The wire body carries no seqLocal (that field is beacon-internal).
        assertTrue(!obj.containsKey("seqLocal"))
    }

    @Test fun optional_fields_round_trip_when_present() {
        val fix = LatestFix(
            lat = 46.87, lng = -114.0,
            recordedAt = FixTime.rfc3339(1_734_830_000_123L),
            speedMps = 31.2, altitudeM = 1210.0, headingDeg = 84.0, accuracyM = 6.0,
            seqLocal = 1L,
        )
        val json = BeaconJson.encodeToString(fix.toPayload())
        val obj = BeaconJson.parseToJsonElement(json).jsonObject
        assertEquals("31.2", obj["speedMps"]?.jsonPrimitive?.contentOrNull)
        assertEquals("1210.0", obj["altitudeM"]?.jsonPrimitive?.contentOrNull)
        assertEquals("84.0", obj["headingDeg"]?.jsonPrimitive?.contentOrNull)
        assertEquals("6.0", obj["accuracyM"]?.jsonPrimitive?.contentOrNull)
    }
}
