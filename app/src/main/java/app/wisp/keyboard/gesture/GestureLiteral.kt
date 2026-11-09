package app.wisp.keyboard.gesture

import kotlin.math.sqrt

/** The letters a swipe actually passed over, in order: the fallback shown when no dictionary word fits. */
object GestureLiteral {
    /**
     * Maps each point to the nearest letter key (within a key and a half), merges repeats, and keeps a letter only if the
     * swipe lingered on it for two or more points or it is the first or last one (so letters merely crossed on the way are dropped).
     */
    fun of(xs: FloatArray, ys: FloatArray, n: Int, geo: GestureGeometry): String {
        if (n < 2) return ""
        val maxSq = (1.5f * geo.keyW) * (1.5f * geo.keyW)
        val seq = IntArray(n); val run = IntArray(n); var m = 0
        for (i in 0 until n) {
            var best = -1; var bestD = maxSq
            for (a in 0 until geo.size) {
                if (!geo.has(a)) continue
                val dx = xs[i] - geo.x[a]; val dy = ys[i] - geo.y[a]; val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = a }
            }
            if (best < 0) continue
            if (m > 0 && seq[m - 1] == best) run[m - 1]++ else { seq[m] = best; run[m] = 1; m++ }
        }
        val sb = StringBuilder()
        for (i in 0 until m) if (run[i] >= 2 || i == 0 || i == m - 1) sb.appendCodePoint(geo.alphabet[seq[i]])
        return sb.toString()
    }
}
