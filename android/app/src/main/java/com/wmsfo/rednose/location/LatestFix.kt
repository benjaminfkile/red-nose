package com.wmsfo.rednose.location

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// One 1 Hz GPS fix (red-nose.md 6.2, contracts 4.2 POST /locations).
// Optional fields absent from the provider serialize as JSON null (7.9).
@Serializable
data class LatestFix(
    val lat: Double,
    val lng: Double,
    val recordedAt: String,
    val speedMps: Double? = null,
    val altitudeM: Double? = null,
    val headingDeg: Double? = null,
    val accuracyM: Double? = null,
    val seqLocal: Long,
)

// The wire body for POST /locations (contracts 4.2): every field except seqLocal.
@Serializable
data class LocationPayload(
    val lat: Double,
    val lng: Double,
    val recordedAt: String,
    val speedMps: Double? = null,
    val altitudeM: Double? = null,
    val headingDeg: Double? = null,
    val accuracyM: Double? = null,
)

fun LatestFix.toPayload(): LocationPayload = LocationPayload(
    lat = lat, lng = lng, recordedAt = recordedAt,
    speedMps = speedMps, altitudeM = altitudeM,
    headingDeg = headingDeg, accuracyM = accuracyM,
)
