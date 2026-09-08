package com.wmsfo.rednose.replay

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Validates a route body against the vendored contracts/schema/route.schema.json
// (contracts 1.4 / 13; red-nose.md 11).  The schema is bundled as an APK asset
// so replay validation is byte-consistent with the JSON Schema the API tests
// against.  A minimal subset of JSON Schema draft 2020-12 is enough for this
// closed shape: type unions, additionalProperties=false, arrays with a uniform
// item schema, and the date-time format check.
class RouteSchema(context: Context) {

    private val root: JsonObject by lazy {
        val bytes = context.assets.open(ASSET_PATH).use { it.readBytes() }
        Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
    }

    fun validate(bodyJson: String) {
        val body = Json.parseToJsonElement(bodyJson)
        check(body, root, path = "$")
    }

    private fun check(value: JsonElement, schema: JsonObject, path: String) {
        val types = schema["type"]?.let { readTypes(it) } ?: return
        val actual = typeOf(value)
        require(actual in types) { "$path: expected ${types.joinToString("|")}, got $actual" }
        if (value is JsonNull) return
        when (actual) {
            "object" -> checkObject(value.jsonObject, schema, path)
            "array" -> checkArray(value.jsonArray, schema, path)
            "string" -> checkString(value.jsonPrimitive.content, schema, path)
        }
    }

    private fun checkObject(obj: JsonObject, schema: JsonObject, path: String) {
        val props = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
        val additional = schema["additionalProperties"]
        if (additional is JsonPrimitive && additional.contentOrNull == "false") {
            for (k in obj.keys) require(k in props) { "$path.$k: unknown property" }
        }
        for ((k, sub) in obj) {
            val subSchema = props[k]?.jsonObject ?: continue
            check(sub, subSchema, "$path.$k")
        }
    }

    private fun checkArray(arr: JsonArray, schema: JsonObject, path: String) {
        val itemSchema = schema["items"]?.jsonObject ?: return
        for ((i, el) in arr.withIndex()) check(el, itemSchema, "$path[$i]")
    }

    private fun checkString(s: String, schema: JsonObject, path: String) {
        val format = (schema["format"] as? JsonPrimitive)?.contentOrNull ?: return
        if (format == "date-time") {
            require(RFC3339.matches(s)) { "$path: expected date-time, got '$s'" }
        }
    }

    private fun readTypes(t: JsonElement): Set<String> = when (t) {
        is JsonPrimitive -> setOf(t.content)
        is JsonArray -> t.map { (it as JsonPrimitive).content }.toSet()
        else -> emptySet()
    }

    private fun typeOf(v: JsonElement): String = when (v) {
        is JsonNull -> "null"
        is JsonObject -> "object"
        is JsonArray -> "array"
        is JsonPrimitive -> when {
            v.isString -> "string"
            v.booleanOrNullValue() != null -> "boolean"
            v.content.contains('.') || v.content.contains('e') || v.content.contains('E') -> "number"
            else -> "integer"
        }
    }

    private fun JsonPrimitive.booleanOrNullValue(): Boolean? =
        if (isString) null else when (content) { "true" -> true; "false" -> false; else -> null }

    companion object {
        private const val ASSET_PATH = "contracts/schema/route.schema.json"
        // RFC 3339 (a strict subset): YYYY-MM-DDTHH:MM:SS(.fff)?(Z|+HH:MM|-HH:MM).
        private val RFC3339 = Regex(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})$"
        )
    }
}
