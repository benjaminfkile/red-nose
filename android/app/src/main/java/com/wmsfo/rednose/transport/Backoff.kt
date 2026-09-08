package com.wmsfo.rednose.transport

// Backoff.delayMs(attempt) = REDNOSE_BACKOFF_MS[min(attempt, 3)] (red-nose.md 7.2).
// Values 1000, 2000, 3000, 5000; the last repeats forever; no maximum and no stopped state.
// `attempt` resets to 0 on any success.
object Backoff {
    val delaysMs: IntArray = intArrayOf(1_000, 2_000, 3_000, 5_000)

    fun delayMs(attempt: Int): Int {
        require(attempt >= 0) { "attempt must be non-negative" }
        val i = if (attempt >= delaysMs.size) delaysMs.size - 1 else attempt
        return delaysMs[i]
    }
}
