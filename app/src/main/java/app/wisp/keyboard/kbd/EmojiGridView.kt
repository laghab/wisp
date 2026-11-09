package app.wisp.keyboard.kbd

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** A scrollable grid of emoji. One View, drawn directly; scrolls by dragging, taps pick an emoji. */
class EmojiGridView(context: Context, private val emoji: Array<String>, private val palette: Palette = Palette()) : View(context) {

    fun interface Listener { fun onEmoji(text: String) }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val rowsVisible = 3
    private val cell = KeyboardView.ROW_HEIGHT_DP * density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val cols get() = (width / cell).toInt().coerceAtLeast(1)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 26f * density }
    private val baseline = -(text.fontMetrics.ascent + text.fontMetrics.descent) / 2f

    private var scroll = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (cell * rowsVisible).toInt())
    }

    private fun maxScroll(): Float {
        val rows = (emoji.size + cols - 1) / cols
        return (rows * cell - cell * rowsVisible).coerceAtLeast(0f)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.background)
        val c = cols
        val cw = width.toFloat() / c
        val top = 0f
        val first = (scroll / cell).toInt()
        val last = ((scroll + cell * rowsVisible) / cell).toInt() + 1
        for (r in first..last) for (col in 0 until c) {
            val i = r * c + col
            if (i >= emoji.size) break
            val cy = top + r * cell - scroll + cell / 2f
            if (cy < top - cell || cy > top + cell * (rowsVisible + 1)) continue
            canvas.drawText(emoji[i], col * cw + cw / 2f, cy + baseline, text)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; lastY = e.y; dragging = false; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && kotlin.math.abs(e.y - downY) > slop) dragging = true
                if (dragging) {
                    scroll = (scroll - (e.y - lastY)).coerceIn(0f, maxScroll())
                    invalidate()
                }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> if (!dragging) {
                val c = cols
                val col = (downX / (width.toFloat() / c)).toInt().coerceIn(0, c - 1)
                val row = ((downY - 0f + scroll) / cell).toInt()
                val i = row * c + col
                if (downY >= 0f && i in emoji.indices) listener?.onEmoji(emoji[i])
            }
        }
        return true
    }
}
