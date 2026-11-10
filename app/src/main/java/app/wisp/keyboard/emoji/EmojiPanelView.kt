package app.wisp.keyboard.emoji

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
 * The emoji, kaomoji and symbols panel (the handoff's Emoji screen): tabs, a search box, a category bar, recents and a scrolling grid.
 * One View drawn directly. Searching types into the box through [appendQuery] and [backspaceQuery]: the keyboard's own keys are shown
 * below the panel while it is in [compact] mode, so no text field is needed.
 */
class EmojiPanelView(context: Context, private val data: EmojiData, private val recents: RecentEmoji, private var theme: Theme) : View(context) {

    interface Listener {
        fun onInsert(text: String, isEmoji: Boolean)
        /** The search box was tapped: show the keyboard's keys under the panel. */
        fun onSearchTapped()
    }

    enum class Tab { EMOJI, KAOMOJI, SYMBOLS }

    var listener: Listener? = null
    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    var tab = Tab.EMOJI
        private set
    var query = ""
        private set
    /** Search mode: tabs and categories are hidden to make room for the keys below. */
    var compact = false
        set(v) { if (field != v) { field = v; scroll = 0f; relayout(); invalidate() } }
    private var scroll = 0f

    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * d; textAlign = Paint.Align.CENTER }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * d }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f * d; textAlign = Paint.Align.CENTER }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f * d; textAlign = Paint.Align.CENTER }
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f * d; textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d }
    private val box = RectF()

    fun setTheme(t: Theme) {
        theme = t
        label.typeface = ThemeFonts.typeface(context, t.font, 600); small.typeface = ThemeFonts.typeface(context, t.font, 500); text.typeface = ThemeFonts.typeface(context, t.font, 400)
        invalidate()
    }

    fun setTab(t: Tab) { if (tab != t) { tab = t; query = ""; scroll = 0f; relayout(); invalidate() } }
    fun appendQuery(s: String) { if (query.length < 40) { query += s; scroll = 0f; relayout(); invalidate() } }
    fun backspaceQuery(): Boolean { if (query.isEmpty()) return false; query = query.dropLast(1); scroll = 0f; relayout(); invalidate(); return true }

    // ---- layout: a list of rows (a section header, or a row of cells) with their tops

    private class Row(val top: Float, val height: Float, val title: String?, val cells: List<String>)
    private var rows: List<Row> = emptyList()
    private var contentH = 0f
    private val sectionTops = ArrayList<Pair<Int, Float>>()    // group index (-1 = recent) -> top

    private val cols get() = if (tab == Tab.KAOMOJI) 2 else 8
    private val cellH get() = if (tab == Tab.KAOMOJI) 48f * d else 44f * d

    private fun relayout() {
        val out = ArrayList<Row>(); sectionTops.clear()
        var y = 0f
        fun addCells(items: List<String>) {
            for (i in items.indices step cols) { out.add(Row(y, cellH, null, items.subList(i, minOf(i + cols, items.size)))); y += cellH }
        }
        fun header(t: String, group: Int) { sectionTops.add(group to y); out.add(Row(y, 26f * d, t, emptyList())); y += 26f * d }
        when (tab) {
            Tab.EMOJI -> if (query.isNotEmpty()) addCells(data.search(query).map { it.ch })
            else {
                val rec = recents.list()
                if (rec.isNotEmpty()) { header("Recent", -1); addCells(rec) }
                for (g in EmojiData.GROUPS.indices) { header(EmojiData.GROUPS[g], g); addCells(data.emoji.filter { it.group == g }.map { it.ch }) }
            }
            Tab.KAOMOJI -> addCells(data.kaomoji)
            Tab.SYMBOLS -> addCells(data.symbols)
        }
        rows = out; contentH = y
        scroll = scroll.coerceIn(0f, maxScroll())
    }

    private val tabsTop get() = if (compact) 0f else 8f * d
    private val tabsH get() = if (compact) 0f else 36f * d + 8f * d
    private val searchTop get() = tabsTop + tabsH
    private val searchH get() = if (tab == Tab.EMOJI) 40f * d + 8f * d else 0f
    private val catsH get() = if (tab == Tab.EMOJI && !compact && query.isEmpty()) 38f * d else 0f
    private val gridTop get() = searchTop + searchH + catsH

    private fun maxScroll() = (contentH - (height - gridTop)).coerceAtLeast(0f)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { relayout() }

    // ---- drawing

    private val tabRects = ArrayList<RectF>()
    private val catRects = ArrayList<Pair<RectF, Int>>()
    private val searchRect = RectF()

    private fun accent() = theme.accent.base() or (0xFF shl 24)
    private fun surface() = theme.key.base() or (0xFF shl 24)

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.board.first().base() or (0xFF shl 24))
        if (!compact) drawTabs(c)
        if (tab == Tab.EMOJI) drawSearch(c)
        if (catsH > 0f) drawCats(c)
        // Grid
        c.save(); c.clipRect(0f, gridTop, width.toFloat(), height.toFloat())
        val cw = width.toFloat() / cols
        val pad = if (tab == Tab.EMOJI) 0f else 4f * d
        for (r in rows) {
            val top = gridTop + r.top - scroll
            if (top + r.height < gridTop || top > height) continue
            if (r.title != null) {
                small.color = theme.stripFg
                c.drawText(r.title.uppercase(), 14f * d, top + r.height - 7f * d, small)
                continue
            }
            for ((i, s) in r.cells.withIndex()) {
                val cx = i * cw + cw / 2f
                when (tab) {
                    Tab.EMOJI -> c.drawText(s, cx, top + r.height / 2f - (glyph.fontMetrics.ascent + glyph.fontMetrics.descent) / 2f, glyph)
                    Tab.KAOMOJI -> { box.set(i * cw + pad, top + 2f * d, (i + 1) * cw - pad, top + r.height - 2f * d); fill.color = surface(); c.drawRoundRect(box, 12f * d, 12f * d, fill); text.color = theme.fg; c.drawText(s, box.centerX(), box.centerY() - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2f, text) }
                    Tab.SYMBOLS -> { box.set(i * cw + 2f * d, top + 2f * d, (i + 1) * cw - 2f * d, top + r.height - 2f * d); fill.color = surface(); c.drawRoundRect(box, 10f * d, 10f * d, fill); mono.color = theme.fg; c.drawText(s, box.centerX(), box.centerY() - (mono.fontMetrics.ascent + mono.fontMetrics.descent) / 2f, mono) }
                }
            }
        }
        if (rows.isEmpty() && query.isNotEmpty()) { small.color = theme.stripFg; c.drawText("No emoji match “$query”", 16f * d, gridTop + 30f * d, small) }
        c.restore()
    }

    private fun drawTabs(c: Canvas) {
        tabRects.clear()
        val names = listOf("Emoji", "Kaomoji", "Symbols")
        val w = (width - 16f * d - 8f * d) / 3f
        for ((i, n) in names.withIndex()) {
            val r = RectF(8f * d + i * (w + 4f * d), tabsTop, 8f * d + i * (w + 4f * d) + w, tabsTop + 36f * d); tabRects.add(r)
            val sel = Tab.values()[i] == tab
            fill.color = if (sel) accent() else surface(); c.drawRoundRect(r, 18f * d, 18f * d, fill)
            label.color = if (sel) theme.accentFg else theme.stripFg
            c.drawText(n, r.centerX(), r.centerY() - (label.fontMetrics.ascent + label.fontMetrics.descent) / 2f, label)
        }
    }

    private fun drawSearch(c: Canvas) {
        searchRect.set(12f * d, searchTop, width - 12f * d, searchTop + 40f * d)
        fill.color = surface(); c.drawRoundRect(searchRect, 20f * d, 20f * d, fill)
        line.color = if (compact) accent() else theme.sep; c.drawRoundRect(searchRect, 20f * d, 20f * d, line)
        Icons.draw(c, Icons.Icon.SEARCH, searchRect.left + 22f * d, searchRect.centerY(), 16f * d, theme.stripFg, strokeWidth = 2f)
        text.textAlign = Paint.Align.LEFT
        text.color = if (query.isEmpty()) theme.stripFg else theme.fg
        val shown = if (query.isEmpty()) "Search emoji" else query
        c.drawText(shown, searchRect.left + 38f * d, searchRect.centerY() - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2f, text)
        if (compact) { val x = searchRect.left + 38f * d + (if (query.isEmpty()) 0f else text.measureText(query)) + 1f * d; fill.color = accent(); c.drawRect(x, searchRect.centerY() - 9f * d, x + 2f * d, searchRect.centerY() + 9f * d, fill) }
        text.textAlign = Paint.Align.CENTER
    }

    private val catGlyphs = listOf("😀", "🧑", "🐶", "🍔", "✈️", "⚽", "💡", "❤️", "🏁")

    private fun drawCats(c: Canvas) {
        catRects.clear()
        val top = searchTop + searchH
        val hasRecent = recents.list().isNotEmpty()
        val entries = (if (hasRecent) listOf(-1) else emptyList()) + EmojiData.GROUPS.indices.toList()
        val w = width.toFloat() / entries.size
        for ((i, g) in entries.withIndex()) {
            val r = RectF(i * w, top, (i + 1) * w, top + 38f * d); catRects.add(r to g)
            if (g == -1) Icons.draw(c, Icons.Icon.CLOCK, r.centerX(), r.centerY(), 18f * d, theme.stripFg)
            else { glyph.textSize = 19f * d; c.drawText(catGlyphs[g], r.centerX(), r.centerY() - (glyph.fontMetrics.ascent + glyph.fontMetrics.descent) / 2f, glyph); glyph.textSize = 26f * d }
        }
        line.color = theme.sep; c.drawLine(0f, top + 38f * d, width.toFloat(), top + 38f * d, line)
    }

    // ---- touch

    private var downX = 0f; private var downY = 0f; private var lastY = 0f; private var dragging = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; lastY = e.y; dragging = false; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && Math.abs(e.y - downY) > slop && downY >= gridTop) dragging = true
                if (dragging) { scroll = (scroll - (e.y - lastY)).coerceIn(0f, maxScroll()); invalidate() }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> if (!dragging) tap(e.x, e.y)
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        if (!compact) tabRects.indexOfFirst { it.contains(x, y) }.takeIf { it >= 0 }?.let { setTab(Tab.values()[it]); return }
        if (tab == Tab.EMOJI && searchRect.contains(x, y)) { listener?.onSearchTapped(); return }
        catRects.firstOrNull { it.first.contains(x, y) }?.let { (_, g) -> sectionTops.firstOrNull { it.first == g }?.let { scroll = it.second.coerceIn(0f, maxScroll()); invalidate() }; return }
        if (y < gridTop) return
        val cw = width.toFloat() / cols
        val row = rows.firstOrNull { it.title == null && y - gridTop + scroll >= it.top && y - gridTop + scroll < it.top + it.height } ?: return
        val i = (x / cw).toInt()
        val s = row.cells.getOrNull(i) ?: return
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        listener?.onInsert(s, tab == Tab.EMOJI)
    }
}
