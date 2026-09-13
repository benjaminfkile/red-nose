package com.wmsfo.rednose.store

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

// EncryptedSharedPreferences behind a Keystore MasterKey, opened only from the :beacon
// process (red-nose.md 9.3).  Keys per 9.3.
class SecureStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(): Enrollment? {
        val key = prefs.getString(K_KEY, null) ?: return null
        return Enrollment(
            apiBaseUrl = prefs.getString(K_API, "") ?: "",
            hubUrl = prefs.getString(K_HUB, "") ?: "",
            ingestChannel = prefs.getString(K_CHANNEL, "") ?: "",
            beaconId = prefs.getLong(K_BEACON_ID, 0L),
            name = prefs.getString(K_NAME, "") ?: "",
            key = key,
            gpsOnlyFallback = prefs.getBoolean(K_GPS_ONLY, false),
        )
    }

    fun save(e: Enrollment) {
        prefs.edit()
            .putString(K_API, e.apiBaseUrl)
            .putString(K_HUB, e.hubUrl)
            .putString(K_CHANNEL, e.ingestChannel)
            .putLong(K_BEACON_ID, e.beaconId)
            .putString(K_NAME, e.name)
            .putString(K_KEY, e.key)
            .putBoolean(K_GPS_ONLY, e.gpsOnlyFallback)
            .apply()
    }

    fun setGpsOnlyFallback(enabled: Boolean) {
        prefs.edit().putBoolean(K_GPS_ONLY, enabled).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val FILE_NAME = "rednose_secure"
        const val K_API = "apiBaseUrl"
        const val K_HUB = "hubUrl"
        const val K_CHANNEL = "ingestChannel"
        const val K_BEACON_ID = "beaconId"
        const val K_NAME = "name"
        const val K_KEY = "key"
        const val K_GPS_ONLY = "gpsOnlyFallback"
    }
}
