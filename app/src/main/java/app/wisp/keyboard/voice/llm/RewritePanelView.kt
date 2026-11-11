package app.wisp.keyboard.voice.llm

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import app.wisp.keyboard.kbd.Icons
import app.wisp.keyboard.style.Theme
import app.wisp.keyboard.style.ThemeFonts

/**
 * The rewrite panel (the handoff's Rewrite screen): tone chips, a result card that shimmers while the model writes, and Replace / Insert
 * below / Regenerate. Nothing here changes the field: it only reports what the user chose.
 */
class RewritePanelView(context: Context, private val rewrite: RewriteController, private var theme: Theme) : View(context) {

    interface Listener {
        fun onTone(tone: Tone)
        fun onReplace(text: String)
        fun onInsertBelow(text: String)
        fun onRegenerate()
        fun onClose()
        fun onOpenModels()
    }

    var listener: Listener? = null
    /** What there is to rewrite: empty shows a hint instead of the chips' result. */
    var hasText = true
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val amber = 0xFFFFB86B.toInt(); private val amberSoft = 0xFFFFCF99.toInt()
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f * d }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f * d; textAlign = Paint.Align.CENTER }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * d }
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d }
    private val box = RectF()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val chipRects = ArrayList<Pair<RectF, Tone>>()
    private var chipScroll = 0f
    private val closeRect = RectF(); private val replaceRect = RectF(); private val belowRect = RectF(); private val againRect = RectF(); private val modelsRect = RectF()

    fun setTheme(t: Theme) {
        theme = t
        title.typeface = ThemeFonts.typeface(context, t.font, 600)
        label.typeface = ThemeFonts.typeface(context, t.font, 500); small.typeface = label.typeface; body.typeface = ThemeFonts.typeface(context, t.font, 400)
        invalidate()
    }


    private fun surface() = theme.key.base() or (0xFF shl 24)
    private fun raised() = theme.mod.base() or (0xFF shl 24)

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.board.first().base() or (0xFF shl 24))
        drawHeader(c)
        if (rewrite.model == null) { drawNotInstalled(c); return }
        drawChips(c)
        drawCard(c)
        drawButtons(c)
        if (rewrite.working) postInvalidateOnAnimation()
    }

    private fun drawHeader(c: Canvas) {
        Icons.draw(c, Icons.Icon.SPARKLE, 24f * d, 17f * d, 16f * d, amber, strokeWidth = 1.9f)
        title.color = theme.fg
        c.drawText("Rewrite", 42f * d, 17f * d - (title.fontMetrics.ascent + title.fontMetrics.descent) / 2f, title)
        // Where the text goes: nowhere. (This is the panel's only privacy note; it has to fit in the keyboard's height.)
        Icons.draw(c, Icons.Icon.SHIELD, 42f * d + title.measureText("Rewrite") + 16f * d, 17f * d, 12f * d, 0xFF6FD3A6.toInt(), strokeWidth = 2f)
        small.color = theme.stripFg
        c.drawText("on this phone", 42f * d + title.measureText("Rewrite") + 28f * d, 17f * d - (small.fontMetrics.ascent + small.fontMetrics.descent) / 2f, small)
        closeRect.set(width - 52f * d, 0f, width - 8f * d, 34f * d)
        Icons.draw(c, Icons.Icon.CLOSE, closeRect.centerX(), closeRect.centerY(), 20f * d, theme.stripFg, strokeWidth = 1.9f)
    }

    private fun drawChips(c: Canvas) {
        chipRects.clear()
        c.save(); c.clipRect(0f, 36f * d, width.toFloat(), 72f * d)
        var x = 14f * d - chipScroll
        for (t in Tone.values()) {
            val name = if (t == Tone.HINDI) "Translate to Hindi" else t.label
            val w = label.measureText(name) + 28f * d
            val r = RectF(x, 38f * d, x + w, 70f * d); chipRects.add(r to t)
            val sel = t == rewrite.tone
            fill.color = if (sel) 0xFF2A2418.toInt().let { if (theme.dark) it else raised() } else surface(); c.drawRoundRect(r, 16f * d, 16f * d, fill)
            line.color = if (sel) amber else theme.sep; c.drawRoundRect(r, 16f * d, 16f * d, line)
            label.color = if (sel) (if (theme.dark) amberSoft else theme.fg) else theme.stripFg
            c.drawText(name, r.centerX(), r.centerY() - (label.fontMetrics.ascent + label.fontMetrics.descent) / 2f, label)
            x += w + 8f * d
        }
        c.restore()
    }

    private fun drawCard(c: Canvas) {
        val card = RectF(14f * d, 78f * d, width - 14f * d, (height - 52f * d).coerceAtLeast(130f * d))
        fill.color = surface(); c.drawRoundRect(card, 16f * d, 16f * d, fill)
        line.color = theme.sep; c.drawRoundRect(card, 16f * d, 16f * d, line)
        small.color = if (theme.dark) amberSoft else theme.stripFg
        c.drawText(rewrite.tone.label.uppercase(), card.left + 14f * d, card.top + 18f * d, small)
        val meta = when (rewrite.phase) { RewriteController.Phase.DONE -> "%.1f s".format(rewrite.elapsedMs / 1000f); RewriteController.Phase.WORKING -> "writing…"; else -> "" }
        small.color = theme.stripFg; small.textAlign = Paint.Align.RIGHT
        c.drawText(meta, card.right - 14f * d, card.top + 18f * d, small); small.textAlign = Paint.Align.LEFT

        val x = card.left + 14f * d; val w = (card.width() - 28f * d).toInt(); val top = card.top + 28f * d; val h = card.height() - 36f * d
        when {
            !hasText -> hint(c, "Type or select some text, then choose a tone.", x, top, w)
            rewrite.phase == RewriteController.Phase.FAILED -> hint(c, rewrite.failure ?: "The rewrite failed", x, top, w)
            rewrite.phase == RewriteController.Phase.IDLE -> hint(c, "Choose a tone to rewrite your text. Nothing changes until you tap Replace or Insert below.", x, top, w)
            rewrite.result.isEmpty() -> shimmer(c, x, top, w.toFloat())
            else -> {
                body.color = theme.fg
                val l = StaticLayout.Builder.obtain(rewrite.result, 0, rewrite.result.length, body, w).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.15f).build()
                c.save(); c.clipRect(x, top, x + w, top + h)
                c.translate(x, top + minOf(0f, h - l.height)); l.draw(c); c.restore()
                if (rewrite.working) shimmerTail(c, x, top + h - 6f * d, w.toFloat())
            }
        }
    }

    private fun hint(c: Canvas, text: String, x: Float, y: Float, w: Int) {
        body.color = theme.stripFg
        val l = StaticLayout.Builder.obtain(text, 0, text.length, body, w).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        c.save(); c.translate(x, y); l.draw(c); c.restore()
    }

    /** Three grey bars with a light band sweeping across them while the model gets going. */
    private fun shimmer(c: Canvas, x: Float, y: Float, w: Float) {
        val t = (SystemClock.uptimeMillis() % 1400) / 1400f
        val base = theme.sep and 0x00FFFFFF or 0xFF000000.toInt()
        for ((i, frac) in listOf(0.92f, 1f, 0.64f).withIndex()) {
            val r = RectF(x, y + i * 24f * d, x + w * frac, y + i * 24f * d + 12f * d)
            fill.shader = null; fill.color = base; fill.alpha = 120; c.drawRoundRect(r, 6f * d, 6f * d, fill)
            val sx = x - w * 0.4f + (w * 1.8f) * t
            fill.shader = LinearGradient(sx, 0f, sx + w * 0.4f, 0f, intArrayOf(0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            fill.alpha = 255
            c.save(); c.clipRect(r); c.drawRoundRect(r, 6f * d, 6f * d, fill); c.restore()
            fill.shader = null
        }
        fill.alpha = 255
    }

    private fun shimmerTail(c: Canvas, x: Float, y: Float, w: Float) {
        val t = (SystemClock.uptimeMillis() % 900) / 900f
        fill.color = amber; fill.alpha = (80 + 100 * Math.sin(t * Math.PI * 2).toFloat().coerceAtLeast(0f)).toInt()
        c.drawRoundRect(RectF(x, y, x + 24f * d, y + 4f * d), 2f * d, 2f * d, fill); fill.alpha = 255
    }

    private fun drawButtons(c: Canvas) {
        val h = 40f * d; val top = height - h - 6f * d; val gap = 8f * d; val left = 14f * d
        val avail = width - 2 * left - 2 * gap - 46f * d
        val w1 = avail * 1.3f / 2.3f; val w2 = avail / 2.3f
        val ready = rewrite.phase == RewriteController.Phase.DONE && rewrite.result.isNotBlank()
        replaceRect.set(left, top, left + w1, top + h)
        belowRect.set(replaceRect.right + gap, top, replaceRect.right + gap + w2, top + h)
        againRect.set(belowRect.right + gap, top, belowRect.right + gap + 46f * d, top + h)
        fill.color = amber; fill.alpha = if (ready) 255 else 90; c.drawRoundRect(replaceRect, 14f * d, 14f * d, fill)
        label.color = 0xFF1A1206.toInt(); label.alpha = if (ready) 255 else 120
        c.drawText("Replace draft", replaceRect.centerX(), replaceRect.centerY() - (label.fontMetrics.ascent + label.fontMetrics.descent) / 2f, label)
        fill.color = raised(); fill.alpha = if (ready) 255 else 90; c.drawRoundRect(belowRect, 14f * d, 14f * d, fill)
        label.color = theme.fg; label.alpha = if (ready) 255 else 120
        c.drawText("Insert below", belowRect.centerX(), belowRect.centerY() - (label.fontMetrics.ascent + label.fontMetrics.descent) / 2f, label)
        val canAgain = hasText && !rewrite.working && rewrite.phase != RewriteController.Phase.IDLE
        fill.color = raised(); fill.alpha = if (canAgain) 255 else 90; c.drawRoundRect(againRect, 14f * d, 14f * d, fill)
        Icons.draw(c, Icons.Icon.REFRESH, againRect.centerX(), againRect.centerY(), 18f * d, theme.fg.let { if (canAgain) it else (it and 0x00FFFFFF) or 0x66000000 }, strokeWidth = 1.9f)
        fill.alpha = 255; label.alpha = 255
    }

    private fun drawNotInstalled(c: Canvas) {
        val card = RectF(14f * d, 40f * d, width - 14f * d, height - 8f * d)
        fill.color = surface(); c.drawRoundRect(card, 16f * d, 16f * d, fill)
        line.color = theme.sep; c.drawRoundRect(card, 16f * d, 16f * d, line)
        val msg = if (rewrite.isOff) "Rewrite is turned off. You can turn it on in Wisp's Models screen." else
            "Rewrite needs a small AI model that runs on this phone, so your text never leaves it. No model is installed yet. Choose one in Wisp's Models screen."
        val tp = TextPaint(body).also { it.color = theme.fg }
        val l = StaticLayout.Builder.obtain(msg, 0, msg.length, tp, (card.width() - 28f * d).toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.15f).build()
        c.save(); c.translate(card.left + 14f * d, card.top + 14f * d); l.draw(c); c.restore()
        modelsRect.set(card.left + 14f * d, card.bottom - 52f * d, card.right - 14f * d, card.bottom - 12f * d)
        fill.color = amber; c.drawRoundRect(modelsRect, 14f * d, 14f * d, fill)
        label.color = 0xFF1A1206.toInt()
        c.drawText("Open Models", modelsRect.centerX(), modelsRect.centerY() - (label.fontMetrics.ascent + label.fontMetrics.descent) / 2f, label)
    }

    // ---- touch

    private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var dragging = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; lastX = e.x; dragging = false; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && downY in (36f * d)..(72f * d) && Math.abs(e.x - downX) > slop) dragging = true
                if (dragging) {
                    val total = Tone.values().sumOf { (label.measureText(if (it == Tone.HINDI) "Translate to Hindi" else it.label) + 36f * d).toDouble() }.toFloat() + 14f * d
                    chipScroll = (chipScroll - (e.x - lastX)).coerceIn(0f, maxOf(0f, total - width)); invalidate()
                }
                lastX = e.x
            }
            MotionEvent.ACTION_UP -> if (!dragging) tap(e.x, e.y)
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        val ready = rewrite.phase == RewriteController.Phase.DONE && rewrite.result.isNotBlank()
        when {
            closeRect.contains(x, y) -> listener?.onClose()
            rewrite.model == null -> if (modelsRect.contains(x, y)) listener?.onOpenModels()
            replaceRect.contains(x, y) -> if (ready) listener?.onReplace(rewrite.result)
            belowRect.contains(x, y) -> if (ready) listener?.onInsertBelow(rewrite.result)
            againRect.contains(x, y) -> if (hasText && !rewrite.working && rewrite.phase != RewriteController.Phase.IDLE) listener?.onRegenerate()
            else -> chipRects.firstOrNull { it.first.contains(x, y) }?.let { if (hasText) listener?.onTone(it.second) }
        }
    }

}
