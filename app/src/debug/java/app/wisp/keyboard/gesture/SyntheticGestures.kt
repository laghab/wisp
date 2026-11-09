package app.wisp.keyboard.gesture

import java.util.Random
import kotlin.math.hypot
import kotlin.math.max

/**
 * Makes plausible swipe traces for a word, with the mistakes real fingers make: each letter is hit a little off,
 * the whole swipe is shifted a little, corners are cut, and the sampling is uneven. [noise] is the standard
 * deviation of the per-letter miss in key widths (0.15 careful, 0.30 normal, 0.45 sloppy).
 */
class SyntheticGestures(private val geo: GestureGeometry, seed: Long) {
    val rnd = Random(seed)

    /** A consistent personal habit on top of the random misses: every swipe is shifted by this (in key widths) and scaled about its start. */
    var habitBiasX = 0f
    var habitBiasY = 0f
    var habitScale = 1f

    class Trace(val xs: FloatArray, val ys: FloatArray, val n: Int)

    fun generate(letters: IntArray, noise: Float): Trace {
        // Collapse double letters: a swipe passes through them once.
        val v = ArrayList<Int>()
        for (l in letters) if (v.isEmpty() || v.last() != l) v.add(l)
        val kw = geo.keyW; val kh = geo.keyH
        val bx = (rnd.nextGaussian() * 0.5 * noise * kw).toFloat(); val by = (rnd.nextGaussian() * 0.5 * noise * kh).toFloat()
        val px = FloatArray(v.size) { geo.x[v[it]] + bx + (rnd.nextGaussian() * noise * kw).toFloat() }
        val py = FloatArray(v.size) { geo.y[v[it]] + by + (rnd.nextGaussian() * noise * kh).toFloat() }
        if (habitScale != 1f) for (i in 1 until v.size) { px[i] = px[0] + (px[i] - px[0]) * habitScale; py[i] = py[0] + (py[i] - py[0]) * habitScale }
        for (i in px.indices) { px[i] += habitBiasX * kw; py[i] += habitBiasY * kh }

        // Dense polyline: straight lines between letters, with each interior corner rounded over a short radius
        // (the finger slows and turns near the letter it aims for, so corners are cut only a little).
        val dx = ArrayList<Float>(); val dy = ArrayList<Float>()
        fun add(x: Float, y: Float) { dx.add(x); dy.add(y) }
        add(px[0], py[0])
        var cx = px[0]; var cy = py[0]
        for (i in 1 until v.size - 1) {
            val lenPrev = hypot(px[i] - px[i - 1], py[i] - py[i - 1]); val lenNext = hypot(px[i + 1] - px[i], py[i + 1] - py[i])
            val r = minOf((0.10f + rnd.nextFloat() * 0.40f) * kw, 0.45f * lenPrev, 0.45f * lenNext)
            val ex = px[i] - (px[i] - px[i - 1]) / max(lenPrev, 1e-3f) * r; val ey = py[i] - (py[i] - py[i - 1]) / max(lenPrev, 1e-3f) * r
            val ox = px[i] + (px[i + 1] - px[i]) / max(lenNext, 1e-3f) * r; val oy = py[i] + (py[i + 1] - py[i]) / max(lenNext, 1e-3f) * r
            line(cx, cy, ex, ey, ::add)
            quad(ex, ey, px[i], py[i], ox, oy, ::add)
            cx = ox; cy = oy
        }
        line(cx, cy, px[v.size - 1], py[v.size - 1], ::add)

        // Pick points along it with uneven spacing and a little jitter.
        val xs = FloatArray(MAX); val ys = FloatArray(MAX)
        var n = 0; var acc = 0f; var next = 0f
        var i = 1
        xs[n] = dx[0]; ys[n] = dy[0]; n++
        while (i < dx.size && n < MAX - 1) {
            acc += hypot(dx[i] - dx[i - 1], dy[i] - dy[i - 1])
            if (acc >= next) {
                xs[n] = dx[i] + (rnd.nextGaussian() * 0.04 * kw).toFloat(); ys[n] = dy[i] + (rnd.nextGaussian() * 0.04 * kh).toFloat(); n++
                next = acc + max(2f, (0.06f + rnd.nextFloat() * 0.12f) * kw)
            }
            i++
        }
        xs[n] = dx.last(); ys[n] = dy.last(); n++
        return Trace(xs, ys, n)
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, add: (Float, Float) -> Unit) {
        val steps = max(2, (hypot(x1 - x0, y1 - y0) / 4f).toInt())
        for (s in 1..steps) add(x0 + (x1 - x0) * s / steps, y0 + (y1 - y0) * s / steps)
    }

    private fun quad(x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float, add: (Float, Float) -> Unit) {
        val steps = max(4, ((hypot(cx - x0, cy - y0) + hypot(x1 - cx, y1 - cy)) / 4f).toInt())
        for (s in 1..steps) {
            val t = s / steps.toFloat(); val u = 1 - t
            add(u * u * x0 + 2 * u * t * cx + t * t * x1, u * u * y0 + 2 * u * t * cy + t * t * y1)
        }
    }

    companion object { const val MAX = 400 }
}
