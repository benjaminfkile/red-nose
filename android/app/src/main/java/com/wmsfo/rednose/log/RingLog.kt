package com.wmsfo.rednose.log

import com.wmsfo.rednose.location.FixTime
import java.io.File
import java.io.RandomAccessFile
import java.util.ArrayDeque

// Two-file ring, REDNOSE_LOG_RING_BYTES total (red-nose.md 12).  The older file is
// truncated when the newer fills.  Every line goes to every listener.
class RingLog(
    directory: File,
    totalBytes: Int,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    private val halfBytes: Long = (totalBytes / 2).coerceAtLeast(1024).toLong()
    private val fileA: File = File(directory, "rednose-a.log")
    private val fileB: File = File(directory, "rednose-b.log")
    private var writer: File
    private val listeners = ArrayList<(String) -> Unit>()
    private val recentTail = ArrayDeque<String>()

    init {
        if (!directory.exists()) directory.mkdirs()
        // Newer file is whichever was touched later; when neither exists start on A.
        writer = if (fileB.exists() && fileB.lastModified() >= fileA.lastModified()) fileB else fileA
    }

    @Synchronized
    fun log(level: Level, message: String) {
        val line = "${FixTime.rfc3339(clockMs())} ${level.name} $message"
        if (writer.length() >= halfBytes) rotate()
        writer.appendText(line + "\n")
        recentTail.addLast(line)
        while (recentTail.size > 512) recentTail.removeFirst()
        val snapshot = listeners.toList()
        snapshot.forEach { it.invoke(line) }
    }

    fun debug(msg: String) = log(Level.DEBUG, msg)
    fun info(msg: String) = log(Level.INFO, msg)
    fun warn(msg: String) = log(Level.WARN, msg)
    fun error(msg: String) = log(Level.ERROR, msg)

    // Convenience surface used by the transport layer.
    fun socket(msg: String, t: Throwable? = null) =
        log(Level.INFO, "socket $msg${t?.let { " err=${it.javaClass.simpleName}:${it.message}" } ?: ""}")

    fun send(msg: String, t: Throwable? = null) =
        log(Level.INFO, "send $msg${t?.let { " err=${it.javaClass.simpleName}:${it.message}" } ?: ""}")

    fun heartbeat(msg: String, t: Throwable? = null) =
        log(Level.INFO, "heartbeat $msg${t?.let { " err=${it.javaClass.simpleName}:${it.message}" } ?: ""}")

    @Synchronized
    fun addListener(l: (String) -> Unit) { listeners.add(l) }

    @Synchronized
    fun removeListener(l: (String) -> Unit) { listeners.remove(l) }

    @Synchronized
    fun recent(maxLines: Int): List<String> {
        if (maxLines <= 0) return emptyList()
        val size = recentTail.size
        val skip = (size - maxLines).coerceAtLeast(0)
        return recentTail.drop(skip)
    }

    /** Concatenated bytes of both files in age order (older first, newer last). */
    @Synchronized
    fun readAll(): ByteArray {
        val older = if (writer == fileA) fileB else fileA
        val newer = writer
        val a = if (older.exists()) older.readBytes() else ByteArray(0)
        val b = if (newer.exists()) newer.readBytes() else ByteArray(0)
        val out = ByteArray(a.size + b.size)
        System.arraycopy(a, 0, out, 0, a.size)
        System.arraycopy(b, 0, out, a.size, b.size)
        return out
    }

    private fun rotate() {
        writer = if (writer == fileA) fileB else fileA
        // Truncate the file we're about to write to.
        RandomAccessFile(writer, "rw").use { it.setLength(0L) }
    }
}
