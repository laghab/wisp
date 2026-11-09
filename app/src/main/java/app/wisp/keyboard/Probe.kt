package app.wisp.keyboard

import android.os.SystemClock
import android.util.Log

/**
 * Debug-only latency probe. In release builds BuildConfig.DEBUG is a constant false, so R8 removes
 * the calls entirely. Read the output with the scripts in tools/bench.
 */
object Probe {
    private var t0 = 0L

    /** Starts a timeline (e.g. when the IME service is created). */
    fun start() { if (BuildConfig.DEBUG) t0 = SystemClock.elapsedRealtimeNanos() }

    /** Logs milliseconds since [start]. */
    fun mark(name: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, "$name +${(SystemClock.elapsedRealtimeNanos() - t0) / 1000}us")
    }

    private var firstDrawDone = false

    /** Logs the first call only (e.g. first frame of the keyboard). */
    fun first(name: String) { if (BuildConfig.DEBUG && !firstDrawDone) { firstDrawDone = true; mark(name) } }

    const val TAG = "WISP_PROBE"
}
