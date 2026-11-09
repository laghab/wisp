package app.wisp.keyboard.gesture

import app.wisp.keyboard.kbd.CompiledLayout
import kotlin.math.hypot
import kotlin.math.max

/** Where each letter of the gesture alphabet sits on the current layout, plus distances between letters. */
class GestureGeometry(val alphabet: IntArray, val x: FloatArray, val y: FloatArray, val keyW: Float, val keyH: Float) {
    val size = alphabet.size
    /** Centre-to-centre distance in pixels between every pair of letters (NaN when a letter has no key). */
    val dist = FloatArray(size * size).also { d ->
        for (a in 0 until size) for (b in 0 until size) d[a * size + b] = hypot(x[a] - x[b], y[a] - y[b])
    }
    fun has(letter: Int) = !x[letter].isNaN()
    fun d(a: Int, b: Int) = dist[a * size + b]

    companion object {
        fun from(layout: CompiledLayout, alphabet: IntArray): GestureGeometry {
            val x = FloatArray(alphabet.size) { Float.NaN }; val y = FloatArray(alphabet.size) { Float.NaN }
            var w = 0f; var h = 0f
            for ((i, cp) in alphabet.withIndex()) {
                val k = layout.indexOfCode(cp)
                if (k < 0) continue
                x[i] = layout.centerX(k); y[i] = layout.centerY(k)
                w = max(w, layout.rects[k * 4 + 2] - layout.rects[k * 4]); h = max(h, layout.rects[k * 4 + 3] - layout.rects[k * 4 + 1])
            }
            return GestureGeometry(alphabet, x, y, w, h)
        }
    }
}
