package app.wisp.keyboard.gesture

import java.io.File
import kotlin.math.sqrt

/**
 * How one person swipes, in key widths so it does not depend on screen size: where their swipes sit relative to the
 * ideal path ([biasX], [biasY]), how long they are compared with it ([lengthRatio]) and how far they stray from it
 * ([spread], 1 = typical). The decoder uses it to undo the habit before matching, so a swiper who always lands a bit
 * high or low gets the right words more often.
 *
 * It starts neutral and learns slowly: early observations count for little, and every value is clamped, so a
 * strange swipe or a wrong guess cannot throw decoding off. Practice swipes (the intended word is certain) count
 * more than ordinary ones.
 */
class GestureStyle {
    var biasX = 0f; private set
    var biasY = 0f; private set
    var lengthRatio = 1f; private set
    var spread = 1f; private set
    /** How much evidence there is (weighted number of swipes). */
    var evidence = 0f; private set

    val isNeutral get() = evidence == 0f

    fun reset() { biasX = 0f; biasY = 0f; lengthRatio = 1f; spread = 1f; evidence = 0f }

    /**
     * Learns from one swipe whose intended word is known: [letters] are the word's alphabet indexes (repeats allowed).
     * [weight] is how much to trust it (about 3 for practice, 0.3 to 1 for ordinary use).
     */
    fun observe(xs: FloatArray, ys: FloatArray, n: Int, letters: IntArray, geo: GestureGeometry, weight: Float) {
        if (n < 3 || geo.keyW <= 0f) return
        // The ideal path: a line through the word's key centres, double letters collapsed.
        val px = ArrayList<Float>(); val py = ArrayList<Float>(); var prev = -1
        for (l in letters) { if (l == prev) continue; if (!geo.has(l)) return; px.add(geo.x[l]); py.add(geo.y[l]); prev = l }
        if (px.size < 2) return
        val tmplLen = polylineLength(px, py)
        val traceLen = polylineLength(xs, ys, n)
        if (tmplLen < 1.2f * geo.keyW) return
        val ratio = traceLen / tmplLen
        if (ratio < 0.5f || ratio > 2f) return                      // not the word the user thought they were swiping
        val u = resample(xs, ys, n); val t = resample(px.toFloatArray(), py.toFloatArray(), px.size)
        var dx = 0f; var dy = 0f
        for (k in 0 until R) { dx += u[k * 2] - t[k * 2]; dy += u[k * 2 + 1] - t[k * 2 + 1] }
        dx /= R; dy /= R
        var resid = 0f
        for (k in 0 until R) { val ex = u[k * 2] - t[k * 2] - dx; val ey = u[k * 2 + 1] - t[k * 2 + 1] - dy; resid += sqrt(ex * ex + ey * ey) }
        resid /= R

        // Slow start: until there is some evidence, a new observation moves the profile only a little.
        val a = weight / (minOf(evidence, 300f) + weight + PRIOR)
        biasX = (biasX + a * (dx / geo.keyW - biasX)).coerceIn(-MAX_BIAS, MAX_BIAS)
        biasY = (biasY + a * (dy / geo.keyW - biasY)).coerceIn(-MAX_BIAS, MAX_BIAS)
        lengthRatio = (lengthRatio + a * (ratio - lengthRatio)).coerceIn(MIN_RATIO, MAX_RATIO)
        spread = (spread + a * ((resid / geo.keyW) / TYPICAL_RESIDUAL - spread)).coerceIn(MIN_SPREAD, MAX_SPREAD)
        evidence += weight
    }

    fun save(file: File) {
        file.parentFile?.mkdirs()
        file.writeText("$biasX $biasY $lengthRatio $spread $evidence\n")
    }

    fun load(file: File) {
        reset()
        if (!file.isFile) return
        try {
            val f = file.readText().trim().split(' ').map { it.toFloat() }
            biasX = f[0].coerceIn(-MAX_BIAS, MAX_BIAS); biasY = f[1].coerceIn(-MAX_BIAS, MAX_BIAS)
            lengthRatio = f[2].coerceIn(MIN_RATIO, MAX_RATIO); spread = f[3].coerceIn(MIN_SPREAD, MAX_SPREAD); evidence = f[4].coerceAtLeast(0f)
        } catch (e: Exception) { reset() }
    }

    private fun polylineLength(xs: List<Float>, ys: List<Float>): Float { var s = 0f; for (i in 1 until xs.size) s += hyp(xs[i] - xs[i - 1], ys[i] - ys[i - 1]); return s }
    private fun polylineLength(xs: FloatArray, ys: FloatArray, n: Int): Float { var s = 0f; for (i in 1 until n) s += hyp(xs[i] - xs[i - 1], ys[i] - ys[i - 1]); return s }
    private fun hyp(a: Float, b: Float) = sqrt(a * a + b * b)

    /** [R] points spaced evenly along the path, as x,y pairs. */
    private fun resample(xs: FloatArray, ys: FloatArray, n: Int): FloatArray {
        val cum = FloatArray(n); for (i in 1 until n) cum[i] = cum[i - 1] + hyp(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        val out = FloatArray(R * 2); var seg = 0
        for (k in 0 until R) {
            val target = cum[n - 1] * k / (R - 1)
            while (seg < n - 2 && cum[seg + 1] < target) seg++
            val len = cum[seg + 1] - cum[seg]; val t = if (len > 0f) ((target - cum[seg]) / len).coerceIn(0f, 1f) else 0f
            out[k * 2] = xs[seg] + (xs[seg + 1] - xs[seg]) * t; out[k * 2 + 1] = ys[seg] + (ys[seg + 1] - ys[seg]) * t
        }
        return out
    }

    companion object {
        private const val R = 24
        const val MAX_BIAS = 0.35f
        const val MIN_RATIO = 0.80f
        const val MAX_RATIO = 1.25f
        const val MIN_SPREAD = 0.70f
        const val MAX_SPREAD = 1.50f
        private const val PRIOR = 12f             // worth of the neutral starting profile, in swipes
        private const val TYPICAL_RESIDUAL = 0.20f // mean distance (key widths) of a normal swipe from its ideal path after the offset is removed
    }
}
