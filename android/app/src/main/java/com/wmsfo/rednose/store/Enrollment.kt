package com.wmsfo.rednose.store

import kotlinx.serialization.Serializable

// The enrolled beacon fields (red-nose.md 4.3, contracts 4.2 enrollment response
// plus gpsOnlyFallback from 8.5).  The raw `key` lives here in memory and on disk;
// ServiceState exposes only the 12-character prefix.
@Serializable
data class Enrollment(
    val apiBaseUrl: String,
    val hubUrl: String,
    val ingestChannel: String,
    val beaconId: Long,
    val name: String,
    val role: String,
    val key: String,
    val gpsOnlyFallback: Boolean = false,
) {
    val keyPrefix: String get() = if (key.length >= 12) key.substring(0, 12) else key

    fun redacted(): EnrollmentPublic = EnrollmentPublic(
        beaconId = beaconId,
        name = name,
        role = role,
        keyPrefix = keyPrefix,
        apiBaseUrl = apiBaseUrl,
        hubUrl = hubUrl,
        ingestChannel = ingestChannel,
        gpsOnlyFallback = gpsOnlyFallback,
    )
}

@Serializable
data class EnrollmentPublic(
    val beaconId: Long,
    val name: String,
    val role: String,
    val keyPrefix: String,
    val apiBaseUrl: String,
    val hubUrl: String,
    val ingestChannel: String,
    val gpsOnlyFallback: Boolean,
)
