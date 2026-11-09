package app.wisp.keyboard.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import app.wisp.keyboard.kbd.KeyboardView
import app.wisp.keyboard.kbd.Palette
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The voice panel: the words as they are recognised, a ribbon that waves with the voice, a mic button and a cancel button.
 * One View drawn directly (no framework UI), and it only animates while the microphone is open.
 *
 * The mic button: press and hold to talk and let go to insert; tap once to keep listening hands-free and tap again to insert;
 * slide away while holding to cancel.
 */
class VoicePanelView(context: Context, private val voice: VoiceController, private val palette: Palette = Palette()) : View(context) {

    interface Listener {
        fun onMicDown()              // start (hold)
        fun onHandsFree()            // a quick tap: keep listening until the next tap
        fun onFinish()               // insert what was said
        fun onCancel()
        fun onFix(problem: VoiceController.Problem)
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val height3 = KeyboardView.ROW_HEIGHT_DP * 3 * density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.text; textSize = 20f * density }
    private val hint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.textMuted; textSize = 14f * density; textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ribbon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 3f * density }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 2.4f * density }
    private val path = Path()
    private val box = RectF()

    private var layout: StaticLayout? = null
    private var layoutText = ""
    private var layoutWidth = 0
    private var smoothLevel = 0f

    private var downOnMic = false
    private var startedByThisPress = false
    private var downAt = 0L
    private var downX = 0f; private var downY = 0f
    private var cancelArmed = false
    private var downOnCancel = false

    // Geometry (recomputed in onSizeChanged)
    private var micX = 0f; private var micY = 0f; private var micR = 0f
    private var cancelX = 0f; private var cancelY = 0f; private var cancelR = 0f

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), height3.toInt())

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        micR = 30f * density; micX = w / 2f; micY = h - micR - 8f * density
        cancelR = 22f * density; cancelX = 36f * density; cancelY = micY
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(palette.background); text.color = palette.text
        val phase = voice.phase
        val listening = phase == VoiceController.Phase.LISTENING || phase == VoiceController.Phase.WARMING
        smoothLevel += ((if (listening) voice.level else 0f) - smoothLevel) * 0.25f

        drawWords(c, phase)
        if (listening || phase == VoiceController.Phase.FINISHING) drawTimer(c)
        drawRibbon(c, listening)
        drawMic(c, phase, listening)
        if (listening || phase == VoiceController.Phase.FINISHING) drawCancel(c)
        if (listening) postInvalidateOnAnimation()
    }

    /** A red dot and the time so far, top right: the microphone is open, and for how long. */
    private fun drawTimer(c: Canvas) {
        val secs = ((SystemClock.elapsedRealtime() - voice.startedAt) / 1000).toInt().coerceAtLeast(0)
        val label = "%d:%02d".format(secs / 60, secs % 60)
        hint.textAlign = Paint.Align.RIGHT; hint.color = palette.textMuted
        val x = width - 16f * density; val y = 22f * density
        c.drawText(label, x, y, hint)
        fill.color = RECORDING_RED; fill.alpha = 255
        c.drawCircle(x - hint.measureText(label) - 10f * density, y - 5f * density, 4f * density, fill)
        hint.textAlign = Paint.Align.CENTER
    }

    private fun drawWords(c: Canvas, phase: VoiceController.Phase) {
        val padX = 20f * density
        val top = 8f * density
        val areaH = micY - micR - 36f * density - top
        val shown = voice.partial
        val failure = voice.failure
        val status: String? = when {
            failure != null -> failure.message
            phase == VoiceController.Phase.WARMING -> "Warming up… you can start talking"
            phase == VoiceController.Phase.FINISHING -> "Finishing…"
            phase == VoiceController.Phase.LISTENING && shown.isEmpty() -> if (voice.handsFree) "Listening… tap the mic when you are done" else "Listening… let go to insert"
            phase == VoiceController.Phase.IDLE -> "Hold the mic and talk, or tap it to keep listening"
            else -> null
        }
        if (shown.isNotEmpty() && failure == null) {
            val w = (width - 2 * padX).toInt()
            if (layout == null || shown != layoutText || w != layoutWidth) {
                layoutText = shown; layoutWidth = w
                layout = StaticLayout.Builder.obtain(shown, 0, shown.length, text, w).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            }
            val l = layout!!
            // Show the newest words: a long dictation scrolls up.
            val dy = min(0f, areaH - l.height)
            c.save(); c.clipRect(0f, top, width.toFloat(), top + areaH); c.translate(padX, top + dy); l.draw(c); c.restore()
        } else if (status != null) {
            hint.color = if (failure != null) palette.accent else palette.textMuted
            val sl = StaticLayout.Builder.obtain(status, 0, status.length, hint, (width - 2 * padX).toInt()).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
            c.save(); c.translate(padX, top + (areaH - sl.height) / 2f); sl.draw(c); c.restore()
        }
    }

    /** Three soft waves across the panel; they grow with the voice. The same ribbon is the swipe trail's cousin. */
    private fun drawRibbon(c: Canvas, listening: Boolean) {
        val cy = micY - micR - 18f * density
        val t = SystemClock.uptimeMillis() / 1000f
        val amp = (2f + 20f * smoothLevel) * density
        for (k in 0 until 3) {
            ribbon.color = palette.accent; ribbon.alpha = intArrayOf(230, 120, 60)[k]
            path.reset()
            val steps = 48
            for (i in 0..steps) {
                val x = width * i / steps.toFloat()
                val edge = sin(Math.PI * i / steps).toFloat()                 // pinned to the middle at the ends
                val y = cy + sin(x / (46f * density) + t * (2.2f + k * 0.7f) + k * 1.9f) * amp * edge * (1f - k * 0.25f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            c.drawPath(path, ribbon)
        }
    }

    private fun drawMic(c: Canvas, phase: VoiceController.Phase, listening: Boolean) {
        if (listening) {
            fill.color = palette.accent; fill.alpha = 60
            c.drawCircle(micX, micY, micR * (1.08f + 0.32f * smoothLevel), fill)
        }
        fill.color = if (cancelArmed) palette.special else palette.accent; fill.alpha = 255
        c.drawCircle(micX, micY, micR, fill)
        icon.color = palette.onAccent
        if (listening && voice.handsFree && phase == VoiceController.Phase.LISTENING) {   // a stop square while hands-free
            box.set(micX - 9f * density, micY - 9f * density, micX + 9f * density, micY + 9f * density)
            fill.color = palette.onAccent; c.drawRoundRect(box, 3f * density, 3f * density, fill)
        } else drawMicShape(c, micX, micY, 1f)
    }

    private fun drawMicShape(c: Canvas, cx: Float, cy: Float, scale: Float) {
        val d = density * scale
        box.set(cx - 5f * d, cy - 13f * d, cx + 5f * d, cy + 3f * d)
        c.drawRoundRect(box, 5f * d, 5f * d, icon)
        box.set(cx - 10f * d, cy - 7f * d, cx + 10f * d, cy + 11f * d)
        c.drawArc(box, 20f, 140f, false, icon)
        c.drawLine(cx, cy + 11f * d, cx, cy + 16f * d, icon)
    }

    private fun drawCancel(c: Canvas) {
        fill.color = palette.special; fill.alpha = 255
        c.drawCircle(cancelX, cancelY, cancelR, fill)
        icon.color = palette.text
        val r = 7f * density
        c.drawLine(cancelX - r, cancelY - r, cancelX + r, cancelY + r, icon); c.drawLine(cancelX - r, cancelY + r, cancelX + r, cancelY - r, icon)
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downAt = e.eventTime; cancelArmed = false
                downOnMic = dist(e.x, e.y, micX, micY) <= micR * 1.5f
                downOnCancel = (voice.isActive) && dist(e.x, e.y, cancelX, cancelY) <= cancelR * 1.6f
                startedByThisPress = false
                if (downOnMic) {
                    val failure = voice.failure
                    when {
                        failure != null -> { listener?.onFix(failure.problem); downOnMic = false }
                        voice.isActive -> {}                                   // a tap finishes it (decided on lift)
                        else -> { listener?.onMicDown(); startedByThisPress = voice.isActive }
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> if (downOnMic && startedByThisPress) {
                val away = abs(e.x - downX) > 110f * density || (downY - e.y) > 110f * density
                if (away != cancelArmed) { cancelArmed = away; invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                if (downOnCancel && dist(e.x, e.y, cancelX, cancelY) <= cancelR * 1.6f) listener?.onCancel()
                else if (downOnMic) {
                    val held = e.eventTime - downAt
                    when {
                        startedByThisPress && cancelArmed -> listener?.onCancel()
                        startedByThisPress && held >= HOLD_MS -> listener?.onFinish()      // hold to talk: let go to insert
                        startedByThisPress -> listener?.onHandsFree()                       // a quick tap: keep listening hands-free
                        voice.isActive -> listener?.onFinish()                              // tapped while listening: finish
                    }
                }
                downOnMic = false; downOnCancel = false; cancelArmed = false; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { downOnMic = false; downOnCancel = false; cancelArmed = false }
        }
        return true
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float) = kotlin.math.hypot((ax - bx).toDouble(), (ay - by).toDouble()).toFloat()

    private companion object {
        const val HOLD_MS = 350L
        val RECORDING_RED get() = app.wisp.keyboard.style.Brand.RECORDING
    }
}
