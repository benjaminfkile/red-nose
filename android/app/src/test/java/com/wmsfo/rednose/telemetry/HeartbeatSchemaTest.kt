package com.wmsfo.rednose.telemetry

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.wmsfo.rednose.log.BeaconJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// red-nose.md 8: the heartbeat body must validate against the vendored
// contracts/schema/heartbeat.schema.json (draft 2020-12).  The typed `health`
// core is filled from probes; `debug` holds the six groups verbatim.
class HeartbeatSchemaTest {

    private val schema: JsonSchema by lazy {
        val root = locateContractsRoot()
        val file = File(root, "schema/heartbeat.schema.json")
        assertTrue("heartbeat.schema.json must be reachable: ${file.absolutePath}", file.exists())
        val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        factory.getSchema(file.toURI())
    }

    private val mapper = ObjectMapper()

    @Test fun canonical_body_validates() {
        val body = Heartbeat(
            sentAt = "2026-12-22T01:31:07.000Z",
            health = HealthGroup(batteryPercent = 87, lastFixAgeS = 1, socketState = "connected"),
            debug = DebugGroup(
                power = PowerGroup(charging = true, batteryTempC = 31.5, thermalStatus = "none"),
                radio = RadioGroup(networkType = "LTE", signalDbm = -95, signalLevel = 3,
                    airplaneMode = false, connected = true),
                gps = GpsGroup(
                    provider = "fused", satellitesUsed = 9, satellitesInView = 14,
                    lastFixAccuracyM = 6.0, fixesLastMinute = 58,
                    permission = GpsPermissionGroup(foreground = true, background = true, precise = true),
                ),
                transport = TransportGroup(reconnectCount = 2, rejoinCount = 1,
                    httpFallbackSeconds = 0, lastReceiptLatencyMs = 120L,
                    sendsFailedSinceBoot = 3),
                process = ProcessGroup(deviceUptimeS = 90000L, serviceUptimeS = 3000L,
                    serviceRestartCount = 1, memoryPressure = "normal",
                    batteryOptimizationExempt = true, notificationPermission = true,
                    systemApp = true, rootAvailable = true),
                identity = IdentityGroup(deviceModel = "Pixel 6a", androidVersion = "14",
                    appVersion = "1.0.3", clockSkewMs = -120L),
            ),
        )
        val json = BeaconJson.encodeToString(body)
        val node = mapper.readTree(json)
        val errors = schema.validate(node)
        assertEquals("expected no schema errors, got: $errors", 0, errors.size)
    }

    @Test fun both_groups_null_validates() {
        val body = Heartbeat(
            sentAt = "2026-12-22T01:31:07.000Z",
            health = null,
            debug = null,
        )
        val json = BeaconJson.encodeToString(body)
        val node = mapper.readTree(json)
        val errors = schema.validate(node)
        assertEquals("expected no schema errors, got: $errors", 0, errors.size)
    }

    @Test fun unknown_top_level_key_rejected() {
        val json = """{"sentAt":"2026-12-22T01:31:07.000Z","unknown":1}"""
        val node = mapper.readTree(json)
        val errors = schema.validate(node)
        assertTrue("expected at least one schema error", errors.isNotEmpty())
    }

    // Locate the repository's `contracts/` directory from wherever Gradle placed the
    // test working directory (usually android/app).  Walks upward until it finds it.
    private fun locateContractsRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "contracts")
            if (File(candidate, "CONTRACTS_VERSION").isFile) return candidate
            dir = dir.parentFile
        }
        error("could not locate contracts/ starting from ${System.getProperty("user.dir")}")
    }
}
