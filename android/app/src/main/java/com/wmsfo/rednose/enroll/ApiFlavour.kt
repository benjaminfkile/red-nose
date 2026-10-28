package com.wmsfo.rednose.enroll

// Which API a flavour enrolls against (red-nose.md 9.1, contracts 8.5).  The
// prod flavour admits only the origin of REDNOSE_PROD_API_BASE_URL; every other
// flavour admits any other origin and never that one.  Mirrors
// src/enroll/apiFlavour.ts.
object ApiFlavour {

    // scheme://host[:port], lowercased, the port only when it is not the
    // scheme's default; null for a malformed string.  Never throws.
    fun origin(url: String): String? {
        val sep = url.indexOf("://")
        if (sep <= 0) return null
        val scheme = url.substring(0, sep).lowercase()
        val rest = url.substring(sep + 3)
        var end = rest.length
        for (c in charArrayOf('/', '?', '#')) {
            val i = rest.indexOf(c)
            if (i in 0 until end) end = i
        }
        val authority = rest.substring(0, end)
        val bracket = authority.lastIndexOf(']')
        val colon = authority.lastIndexOf(':')
        val host: String
        var port: Int? = null
        if (colon > bracket) {
            host = authority.substring(0, colon)
            val digits = authority.substring(colon + 1)
            if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
            port = digits.toIntOrNull() ?: return null
        } else {
            host = authority
        }
        if (host.isEmpty()) return null
        val defaultPort = when (scheme) { "http" -> 80; "https" -> 443; else -> null }
        val suffix = if (port == null || port == defaultPort) "" else ":$port"
        return "$scheme://${host.lowercase()}$suffix"
    }

    fun admits(apiBaseUrl: String, flavour: String, prodApiBaseUrl: String): Boolean {
        val api = origin(apiBaseUrl) ?: return false
        val prod = origin(prodApiBaseUrl) ?: return false
        return if (flavour == "prod") api == prod else api != prod
    }
}
