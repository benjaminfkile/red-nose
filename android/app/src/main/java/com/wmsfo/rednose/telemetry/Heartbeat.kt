package com.wmsfo.rednose.telemetry

import kotlinx.serialization.Serializable

// The POST /beacons/heartbeat body (contracts 4.2; red-nose.md 8): sentAt, a typed
// `health` core the API reads, and a free `debug` object the panel renders as a
// JSON tree.  Every leaf is nullable (a failing probe leaves its group's fields null).
@Serializable
data class Heartbeat(
    val sentAt: String,
    val health: HealthGroup? = null,
    val debug: DebugGroup? = null,
)

@Serializable
data class HealthGroup(
    val batteryPercent: Int? = null,
    val lastFixAgeS: Int? = null,
    val socketState: String? = null,
)

@Serializable
data class DebugGroup(
    val power: PowerGroup? = null,
    val radio: RadioGroup? = null,
    val gps: GpsGroup? = null,
    val transport: TransportGroup? = null,
    val process: ProcessGroup? = null,
    val identity: IdentityGroup? = null,
    val guard: GuardGroup? = null,
)

@Serializable
data class PowerGroup(
    val charging: Boolean? = null,
    val batteryTempC: Double? = null,
    val thermalStatus: String? = null,
)

@Serializable
data class RadioGroup(
    val networkType: String? = null,
    val signalDbm: Int? = null,
    val signalLevel: Int? = null,
    val airplaneMode: Boolean? = null,
    val connected: Boolean? = null,
)

@Serializable
data class GpsPermissionGroup(
    val foreground: Boolean? = null,
    val background: Boolean? = null,
    val precise: Boolean? = null,
)

@Serializable
data class GpsGroup(
    val provider: String? = null,
    val satellitesUsed: Int? = null,
    val satellitesInView: Int? = null,
    val lastFixAccuracyM: Double? = null,
    val fixesLastMinute: Int? = null,
    val permission: GpsPermissionGroup? = null,
)

@Serializable
data class TransportGroup(
    val reconnectCount: Int? = null,
    val rejoinCount: Int? = null,
    val httpFallbackSeconds: Int? = null,
    val lastReceiptLatencyMs: Long? = null,
    val sendsFailedSinceBoot: Int? = null,
)

@Serializable
data class ProcessGroup(
    val deviceUptimeS: Long? = null,
    val serviceUptimeS: Long? = null,
    val serviceRestartCount: Int? = null,
    val memoryPressure: String? = null,
    val batteryOptimizationExempt: Boolean? = null,
    val notificationPermission: Boolean? = null,
    val systemApp: Boolean? = null,
    val rootAvailable: Boolean? = null,
)

@Serializable
data class IdentityGroup(
    val deviceModel: String? = null,
    val androidVersion: String? = null,
    val appVersion: String? = null,
    val clockSkewMs: Long? = null,
)

@Serializable
data class GuardGroup(
    val airplaneModeRestores: Int = 0,
    val locationRestores: Int = 0,
    val mobileDataRestores: Int = 0,
    val batterySaverRestores: Int = 0,
    val permissionRestores: Int = 0,
    val dozeAllowlistRestores: Int? = null,
    val lastRestoredItem: String? = null,
    val lastRestoredAt: String? = null,
    val lastError: String? = null,
)
