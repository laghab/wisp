package app.wisp.keyboard.kbd

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import app.wisp.keyboard.style.Theme

/** The rail beside a one-handed keyboard: one button swaps the side, the other goes back to full width. */
class PlacementRail(context: Context, private var theme: Theme) : View(context) {
    /** "swap" or "full". */
    var onAction: ((String) -> Unit)? = null
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val swap = RectF(); private val full = RectF()

    fun setTheme(t: Theme) { theme = t; invalidate() }

    override fun onDraw(c: Canvas) {
        val size = minOf(width - 16f * d, 52f * d)
        if (size <= 0) return
        val cx = width / 2f
        swap.set(cx - size / 2, 16f * d, cx + size / 2, 16f * d + size)
        full.set(cx - size / 2, swap.bottom + 14f * d, cx + size / 2, swap.bottom + 14f * d + size)
        for (r in arrayOf(swap, full)) { fill.color = theme.mod.base(); c.drawRoundRect(r, theme.radius[0] * d, theme.radius[0] * d, fill) }
        line.color = theme.modFg
        val a = size * 0.26f; val k = size * 0.1f
        val sx = swap.centerX(); val sy = swap.centerY()
        c.drawLine(sx - a, sy - k, sx + a, sy - k, line); c.drawLine(sx + a, sy - k, sx + a - k, sy - 2 * k, line); c.drawLine(sx + a, sy - k, sx + a - k, sy, line)
        c.drawLine(sx + a, sy + k, sx - a, sy + k, line); c.drawLine(sx - a, sy + k, sx - a + k, sy, line); c.drawLine(sx - a, sy + k, sx - a + k, sy + 2 * k, line)
        val fx = full.centerX(); val fy = full.centerY()
        c.drawLine(fx - a, fy, fx + a, fy, line)
        c.drawLine(fx - a, fy, fx - a + k, fy - k, line); c.drawLine(fx - a, fy, fx - a + k, fy + k, line)
        c.drawLine(fx + a, fy, fx + a - k, fy - k, line); c.drawLine(fx + a, fy, fx + a - k, fy + k, line)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            if (swap.contains(e.x, e.y)) onAction?.invoke("swap") else if (full.contains(e.x, e.y)) onAction?.invoke("full")
        }
        return true
    }
}
