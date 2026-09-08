package com.wmsfo.rednose.log

import com.wmsfo.rednose.transport.RestClient
import kotlinx.serialization.Serializable

// Uploads the concatenated ring log to POST /beacons/logs (contracts 4.2, red-nose.md 12).
class LogUploader(
    private val ring: RingLog,
    private val rest: RestClient,
    private val appVersion: String,
) {
    suspend fun upload(): Result {
        val body = ring.readAll()
        return try {
            val r = rest.postLogs(body, appVersion)
            if (r.ok) Result(true, r.id, body.size, r.receivedAt, null, null)
            else Result(false, null, null, null, r.code, r.message)
        } catch (e: Exception) {
            Result(false, null, null, null, "network_error", e.message ?: e.javaClass.simpleName)
        }
    }

    @Serializable
    data class Result(
        val ok: Boolean,
        val id: Long? = null,
        val sizeBytes: Int? = null,
        val receivedAt: String? = null,
        val code: String? = null,
        val message: String? = null,
    )
}
