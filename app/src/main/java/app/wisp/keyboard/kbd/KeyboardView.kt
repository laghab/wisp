package app.wisp.keyboard.kbd

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import app.wisp.keyboard.BuildConfig
import app.wisp.keyboard.LatencyStats
import app.wisp.keyboard.Probe

/**
 * The key surface. One View, one draw pass, no per-key child views. Geometry lives in flat arrays
 * ([CompiledLayout]); touch handling goes through [KeyTouchTracker], which does not allocate.
 */
class KeyboardView(context: Context, private var spec: LayoutSpec, val palette: Palette = Palette()) :
    View(context), KeyTouchTracker.Sink {

    interface Listener {
        fun onKey(code: Int, x: Int, y: Int)
        fun onRepeat(code: Int, count: Int) {}
        fun onCursorSteps(steps: Int) {}
        /** Long press on a key that supports it (currently the switch-keyboard key). */
        fun onLongPress(code: Int) {}
        /** A swipe across the letters finished. [xs]/[ys] are only valid during this call; copy what you keep. */
        fun onGesture(xs: FloatArray, ys: FloatArray, count: Int) {}
        /** One of the two buttons in the empty space of one-handed mode: "swap" the side, or go back to "full" width. */
        fun onOneHanded(action: String) {}
    }

    private val density = resources.displayMetrics.density

    var listener: Listener? = null

    var shifted = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val tapLatency = LatencyStats("tap_up_to_commit")
    private val drawTime = LatencyStats("on_draw")
    /** Swipe typing on or off (the setting); takes effect the next time the layout is built. */
    var gesturesEnabled = true
        set(v) { if (field != v) { field = v; layout?.let { tracker?.cancelAll(); tracker = makeTracker(it) } } }

    // Trail: drawn while the finger is down, then fades out.
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 5f * density; color = palette.accent
    }
    private val fadeX = FloatArray(KeyTouchTracker.MAX_TRACE)
    private val fadeY = FloatArray(KeyTouchTracker.MAX_TRACE)
    private var fadeCount = 0
    private var fadeStart = 0L
    private var longPressKey = -1
    private var longPressFired = false
    private val longPress = Runnable {
        val l = layout ?: return@Runnable
        if (longPressKey in 0 until l.count) {
            longPressFired = true
            val pop = l.popups[longPressKey]
            if (pop != null && l.codes[longPressKey] != KeyCodes.SWITCH) showPopup(longPressKey, pop) else listener?.onLongPress(l.codes[longPressKey])
        }
    }

    private var repeatCode = 0
    private var repeatCount = 0
    private val repeater = object : Runnable {
        override fun run() {
            if (repeatCode == 0) return
            listener?.onRepeat(repeatCode, ++repeatCount)
            postDelayed(this, if (repeatCount > InputController.WORD_DELETE_AFTER) REPEAT_SLOW_MS else REPEAT_FAST_MS)
        }
    }

    private var rowHeightPx = ROW_HEIGHT_DP * density
    private var gapX = 2.5f * density
    private var gapY = 3.5f * density
    private var radius = 9f * density
    private var oneHanded = "off"
    private var keysLeft = 0f
    private var emptyStripTouch = false
    private var keysRight = 0f
    private val sideButton = RectF()
    private val fullButton = RectF()
    private var split = false
    private var haptic = "light"
    private var sound = false
    private var trailOn = true
    private var vibrator: android.os.Vibrator? = null

    private var layout: CompiledLayout? = null

    /** The geometry currently drawn, for code that needs key positions (the dictionary's proximity model). */
    val compiledLayout: CompiledLayout? get() = layout

    /** Called (on the UI thread) whenever the geometry is rebuilt. */
    var onLayoutBuilt: ((CompiledLayout) -> Unit)? = null
    private var tracker: KeyTouchTracker? = null
    private var pressed = BooleanArray(0)
    /** When each key was let go, for the short fade back from the pressed colour. 0 = not fading. */
    private var releasedAt = LongArray(0)
    private var fading = false
    private var dirty = IntArray(0)
    private var labelY = FloatArray(0)

    private val fill = Paint()
    private val bigText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 22f * density; color = palette.text
    }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 15f * density; color = palette.textMuted
    }
    private val box = RectF()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val h = (rowHeightPx * spec.rows.size + (PAD_TOP_DP + PAD_BOTTOM_DP) * density).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), h)
    }

    /** Swaps the layout (e.g. number pad for a numeric field). */
    fun setSpec(newSpec: LayoutSpec) {
        if (newSpec === spec) return
        spec = newSpec
        layout = null; tracker?.cancelAll(); tracker = null
        requestLayout()
        if (width > 0) rebuild(width, (rowHeightPx * spec.rows.size + (PAD_TOP_DP + PAD_BOTTOM_DP) * density).toInt())
        invalidate()
    }

    /** Applies the user's look and feel. Cheap to call; geometry is rebuilt only if it changed. */
    fun applyStyle(style: app.wisp.keyboard.style.KeyboardStyle) {
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val h = ROW_HEIGHT_DP * density * style.heightPercent(landscape) / 100f
        val gx = 2.5f * density * style.gap / 100f; val gy = 3.5f * density * style.gap / 100f
        val geometryChanged = h != rowHeightPx || gx != gapX || style.oneHanded != oneHanded || style.split != split
        rowHeightPx = h; gapX = gx; gapY = gy; radius = style.cornerDp() * density
        oneHanded = style.oneHanded; split = style.split
        haptic = style.haptic; sound = style.sound; trailOn = style.trailOn
        trailPaint.strokeWidth = style.trailWidthDp * density
        trailPaint.color = style.trailColorInt(context)
        stroke.color = palette.textMuted
        if (geometryChanged) { layout = null; tracker?.cancelAll(); tracker = null; requestLayout(); if (width > 0) rebuild(width, (rowHeightPx * spec.rows.size + (PAD_TOP_DP + PAD_BOTTOM_DP) * density).toInt()) }
        invalidate()
    }

    private fun feedback() {
        if (haptic == "light") performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        else if (haptic == "strong") {
            val v = vibrator ?: (context.getSystemService(android.os.Vibrator::class.java)).also { vibrator = it }
            v?.vibrate(android.os.VibrationEffect.createOneShot(22, 200))
        }
        if (sound) (context.getSystemService(android.media.AudioManager::class.java)).playSoundEffect(android.media.AudioManager.FX_KEYPRESS_STANDARD, -1f)
    }

    private val labelOverrides = HashMap<Int, String>()

    /** Changes the text on the key with [code], e.g. the enter key's "Search" label. Survives layout rebuilds. */
    fun setLabel(code: Int, text: String) {
        labelOverrides[code] = text
        applyOverrides()
        invalidate()
    }

    private fun applyOverrides() {
        val l = layout ?: return
        for ((code, text) in labelOverrides) {
            val i = l.indexOfCode(code)
            if (i >= 0) { l.labels[i] = text; l.labelsUpper[i] = text }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuild(w, h)

    private fun makeTracker(l: CompiledLayout): KeyTouchTracker {
        val canSwipe = BooleanArray(l.count) { l.kinds[it] == KeyKind.LETTER && Character.isLetter(l.codes[it]) }
        val letterKey = canSwipe.indexOfFirst { it }
        val keyWidth = if (letterKey >= 0) l.rects[letterKey * 4 + 2] - l.rects[letterKey * 4] else 40f * density
        return KeyTouchTracker(
            KeyGrid(l), this, l.indexOfCode(KeyCodes.SPACE), 24f * density, 14f * density,
            gestureStart = if (gesturesEnabled && letterKey >= 0) canSwipe else null, gestureSlopPx = 0.6f * keyWidth,
        )
    }

    private fun rebuild(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val padX = PAD_X_DP * density
        // One-handed: the keys use 80% of the width and sit at the chosen side.
        val shrink = if (oneHanded == "off") 0f else w * 0.2f
        val left = padX + if (oneHanded == "right") shrink else 0f
        val right = padX + if (oneHanded == "left") shrink else 0f
        keysLeft = left; keysRight = w - right
        val l = CompiledLayout(spec, w.toFloat(), h.toFloat(), left, PAD_TOP_DP * density, PAD_BOTTOM_DP * density, right, if (split) w * 0.08f else 0f)
        layout = l
        pressed = BooleanArray(l.count)
        releasedAt = LongArray(l.count)
        dirty = IntArray(l.count * 4) { i -> val v = l.rects[i]; if (i % 4 < 2) v.toInt() else v.toInt() + 1 }
        labelY = FloatArray(l.count) { i ->
            val p = if (l.kinds[i] == KeyKind.LETTER) bigText else smallText
            val fm = p.fontMetrics
            l.centerY(i) - (fm.ascent + fm.descent) / 2f
        }
        applyOverrides()
        onLayoutBuilt?.invoke(l)
        tracker = makeTracker(l)
    }

    override fun onDraw(canvas: Canvas) {
        val l = layout ?: return
        val d0 = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        Probe.first("first_draw")
        canvas.drawColor(palette.background)
        val r = l.rects
        val now = SystemClock.uptimeMillis()
        fading = false
        for (i in 0 until l.count) {
            val o = i * 4
            box.set(r[o] + gapX, r[o + 1] + gapY, r[o + 2] - gapX, r[o + 3] - gapY)
            val base = when {
                l.kinds[i] == KeyKind.ACCENT -> palette.accent
                l.kinds[i] == KeyKind.SPECIAL -> palette.special
                else -> palette.key
            }
            fill.color = when {
                pressed[i] -> if (l.kinds[i] == KeyKind.ACCENT) base else palette.keyPressed
                releasedAt[i] != 0L && l.kinds[i] != KeyKind.ACCENT -> {
                    val t = (now - releasedAt[i]) / RELEASE_FADE_MS.toFloat()
                    if (t >= 1f) { releasedAt[i] = 0L; base } else { fading = true; blend(palette.keyPressed, base, t) }
                }
                else -> base
            }
            canvas.drawRoundRect(box, radius, radius, fill)
            if (l.codes[i] == KeyCodes.SWITCH) { drawGlobe(canvas, l.centerX(i), l.centerY(i)); continue }
            if (l.codes[i] == KeyCodes.EMOJI) { drawSmiley(canvas, l.centerX(i), l.centerY(i)); continue }
            val label = if (shifted) l.labelsUpper[i] else l.labels[i]
            if (label.isNotEmpty()) {
                val p = if (l.kinds[i] == KeyKind.LETTER) bigText else smallText
                p.color = if (l.kinds[i] == KeyKind.ACCENT) palette.onAccent else if (p === bigText) palette.text else palette.textMuted
                canvas.drawText(label, l.centerX(i), labelY[i], p)
            }
        }
        if (fading) postInvalidateOnAnimation()
        if (oneHanded != "off") drawOneHandedButtons(canvas)
        drawTrail(canvas)
        if (BuildConfig.DEBUG) drawTime.record(System.nanoTime() - d0)
    }

    /** In the empty strip beside a one-handed keyboard: swap sides, and go back to full width. */
    private fun drawOneHandedButtons(c: Canvas) {
        val gapW = if (oneHanded == "left") width - keysRight else keysLeft
        if (gapW < 40f * density) return
        val x0 = if (oneHanded == "left") keysRight else 0f
        val size = minOf(gapW - 16f * density, 56f * density)
        val cx = x0 + gapW / 2f
        sideButton.set(cx - size / 2, height * 0.12f, cx + size / 2, height * 0.12f + size)
        fullButton.set(cx - size / 2, sideButton.bottom + 14f * density, cx + size / 2, sideButton.bottom + 14f * density + size)
        for (r in arrayOf(sideButton, fullButton)) { fill.color = palette.special; c.drawRoundRect(r, radius, radius, fill) }
        stroke.style = Paint.Style.STROKE
        // swap: an arrow each way
        val sx = sideButton.centerX(); val sy = sideButton.centerY(); val a = size * 0.26f; val k = size * 0.1f
        c.drawLine(sx - a, sy - k, sx + a, sy - k, stroke); c.drawLine(sx + a, sy - k, sx + a - k, sy - 2 * k, stroke); c.drawLine(sx + a, sy - k, sx + a - k, sy, stroke)
        c.drawLine(sx + a, sy + k, sx - a, sy + k, stroke); c.drawLine(sx - a, sy + k, sx - a + k, sy, stroke); c.drawLine(sx - a, sy + k, sx - a + k, sy + 2 * k, stroke)
        // full width: arrows pointing apart
        val fx = fullButton.centerX(); val fy = fullButton.centerY()
        c.drawLine(fx - a, fy, fx + a, fy, stroke)
        c.drawLine(fx - a, fy, fx - a + k, fy - k, stroke); c.drawLine(fx - a, fy, fx - a + k, fy + k, stroke)
        c.drawLine(fx + a, fy, fx + a - k, fy - k, stroke); c.drawLine(fx + a, fy, fx + a - k, fy + k, stroke)
    }

    /** True if a touch at [x] in one-handed mode is in the empty strip (and so is not a key press). */
    private fun inEmptyStrip(x: Float) = oneHanded != "off" && (x < keysLeft - 2f * density || x > keysRight + 2f * density)

    private fun drawTrail(c: Canvas) {
        if (!trailOn) return
        val t = tracker
        if (t != null && t.gesturing) { strokeTrail(c, t.traceX, t.traceY, t.traceCount, 1f); return }
        if (fadeCount > 1) {
            val age = SystemClock.uptimeMillis() - fadeStart
            if (age >= FADE_MS) { fadeCount = 0; return }
            strokeTrail(c, fadeX, fadeY, fadeCount, 1f - age / FADE_MS.toFloat())
            postInvalidateOnAnimation()
        }
    }

    /** Draws the newest part of the path, older segments fainter, all scaled by [strength] (1 = fully visible). */
    private fun strokeTrail(c: Canvas, xs: FloatArray, ys: FloatArray, n: Int, strength: Float) {
        val from = maxOf(1, n - TRAIL_POINTS)
        for (i in from until n) {
            val frac = (i - from + 1f) / (n - from)
            trailPaint.alpha = (255 * strength * (0.15f + 0.85f * frac)).toInt()
            c.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i], trailPaint)
        }
    }

    override fun gestureStarted() { invalidate() }
    override fun gestureMoved(count: Int) { invalidate() }
    override fun gestureEnded(xs: FloatArray, ys: FloatArray, count: Int) {
        System.arraycopy(xs, 0, fadeX, 0, count); System.arraycopy(ys, 0, fadeY, 0, count)
        fadeCount = count; fadeStart = SystemClock.uptimeMillis()
        invalidate()
        listener?.onGesture(xs, ys, count)
    }
    override fun gestureCancelled() { invalidate() }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f * density; color = palette.textMuted
    }

    /** A small globe: circle, meridian ellipse, equator. Drawn, not a font glyph, so it looks the same everywhere. */
    private fun drawGlobe(c: Canvas, cx: Float, cy: Float) {
        val r = 9f * density
        c.drawCircle(cx, cy, r, stroke)
        c.drawLine(cx - r, cy, cx + r, cy, stroke)
        box.set(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r)
        c.drawOval(box, stroke)
    }

    private fun drawSmiley(c: Canvas, cx: Float, cy: Float) {
        val r = 9f * density
        c.drawCircle(cx, cy, r, stroke)
        stroke.style = Paint.Style.FILL
        c.drawCircle(cx - r * 0.35f, cy - r * 0.25f, r * 0.11f, stroke)
        c.drawCircle(cx + r * 0.35f, cy - r * 0.25f, r * 0.11f, stroke)
        stroke.style = Paint.Style.STROKE
        box.set(cx - r * 0.5f, cy - r * 0.25f, cx + r * 0.5f, cy + r * 0.5f)
        c.drawArc(box, 20f, 140f, false, stroke)
    }

    // ---- long-press popups: hold a key, slide to a character, let go --------------------------------------------------

    private var popupWindow: android.widget.PopupWindow? = null
    private var popupChars = ""
    private var popupLeft = 0f
    private var popupCell = 0f
    private var popupIndex = 0
    private var popupView: View? = null

    private fun showPopup(key: Int, chars: String) {
        val l = layout ?: return
        tracker?.cancelAll()
        val list = chars.codePoints().toArray().map { String(Character.toChars(it)) }
        if (list.isEmpty()) return
        popupChars = chars
        val cell = maxOf(l.rects[key * 4 + 2] - l.rects[key * 4], 44f * density)
        val h = (rowHeightPx * 0.95f).toInt()
        val loc = IntArray(2); getLocationInWindow(loc)
        val screenW = resources.displayMetrics.widthPixels
        val total = cell * list.size
        var left = loc[0] + l.rects[key * 4] - (if (list.size > 1) 0f else 0f)
        left = left.coerceIn(0f, maxOf(0f, screenW - total))
        popupLeft = left; popupCell = cell; popupIndex = 0
        val v = object : View(context) {
            private val bg = Paint(Paint.ANTI_ALIAS_FLAG); private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 22f * density }
            override fun onDraw(c: Canvas) {
                bg.color = palette.special; c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, bg)
                for (i in list.indices) {
                    if (i == popupIndex) { bg.color = palette.accent; c.drawRoundRect(i * popupCell + gapX, gapY, (i + 1) * popupCell - gapX, height - gapY, radius, radius, bg) }
                    tp.color = if (i == popupIndex) palette.onAccent else palette.text
                    val fm = tp.fontMetrics
                    c.drawText(if (shifted) list[i].uppercase() else list[i], i * popupCell + popupCell / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, tp)
                }
            }
        }
        popupView = v
        val w = android.widget.PopupWindow(v, total.toInt(), h, false).apply { isTouchable = false; isClippingEnabled = false; elevation = 8f * density }
        val top = (loc[1] + l.rects[key * 4 + 1] - h - 4 * density).toInt()
        w.showAtLocation(this, android.view.Gravity.NO_GRAVITY, left.toInt(), top)
        popupWindow = w
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    private fun dismissPopup() { popupWindow?.dismiss(); popupWindow = null; popupView = null }

    private fun handlePopupTouch(e: MotionEvent): Boolean {
        val count = popupChars.codePointCount(0, popupChars.length)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val i = ((e.rawX - popupLeft) / popupCell).toInt().coerceIn(0, count - 1)
                if (i != popupIndex) { popupIndex = i; popupView?.invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                val cp = popupChars.codePointAt(popupChars.offsetByCodePoints(0, popupIndex))
                dismissPopup()
                val ch = if (shifted) String(Character.toChars(cp)).uppercase().codePointAt(0) else cp
                listener?.onKey(ch, e.x.toInt(), e.y.toInt())
            }
            MotionEvent.ACTION_CANCEL -> dismissPopup()
        }
        return true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (popupWindow != null) return handlePopupTouch(e)
        if (oneHanded != "off" && (e.actionMasked == MotionEvent.ACTION_DOWN || emptyStripTouch) && inEmptyStrip(e.x)) {
            emptyStripTouch = e.actionMasked != MotionEvent.ACTION_UP && e.actionMasked != MotionEvent.ACTION_CANCEL
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                if (sideButton.contains(e.x, e.y)) listener?.onOneHanded("swap") else if (fullButton.contains(e.x, e.y)) listener?.onOneHanded("full")
            }
            return true
        }
        val t = tracker ?: return false
        val t0 = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                t.onDown(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) t.onMove(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                t.onUp(e.getPointerId(i), e.getX(i), e.getY(i))
                if (BuildConfig.DEBUG) tapLatency.record(System.nanoTime() - t0)
            }
            MotionEvent.ACTION_CANCEL -> t.cancelAll()
        }
        return true
    }

    // KeyTouchTracker.Sink
    override fun pressed(key: Int) {
        setPressed(key, true)
        val code = layout?.codes?.get(key) ?: return
        if (code != KeyCodes.SHIFT) feedback()
        if (code == KeyCodes.SWITCH || layout?.popups?.get(key) != null) {
            longPressKey = key; longPressFired = false
            removeCallbacks(longPress); postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout().toLong())
        }
        if (code == KeyCodes.BACKSPACE) {
            // Acts on touch-down and repeats while held, like every other keyboard.
            listener?.onKey(code, -1, -1)
            repeatCode = code; repeatCount = 0
            removeCallbacks(repeater); postDelayed(repeater, REPEAT_START_MS)
        }
    }

    override fun released(key: Int) {
        setPressed(key, false)
        if (key == longPressKey) removeCallbacks(longPress)
        if (repeatCode != 0 && layout?.codes?.get(key) == repeatCode) { repeatCode = 0; removeCallbacks(repeater) }
    }

    override fun tapped(key: Int, x: Float, y: Float) {
        val code = layout?.codes?.get(key) ?: return
        if (code == KeyCodes.SWITCH && longPressFired && key == longPressKey) { longPressFired = false; return }
        if (code != KeyCodes.BACKSPACE) listener?.onKey(code, x.toInt(), y.toInt())
    }

    override fun slid(steps: Int) { listener?.onCursorSteps(steps) }

    override fun onDetachedFromWindow() { dismissPopup(); repeatCode = 0; removeCallbacks(repeater); removeCallbacks(longPress); super.onDetachedFromWindow() }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val u = 1f - t
        return ((((a ushr 24) * u + (b ushr 24) * t).toInt()) shl 24) or ((((a shr 16 and 0xFF) * u + (b shr 16 and 0xFF) * t).toInt()) shl 16) or
            ((((a shr 8 and 0xFF) * u + (b shr 8 and 0xFF) * t).toInt()) shl 8) or (((a and 0xFF) * u + (b and 0xFF) * t).toInt())
    }

    private fun setPressed(key: Int, on: Boolean) {
        if (key !in pressed.indices || pressed[key] == on) return
        pressed[key] = on
        releasedAt[key] = if (!on && android.animation.ValueAnimator.areAnimatorsEnabled()) SystemClock.uptimeMillis() else 0L
        val o = key * 4
        invalidate(dirty[o], dirty[o + 1], dirty[o + 2], dirty[o + 3])
    }

    companion object {
        const val ROW_HEIGHT_DP = 54f
        const val PAD_X_DP = 3f
        const val PAD_TOP_DP = 6f
        const val PAD_BOTTOM_DP = 8f
        const val FADE_MS = 260L
        const val RELEASE_FADE_MS = 90L
        const val TRAIL_POINTS = 40
        const val REPEAT_START_MS = 400L
        const val REPEAT_FAST_MS = 55L
        const val REPEAT_SLOW_MS = 160L
    }
}
