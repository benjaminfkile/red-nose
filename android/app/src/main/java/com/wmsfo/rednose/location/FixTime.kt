package com.wmsfo.rednose.location

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// One RFC 3339 formatter for every serialized timestamp (contracts 0.2 + red-nose.md 7.9):
// UTC, three fractional digits, `Z` suffix.
object FixTime {
    private val fmt: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            return f
        }
    }

    fun rfc3339(epochMs: Long): String = fmt.get()!!.format(Date(epochMs))
}
