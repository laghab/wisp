package app.wisp.keyboard.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.View
import app.wisp.keyboard.kbd.Icons
import app.wisp.keyboard.style.Theme
import app.wisp.keyboard.style.ThemeFonts
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * The voice panel (the handoff's Voice screen): "on-device" and language chips, the words as they are recognised (confirmed ones solid,
 * the last few still-changing ones grey), a waveform, a pulsing mic, a status line, and Undo phrase / Auto-punctuation / Polish.
 * One View drawn directly, and it only animates while the microphone is open.
 *
 * The mic: tap to start listening, tap again to stop and insert. The X throws the dictation away; the back arrow inserts what was said
 * and returns to the keys.
 */
class VoicePanelView(context: Context, private val voice: VoiceController, private var theme: Theme) : View(context) {

    interface Listener {
        fun onMic()                  // the mic was tapped: start listening, or stop and insert what was said
        fun onCancel()
        fun onBack()                 // the arrow: insert what was said, or just go back when nothing is being said
        fun onFix(problem: VoiceController.Problem)
        fun onUndoPhrase()
        fun onTogglePunctuation()
        fun onPolish()
        fun onLanguage()
    }

    var listener: Listener? = null
    var autoPunctuation = true
        set(v) { field = v; invalidate() }
    /** Polish (AI rewrite) is only offered when a rewrite model is installed. */
    var polishAvailable = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val amber = 0xFFFFB86B.toInt()
    private val amberSoft = 0xFFFFCF99.toInt()

    private val words = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f * d }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * d; textAlign = Paint.Align.CENTER }
    private val status = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f * d; textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d }
    private val box = RectF()

    private var smoothLevel = 0f
    private var wordLayout: StaticLayout? = null
    private var wordText = ""
    private var wordWidth = 0

    fun setTheme(t: Theme) {
        theme = t
        words.typeface = ThemeFonts.typeface(context, t.font, 400)
        label.typeface = ThemeFonts.typeface(context, t.font, 500); status.typeface = label.typeface
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (HEIGHT_DP * d).toInt())

    // Geometry in dp from the top
    private val headerH = 40f; private val wordsTop = 40f; private val wordsH = 58f
    private val barsTop = 102f; private val barsH = 34f
    private val micCy = 178f; private val micR = 36f
    private val statusY = 226f
    private val buttonsTop = 238f; private val buttonsH = 40f
    private val footerY = 298f

    private val micRect = RectF(); private val backRect = RectF(); private val langRect = RectF()
    private val undoRect = RectF(); private val punctRect = RectF(); private val polishRect = RectF()
    private val cancelRect = RectF()

    private fun surface() = theme.mod.base() or (0xFF shl 24)

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.board.first().base() or (0xFF shl 24))
        val phase = voice.phase
        val listening = (phase == VoiceController.Phase.LISTENING || phase == VoiceController.Phase.WARMING)
        val live = listening
        smoothLevel += ((if (live) voice.level else 0f) - smoothLevel) * 0.25f

        drawHeader(c, listening)
        drawWords(c, listening)
        drawBars(c, live)
        drawMic(c, phase, listening)
        drawStatus(c, phase)
        drawButtons(c)
        drawFooter(c)
        if (live || phase == VoiceController.Phase.FINISHING) postInvalidateOnAnimation()
    }

    private fun drawHeader(c: Canvas, listening: Boolean) {
        // "On-device" chip
        val chip = "On-device · private"
        val cw = label.measureText(chip) + 36f * d
        box.set(8f * d, 5f * d, 8f * d + cw, 35f * d)
        fill.color = 0xFF1F1B14.toInt(); c.drawRoundRect(box, 15f * d, 15f * d, fill)
        line.color = 0xFF3A2F1E.toInt(); c.drawRoundRect(box, 15f * d, 15f * d, line)
        Icons.draw(c, Icons.Icon.SHIELD, box.left + 16f * d, box.centerY(), 14f * d, amberSoft, strokeWidth = 2f)
        label.color = amberSoft
        c.drawText(chip, box.left + 26f * d + label.measureText(chip) / 2f, box.centerY() - (label.fontMetrics.ascent + label.fontMetrics.descent) / 2f, label)
        // Language chip
        val lang = "EN ▾"
        val lw = label.measureText(lang) + 24f * d
        langRect.set(box.right + 8f * d, 5f * d, box.right + 8f * d + lw, 35f * d)
        fill.color = surface(); c.drawRoundRect(langRect, 15f * d, 15f * d, fill)
        line.color = theme.sep; c.drawRoundRect(langRect, 15f * d, 15f * d, line)
        label.color = theme.stripFg
        c.drawText(lang, langRect.centerX(), langRect.centerY() - (label.fontMetrics.ascent + label.fontMetrics.descent) / 2f, label)
        // Timer while the microphone is open
        if (listening) {
            val secs = ((SystemClock.elapsedRealtime() - voice.startedAt) / 1000).toInt().coerceAtLeast(0)
            val t = "%d:%02d".format(secs / 60, secs % 60)
            label.textAlign = Paint.Align.LEFT; label.color = theme.stripFg
            val x = langRect.right + 14f * d
            fill.color = app.wisp.keyboard.style.Brand.RECORDING; c.drawCircle(x + 3f * d, 20f * d, 3.5f * d, fill)
            c.drawText(t, x + 12f * d, 20f * d - (label.fontMetrics.ascent + label.fontMetrics.descent) / 2f, label)
            label.textAlign = Paint.Align.CENTER
        }
        // Back arrow
        backRect.set(width - 52f * d, 0f, width - 8f * d, headerH * d)
        Icons.draw(c, Icons.Icon.BACK_CHEVRON, backRect.centerX(), backRect.centerY(), 22f * d, theme.stripFg)
    }

    private fun drawWords(c: Canvas, listening: Boolean) {
        val padX = 20f * d
        val top = wordsTop * d; val h = wordsH * d
        val shown = voice.partial
        val failure = voice.failure
        if (shown.isEmpty() || failure != null) return
        val w = (width - 2 * padX).toInt()
        // Confirmed words solid, the last three (still changing) grey.
        val parts = shown.trim().split(' ')
        val cut = if (listening) (parts.size - 3).coerceAtLeast(0) else parts.size
        val confirmed = parts.take(cut).joinToString(" ")
        val tentative = parts.drop(cut).joinToString(" ")
        val full = if (tentative.isEmpty()) confirmed else if (confirmed.isEmpty()) tentative else "$confirmed $tentative"
        if (wordLayout == null || full != wordText || w != wordWidth) {
            wordText = full; wordWidth = w
            val sp = SpannableString(full)
            sp.setSpan(ForegroundColorSpan(theme.fg), 0, confirmed.length, 0)
            if (tentative.isNotEmpty()) sp.setSpan(ForegroundColorSpan(0xFF8E97A6.toInt()), full.length - tentative.length, full.length, 0)
            wordLayout = StaticLayout.Builder.obtain(sp, 0, sp.length, words, w).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.2f).build()
        }
        val l = wordLayout!!
        val dy = min(0f, h - l.height)       // a long dictation scrolls so the newest words stay in view
        c.save(); c.clipRect(0f, top, width.toFloat(), top + h); c.translate(padX, top + dy); l.draw(c); c.restore()
    }

    private val heights = floatArrayOf(10f, 18f, 26f, 14f, 30f, 22f, 12f, 34f, 20f, 28f, 16f, 36f, 24f, 12f, 30f, 18f, 26f, 34f, 14f, 22f, 30f, 16f, 28f, 12f, 24f, 32f, 18f, 10f)

    /** 28 bars that rise and fall with the voice (flat and dim when idle). */
    private fun drawBars(c: Canvas, live: Boolean) {
        val n = heights.size
        val bw = 4f * d; val gap = 4f * d
        val total = n * bw + (n - 1) * gap
        var x = (width - total) / 2f
        val cy = (barsTop + barsH / 2f) * d
        val t = SystemClock.uptimeMillis() / 1000f
        for (i in 0 until n) {
            val wave = if (live) 0.35f + 0.65f * (0.5f + 0.5f * sin(t * 6.3f - i * 0.52f)) else 0.12f
            val amp = if (live) (0.25f + 0.75f * smoothLevel.coerceIn(0f, 1f)) * wave else wave
            val h = (heights[i] * d * amp).coerceAtLeast(3f * d)
            fill.color = if (i % 3 == 0) amber else if (i % 3 == 1) amberSoft else 0xFFC98A4A.toInt()
            fill.alpha = if (live) 255 else 110
            box.set(x, cy - h / 2f, x + bw, cy + h / 2f)
            c.drawRoundRect(box, 2f * d, 2f * d, fill)
            x += bw + gap
        }
        fill.alpha = 255
    }

    private fun drawMic(c: Canvas, phase: VoiceController.Phase, listening: Boolean) {
        val cx = width / 2f; val cy = micCy * d; val r = micR * d
        micRect.set(cx - r, cy - r, cx + r, cy + r)
        val live = listening
        if (live) {
            // Two rings that swell and fade, half a cycle apart.
            val t = (SystemClock.uptimeMillis() % 1800) / 1800f
            for (k in 0..1) {
                val p = (t + k * 0.5f) % 1f
                fill.color = amber; fill.alpha = ((1f - p) * 140).toInt()
                c.drawCircle(cx, cy, r * (1f + 0.9f * p), fill)
            }
            fill.alpha = 255
        }
        fill.color = if (live || phase == VoiceController.Phase.FINISHING) amber else 0xFF2A2418.toInt()
        c.drawCircle(cx, cy, r, fill)
        val ink = if (live || phase == VoiceController.Phase.FINISHING) 0xFF1A1206.toInt() else amber
        if (listening) {
            // Listening: a stop square, because another tap ends it.
            fill.color = ink
            box.set(cx - 9f * d, cy - 9f * d, cx + 9f * d, cy + 9f * d); c.drawRoundRect(box, 3f * d, 3f * d, fill)
        } else Icons.draw(c, Icons.Icon.MIC, cx, cy, 30f * d, ink, strokeWidth = 1.9f)
        // Cancel, left of the mic, while recording
        if (voice.isActive) {
            cancelRect.set(cx - r - 64f * d, cy - 22f * d, cx - r - 20f * d, cy + 22f * d)
            fill.color = surface(); c.drawCircle(cancelRect.centerX(), cancelRect.centerY(), 22f * d, fill)
            line.color = theme.fg; line.strokeCap = Paint.Cap.ROUND
            val q = 7f * d
            c.drawLine(cancelRect.centerX() - q, cancelRect.centerY() - q, cancelRect.centerX() + q, cancelRect.centerY() + q, line)
            c.drawLine(cancelRect.centerX() - q, cancelRect.centerY() + q, cancelRect.centerX() + q, cancelRect.centerY() - q, line)
        } else cancelRect.setEmpty()
    }

    private fun drawStatus(c: Canvas, phase: VoiceController.Phase) {
        val failure = voice.failure
        val s = when {
            failure != null -> failure.message
            phase == VoiceController.Phase.WARMING -> "Warming up… you can start talking"
            phase == VoiceController.Phase.FINISHING -> "Finishing…"
            phase == VoiceController.Phase.LISTENING -> "Listening. Tap the mic to finish"
            else -> "Tap the mic and start speaking"
        }
        status.color = if (failure != null) theme.accent.base() or (0xFF shl 24) else 0xFFC9D0DC.toInt().let { if (theme.dark) it else theme.stripFg }
        val sl = StaticLayout.Builder.obtain(s, 0, s.length, TextPaint(status).also { it.textAlign = Paint.Align.LEFT }, (width - 32f * d).toInt()).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        c.save(); c.translate(16f * d, statusY * d - sl.height / 2f); sl.draw(c); c.restore()
    }

    private fun drawButtons(c: Canvas) {
        val gap = 8f * d; val left = 10f * d
        val n = if (polishAvailable) 3 else 2
        val w = (width - 2 * left - (n - 1) * gap) / n
        val top = buttonsTop * d; val h = buttonsH * d
        undoRect.set(left, top, left + w, top + h)
        punctRect.set(left + w + gap, top, left + 2 * w + gap, top + h)
        polishRect.set(left + 2 * (w + gap), top, left + 3 * w + 2 * gap, top + h)
        button(c, undoRect, "Undo phrase", surface(), theme.fg)
        button(c, punctRect, if (autoPunctuation) "Auto-punctuation: on" else "Auto-punctuation: off", if (autoPunctuation) 0xFF1B2A20.toInt().let { if (theme.dark) it else surface() } else surface(), theme.fg)
        if (polishAvailable) { button(c, polishRect, "✦ Polish", 0xFF2A2418.toInt(), amberSoft) } else polishRect.setEmpty()
    }

    private fun button(c: Canvas, r: RectF, text: String, bg: Int, fg: Int) {
        fill.color = bg; c.drawRoundRect(r, 12f * d, 12f * d, fill)
        label.color = fg
        val shown = if (label.measureText(text) > r.width() - 12f * d) text.substringBefore(':') else text
        c.drawText(shown, r.centerX(), r.centerY() - (label.fontMetrics.ascent + label.fontMetrics.descent) / 2f, label)
    }

    private fun drawFooter(c: Canvas) {
        line.color = theme.sep; c.drawLine(0f, (footerY - 4f) * d, width.toFloat(), (footerY - 4f) * d, line)
        Icons.draw(c, Icons.Icon.SHIELD, width / 2f - 118f * d, (footerY + 10f) * d, 13f * d, 0xFF6FD3A6.toInt(), strokeWidth = 2f)
        status.textSize = 11f * d; status.color = theme.stripFg
        c.drawText("Audio is processed on this phone and not kept", width / 2f + 8f * d, (footerY + 10f) * d - (status.fontMetrics.ascent + status.fontMetrics.descent) / 2f, status)
        status.textSize = 13f * d
    }

    // ---- touch

    private var downOnMic = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downOnMic = micRect.centerX().let { hypot(e.x - it, e.y - micRect.centerY()) <= micR * d * 1.4f }
                val failure = voice.failure
                if (downOnMic && failure != null) { listener?.onFix(failure.problem); downOnMic = false }
            }
            MotionEvent.ACTION_UP -> {
                val x = e.x; val y = e.y
                when {
                    downOnMic -> listener?.onMic()
                    cancelRect.contains(x, y) -> listener?.onCancel()
                    backRect.contains(x, y) -> listener?.onBack()
                    langRect.contains(x, y) -> listener?.onLanguage()
                    undoRect.contains(x, y) -> listener?.onUndoPhrase()
                    punctRect.contains(x, y) -> listener?.onTogglePunctuation()
                    polishRect.contains(x, y) -> listener?.onPolish()
                }
                downOnMic = false; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> downOnMic = false
        }
        return true
    }

    private fun hypot(a: Float, b: Float) = kotlin.math.hypot(a.toDouble(), b.toDouble()).toFloat()

    private companion object {
        const val HEIGHT_DP = 320f
    }
}
