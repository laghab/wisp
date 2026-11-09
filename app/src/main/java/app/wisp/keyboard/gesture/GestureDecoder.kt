package app.wisp.keyboard.gesture

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Turns a traced path into ranked words.
 *
 * 1. Prune. Only words that start near where the finger went down, end near where it lifted, and whose ideal path is
 *    about as long as the trace can be the answer. That leaves a few hundred of tens of thousands, found by scanning
 *    the index groups for the possible first letters.
 * 2. Score. Each survivor's ideal path (a line through its key centres) and the trace are both resampled to the
 *    same number of points spaced evenly along their length; the cost is how far apart the points are, ignoring
 *    anything inside a tolerance (a swipe does not have to be exact), plus small penalties for a wrong start, end
 *    or length.
 * 3. Rank. The cost is lowered for common words, so "the" beats "tie" when the paths look alike.
 *
 * No allocation per call: the working arrays are created once and reused. Not thread-safe.
 */
class GestureDecoder(private val index: GestureIndex, private val geo: GestureGeometry) {

    class Results(val capacity: Int = 10) {
        var count = 0
            internal set
        internal val ids = IntArray(capacity)
        val cost = FloatArray(capacity)
        internal lateinit var source: GestureIndex
        internal var extraWords: Array<ExtraWord> = emptyArray()
        fun word(i: Int): String = if (ids[i] < 0) extraWords[-ids[i] - 1].word else source.original(ids[i])
        fun prob(i: Int): Int = if (ids[i] < 0) extraWords[-ids[i] - 1].probPoints else source.prob(ids[i])
        override fun toString() = (0 until count).joinToString { "${word(it)}:${"%.2f".format(cost[it])}" }
    }

    /** A personal word the dictionary lacks (a name), swipeable like any other. [letters] are alphabet indexes. */
    class ExtraWord(val word: String, val letters: IntArray, val probPoints: Int)

    /** Personal words to consider besides the index, and extra probability points for index words the user favours (by index id). */
    @Volatile var extras: Array<ExtraWord> = emptyArray()
    @Volatile var boostPoints: Map<Int, Int> = emptyMap()

    /** How this user swipes; null or neutral means the plain behaviour. */
    @Volatile var style: GestureStyle? = null
    private val workX = FloatArray(MAX_POINTS)
    private val workY = FloatArray(MAX_POINTS)
    private var lengthCentre = 1f
    private var activeTolerance = 0.25f

    // Tunables (in key widths unless noted). Defaults are set from the evaluation, see docs/gesture-eval.md.
    var endRadius = 1.4f           // how far from the touch-down / lift-off point a first / last letter's key may be
    var tolerance = 0.25f          // slack around the ideal path before distance costs anything
    var edgePenalty = 0.10f        // per key width the start / end touch is from the first / last letter
    var lengthPenalty = 0.30f      // per e-fold the trace length differs from the ideal path length
    var priorWeight = 0.40f        // cost reduction for the most common words
    var minLengthRatio = 0.65f
    var maxLengthRatio = 1.60f

    /** Counters from the last decode, for tuning: words scanned, passing the end check, the length check, the letter-visit check, and aligned. */
    var statScanned = 0; var statEnd = 0; var statLength = 0; var statVisit = 0; var statAligned = 0

    private val R = 24
    private val ux = FloatArray(R); private val uy = FloatArray(R)
    private val tx = FloatArray(R); private val ty = FloatArray(R)
    private val cum = FloatArray(MAX_LETTERS + 1)
    private val cl = IntArray(MAX_LETTERS)
    private val startOk = BooleanArray(geo.size)
    private val endOk = BooleanArray(geo.size)
    private val startDist = FloatArray(geo.size)
    /** For each letter: how close (in key widths) the swipe comes to its key. Computed once per swipe, shared by every candidate word. */
    private val visitDist = FloatArray(geo.size)
    private val endDist = FloatArray(geo.size)
    private val cumTrace = FloatArray(MAX_POINTS)
    private val prevRow = FloatArray(R + 1)
    private val curRow = FloatArray(R + 1)
    var visitMax = 1.5f           // a swipe that never comes this close (key widths) to one of a word's letters cannot mean that word
    var visitTolerance = 0.40f
    var visitWeight = 0.30f
    var bandFraction = 0.20f       // how far the two paths may drift apart in time (fraction of the path)

    /** Decodes the trace ([n] points of [xs], [ys]) into [out]; returns how many words were found (best first). */
    fun decode(rawXs: FloatArray, rawYs: FloatArray, n: Int, out: Results): Int {
        out.count = 0; out.source = index
        val pts = minOf(n, MAX_POINTS)
        if (pts < 2) return 0
        val kw = geo.keyW
        // Undo this user's habits first: their usual offset, their usual swipe size, how far they usually stray.
        val st = style
        val xs: FloatArray; val ys: FloatArray
        if (st == null || st.isNeutral) { xs = rawXs; ys = rawYs; lengthCentre = 1f; activeTolerance = tolerance }
        else {
            val bx = st.biasX * kw; val by = st.biasY * kw
            for (i in 0 until pts) { workX[i] = rawXs[i] - bx; workY[i] = rawYs[i] - by }
            xs = workX; ys = workY; lengthCentre = st.lengthRatio; activeTolerance = tolerance * st.spread
        }
        // Length of the trace.
        var traceLen = 0f
        cumTrace[0] = 0f
        for (i in 1 until pts) { traceLen += dist(xs[i - 1], ys[i - 1], xs[i], ys[i]); cumTrace[i] = traceLen }
        if (traceLen < 1.2f * kw) return 0                       // a tap or a wiggle, not a swipe
        resampleTrace(xs, ys, pts, traceLen)

        val sx = xs[0]; val sy = ys[0]; val ex = xs[pts - 1]; val ey = ys[pts - 1]
        val maxD = endRadius * kw
        for (a in 0 until geo.size) {
            if (!geo.has(a)) { startOk[a] = false; endOk[a] = false; continue }
            startDist[a] = dist(sx, sy, geo.x[a], geo.y[a]); endDist[a] = dist(ex, ey, geo.x[a], geo.y[a])
            startOk[a] = startDist[a] <= maxD; endOk[a] = endDist[a] <= maxD
        }

        statScanned = 0; statEnd = 0; statLength = 0; statVisit = 0; statAligned = 0
        for (a in 0 until geo.size) {
            if (!geo.has(a)) { visitDist[a] = Float.MAX_VALUE; continue }
            var best = Float.MAX_VALUE
            for (k in 0 until R) { val dx = ux[k] - geo.x[a]; val dy = uy[k] - geo.y[a]; val d = dx * dx + dy * dy; if (d < best) best = d }
            visitDist[a] = sqrt(best) / kw
        }

        out.extraWords = extras
        val boosts = boostPoints
        for (first in 0 until geo.size) {
            if (!startOk[first]) continue
            val from = index.bucketStart(first); val to = index.bucketEnd(first)
            for (w in from until to) {
                statScanned++
                val last = index.lastLetter(w)
                if (!endOk[last]) continue
                statEnd++
                val m = collapseIndexWord(w)
                if (m < 2) continue
                val prob = if (boosts.isEmpty()) index.prob(w) else minOf(MAX_PROB, index.prob(w) + (boosts[w] ?: 0))
                consider(out, w, m, first, last, prob, traceLen)
            }
        }
        // Personal words that are not in the index (names the user has taught Wisp).
        val extra = extras
        for (k in extra.indices) {
            val e = extra[k]
            val first = e.letters[0]; val last = e.letters[e.letters.size - 1]
            if (!startOk[first] || !endOk[last]) continue
            val m = collapseLetters(e.letters)
            if (m < 2) continue
            consider(out, -(k + 1), m, first, last, e.probPoints, traceLen)
        }
        return out.count
    }

    /** Collapses repeated letters ("hello" -> h e l o: a swipe passes through a double letter once) into [cl]/[cum]; returns the count, or -1. */
    private fun collapseIndexWord(w: Int): Int {
        val len = index.foldedLen(w)
        var m = 0; var prev = -1
        cum[0] = 0f
        for (j in 0 until len) {
            val l = index.letter(w, j)
            if (l == prev) continue
            if (!geo.has(l)) return -1
            if (m > 0) cum[m] = cum[m - 1] + geo.d(prev, l)
            cl[m++] = l; prev = l
            if (m >= MAX_LETTERS) break
        }
        return m
    }

    private fun collapseLetters(letters: IntArray): Int {
        var m = 0; var prev = -1
        cum[0] = 0f
        for (l in letters) {
            if (l == prev) continue
            if (!geo.has(l)) return -1
            if (m > 0) cum[m] = cum[m - 1] + geo.d(prev, l)
            cl[m++] = l; prev = l
            if (m >= MAX_LETTERS) break
        }
        return m
    }

    /** Scores one candidate whose collapsed letters are in [cl]/[cum], and keeps it if it makes the list. */
    private fun consider(out: Results, id: Int, m: Int, first: Int, last: Int, prob: Int, traceLen: Float) {
        val tmplLen = cum[m - 1]
        val ratio = traceLen / tmplLen / lengthCentre
        if (ratio < minLengthRatio || ratio > maxLengthRatio) return
        statLength++
        // Cheapest this word could possibly cost (everything but the path comparison is known now). If even that
        // cannot make the top list, skip the expensive part.
        val fixed = edgeAndLength(ratio, startDist[first], endDist[last]) - priorWeight * prob / 255f
        if (out.count == out.capacity && fixed >= out.cost[out.count - 1]) return
        val visit = visitCost(m)
        if (visit < 0f) return
        statVisit++
        val known = fixed + visitWeight * visit
        if (out.count == out.capacity && known >= out.cost[out.count - 1]) return   // the path cost can only add to this
        statAligned++
        // Largest alignment cost (in the un-normalised units of the table) at which this word still makes the list.
        val abandonAt = if (out.count == out.capacity) (out.cost[out.count - 1] - known) * (2 * R) else 1e9f
        val path = pathCost(m, tmplLen, abandonAt)
        if (path >= 1e8f) return
        insert(out, id, known + path)
    }

    private fun edgeAndLength(ratio: Float, startD: Float, endD: Float): Float {
        val kw = geo.keyW
        return edgePenalty * (max(0f, startD / kw - 0.5f) + max(0f, endD / kw - 0.5f)) + lengthPenalty * abs(ln(ratio))
    }

    /** How well the swipe visits each letter of the word; negative means some letter is never approached. */
    private fun visitCost(m: Int): Float {
        var sum = 0f
        for (v in 0 until m) {
            val d = visitDist[cl[v]]
            if (d > visitMax) return -1f
            val e = d - visitTolerance
            if (e > 0f) sum += e
        }
        return sum / m
    }

    private fun pathCost(m: Int, tmplLen: Float, abandonAt: Float): Float {
        // Resample the ideal path to R evenly spaced points.
        var seg = 0
        for (k in 0 until R) {
            val target = tmplLen * k / (R - 1)
            while (seg < m - 2 && cum[seg + 1] < target) seg++
            val segLen = cum[seg + 1] - cum[seg]
            val t = if (segLen > 0f) ((target - cum[seg]) / segLen).coerceIn(0f, 1f) else 0f
            val a = cl[seg]; val b = cl[seg + 1]
            tx[k] = geo.x[a] + (geo.x[b] - geo.x[a]) * t
            ty[k] = geo.y[a] + (geo.y[b] - geo.y[a]) * t
        }
        return alignedCost(geo.keyW, abandonAt)
    }

    /**
     * Dynamic time warping between the trace and the ideal path: the cheapest way to pair up their points in order,
     * so a swipe that rushes one corner and lingers on another still lines up with the right letters.
     */
    private fun alignedCost(kw: Float, abandonAt: Float): Float {
        val band = maxOf(3, (R * bandFraction).toInt())
        val inf = 1e9f
        java.util.Arrays.fill(prevRow, inf); prevRow[0] = 0f
        for (i in 1..R) {
            java.util.Arrays.fill(curRow, inf)
            val jLo = maxOf(1, i - band); val jHi = minOf(R, i + band)
            var rowMin = inf
            for (j in jLo..jHi) {
                var c = dist(ux[i - 1], uy[i - 1], tx[j - 1], ty[j - 1]) / kw - activeTolerance
                if (c < 0f) c = 0f
                var best = prevRow[j - 1]
                if (prevRow[j] < best) best = prevRow[j]
                if (curRow[j - 1] < best) best = curRow[j - 1]
                val v = best + c
                curRow[j] = v
                if (v < rowMin) rowMin = v
            }
            // Costs only grow along a path, so once every cell of a row is beyond what this word may cost to stay in
            // the top list, it cannot get back under: stop early.
            if (rowMin >= abandonAt) return inf
            System.arraycopy(curRow, 0, prevRow, 0, R + 1)
        }
        return prevRow[R] / (2 * R)
    }

    private fun resampleTrace(xs: FloatArray, ys: FloatArray, pts: Int, total: Float) {
        var seg = 0
        for (k in 0 until R) {
            val target = total * k / (R - 1)
            while (seg < pts - 2 && cumTrace[seg + 1] < target) seg++
            val segLen = cumTrace[seg + 1] - cumTrace[seg]
            val t = if (segLen > 0f) ((target - cumTrace[seg]) / segLen).coerceIn(0f, 1f) else 0f
            ux[k] = xs[seg] + (xs[seg + 1] - xs[seg]) * t
            uy[k] = ys[seg] + (ys[seg + 1] - ys[seg]) * t
        }
    }

    private fun insert(out: Results, id: Int, cost: Float) {
        var pos = out.count
        if (pos == out.capacity) { if (cost >= out.cost[pos - 1]) return; pos-- } else out.count++
        while (pos > 0 && out.cost[pos - 1] > cost) { out.cost[pos] = out.cost[pos - 1]; out.ids[pos] = out.ids[pos - 1]; pos-- }
        out.cost[pos] = cost; out.ids[pos] = id
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float { val dx = ax - bx; val dy = ay - by; return sqrt(dx * dx + dy * dy) }

    companion object {
        const val MAX_LETTERS = 40
        const val MAX_PROB = 300
        const val MAX_POINTS = 512
    }
}
