package app.wisp.keyboard.kbd

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * The strip above the keys. While a word is being typed it shows up to three suggestions; otherwise it shows the
 * editing tools (select all, cut, copy, paste).
 */
class SuggestionBarView(context: Context, private val palette: Palette = Palette()) : View(context) {

    interface Listener {
        fun onSuggestion(index: Int)
        fun onTool(tool: Tool)
        /** The mic was pressed: start listening. */
        fun onMicDown()
        /** ...and let go after [heldMs]: a long hold means "talk, then insert"; a quick tap means "keep listening". */
        fun onMicUp(heldMs: Long)
        /** The gear was tapped: open Wisp's settings. */
        fun onSettings() {}
    }

    enum class Tool(val label: String) { SELECT_ALL("Select all"), CUT("Cut"), COPY("Copy"), PASTE("Paste"), INCOGNITO("Private") }

    var listener: Listener? = null

    /** False where voice input must not run (password fields). */
    var micEnabled = true
        set(v) { if (field != v) { field = v; invalidate() } }

    private var items: List<BarItem> = emptyList()

    /** While on, nothing typed is learned; the toggle is shown in the accent colour. */
    var incognito = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private var composing = false
    private val density = resources.displayMetrics.density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 17f * density }
    private val divider = Paint().apply { color = palette.key; strokeWidth = 1f * density }
    private var tools: List<Tool> = Tool.values().toList()

    /** Which tools the strip shows, in order (the user can reorder and remove them). */
    fun setTools(list: List<Tool>) { if (list != tools) { tools = list; invalidate() } }

    fun setState(s: BarState) {
        if (items === s.items && composing == s.composing) return
        items = s.items; composing = s.composing
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (HEIGHT_DP * density).toInt())

    private val micWidth get() = 52f * density
    private val gearWidth get() = 44f * density
    private val rightEdge get() = micWidth + gearWidth
    private val micPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 2f * density }
    private val micBox = android.graphics.RectF()

    override fun onDraw(c: Canvas) {
        c.drawColor(palette.background); divider.color = palette.key
        drawMic(c); drawGear(c)
        val cells = if (composing) 3 else tools.size.coerceAtLeast(1)
        val cw = (width - rightEdge) / cells
        val fm = text.fontMetrics
        val y = height / 2f - (fm.ascent + fm.descent) / 2f
        for (i in 0 until if (!composing && tools.isEmpty()) 0 else cells) {
            val label: String; val strong: Boolean
            if (composing) {
                val it = items.getOrNull(i) ?: continue
                label = it.text; strong = it.highlight
            } else { label = tools[i].label; strong = tools[i] == Tool.INCOGNITO && incognito }
            text.typeface = if (strong) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            text.color = if (strong) palette.accent else if (composing) palette.text else palette.textMuted
            c.drawText(ellipsize(label, cw - 12 * density), i * cw + cw / 2f, y, text)
            if (i > 0) c.drawLine(i * cw, height * 0.28f, i * cw, height * 0.72f, divider)
        }
    }

    /** A small microphone at the right end of the strip, always there so voice is one tap away. */
    private fun drawMic(c: Canvas) {
        micPaint.color = if (micEnabled) palette.accent else palette.key
        val cx = width - micWidth / 2f; val cy = height / 2f; val d = density
        micBox.set(cx - 4.5f * d, cy - 11f * d, cx + 4.5f * d, cy + 3f * d); c.drawRoundRect(micBox, 4.5f * d, 4.5f * d, micPaint)
        micBox.set(cx - 9f * d, cy - 6f * d, cx + 9f * d, cy + 10f * d); c.drawArc(micBox, 20f, 140f, false, micPaint)
        c.drawLine(cx, cy + 10f * d, cx, cy + 14f * d, micPaint)
    }

    /** A small cog just left of the mic: settings, one tap away from anywhere. */
    private fun drawGear(c: Canvas) {
        micPaint.color = palette.textMuted
        val cx = width - micWidth - gearWidth / 2f; val cy = height / 2f; val d = density
        c.drawCircle(cx, cy, 4f * d, micPaint)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0); val cs = Math.cos(a).toFloat(); val sn = Math.sin(a).toFloat()
            c.drawLine(cx + cs * 7f * d, cy + sn * 7f * d, cx + cs * 10f * d, cy + sn * 10f * d, micPaint)
        }
    }

    private fun ellipsize(s: String, maxWidth: Float): String {
        if (text.measureText(s) <= maxWidth) return s
        var n = s.length
        while (n > 1 && text.measureText(s, 0, n) + text.measureText("…") > maxWidth) n--
        return s.substring(0, n) + "…"
    }

    private var micDownAt = 0L
    private var micPressed = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && e.x >= width - micWidth && micEnabled) { micPressed = true; micDownAt = e.eventTime; listener?.onMicDown(); return true }
        if (micPressed && (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)) { micPressed = false; listener?.onMicUp(e.eventTime - micDownAt); return true }
        if (micPressed) return true
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            if (e.x >= width - micWidth) return true
            if (e.x >= width - rightEdge) { listener?.onSettings(); return true }
            val cells = if (composing) 3 else tools.size.coerceAtLeast(1)
            if (!composing && tools.isEmpty()) return true
            val i = (e.x / ((width - rightEdge) / cells)).toInt().coerceIn(0, cells - 1)
            if (composing) { if (i < items.size) listener?.onSuggestion(i) } else listener?.onTool(tools[i])
        }
        return true
    }

    companion object { const val HEIGHT_DP = 44f }
}
