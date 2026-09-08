package com.wmsfo.rednose.replay

import kotlinx.serialization.Serializable

// Route object of contracts 1.4: schemaVersion, name, points[{lat,lng,recordedAt}].
@Serializable
data class Route(
    val schemaVersion: Int,
    val name: String,
    val points: List<Point>,
) {
    @Serializable
    data class Point(
        val lat: Double,
        val lng: Double,
        val recordedAt: String,
    )
}
