package com.wmsfo.rednose.guard

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Runs a root command on its own single-thread executor so the beacon
// dispatcher never blocks (red-nose.md 5.5, 7.1).  A timeout destroys the
// process and returns an exitCode of null.
interface RootShell {
    suspend fun run(command: String): RootResult
}

// exitCode is null on timeout.  output has stdout and stderr merged, trimmed.
data class RootResult(val exitCode: Int?, val output: String)

class SuRootShell(
    private val timeoutMs: Long = 5_000L,
    dispatcher: CoroutineDispatcher? = null,
) : RootShell {

    private val ownExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rednose-root-shell").apply { isDaemon = true }
    }
    private val dispatcher: CoroutineDispatcher =
        dispatcher ?: ownExecutor.asCoroutineDispatcher()

    override suspend fun run(command: String): RootResult = withContext(dispatcher) {
        val process = try {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (t: Throwable) {
            return@withContext RootResult(
                exitCode = -1,
                output = "${t.javaClass.simpleName}:${t.message ?: ""}",
            )
        }
        val done = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        val exitCode: Int? = if (done) {
            process.exitValue()
        } else {
            try { process.destroy() } catch (_: Throwable) {}
            try { process.waitFor(200L, TimeUnit.MILLISECONDS) } catch (_: Throwable) {}
            null
        }
        val output = try {
            process.inputStream.bufferedReader().readText().trim()
        } catch (_: Throwable) { "" }
        RootResult(exitCode = exitCode, output = output)
    }
}
