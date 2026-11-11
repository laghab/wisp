package app.wisp.keyboard.kbd

import android.os.SystemClock
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import app.wisp.keyboard.style.Theme
import app.wisp.keyboard.style.ThemeFonts

/**
 * The strip above the keys (48 dp). While a word is being typed it shows three suggestions with the best one bold in
 * the middle; otherwise it shows the tool icons. A grid button on the left swaps between the quick tools and the editing
 * tools, and the voice button is always on the right.
 */
class SuggestionBarView(context: Context, private val palette: Palette = Palette()) : View(context) {

    interface Listener {
        fun onSuggestion(index: Int)
        fun onTool(tool: Tool)
        /** The mic was tapped: start listening, or, while listening, stop and insert. */
        fun onMic()
        /** The gear was tapped: open Wisp's settings. */
        fun onSettings() {}
    }

    /** A tool on the strip: [icon] for the quick tools, a text [label] for the editing tools. */
    enum class Tool(val label: String, val icon: Icons.Icon?) {
        CLIPBOARD("Clipboard", Icons.Icon.CLIPBOARD), EMOJI("Emoji", Icons.Icon.EMOJI), REWRITE("Rewrite", Icons.Icon.SPARKLE), RESIZE("Resize", Icons.Icon.CROP), THEMES("Themes", Icons.Icon.THEME), SETTINGS("Settings", Icons.Icon.SLIDERS),
        SELECT_ALL("Select all", null), CUT("Cut", null), COPY("Copy", null), PASTE("Paste", null), INCOGNITO("Private", null);

        val isEdit get() = icon == null
    }

    var listener: Listener? = null

    /** False where voice input must not run (password fields). */
    var micEnabled = true
        set(v) { if (field != v) { field = v; invalidate() } }

    private var items: List<BarItem> = emptyList()

    /** While on, nothing typed is learned; the toggle is shown in the accent colour. */
    var incognito = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private var composing = false
    private var showEdit = false
    private var theme: Theme? = null
    private val density = resources.displayMetrics.density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 16f * density }
    private val line = Paint().apply { strokeWidth = 1f * density }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var tools: List<Tool> = Tool.values().toList()

    /** Which tools the strip shows, in order (the user can reorder and remove them). */
    fun setTools(list: List<Tool>) { if (list != tools) { tools = list; invalidate() } }

    /** Takes the font, colours and mic glow of the current theme. */
    fun setTheme(t: Theme) {
        theme = t
        text.typeface = ThemeFonts.typeface(context, t.font, if (t.weight > 500) 500 else t.weight)
        invalidate()
    }

    // Voice typing shows in this strip, not in a panel of its own: the live transcript takes the place of the suggestions and tools, and the
    // mic becomes the stop button. The keys below stay exactly as they are.
    private var voiceActive = false
    private var voiceText = ""
    private var voiceMessage: String? = null

    /** [active]: the microphone is open; [text]: what has been recognised so far; [message]: a status or problem to show instead of text. */
    fun setVoice(active: Boolean, text: String, message: String?) {
        if (voiceActive == active && voiceText == text && voiceMessage == message) return
        voiceActive = active; voiceText = text; voiceMessage = message
        invalidate()
    }

    fun setState(s: BarState) {
        if (items === s.items && composing == s.composing) return
        items = s.items; composing = s.composing
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (HEIGHT_DP * density).toInt())

    private val cell get() = 44f * density
    private val micSize get() = 40f * density
    private val micArea get() = 48f * density
    private val ink get() = theme?.stripFg ?: palette.textMuted
    private val quick get() = tools.filter { !it.isEdit }
    private val edit get() = tools.filter { it.isEdit }
    private fun row(): List<Tool> = if (showEdit) edit else quick

    override fun onDraw(c: Canvas) {
        val t = theme
        line.color = t?.sep ?: palette.key
        drawMic(c)
        val d = density
        if (voiceActive || voiceMessage != null) { drawTranscript(c); return }
        val left = if (composing) 4f * d else 4f * d + cell
        if (!composing) Icons.draw(c, Icons.Icon.GRID, 4f * d + cell / 2f, height / 2f, 20f * d, if (showEdit) palette.accent else ink)
        val x0 = left; val x1 = width - micArea
        val fm = text.fontMetrics
        val y = height / 2f - (fm.ascent + fm.descent) / 2f
        if (composing) {
            val cw = (x1 - x0) / 3f
            for (i in 0 until 3) {
                val it = items.getOrNull(i)?.takeIf { s -> s.text.isNotEmpty() } ?: continue
                text.typeface = Typeface.create(text.typeface, if (i == 1) Typeface.BOLD else Typeface.NORMAL)
                text.color = if (it.highlight) palette.accent else ink
                text.alpha = if (i == 1) 255 else 204
                val label = if (it.quoted) "“${it.text}”" else it.text      // as the word is: a theme's capitals look never turns suggestions into shouting
                c.drawText(ellipsize(label, cw - 12f * d), x0 + i * cw + cw / 2f, y, text)
                if (i > 0) c.drawLine(x0 + i * cw, height / 2f - 10f * d, x0 + i * cw, height / 2f + 10f * d, line)
            }
            text.alpha = 255
            return
        }
        val r = row()
        if (r.isEmpty()) return
        val cw = (x1 - x0) / r.size
        for ((i, tool) in r.withIndex()) {
            val cx = x0 + i * cw + cw / 2f
            val active = tool == Tool.INCOGNITO && incognito
            val col = if (active) palette.accent else if (tool == Tool.REWRITE) 0xFFFFB86B.toInt() else ink
            if (tool.icon != null) Icons.draw(c, tool.icon, cx, height / 2f, 21f * d, col)
            else {
                text.typeface = Typeface.create(text.typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
                text.color = col; text.textSize = 15f * d
                c.drawText(ellipsize(tool.label, cw - 8f * d), cx, y, text)
                text.textSize = 16f * d
                if (i > 0) c.drawLine(x0 + i * cw, height / 2f - 10f * d, x0 + i * cw, height / 2f + 10f * d, line)
            }
        }
    }

    /** The words so far: confirmed ones solid, the last three (still changing) dimmer; a long dictation keeps its newest words in view. */
    private fun drawTranscript(c: Canvas) {
        val d = density
        val y = height / 2f - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2f
        var x = 14f * d
        if (voiceActive) { fill.color = app.wisp.keyboard.style.Brand.RECORDING; c.drawCircle(x + 3.5f * d, height / 2f, 3.5f * d, fill); x += 18f * d }
        val room = width - micArea - x - 8f * d
        text.textAlign = Paint.Align.LEFT
        val message = voiceMessage
        if (voiceText.isBlank() || message != null) {
            text.color = if (message != null && !voiceActive) palette.accent else ink; text.alpha = 200
            c.drawText(ellipsize(message ?: "Listening…", room), x, y, text)
        } else {
            val words = voiceText.trim()
            var from = 0
            while (from < words.length - 1 && text.measureText(words, from, words.length) > room) from++
            val shown = words.substring(from)
            val tent = words.split(' ').takeLast(3).joinToString(" ").length.coerceAtMost(shown.length)
            val solid = shown.substring(0, shown.length - tent)
            text.color = palette.text; text.alpha = 255
            c.drawText(solid, x, y, text)
            text.alpha = 140
            c.drawText(shown.substring(solid.length), x + text.measureText(solid), y, text)
        }
        text.alpha = 255; text.textAlign = Paint.Align.CENTER
    }

    /** The voice button, always there so voice is one tap away: an accent disc with the mic on it (a stop square while listening). */
    private fun drawMic(c: Canvas) {
        val t = theme
        val cx = width - micArea / 2f; val cy = height / 2f
        val acc = if (micEnabled) palette.accent else palette.key
        fill.color = acc
        fill.setShadowLayer(0f, 0f, 0f, 0)
        if (micEnabled && t?.micGlow != null) { fill.setShadowLayer(t.micGlow.blur * density, 0f, 0f, t.micGlow.color); setLayerType(LAYER_TYPE_SOFTWARE, null) }
        if (voiceActive) {
            // A ring that swells and fades while the microphone is open.
            val ph = (SystemClock.uptimeMillis() % 1500) / 1500f
            fill.alpha = ((1f - ph) * 110).toInt(); c.drawCircle(cx, cy, micSize / 2f * (1f + 0.6f * ph), fill); fill.alpha = 255
            postInvalidateOnAnimation()
        }
        c.drawCircle(cx, cy, micSize / 2f, fill)
        fill.clearShadowLayer()
        if (voiceActive) { fill.color = palette.onAccent; c.drawRoundRect(cx - 7f * density, cy - 7f * density, cx + 7f * density, cy + 7f * density, 2.5f * density, 2.5f * density, fill) }
        else Icons.draw(c, Icons.Icon.MIC, cx, cy, 19f * density, if (micEnabled) palette.onAccent else palette.textMuted, strokeWidth = 1.9f)
    }

    private fun ellipsize(s: String, maxWidth: Float): String {
        if (text.measureText(s) <= maxWidth) return s
        var n = s.length
        while (n > 1 && text.measureText(s, 0, n) + text.measureText("…") > maxWidth) n--
        return s.substring(0, n) + "…"
    }

    private var micPressed = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && e.x >= width - micArea && micEnabled) { micPressed = true; return true }
        if (micPressed && (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)) { micPressed = false; if (e.actionMasked == MotionEvent.ACTION_UP) listener?.onMic(); return true }
        if (micPressed) return true
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            if (e.x >= width - micArea) return true
            val x0 = if (composing) 4f * density else 4f * density + cell
            if (composing) {
                val i = ((e.x - x0) / ((width - micArea - x0) / 3f)).toInt().coerceIn(0, 2)
                if (items.getOrNull(i)?.text?.isNotEmpty() == true) listener?.onSuggestion(i)
                return true
            }
            if (e.x < x0) { showEdit = !showEdit; invalidate(); return true }
            val r = row()
            if (r.isEmpty()) return true
            val i = ((e.x - x0) / ((width - micArea - x0) / r.size)).toInt().coerceIn(0, r.size - 1)
            if (r[i] == Tool.SETTINGS) listener?.onSettings() else listener?.onTool(r[i])
        }
        return true
    }

    companion object { const val HEIGHT_DP = 48f }
}
