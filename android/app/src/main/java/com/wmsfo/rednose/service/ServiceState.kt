package com.wmsfo.rednose.service

import com.wmsfo.rednose.location.LatestFix
import com.wmsfo.rednose.store.EnrollmentPublic
import com.wmsfo.rednose.telemetry.Heartbeat
import kotlinx.serialization.Serializable

// The JSON shape shipped to JS every second (red-nose.md 4.3).
// The raw key never appears here; only the enrollment's key prefix does.
@Serializable
data class ServiceState(
    val serviceRunning: Boolean,
    val serviceStartedAt: String? = null,
    val enrollment: EnrollmentPublic? = null,
    val socketState: String,
    val revoked: Boolean = false,
    val liveEventId: Long? = null,
    val isActive: Boolean? = null,
    val lastHeartbeatAcceptedAt: String? = null,
    val lastHeartbeatError: String? = null,
    val clockSkewMs: Long? = null,
    val latestFix: LatestFix? = null,
    val lastDeliveredSeqLocal: Long? = null,
    val lastReceiptLatencyMs: Long? = null,
    val lastSendError: String? = null,
    val telemetry: Heartbeat,
    val replay: ReplayStatus? = null,
    val replayAllowed: Boolean,
    val checklist: Checklist,
    val appVersion: String,
)

@Serializable
data class ReplayStatus(
    val running: Boolean,
    val source: String,
    val index: Int,
    val total: Int,
    val ratePerSecond: Int,
)

// The checklist of red-nose.md 13.  This R1 pass carries the shape; the probes
// wire up in R2 alongside the JS Status screen.
@Serializable
data class Checklist(
    val fineLocation: Boolean = false,
    val backgroundLocation: Boolean = false,
    val preciseLocation: Boolean = false,
    val notifications: Boolean = false,
    val batteryOptimizationExempt: Boolean = false,
    val locationServicesOn: Boolean = false,
    val playServices: Boolean = false,
    val phoneState: Boolean = false,
    val systemApp: Boolean = false,
    val rootAvailable: Boolean = false,
    val launcher: Boolean = false,
    val serviceRunning: Boolean = false,
)
