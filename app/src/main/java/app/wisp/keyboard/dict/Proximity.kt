package app.wisp.keyboard.dict

import app.wisp.keyboard.kbd.CompiledLayout
import com.android.inputmethod.keyboard.ProximityInfo
import java.io.Closeable
import kotlin.math.max

/**
 * Tells the dictionary engine where the keys are, so a tap near the edge of "e" can still mean "w" or "r".
 * Built once per layout size; closing it frees the native object.
 */
class Proximity private constructor(var handle: Long) : Closeable {

    override fun close() {
        if (handle != 0L) { ProximityInfo.releaseProximityInfoNative(handle); handle = 0 }
    }

    companion object {
        private const val GRID_W = 32
        private const val GRID_H = 16
        private const val MAX_PROXIMITY_CHARS = 16     // must equal MAX_PROXIMITY_CHARS_SIZE in defines.h
        private const val SEARCH_DISTANCE = 1.2f       // key widths around a cell centre that count as neighbours
        private const val NOT_A_CODE = -1

        fun build(layout: CompiledLayout): Proximity {
            // Only keys that type something take part (letters, digits, punctuation, space).
            val keys = (0 until layout.count).filter { layout.codes[it] >= ' '.code }
            val n = keys.size
            val xs = IntArray(n); val ys = IntArray(n); val ws = IntArray(n); val hs = IntArray(n); val codes = IntArray(n)
            var keyW = 0f; var keyH = 0f
            for ((j, k) in keys.withIndex()) {
                val r = layout.rects
                xs[j] = r[k * 4].toInt(); ys[j] = r[k * 4 + 1].toInt()
                ws[j] = (r[k * 4 + 2] - r[k * 4]).toInt(); hs[j] = (r[k * 4 + 3] - r[k * 4 + 1]).toInt()
                codes[j] = layout.codes[k]
                if (layout.kinds[k] == app.wisp.keyboard.kbd.KeyKind.LETTER) { keyW = max(keyW, ws[j].toFloat()); keyH = max(keyH, hs[j].toFloat()) }
            }
            val width = layout.width.toInt(); val height = layout.height.toInt()
            val cellW = (width + GRID_W - 1) / GRID_W; val cellH = (height + GRID_H - 1) / GRID_H
            val threshold = keyW * SEARCH_DISTANCE; val t2 = threshold * threshold

            val prox = IntArray(GRID_W * GRID_H * MAX_PROXIMITY_CHARS) { NOT_A_CODE }
            val dist = FloatArray(n)
            val order = ArrayList<Int>(n)
            for (cy in 0 until GRID_H) for (cx in 0 until GRID_W) {
                val px = cx * cellW + cellW / 2f; val py = cy * cellH + cellH / 2f
                order.clear()
                for (j in 0 until n) {
                    val dx = if (px < xs[j]) xs[j] - px else if (px > xs[j] + ws[j]) px - (xs[j] + ws[j]) else 0f
                    val dy = if (py < ys[j]) ys[j] - py else if (py > ys[j] + hs[j]) py - (ys[j] + hs[j]) else 0f
                    dist[j] = dx * dx + dy * dy
                    if (dist[j] < t2) order.add(j)
                }
                order.sortBy { dist[it] }
                val base = (cy * GRID_W + cx) * MAX_PROXIMITY_CHARS
                for ((i, j) in order.take(MAX_PROXIMITY_CHARS).withIndex()) prox[base + i] = codes[j]
            }
            val handle = ProximityInfo.setProximityInfoNative(
                width, height, GRID_W, GRID_H, keyW.toInt(), keyH.toInt(), prox, n, xs, ys, ws, hs, codes,
                null, null, null,
            )
            return Proximity(handle)
        }
    }
}
