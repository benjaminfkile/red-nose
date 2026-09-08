package com.wmsfo.rednose.telemetry

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.telephony.TelephonyManager
import com.wmsfo.rednose.log.RingLog

// Network type, signal, airplane mode, validated-transport bit (red-nose.md 8).
class RadioProbe(
    private val context: Context,
    private val log: RingLog,
    private val minuteMs: Long = 60_000L,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private var lastLogAtMs: Long = 0L

    fun read(): RadioGroup? = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false
        val airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val phoneStateGranted = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        var networkType: String? = null
        var signalDbm: Int? = null
        var signalLevel: Int? = null
        if (tm != null && phoneStateGranted) {
            try {
                networkType = networkTypeName(tm.dataNetworkType)
            } catch (_: SecurityException) {
                networkType = null
            }
            try {
                val sig = tm.signalStrength?.cellSignalStrengths?.firstOrNull()
                signalDbm = sig?.dbm
                signalLevel = sig?.level
            } catch (_: Throwable) {
                // leave nulls
            }
        }
        if (networkType == null && caps != null) {
            networkType = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                else -> "NONE"
            }
        }
        RadioGroup(
            networkType = networkType,
            signalDbm = signalDbm,
            signalLevel = signalLevel,
            airplaneMode = airplane,
            connected = connected,
        )
    } catch (e: Throwable) {
        maybeLog("radio probe failed: ${e.javaClass.simpleName}:${e.message}")
        null
    }

    private fun networkTypeName(t: Int): String = when (t) {
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "NR"
        TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA -> "HSPA"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN"
        else -> "T$t"
    }

    private fun maybeLog(msg: String) {
        val now = clockMs()
        if (now - lastLogAtMs >= minuteMs) {
            log.warn(msg)
            lastLogAtMs = now
        }
    }
}
