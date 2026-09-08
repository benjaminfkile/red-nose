package com.wmsfo.rednose.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicLong

// Default source: FusedLocationProviderClient at 1 Hz, high accuracy (red-nose.md 6.1).
class FusedFixSource(
    private val context: Context,
    private val looper: Looper,
) : FixSource {

    override val provider: String = "fused"
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val seq = AtomicLong(0L)
    private val _fixes = MutableSharedFlow<LatestFix>(
        replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val fixes: SharedFlow<LatestFix> = _fixes

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            _fixes.tryEmit(loc.toLatestFix(seq.incrementAndGet()))
        }
    }

    @SuppressLint("MissingPermission")
    override fun start() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L)
            .setWaitForAccurateLocation(false)
            .build()
        client.requestLocationUpdates(req, callback, looper)
    }

    override fun stop() {
        client.removeLocationUpdates(callback)
    }
}

internal fun Location.toLatestFix(seqLocal: Long): LatestFix = LatestFix(
    lat = latitude,
    lng = longitude,
    recordedAt = FixTime.rfc3339(time),
    speedMps = if (hasSpeed()) speed.toDouble() else null,
    altitudeM = if (hasAltitude()) altitude else null,
    headingDeg = if (hasBearing()) bearing.toDouble() else null,
    accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
    seqLocal = seqLocal,
)
