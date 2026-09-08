package com.wmsfo.rednose.log

import kotlinx.serialization.json.Json

// The one kotlinx.serialization instance every body uses (red-nose.md 7.9).
val BeaconJson: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
}
