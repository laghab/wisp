package app.wisp.keyboard.kbd

/**
 * Turns raw pointer events into key presses. Pure Kotlin with fixed arrays: no allocation per event, and
 * unit-testable on the JVM. A key is "tapped" when the finger lifts on the same key it went down on.
 */
class KeyTouchTracker(
    private val grid: KeyGrid,
    private val sink: Sink,
    /** Index of the key that turns a horizontal drag into cursor steps (the space bar), or -1. */
    private val slideKey: Int = -1,
    private val slideStartPx: Float = 24f,
    private val slideStepPx: Float = 14f,
    /** Per key: may a swipe (gesture) start on it? Null disables gesture typing. */
    private val gestureStart: BooleanArray? = null,
    /** How far (px) a touch that began on a gesture key must travel before it counts as a swipe rather than a tap. */
    private val gestureSlopPx: Float = 0f,
) {

    interface Sink {
        fun pressed(key: Int)
        fun released(key: Int)
        fun tapped(key: Int, x: Float, y: Float)
        /** Space-bar drag: positive steps move right. */
        fun slid(steps: Int) {}

        /** A touch on a letter key has travelled far enough to be a swipe. The key it began on is released. */
        fun gestureStarted() {}
        /** The swipe continues; [traceX]/[traceY] hold the whole path so far ([count] points). */
        fun gestureMoved(count: Int) {}
        /** The finger lifted: the complete path is in [xs]/[ys] ([count] points). */
        fun gestureEnded(xs: FloatArray, ys: FloatArray, count: Int) {}
        fun gestureCancelled() {}
    }

    /** The path of the swipe being tracked (valid while [gesturing]); the view draws the trail from it. */
    val traceX = FloatArray(MAX_TRACE)
    val traceY = FloatArray(MAX_TRACE)
    var traceCount = 0
        private set
    var gesturing = false
        private set
    private var gestureSlot = -1

    private val ids = IntArray(MAX_POINTERS) { -1 }
    private val downKey = IntArray(MAX_POINTERS)
    private val curKey = IntArray(MAX_POINTERS)
    private val downX = FloatArray(MAX_POINTERS)
    private val lastX = FloatArray(MAX_POINTERS)
    private val sliding = BooleanArray(MAX_POINTERS)

    private fun slot(id: Int): Int { for (i in 0 until MAX_POINTERS) if (ids[i] == id) return i; return -1 }

    fun onDown(id: Int, x: Float, y: Float) {
        val s = slot(-1)
        if (s < 0) return
        val k = grid.keyAt(x, y)
        ids[s] = id; downKey[s] = k; curKey[s] = k; downX[s] = x; lastX[s] = x; sliding[s] = false
        if (k >= 0) sink.pressed(k)
        if (gestureStart != null && k >= 0 && gestureStart[k] && gestureSlot < 0) {
            gestureSlot = s; gesturing = false; traceCount = 0
            addTrace(x, y)
        }
    }

    fun onMove(id: Int, x: Float, y: Float) {
        val s = slot(id)
        if (s < 0) return
        if (s == gestureSlot) {
            addTrace(x, y)
            if (!gesturing) {
                val dx = x - downX[s]; val dy = y - traceY[0]
                if (dx * dx + dy * dy > gestureSlopPx * gestureSlopPx) {
                    gesturing = true
                    if (curKey[s] >= 0) { sink.released(curKey[s]); curKey[s] = -1 }
                    sink.gestureStarted()
                }
            }
            if (gesturing) { sink.gestureMoved(traceCount); return }
        }
        if (slideKey >= 0 && downKey[s] == slideKey) {
            if (!sliding[s] && kotlin.math.abs(x - downX[s]) > slideStartPx) {
                sliding[s] = true
                lastX[s] = x
                if (curKey[s] >= 0) { sink.released(curKey[s]); curKey[s] = -1 }
            }
            if (sliding[s]) {
                val steps = ((x - lastX[s]) / slideStepPx).toInt()
                if (steps != 0) { sink.slid(steps); lastX[s] += steps * slideStepPx }
                return
            }
        }
        val k = grid.keyAt(x, y)
        if (k == curKey[s]) return
        if (curKey[s] >= 0) sink.released(curKey[s])
        curKey[s] = k
        if (k >= 0) sink.pressed(k)
    }

    fun onUp(id: Int, x: Float, y: Float) {
        val s = slot(id)
        if (s < 0) return
        val k = grid.keyAt(x, y)
        val down = downKey[s]
        val cur = curKey[s]
        val wasSliding = sliding[s]
        if (s == gestureSlot) {
            addTrace(x, y)
            val was = gesturing
            gestureSlot = -1; gesturing = false
            ids[s] = -1
            if (was) { sink.gestureEnded(traceX, traceY, traceCount); return }
        }
        ids[s] = -1
        if (cur >= 0) sink.released(cur)
        if (!wasSliding && k >= 0 && k == down) sink.tapped(k, x, y)
    }

    fun onCancel(id: Int) {
        val s = slot(id)
        if (s < 0) return
        val cur = curKey[s]
        if (s == gestureSlot) { val was = gesturing; gestureSlot = -1; gesturing = false; if (was) sink.gestureCancelled() }
        ids[s] = -1
        if (cur >= 0) sink.released(cur)
    }

    private fun addTrace(x: Float, y: Float) {
        if (traceCount < MAX_TRACE) { traceX[traceCount] = x; traceY[traceCount] = y; traceCount++ }
    }

    fun cancelAll() { for (i in 0 until MAX_POINTERS) if (ids[i] != -1) onCancel(ids[i]) }

    companion object { const val MAX_POINTERS = 4; const val MAX_TRACE = 512 }
}
