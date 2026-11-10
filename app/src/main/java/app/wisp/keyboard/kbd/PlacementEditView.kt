package app.wisp.keyboard.kbd

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import app.wisp.keyboard.style.Theme

/**
 * Edit mode over the open keyboard, like Gboard's: a frame with handles on the edges and corners to resize it like a crop, a drag
 * anywhere inside to move it (up leaves a gap below), the layout modes along the top, and Reset and Done. It only edits a
 * [Placement]; the keyboard re-solves and redraws live through [Listener.onChange].
 */
class PlacementEditView(context: Context, private val theme: Theme, private val accent: Int) : View(context) {

    interface Listener {
        /** The placement was edited; the keyboard is already showing it. */
        fun onChange(p: Placement)
        fun onDone()
        fun onReset()
        fun onNumberRow()
    }

    var listener: Listener? = null
    /** Whether the digit row is on; the panel shows it as a toggle. */
    var numberRow = false
        set(v) { field = v; invalidate() }
    private val d = resources.displayMetrics.density
    private val screenW = resources.displayMetrics.widthPixels / d
    private val screenH = resources.displayMetrics.heightPixels / d

    private var placement = Placement()
    private var frame: Placement.Frame = placement.solve(screenW, screenH)

    /** Called by the host after it applied [p]. */
    fun show(p: Placement, f: Placement.Frame) { placement = p; frame = f; invalidate() }

    private val panelH get() = PANEL_DP * d
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 13f * d }
    private val box = RectF()

    private val modes = listOf("Full" to Placement.Mode.FULL, "Left hand" to Placement.Mode.ONE_HANDED_LEFT, "Right hand" to Placement.Mode.ONE_HANDED_RIGHT, "Floating" to Placement.Mode.FLOATING, "Split" to Placement.Mode.SPLIT)
    private val buttons = ArrayList<Pair<RectF, () -> Unit>>()

    /** The keyboard's rectangle in this view, px: the window is bottom-aligned, the gap sits under the keyboard. */
    private fun frameRect(r: RectF) {
        val bottom = height - frame.gap * d
        r.set(frame.left * d, bottom - frame.height * d, (frame.left + frame.width) * d, bottom)
    }

    override fun onDraw(c: Canvas) {
        val r = RectF(); frameRect(r)
        // Scrim outside the keyboard, so the frame reads as the thing being edited.
        fill.color = 0x66000000
        c.drawRect(0f, 0f, width.toFloat(), r.top, fill); c.drawRect(0f, r.bottom, width.toFloat(), height.toFloat(), fill)
        c.drawRect(0f, r.top, r.left, r.bottom, fill); c.drawRect(r.right, r.top, width.toFloat(), r.bottom, fill)
        stroke.color = accent
        c.drawRect(r, stroke)
        // Handles: pills on the edges, discs on the corners.
        fill.color = accent
        val hw = 22f * d; val ht = 5f * d
        for ((cx, cy) in listOf((r.left + r.right) / 2f to r.top, (r.left + r.right) / 2f to r.bottom)) { box.set(cx - hw, cy - ht, cx + hw, cy + ht); c.drawRoundRect(box, ht, ht, fill) }
        for ((cx, cy) in listOf(r.left to (r.top + r.bottom) / 2f, r.right to (r.top + r.bottom) / 2f)) { box.set(cx - ht, cy - hw, cx + ht, cy + hw); c.drawRoundRect(box, ht, ht, fill) }
        for ((cx, cy) in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)) c.drawCircle(cx, cy, 8f * d, fill)
        // The move hint in the middle.
        Icons.draw(c, Icons.Icon.CROP, (r.left + r.right) / 2f, (r.top + r.bottom) / 2f, 28f * d, 0xCCFFFFFF.toInt())

        drawPanel(c)
    }

    private fun drawPanel(c: Canvas) {
        buttons.clear()
        fill.color = theme.board.first().base() or (0xFF shl 24)
        c.drawRect(0f, 0f, width.toFloat(), panelH, fill)
        var x = 8f * d; val y0 = 6f * d; val h = panelH - 12f * d
        val items: List<Triple<String, Boolean, () -> Unit>> = modes.map { (label, m) -> Triple<String, Boolean, () -> Unit>(label, m == placement.mode) { setMode(m) } } +
            listOf(Triple<String, Boolean, () -> Unit>("Number row", numberRow) { listener?.onNumberRow() }, Triple<String, Boolean, () -> Unit>("Reset", false) { listener?.onReset() }, Triple<String, Boolean, () -> Unit>("Done", true) { listener?.onDone() })
        // Done is always visible: lay the buttons out from the right if they would not fit.
        val widths = items.map { text.measureText(it.first) + 22f * d }
        val total = widths.sum() + (items.size - 1) * 6f * d
        x = if (total + 16f * d > width) 8f * d - (total + 16f * d - width) else 8f * d
        val doneIdx = items.size - 1
        for ((i, it) in items.withIndex()) {
            val w = widths[i]
            val rx = if (i == doneIdx && total + 16f * d > width) width - 8f * d - w else x
            box.set(rx, y0, rx + w, y0 + h)
            val on = it.second
            fill.color = if (it.first == "Done") accent else if (on) (accent and 0x00FFFFFF) or 0x55000000 else 0x33FFFFFF
            c.drawRoundRect(box, h / 2f, h / 2f, fill)
            text.color = if (it.first == "Done") theme.accentFg else theme.fg
            c.drawText(it.first, box.centerX(), box.centerY() - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2f, text)
            buttons.add(RectF(box) to it.third)
            x += w + 6f * d
        }
    }

    private fun setMode(m: Placement.Mode) {
        val w = when (m) { Placement.Mode.ONE_HANDED_LEFT, Placement.Mode.ONE_HANDED_RIGHT -> if (placement.width > 0.9f) 0.8f else placement.width; Placement.Mode.SPLIT -> 1f; else -> placement.width }
        listener?.onChange(placement.copy(mode = m, width = w))
    }

    // ---- dragging

    private enum class Grab { NONE, MOVE, TOP, BOTTOM, LEFT, RIGHT }
    private var grabs = emptySet<Grab>()
    private var startPlacement = placement
    private var startFrame = frame
    private var startX = 0f; private var startY = 0f
    private var pressedButton: (() -> Unit)? = null

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedButton = buttons.firstOrNull { it.first.contains(e.x, e.y) }?.second
                if (pressedButton != null) return true
                grabs = hit(e.x, e.y)
                startPlacement = placement; startFrame = frame; startX = e.x; startY = e.y
                return true
            }
            MotionEvent.ACTION_MOVE -> if (grabs.isNotEmpty() && pressedButton == null) drag((e.x - startX) / d, (e.y - startY) / d)
            MotionEvent.ACTION_UP -> { if (pressedButton != null && buttons.any { it.second === pressedButton && it.first.contains(e.x, e.y) }) pressedButton?.invoke(); pressedButton = null; grabs = emptySet() }
            MotionEvent.ACTION_CANCEL -> { pressedButton = null; grabs = emptySet() }
        }
        return true
    }

    /** Which edges the touch grabs: a corner grabs two, an edge one, the inside moves the whole keyboard. */
    private fun hit(x: Float, y: Float): Set<Grab> {
        val r = RectF(); frameRect(r)
        val slop = 28f * d
        if (x < r.left - slop || x > r.right + slop || y < r.top - slop || y > r.bottom + slop) return emptySet()
        val g = HashSet<Grab>()
        if (Math.abs(y - r.top) < slop) g.add(Grab.TOP)
        if (Math.abs(y - r.bottom) < slop) g.add(Grab.BOTTOM)
        if (Math.abs(x - r.left) < slop) g.add(Grab.LEFT)
        if (Math.abs(x - r.right) < slop) g.add(Grab.RIGHT)
        // Edges are only handles; a thin keyboard must still be movable from its middle.
        if (g.isEmpty() || (g == setOf(Grab.TOP, Grab.BOTTOM))) return setOf(Grab.MOVE)
        return g
    }

    private fun drag(dx: Float, dy: Float) {
        val f0 = startFrame; val p0 = startPlacement
        var left = f0.left; var width = f0.width; var height = f0.height; var gap = f0.gap
        if (Grab.MOVE in grabs) { left += dx; gap -= dy }
        if (Grab.TOP in grabs) height -= dy
        if (Grab.BOTTOM in grabs) { height += dy; gap -= dy }
        if (Grab.LEFT in grabs) { left += dx; width -= dx }
        if (Grab.RIGHT in grabs) width += dx
        val keys = Placement.KEYS_DP
        val newWidth = (width / screenW).coerceIn(0f, 1f)
        val x = if (screenW - width > 1f) (left / (screenW - width)).coerceIn(0f, 1f) else 0f
        listener?.onChange(p0.copy(width = newWidth, height = (height - Placement.STRIP_DP) / keys, x = x, gap = gap))
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))

    companion object { const val PANEL_DP = 48f }
}
