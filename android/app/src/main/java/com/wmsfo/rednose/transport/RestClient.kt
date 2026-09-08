package com.wmsfo.rednose.transport

import com.wmsfo.rednose.location.LatestFix
import com.wmsfo.rednose.location.toPayload
import com.wmsfo.rednose.log.BeaconJson
import com.wmsfo.rednose.telemetry.Heartbeat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// One OkHttpClient with connect 5 s, read/write 10 s, no automatic retries
// (red-nose.md 7.7).  X-Beacon-Key on every call; X-App-Version on log uploads.
class RestClient(
    private val client: OkHttpClient = defaultClient(),
    private val json: kotlinx.serialization.json.Json = BeaconJson,
) : SendLoop.RestSender {

    interface RestApi {
        val apiBaseUrl: String
        val beaconKey: String
    }

    @Volatile private var config: RestApi? = null

    fun configure(config: RestApi?) { this.config = config }

    private fun cfg(): RestApi = config ?: error("RestClient not configured (call configure() after enrollment)")

    override suspend fun postLocation(fix: LatestFix): SendLoop.PostResult = withContext(Dispatchers.IO) {
        val c = cfg()
        val body = json.encodeToString(fix.toPayload())
            .toRequestBody(APPLICATION_JSON)
        val req = Request.Builder()
            .url("${c.apiBaseUrl.trimEnd('/')}/locations")
            .header("X-Beacon-Key", c.beaconKey)
            .post(body)
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val txt = resp.body?.string() ?: ""
                    val server = parseServer(txt)
                    SendLoop.PostResult(true, server.serverTime, null, null)
                } else {
                    val err = parseError(resp.body?.string())
                    SendLoop.PostResult(false, null, err.code ?: "http_${resp.code}", err.requestId)
                }
            }
        } catch (e: Exception) {
            SendLoop.PostResult(false, null, "network_error", null)
        }
    }

    suspend fun postHeartbeat(hb: Heartbeat): HeartbeatResult = withContext(Dispatchers.IO) {
        val c = cfg()
        val body = json.encodeToString(hb).toRequestBody(APPLICATION_JSON)
        val req = Request.Builder()
            .url("${c.apiBaseUrl.trimEnd('/')}/beacons/heartbeat")
            .header("X-Beacon-Key", c.beaconKey)
            .post(body)
            .build()
        val tSend = System.currentTimeMillis()
        try {
            client.newCall(req).execute().use { resp ->
                val tReceive = System.currentTimeMillis()
                val txt = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    val parsed = parseHeartbeat(txt)
                    HeartbeatResult(
                        ok = true,
                        code = resp.code,
                        serverTime = parsed.serverTime,
                        liveEventId = parsed.liveEventId,
                        isActive = parsed.isActive,
                        receivedAt = parsed.receivedAt,
                        tSendMs = tSend, tReceiveMs = tReceive,
                        errorCode = null, requestId = null,
                    )
                } else {
                    val err = parseError(txt)
                    HeartbeatResult(
                        ok = false,
                        code = resp.code,
                        serverTime = null, liveEventId = null, isActive = null, receivedAt = null,
                        tSendMs = tSend, tReceiveMs = tReceive,
                        errorCode = err.code ?: "http_${resp.code}",
                        requestId = err.requestId,
                    )
                }
            }
        } catch (e: Exception) {
            val tReceive = System.currentTimeMillis()
            HeartbeatResult(
                ok = false, code = 0, serverTime = null, liveEventId = null, isActive = null,
                receivedAt = null, tSendMs = tSend, tReceiveMs = tReceive,
                errorCode = "network_error", requestId = null,
            )
        }
    }

    suspend fun postLogs(bytes: ByteArray, appVersion: String): LogResult = withContext(Dispatchers.IO) {
        val c = cfg()
        val body = bytes.toRequestBody(TEXT_PLAIN)
        val req = Request.Builder()
            .url("${c.apiBaseUrl.trimEnd('/')}/beacons/logs")
            .header("X-Beacon-Key", c.beaconKey)
            .header("X-App-Version", appVersion)
            .post(body)
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val txt = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    val obj = try { json.parseToJsonElement(txt).jsonObject } catch (_: Throwable) { null }
                    LogResult(
                        ok = true,
                        id = obj?.get("id")?.jsonPrimitive?.longOrNull,
                        receivedAt = obj?.get("receivedAt")?.jsonPrimitive?.contentOrNull,
                        code = null, message = null,
                    )
                } else {
                    val err = parseError(txt)
                    LogResult(ok = false, id = null, receivedAt = null, code = err.code ?: "http_${resp.code}", message = err.message)
                }
            }
        } catch (e: Exception) {
            LogResult(false, null, null, "network_error", e.message)
        }
    }

    private fun parseServer(txt: String): ServerFields = try {
        val obj = json.parseToJsonElement(txt).jsonObject
        ServerFields(
            serverTime = obj["serverTime"]?.jsonPrimitive?.contentOrNull,
        )
    } catch (_: Throwable) { ServerFields(null) }

    private fun parseHeartbeat(txt: String): ParsedHeartbeat = try {
        val obj = json.parseToJsonElement(txt).jsonObject
        ParsedHeartbeat(
            serverTime = obj["serverTime"]?.jsonPrimitive?.contentOrNull,
            receivedAt = obj["receivedAt"]?.jsonPrimitive?.contentOrNull,
            liveEventId = obj["liveEventId"]?.jsonPrimitive?.longOrNull,
            isActive = obj["isActive"]?.jsonPrimitive?.booleanOrNull,
        )
    } catch (_: Throwable) { ParsedHeartbeat(null, null, null, null) }

    private fun parseError(txt: String?): ParsedError {
        if (txt.isNullOrBlank()) return ParsedError(null, null, null)
        return try {
            val obj = json.parseToJsonElement(txt).jsonObject
            ParsedError(
                code = obj["code"]?.jsonPrimitive?.contentOrNull,
                message = obj["message"]?.jsonPrimitive?.contentOrNull,
                requestId = obj["requestId"]?.jsonPrimitive?.contentOrNull,
            )
        } catch (_: Throwable) { ParsedError(null, null, null) }
    }

    data class ServerFields(val serverTime: String?)
    data class ParsedHeartbeat(
        val serverTime: String?,
        val receivedAt: String?,
        val liveEventId: Long?,
        val isActive: Boolean?,
    )
    data class ParsedError(val code: String?, val message: String?, val requestId: String?)

    @Serializable
    data class HeartbeatResult(
        val ok: Boolean,
        val code: Int,
        val serverTime: String?,
        val liveEventId: Long?,
        val isActive: Boolean?,
        val receivedAt: String?,
        val tSendMs: Long,
        val tReceiveMs: Long,
        val errorCode: String?,
        val requestId: String?,
    )

    data class LogResult(
        val ok: Boolean,
        val id: Long?,
        val receivedAt: String?,
        val code: String?,
        val message: String?,
    )

    companion object {
        private val APPLICATION_JSON = "application/json; charset=utf-8".toMediaType()
        private val TEXT_PLAIN = "text/plain; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
