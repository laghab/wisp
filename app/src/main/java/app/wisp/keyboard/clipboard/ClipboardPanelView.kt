package app.wisp.keyboard.clipboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import app.wisp.keyboard.kbd.Icons
import app.wisp.keyboard.style.Theme
import app.wisp.keyboard.style.ThemeFonts

/**
 * The clipboard panel (the handoff's Clipboard screen): a header with the history switch, filter chips, a two-column grid of
 * copied items, and a privacy note. One View drawn directly; tap inserts an item, long-press offers Pin and Delete.
 */
class ClipboardPanelView(context: Context, private val history: ClipboardHistory, private var theme: Theme) : View(context) {

    interface Listener {
        fun onInsert(item: ClipItem)
        fun onBack()
        /** The history switch changed to [on]. */
        fun onHistoryToggled(on: Boolean)
        fun onChanged()
    }

    var listener: Listener? = null

    /** Why the last look at the clipboard found nothing: null when it worked, otherwise a short reason shown in the empty panel. */
    var problem: String? = null
    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()

    private var filter = ClipFilter.ALL
    private var scroll = 0f
    private var menuFor = -1L
    private var items: List<ClipItem> = emptyList()

    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f * d }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * d }
    private val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * d }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f * d }
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 19f * d; typeface = Typeface.MONOSPACE; letterSpacing = 0.06f }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d }
    private val box = RectF()

    private val chips = listOf(ClipFilter.ALL to "All", ClipFilter.PINNED to "Pinned", ClipFilter.LINKS to "Links", ClipFilter.CODES to "Codes", ClipFilter.IMAGES to "Images")
    private val chipRects = ArrayList<RectF>()
    private val historyRect = RectF(); private val backRect = RectF()
    private val cardRects = ArrayList<RectF>()
    private val menuRects = ArrayList<Pair<RectF, String>>()

    fun setTheme(t: Theme) {
        theme = t
        val face = ThemeFonts.typeface(context, t.font, 600)
        title.typeface = face; label.typeface = ThemeFonts.typeface(context, t.font, 500); tag.typeface = label.typeface; body.typeface = ThemeFonts.typeface(context, t.font, t.weight.coerceAtMost(500))
        invalidate()
    }

    /** Reloads from the history (the panel was opened, or something was copied). */
    fun refresh() { items = history.items(filter); scroll = scroll.coerceIn(0f, maxScroll()); menuFor = -1; invalidate() }

    private val headerH get() = 48f * d
    private val chipsH get() = 44f * d
    private val footerH get() = 28f * d
    private val gridTop get() = headerH + chipsH
    private val cardH get() = 96f * d
    private val gap get() = 8f * d
    private val pad get() = 12f * d

    private fun maxScroll(): Float {
        val rows = (items.size + 1) / 2
        val content = rows * (cardH + gap)
        return (content - (height - gridTop - footerH)).coerceAtLeast(0f)
    }

    override fun onDraw(c: Canvas) {
        val bg = theme.board.first().base() or (0xFF shl 24)
        c.drawColor(bg)
        text(theme)
        // Header
        backRect.set(8f * d, 2f * d, 8f * d + 44f * d, 46f * d)
        Icons.draw(c, Icons.Icon.BACK_CHEVRON, backRect.centerX(), backRect.centerY(), 22f * d, theme.stripFg)
        title.color = theme.fg
        c.drawText("Clipboard", backRect.right + 4f * d, headerH / 2f - (title.fontMetrics.ascent + title.fontMetrics.descent) / 2f, title)
        val on = history.enabled
        val hl = if (on) "History on" else "History off"
        val hw = label.measureText(hl) + 24f * d
        historyRect.set(width - 12f * d - hw, (headerH - 32f * d) / 2f, width - 12f * d, (headerH + 32f * d) / 2f)
        fill.color = if (on) accent() else theme.mod.base() or (0xFF shl 24)
        c.drawRoundRect(historyRect, 16f * d, 16f * d, fill)
        label.color = if (on) theme.accentFg else theme.fg
        label.typeface = Typeface.create(label.typeface, Typeface.BOLD)
        c.drawText(hl, historyRect.centerX() - label.measureText(hl) / 2f, historyRect.centerY() - (label.fontMetrics.ascent + label.fontMetrics.descent) / 2f, label)
        label.typeface = Typeface.create(label.typeface, Typeface.NORMAL)
        // Filter chips
        chipRects.clear()
        var x = pad
        for ((f, name) in chips) {
            val w = label.measureText(name) + 24f * d
            val r = RectF(x, headerH + 4f * d, x + w, headerH + 4f * d + 32f * d); chipRects.add(r)
            val sel = f == filter
            fill.color = if (sel) accent() else 0x00000000; if (sel) c.drawRoundRect(r, 16f * d, 16f * d, fill)
            line.color = if (sel) accent() else theme.sep; c.drawRoundRect(r, 16f * d, 16f * d, line)
            label.color = if (sel) theme.accentFg else theme.stripFg
            c.drawText(name, r.centerX() - label.measureText(name) / 2f, r.centerY() - (label.fontMetrics.ascent + label.fontMetrics.descent) / 2f, label)
            x += w + 6f * d
        }
        // Body
        c.save(); c.clipRect(0f, gridTop, width.toFloat(), height - footerH)
        cardRects.clear(); menuRects.clear()
        if (!history.enabled) empty(c, "History is off", "Copied text is not kept. Turn it on to see recent copies here.")
        else if (items.isEmpty() && problem != null) empty(c, "Can't read the clipboard", problem!!)
        else if (items.isEmpty()) empty(c, "Nothing here yet", "Things you copy appear here for an hour. Pin the ones you reuse.")
        else {
            val cw = (width - 2 * pad - gap) / 2f
            for ((i, it) in items.withIndex()) {
                val col = i % 2; val row = i / 2
                val left = pad + col * (cw + gap); val top = gridTop + row * (cardH + gap) - scroll
                val r = RectF(left, top, left + cw, top + cardH); cardRects.add(r)
                if (r.bottom < gridTop || r.top > height - footerH) continue
                card(c, r, it)
            }
        }
        c.restore()
        // Footer
        line.color = theme.sep; c.drawLine(0f, height - footerH, width.toFloat(), height - footerH, line)
        Icons.draw(c, Icons.Icon.SHIELD, width / 2f - 150f * d, height - footerH / 2f, 13f * d, 0xFF6FD3A6.toInt(), strokeWidth = 2f)
        tag.color = theme.stripFg
        c.drawText("Passwords are never stored · unpinned items clear after 1 h", width / 2f - 138f * d, height - footerH / 2f - (tag.fontMetrics.ascent + tag.fontMetrics.descent) / 2f, tag)
    }

    private fun accent() = theme.accent.base() or (0xFF shl 24)

    private fun text(t: Theme) { /* paints take their colours where they draw */ }

    private fun empty(c: Canvas, head: String, sub: String) {
        title.color = theme.fg; c.drawText(head, width / 2f - title.measureText(head) / 2f, gridTop + 70f * d, title)
        tag.color = theme.stripFg; c.drawText(sub, width / 2f - tag.measureText(sub) / 2f, gridTop + 94f * d, tag)
    }

    private fun card(c: Canvas, r: RectF, it: ClipItem) {
        fill.color = theme.key.base() or (0xFF shl 24); c.drawRoundRect(r, 14f * d, 14f * d, fill)
        line.color = theme.sep; c.drawRoundRect(r, 14f * d, 14f * d, line)
        val tagColor = if (it.pinned) accent() else theme.stripFg
        tag.color = tagColor
        val ix = r.left + 12f * d + 6f * d; val iy = r.top + 18f * d
        val icon = when { it.pinned -> Icons.Icon.PIN; it.kind == Kind.LINK -> Icons.Icon.LINK; it.kind == Kind.CODE -> Icons.Icon.CLOCK; else -> null }
        var tx = r.left + 12f * d
        if (icon != null) { Icons.draw(c, icon, ix, iy, 12f * d, tagColor, strokeWidth = 2f); tx += 18f * d }
        c.drawText(tagText(it), tx, iy - (tag.fontMetrics.ascent + tag.fontMetrics.descent) / 2f, tag)
        val p = if (it.kind == Kind.CODE) mono else body
        p.color = theme.fg
        drawWrapped(c, if (it.kind == Kind.IMAGE) "Tap to send as image" else it.text, r.left + 12f * d, r.top + 38f * d, r.width() - 24f * d, r.bottom - 8f * d, p)
        if (menuFor == it.id) {
            fill.color = 0xEE000000.toInt(); c.drawRoundRect(r, 14f * d, 14f * d, fill)
            val bw = (r.width() - 36f * d) / 2f
            for ((k, name) in listOf(if (it.pinned) "Unpin" else "Pin", "Delete").withIndex()) {
                val b = RectF(r.left + 12f * d + k * (bw + 12f * d), r.centerY() - 18f * d, r.left + 12f * d + k * (bw + 12f * d) + bw, r.centerY() + 18f * d)
                fill.color = if (k == 0) accent() else 0x44FFFFFF; c.drawRoundRect(b, 18f * d, 18f * d, fill)
                label.color = if (k == 0) theme.accentFg else 0xFFFFFFFF.toInt()
                c.drawText(name, b.centerX() - label.measureText(name) / 2f, b.centerY() - (label.fontMetrics.ascent + label.fontMetrics.descent) / 2f, label)
                menuRects.add(b to name)
            }
        }
    }

    private fun tagText(it: ClipItem): String {
        val ms = history.expiresInMs(it)
        return when {
            it.pinned -> "Pinned"
            it.kind == Kind.CODE && ms != null -> "Code · clears in ${((ms + 59_999) / 60_000).coerceAtLeast(1)} min"
            it.kind == Kind.LINK -> "Link"
            it.kind == Kind.IMAGE -> "Image"
            else -> "Copied"
        }
    }

    private fun drawWrapped(c: Canvas, s: String, x: Float, y0: Float, w: Float, bottom: Float, p: Paint) {
        var y = y0 - p.fontMetrics.ascent
        val lh = p.fontSpacing
        var rest = s.replace('\n', ' ')
        var lines = 0
        while (rest.isNotEmpty() && y <= bottom) {
            val n = p.breakText(rest, true, w, null).coerceAtLeast(1)
            var cut = n
            if (n < rest.length) { val sp = rest.lastIndexOf(' ', n); if (sp > n / 2) cut = sp }
            val last = y + lh > bottom && cut < rest.length
            val seg = rest.substring(0, cut).trim()
            c.drawText(if (last) seg.dropLast(1) + "…" else seg, x, y, p)
            rest = rest.substring(cut).trimStart(); y += lh; lines++
            if (lines >= 3) { if (rest.isNotEmpty()) { /* clipped */ }; break }
        }
    }

    // ---- touch

    private var downX = 0f; private var downY = 0f; private var lastY = 0f
    private var dragging = false
    private var longFired = false
    private val longPress = Runnable {
        val i = cardAt(downX, downY)
        if (i >= 0 && !dragging) { menuFor = items[i].id; longFired = true; performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); invalidate() }
    }

    private fun cardAt(x: Float, y: Float): Int { if (y < gridTop || y > height - footerH) return -1; return cardRects.indexOfFirst { it.contains(x, y) } }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; lastY = e.y; dragging = false; longFired = false; parent?.requestDisallowInterceptTouchEvent(true); postDelayed(longPress, longPressMs) }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && Math.abs(e.y - downY) > slop) { dragging = true; removeCallbacks(longPress) }
                if (dragging) { scroll = (scroll - (e.y - lastY)).coerceIn(0f, maxScroll()); invalidate() }
                lastY = e.y
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
            MotionEvent.ACTION_UP -> { removeCallbacks(longPress); if (!dragging && !longFired) tap(e.x, e.y) }
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        if (menuFor != -1L) {
            val id = menuFor; menuFor = -1
            val hit = menuRects.firstOrNull { it.first.contains(x, y) }?.second
            val item = items.firstOrNull { it.id == id }
            if (hit != null && item != null) { if (hit == "Delete") history.remove(id) else history.pin(id, !item.pinned); listener?.onChanged(); refresh() } else invalidate()
            return
        }
        if (backRect.contains(x, y)) { listener?.onBack(); return }
        if (historyRect.contains(x, y)) { history.enabled = !history.enabled; listener?.onHistoryToggled(history.enabled); refresh(); return }
        chipRects.indexOfFirst { it.contains(x, y) }.takeIf { it >= 0 }?.let { filter = chips[it].first; scroll = 0f; refresh(); return }
        val i = cardAt(x, y)
        if (i >= 0) listener?.onInsert(items[i])
    }

    override fun onDetachedFromWindow() { removeCallbacks(longPress); super.onDetachedFromWindow() }
}
