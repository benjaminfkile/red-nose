package com.wmsfo.rednose.replay

import android.content.Context
import android.net.Uri
import com.wmsfo.rednose.log.BeaconJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

// Loads a route object from a URL or a content:// URI (red-nose.md 11) and
// validates its bytes against the vendored contracts/schema/route.schema.json
// (contracts 1.4 / 13) before returning the parsed Route.
class RouteLoader(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient(),
    private val schema: RouteSchema = RouteSchema(context),
) {
    suspend fun load(source: String): Route = withContext(Dispatchers.IO) {
        val bytes = when {
            source.startsWith("http://") || source.startsWith("https://") -> {
                val req = Request.Builder().url(source).get().build()
                client.newCall(req).execute().use { r ->
                    require(r.isSuccessful) { "route HTTP ${r.code}" }
                    r.body?.bytes() ?: ByteArray(0)
                }
            }
            source.startsWith("content://") -> {
                val uri = Uri.parse(source)
                context.contentResolver.openInputStream(uri).use { input ->
                    input?.readBytes() ?: error("content URI unreadable")
                }
            }
            else -> error("route source must be http(s):// or content://")
        }
        val json = String(bytes, Charsets.UTF_8)
        schema.validate(json)
        val route = BeaconJson.decodeFromString<Route>(json)
        require(route.schemaVersion == 1) { "route.schemaVersion must be 1" }
        require(route.points.size in 2..50_000) { "route.points must be 2..50000" }
        route
    }
}
