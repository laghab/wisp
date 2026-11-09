package app.wisp.keyboard

import android.util.Log

/**
 * Debug-only latency histogram: a fixed ring of samples, no allocation per sample. Prints median/p95/p99
 * every [reportEvery] samples. Compiled out of release builds (BuildConfig.DEBUG is a constant).
 */
class LatencyStats(private val name: String, private val reportEvery: Int = 100) {
    private val ring = LongArray(1024)
    private var n = 0

    fun record(ns: Long) {
        if (!BuildConfig.DEBUG) return
        ring[n % ring.size] = ns
        n++
        if (n % reportEvery == 0) report()
    }

    private fun report() {
        val count = minOf(n, ring.size)
        val copy = ring.copyOf(count).also { it.sort() }
        fun pct(p: Double) = copy[((count - 1) * p).toInt()] / 1000
        Log.i(Probe.TAG, "$name n=$n p50=${pct(0.5)}us p95=${pct(0.95)}us p99=${pct(0.99)}us max=${copy[count - 1] / 1000}us")
    }
}
