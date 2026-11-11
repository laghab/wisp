package app.wisp.keyboard.kbd

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import app.wisp.keyboard.style.Theme
import app.wisp.keyboard.style.ThemeFonts
import app.wisp.keyboard.style.Themes
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import app.wisp.keyboard.BuildConfig
import app.wisp.keyboard.LatencyStats
import app.wisp.keyboard.Probe
import app.wisp.keyboard.core.Act
import app.wisp.keyboard.core.Native

/**
 * The key surface. One View, one draw pass, no per-key child views. Geometry and every touch gesture
 * live in the Rust core ([Native]); this class forwards raw pointer events and acts on what comes back.
 */
class KeyboardView(context: Context, private var spec: LayoutSpec, val palette: Palette = Palette()) :
    View(context) {

    interface Listener {
        fun onKey(code: Int, x: Int, y: Int)
        /** Backspace held: one character, or a whole word when [word]. */
        fun onRepeat(word: Boolean) {}
        fun onCursorSteps(steps: Int) {}
        /** A backspace swipe covers [chars] characters before the cursor, one more per finger step (0 clears the selection); it ends with [onWordSwipeEnd]. */
        fun onWordSwipe(chars: Int) {}
        fun onWordSwipeEnd(chars: Int) {}
        /** Shift held and slid onto a letter. */
        fun onShiftLetter(code: Int, x: Int, y: Int) {}
        /** Long press on a key that supports it (currently the switch-keyboard key). */
        fun onLongPress(code: Int) {}
        /** A swipe across the letters finished. [xs]/[ys] are only valid during this call; copy what you keep. */
        fun onGesture(xs: FloatArray, ys: FloatArray, count: Int) {}
    }

    private val density = resources.displayMetrics.density

    var listener: Listener? = null

    var shifted = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** The typing language shown on the space bar, and the code of the next one, which its long-press menu switches to. */
    var languageName = ""
        set(v) { if (field != v) { field = v; invalidate() } }
    var languageCode = ""
    /** The code of the language in use ("EN"), shown small on the space bar when its picture covers the name. */
    var languageNow = ""

    /** Caps lock (shift held on), as opposed to shift for one letter: the shift key shows a bar under its arrow. */
    var capsLock = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val tapLatency = LatencyStats("tap_up_to_commit")
    private val drawTime = LatencyStats("on_draw")
    /** Swipe typing on or off (the setting); takes effect the next time the layout is built. */
    var gesturesEnabled = true
        set(v) { if (field != v) { field = v; if (width > 0) rebuild(width, height) } }

    // Trail: drawn while the finger is down, then fades out.
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 5f * density; color = palette.accent
    }
    private val fadeX = FloatArray(MAX_TRACE)
    private val fadeY = FloatArray(MAX_TRACE)
    private var fadeCount = 0
    private var fadeStart = 0L

    // The Rust touch machine for the current layout, and the buffers its events travel in.
    private var handle = 0L
    private val actions = IntArray(72)
    private val traceX = FloatArray(MAX_TRACE)
    private val traceY = FloatArray(MAX_TRACE)
    private var traceCount = 0
    private var gesturing = false
    private val tick = Runnable { dispatch(Native.TICK, 0, 0f, 0f, SystemClock.uptimeMillis()) }

    private var rowHeightPx = ROW_HEIGHT_DP * density
    private var keyGapPx = KEY_GAP_DP * density
    private var rowGapPx = ROW_GAP_DP * density
    private var theme: Theme = Themes.get(context, Themes.DEFAULT)
    private var renderer = ThemeRenderer(theme, density)
    private var keyHints = true
    private var radius = theme.radius[0] * density
    /** Where the keyboard sits is the host's job; the view only needs the key height scale and the middle gap of split mode. */
    var rowScale = 1f
        set(v) { if (field != v) { field = v; rowHeightPx = ROW_HEIGHT_DP * density * v; if (width > 0) { release(); requestLayout(); rebuild(width, contentHeight()) } } }
    var splitGapPx = 0f
        set(v) { if (field != v) { field = v; if (width > 0) rebuild(width, contentHeight()) } }
    private var haptic = "light"
    private var wordSwipe = true
    private var longPressMs = android.view.ViewConfiguration.getLongPressTimeout()
    private var cursorSlide = true
    private var sound = false
    private var trailOn = true
    private var vibrator: android.os.Vibrator? = null

    private var layout: CompiledLayout? = null

    /** The geometry currently drawn, for code that needs key positions (the dictionary's proximity model). */
    val compiledLayout: CompiledLayout? get() = layout

    /** Called (on the UI thread) whenever the geometry is rebuilt. */
    var onLayoutBuilt: ((CompiledLayout) -> Unit)? = null
    private var pressed = BooleanArray(0)
    /** When each key was let go, for the short fade back from the pressed colour. 0 = not fading. */
    private var releasedAt = LongArray(0)
    private var fading = false
    private var labelY = FloatArray(0)

    private val fill = Paint()
    private val bigText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT; typeface = Typeface.MONOSPACE; textSize = 9f * density }
    private val bitmapPaint = Paint()
    private val glideLit = java.util.BitSet()
    // The space bar's picture, where the theme has one and the user has not turned it off. It moves, so the space key redraws on a timer
    // while the keyboard is showing (and stays still when the phone's animations are off).
    private var artMode = "subtle"
    private var art: app.wisp.keyboard.style.SvgArt? = null
    private var artFor: Theme? = null
    private var artSince = 0L
    private val artRect = RectF()
    private val artClip = android.graphics.Path()
    private val artTick = Runnable { artRect.let { invalidate(it.left.toInt() - 1, it.top.toInt() - 1, it.right.toInt() + 1, it.bottom.toInt() + 1) } }
    private val artFonts: (String, Int) -> android.graphics.Typeface = { family, weight -> ThemeFonts.byFamily(context, family, weight) }

    private fun currentArt(): app.wisp.keyboard.style.SvgArt? {
        if (artFor !== theme) { artFor = theme; art = if (theme.art != null) app.wisp.keyboard.style.SvgArt.load(context, theme.id, theme.dark) else null; artSince = SystemClock.uptimeMillis() }
        return if (artMode == "off") null else art
    }

    private val QUIET_ART = setOf("sakura", "dandelion", "moon")
    private var tintedTheme: Theme? = null
    private var tintedBigSize = 0f
    private val box = RectF()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), contentHeight())
    }

    /** Swaps the layout (e.g. number pad for a numeric field). */
    fun setSpec(newSpec: LayoutSpec) {
        if (newSpec === spec) return
        spec = newSpec
        release()
        requestLayout()
        if (width > 0) rebuild(width, contentHeight())
        invalidate()
    }

    /** Applies the user's look and feel. Cheap to call; geometry is rebuilt only if it changed. */
    fun applyStyle(style: app.wisp.keyboard.style.KeyboardStyle) {
        val h = ROW_HEIGHT_DP * density * rowScale
        val gx = KEY_GAP_DP * density * style.gap / 100f; val gy = ROW_GAP_DP * density * style.gap / 100f
        val bandChanged = style.themeFor(context).bandHeight != theme.bandHeight
        val geometryChanged = bandChanged || h != rowHeightPx || gx != keyGapPx || gy != rowGapPx 
        rowHeightPx = h; keyGapPx = gx; rowGapPx = gy
        setTheme(style.themeFor(context))
        keyHints = style.keyHints
        setPhoto(style.photo, style.photoDim)
        artMode = style.spaceArt
        haptic = style.haptic; sound = style.sound; trailOn = style.trailOn
        wordSwipe = style.wordSwipe; cursorSlide = style.cursorSlide; longPressMs = style.longPressMs
        if (handle != 0L) Native.touchConfig(handle, touchConfig())
        trailPaint.strokeWidth = style.trailWidthDp * density
        trailPaint.color = style.trailColorInt(context)
        if (geometryChanged) { release(); requestLayout(); if (width > 0) rebuild(width, contentHeight()) }
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

    /**
     * When set, the keyboard is as tall as a layout with this many rows would be, whatever layout it shows: symbols, the number pad or a layout
     * with fewer rows share that height instead of changing it, so the keyboard never jumps in size when you switch pages or open a panel.
     */
    var heightRows = 0
        set(v) { if (field != v) { field = v; release(); requestLayout(); if (width > 0) rebuild(width, contentHeight()) } }

    private fun heightFor(rows: Int) =
        ((PAD_TOP_DP + PAD_BOTTOM_DP + theme.bandHeight) * density + rowHeightPx * rows + rowGapPx * (rows - 1)).toInt()

    private fun contentHeight() = heightFor(if (heightRows > 0) heightRows else spec.rows.size)

    /** The height of one row: its share of the keyboard's height when [heightRows] fixes it, otherwise the plain row height. */
    private fun effectiveRowHeight(): Float {
        if (heightRows <= 0 || heightRows == spec.rows.size) return rowHeightPx
        val n = spec.rows.size
        return (contentHeight() - (PAD_TOP_DP + PAD_BOTTOM_DP + theme.bandHeight) * density - rowGapPx * (n - 1)) / n
    }

    /** Frees everything for a view that was never attached (an off-screen render for a thumbnail). */
    fun dispose() { removeCallbacks(artTick); release(); renderer.release() }

    /** Stops any touch in progress and frees the Rust layout. */
    private fun release() {
        removeCallbacks(tick); dismissPopup(); gesturing = false
        for (i in pressed.indices) pressed[i] = false
        if (handle != 0L) { Native.touchFree(handle); handle = 0 }
        layout = null
    }

    private fun rebuild(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        release()
        val padX = PAD_X_DP * density
        val left = padX; val right = padX
        val slots = LayoutSlots(spec) { k -> gesturesEnabled && k.kind == KeyKind.LETTER && Character.isLetter(k.code) }
        val metrics = floatArrayOf(
            effectiveRowHeight(), rowGapPx, keyGapPx, left, right, (PAD_TOP_DP + theme.bandHeight) * density, PAD_BOTTOM_DP * density,
            PAD_UNIT_DP * density, splitGapPx,
        )
        val rowW = (w - left - right) / 10f
        popupCell = maxOf(rowW, 44f * density)
        handle = Native.touchNew(slots.weights, slots.flags, slots.rowLens, w.toFloat(), metrics, slots.keyInfo, touchConfig())
        if (handle == 0L) return
        val n = slots.keyInfo.size
        val rects = FloatArray(n * 4)
        Native.touchRects(handle, rects)
        val l = CompiledLayout(spec, w.toFloat(), h.toFloat(), rects)
        layout = l
        pressed = BooleanArray(l.count)
        releasedAt = LongArray(l.count)
        labelY = FloatArray(l.count) { i ->
            val p = if (l.kinds[i] == KeyKind.LETTER) bigText else smallText
            val fm = p.fontMetrics
            l.centerY(i) - (fm.ascent + fm.descent) / 2f
        }
        renderer.clear()
        applyOverrides()
        invalidate()
        onLayoutBuilt?.invoke(l)
    }

    private fun touchConfig() = floatArrayOf(
        SLOP_DP * density, CHAR_STEP_DP * density, CURSOR_STEP_DP * density, popupCell,
        longPressMs.toFloat(), REPEAT_DELAY_MS, if (wordSwipe) 1f else 0f, if (cursorSlide) 1f else 0f,
    )

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (handle == 0L && width > 0) rebuild(width, height) }

    private var photoPath = ""
    private var photoBitmap: android.graphics.Bitmap? = null

    private fun setPhoto(path: String, dim: Int) {
        if (path != photoPath) { photoPath = path; photoBitmap = app.wisp.keyboard.style.PhotoBackground.load(path) }
        val b = photoBitmap
        val before = renderer.photo to renderer.photoDim
        renderer.photo = b
        // Opaque keys carry their own contrast; only see-through keys need the photo darkened for the labels.
        val seeThrough = (theme.key.base() ushr 24) < 0xF0
        renderer.photoDim = if (b == null) 0 else if (seeThrough) app.wisp.keyboard.style.PhotoBackground.effectiveDim(dim, app.wisp.keyboard.style.PhotoBackground.meanLuminance(b), theme.fg) else dim
        if (before != (renderer.photo to renderer.photoDim)) renderer.boardChanged()
        invalidate()
    }

    /** Switches skin: caches are rebuilt on the next draw, and label paints take the theme's font and spacing. */
    fun setTheme(t: Theme) {
        if (t == theme) return
        theme = t
        renderer.release(); renderer = ThemeRenderer(t, density)
        val face = ThemeFonts.typeface(context, t.font, t.weight)
        for (p in arrayOf(bigText, smallText)) { p.typeface = face; p.letterSpacing = t.track }
        bigText.textSize = t.size * density
        smallText.textSize = minOf(t.size, 15f) * density
        radius = t.radius[0] * density
        if (layout != null) {
            labelY = FloatArray(layout!!.count) { i -> val p = if (layout!!.kinds[i] == KeyKind.LETTER) bigText else smallText; val fm = p.fontMetrics; layout!!.centerY(i) - (fm.ascent + fm.descent) / 2f }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val l = layout ?: return
        val d0 = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        Probe.first("first_draw")
        if (bigText.typeface == null || tintedBigSize != theme.size || tintedTheme !== theme) { setThemePaints(); tintedBigSize = theme.size; tintedTheme = theme }
        renderer.prepareBackdrop(width, height)
        renderer.drawBoard(canvas, width, height)
        renderer.drawBand(canvas, width)
        val r = l.rects
        val now = SystemClock.uptimeMillis()
        val m = renderer.margin.toFloat()
        fading = false
        for (i in 0 until l.count) {
            val o = i * 4
            val w = Math.round(r[o + 2] - r[o]); val h = Math.round(r[o + 3] - r[o + 1])
            val role = when (l.kinds[i]) { KeyKind.ACCENT -> ThemeRenderer.Role.ACCENT; KeyKind.SPECIAL -> ThemeRenderer.Role.MOD; else -> ThemeRenderer.Role.KEY }
            val lit = pressed[i] || glideLit.get(i)
            val x = r[o] - m; val y = r[o + 1] - m
            // Comic's hand-set keys: each one is turned a little, the same way every time.
            val tilted = theme.tilt && l.kinds[i] != KeyKind.SPACE
            if (tilted) { canvas.save(); canvas.rotate((((i + 1) * 37 % 5) - 2) * 0.6f, (r[o] + r[o + 2]) / 2f, (r[o + 1] + r[o + 3]) / 2f) }
            if (lit) canvas.drawBitmap(renderer.key(role, true, w, h, r[o], r[o + 1]), x, y, null)
            else {
                canvas.drawBitmap(renderer.key(role, false, w, h, r[o], r[o + 1]), x, y, null)
                if (releasedAt[i] != 0L) {
                    val t = (now - releasedAt[i]) / RELEASE_FADE_MS.toFloat()
                    if (t >= 1f) releasedAt[i] = 0L
                    else { fading = true; bitmapPaint.alpha = ((1f - t) * 255).toInt(); canvas.drawBitmap(renderer.key(role, true, w, h, r[o], r[o + 1]), x, y, bitmapPaint); bitmapPaint.alpha = 255 }
                }
            }
            val ink = if (lit || role == ThemeRenderer.Role.ACCENT) theme.accentFg else if (role == ThemeRenderer.Role.MOD) theme.modFg else theme.fg
            val picture = if (l.codes[i] == KeyCodes.SPACE) currentArt() else null
            if (picture != null) drawArt(canvas, picture, l, i, lit, now) else drawKeyFace(canvas, l, i, ink)
            if (tilted) canvas.restore()
        }
        if (fading) postInvalidateOnAnimation()
        drawTrail(canvas)
        if (BuildConfig.DEBUG) drawTime.record(System.nanoTime() - d0)
    }

    /**
     * The time the picture is drawn at. "Still" holds the first frame. "Subtle" lets the pictures that are quiet by nature (blossom, seeds, the
     * moon) play as designed and slows the busy ones (belts, gears, neon, speed lines) to a crawl, so the space bar never pulls the eye.
     */
    private fun artSeconds(now: Long): Float {
        if (artMode == "still") return 0f
        val s = (now - artSince) / 1000f
        return if (artMode == "subtle" && theme.id !in QUIET_ART) s * 0.3f else s
    }

    private fun drawArt(c: Canvas, picture: app.wisp.keyboard.style.SvgArt, l: CompiledLayout, i: Int, lit: Boolean, now: Long) {
        val o = i * 4
        artRect.set(l.rects[o], l.rects[o + 1], l.rects[o + 2], l.rects[o + 3])
        renderer.keyShape(artClip, artRect.left, artRect.top, artRect.right, artRect.bottom)       // the key's own shape: its corner radii, or Cyberpunk's cut corners
        c.save(); c.clipPath(artClip)
        theme.art?.bg?.let { bg -> bitmapPaint.color = bg.base(); c.drawRect(artRect, bitmapPaint); bitmapPaint.color = 0xFFFFFFFF.toInt() }      // behind a picture that does not reach the edges
        picture.draw(c, artRect, artSeconds(now), slice = theme.art?.id != "moon", fonts = artFonts)
        if (lit) { bitmapPaint.alpha = 70; bitmapPaint.color = theme.accent.base(); c.drawRect(artRect, bitmapPaint); bitmapPaint.alpha = 255 }
        c.restore()
        renderer.drawBorder(c, artRect.left, artRect.top, artRect.right, artRect.bottom)        // the picture covered the key's border: put it back
        // The language is still named, small, in the corner.
        if (languageName.isNotEmpty()) { smallText.color = theme.fg; smallText.alpha = 130; smallText.textSize = 10f * density; c.drawText(languageNow.ifEmpty { languageName }, artRect.left + 6f * density, artRect.bottom - 5f * density, smallText); smallText.alpha = 255; smallText.textSize = minOf(theme.size, 15f) * density }
        if (picture.animated && artMode != "still" && android.animation.ValueAnimator.areAnimatorsEnabled() && isShown) postDelayed(artTick, if (artMode == "subtle") 66L else 33L)
    }

    private fun setThemePaints() {
        val face = ThemeFonts.typeface(context, theme.font, theme.weight)
        for (p in arrayOf(bigText, smallText)) { p.typeface = face; p.letterSpacing = theme.track }
        bigText.textSize = theme.size * density
        smallText.textSize = minOf(theme.size, 15f) * density
        val ts = theme.textShadow
        for (p in arrayOf(bigText, smallText)) if (ts != null) p.setShadowLayer(maxOf(ts.blur, 0.01f) * density, ts.dx * density, ts.dy * density, ts.color) else p.clearShadowLayer()
    }

    private fun drawKeyFace(c: Canvas, l: CompiledLayout, i: Int, ink: Int) {
        val code = l.codes[i]
        val cx = l.centerX(i); val cy = l.centerY(i)
        val d = density
        when (code) {
            KeyCodes.SHIFT -> { Icons.draw(c, if (capsLock) Icons.Icon.CAPS else Icons.Icon.SHIFT, cx, cy, 22f * d, ink, fill = shifted); return }
            KeyCodes.BACKSPACE -> { Icons.draw(c, Icons.Icon.BACK, cx, cy, 24f * d, ink); return }
            KeyCodes.EMOJI -> { Icons.draw(c, Icons.Icon.EMOJI, cx, cy, 22f * d, ink); return }
            KeyCodes.SWITCH -> { Icons.draw(c, Icons.Icon.GLOBE, cx, cy, 20f * d, ink); return }
            KeyCodes.ENTER -> if (l.labels[i].length <= 1) { Icons.draw(c, Icons.Icon.ENTER, cx, cy, 22f * d, ink, strokeWidth = 2f); return }
        }
        val letter = l.kinds[i] == KeyKind.LETTER
        val raw = if (shifted) l.labelsUpper[i] else l.labels[i]
        // Letters follow shift in every theme (small when it is off, capitals when it is on); a theme's "upper" look only applies to the other labels.
        val label = if (theme.upper && !letter && !raw.isEmpty()) raw.uppercase() else raw
        if (label.isNotEmpty()) {
            val p = if (letter) bigText else smallText
            p.color = ink
            if (l.kinds[i] == KeyKind.SPACE || code == KeyCodes.SPACE) { p.alpha = 166 }
            canvas_text(c, label, cx, labelY[i], p)
            p.alpha = 255
        } else if (code == KeyCodes.SPACE) {
            smallText.color = ink; smallText.alpha = 166
            val fm = smallText.fontMetrics
            canvas_text(c, languageName.ifEmpty { theme.spaceLabel }, cx, cy - (fm.ascent + fm.descent) / 2f, smallText.also { it.textSize = 12f * density })
            smallText.alpha = 255; smallText.textSize = minOf(theme.size, 15f) * density
        }
        if (keyHints && letter && Character.isLetter(code)) {
            val hint = l.popups[i]?.takeIf { it.isNotEmpty() }?.let { String(Character.toChars(it.codePointAt(0))) }
            if (hint != null) {
                hintText.color = ink; hintText.alpha = 140
                c.drawText(hint, l.rects[i * 4 + 2] - 5f * density, l.rects[i * 4 + 1] + 12f * density, hintText)
            }
        }
    }

    private fun canvas_text(c: Canvas, text: String, x: Float, y: Float, p: Paint) = c.drawText(text, x, y, p)


    private fun drawTrail(c: Canvas) {
        if (!trailOn) return
        if (gesturing) { strokeTrail(c, traceX, traceY, traceCount, 1f); return }
        if (fadeCount > 1) {
            val age = SystemClock.uptimeMillis() - fadeStart
            if (age >= FADE_MS) { fadeCount = 0; return }
            strokeTrail(c, fadeX, fadeY, fadeCount, 1f - age / FADE_MS.toFloat())
            postInvalidateOnAnimation()
        }
    }

    private val trailPath = android.graphics.Path()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * density }

    /** The swipe path: a wide faint stroke under a narrow bright one, round caps (the handoff's trail), scaled by [strength]. */
    private fun strokeTrail(c: Canvas, xs: FloatArray, ys: FloatArray, n: Int, strength: Float) {
        if (n < 2) return
        trailPath.rewind(); trailPath.moveTo(xs[0], ys[0])
        for (i in 1 until n) trailPath.lineTo(xs[i], ys[i])
        val base = trailPaint.color
        val thin = trailPaint.strokeWidth
        trailPaint.strokeJoin = Paint.Join.ROUND
        trailPaint.strokeWidth = maxOf(thin * 3.4f, 12f * density); trailPaint.color = base; trailPaint.alpha = (255 * 0.28f * strength).toInt()
        c.drawPath(trailPath, trailPaint)
        trailPaint.strokeWidth = thin; trailPaint.color = app.wisp.keyboard.style.Brand.mixColours(base, 0xFFFFFFFF.toInt(), 0.5f); trailPaint.alpha = (255 * strength).toInt()
        c.drawPath(trailPath, trailPaint)
        trailPaint.color = base
    }

    // ---- long-press popups: the Rust machine tracks the finger; this only draws and commits -------------------------

    private var popupWindow: android.widget.PopupWindow? = null
    private var popupChars = ""
    private var popupCell = 0f
    private var popupIndex = 0
    private var popupView: View? = null
    private var switchHeld = false

    private fun openPopup(key: Int) {
        val l = layout ?: return
        if (l.codes[key] == KeyCodes.SWITCH) { switchHeld = true; listener?.onLongPress(l.codes[key]); return }
        showPopup(key, l.popups[key] ?: return)
    }

    private fun showPopup(key: Int, chars: String) {
        val l = layout ?: return
        val list = chars.codePoints().toArray().map { String(Character.toChars(it)) }
        if (list.isEmpty()) return
        popupChars = chars
        val cell = popupCell
        val h = (rowHeightPx * 0.95f).toInt()
        val loc = IntArray(2); getLocationInWindow(loc)
        val total = cell * list.size
        // Same placement as the Rust machine uses to map the finger to an item.
        val left = l.rects[key * 4].coerceIn(0f, maxOf(0f, width - total))
        popupIndex = 0
        val inset = 3f * density
        val v = object : View(context) {
            private val bg = Paint(Paint.ANTI_ALIAS_FLAG); private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 22f * density }
            override fun onDraw(c: Canvas) {
                bg.color = palette.special; c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, bg)
                for (i in list.indices) {
                    if (i == popupIndex) { bg.color = palette.accent; c.drawRoundRect(i * cell + inset, inset, (i + 1) * cell - inset, height - inset, radius, radius, bg) }
                    tp.color = if (i == popupIndex) palette.onAccent else palette.text
                    val fm = tp.fontMetrics
                    val cx = i * cell + cell / 2f
                    when (list[i].codePointAt(0)) {
                        KeyCodes.POP_GLOBE -> Icons.draw(c, Icons.Icon.GLOBE, cx, height / 2f, 22f * density, tp.color)
                        KeyCodes.POP_LANGUAGE -> { val t = tp.textSize; tp.textSize = 16f * density; c.drawText(languageCode, cx, height / 2f - (tp.fontMetrics.ascent + tp.fontMetrics.descent) / 2f, tp); tp.textSize = t }
                        else -> c.drawText(if (shifted) list[i].uppercase() else list[i], cx, height / 2f - (fm.ascent + fm.descent) / 2f, tp)
                    }
                }
            }
        }
        popupView = v
        val w = android.widget.PopupWindow(v, total.toInt(), h, false).apply { isTouchable = false; isClippingEnabled = false; elevation = 8f * density }
        val top = (loc[1] + l.rects[key * 4 + 1] - h - 4 * density).toInt()
        w.showAtLocation(this, android.view.Gravity.NO_GRAVITY, loc[0] + left.toInt(), top)
        popupWindow = w
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    private fun dismissPopup() { popupWindow?.dismiss(); popupWindow = null; popupView = null; popupChars = "" }

    private fun commitPopup(index: Int, x: Float, y: Float) {
        if (switchHeld) { switchHeld = false; return }
        if (popupChars.isEmpty()) return
        val count = popupChars.codePointCount(0, popupChars.length)
        val cp = popupChars.codePointAt(popupChars.offsetByCodePoints(0, index.coerceIn(0, count - 1)))
        dismissPopup()
        val action = when (cp) { KeyCodes.POP_GLOBE -> KeyCodes.SWITCH; KeyCodes.POP_LANGUAGE -> KeyCodes.LANGUAGE; else -> 0 }
        if (action != 0) { listener?.onKey(action, x.toInt(), y.toInt()); return }
        val ch = if (shifted) String(Character.toChars(cp)).uppercase().codePointAt(0) else cp
        listener?.onKey(ch, x.toInt(), y.toInt())
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (handle == 0L) return false
        val t0 = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        val now = e.eventTime
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                dispatch(Native.DOWN, e.getPointerId(i), e.getX(i), e.getY(i), now)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) dispatch(Native.MOVE, e.getPointerId(i), e.getX(i), e.getY(i), now)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                dispatch(Native.UP, e.getPointerId(i), e.getX(i), e.getY(i), now)
                if (BuildConfig.DEBUG) tapLatency.record(System.nanoTime() - t0)
            }
            MotionEvent.ACTION_CANCEL -> { dispatch(Native.CANCEL_ALL, 0, 0f, 0f, now); dismissPopup(); switchHeld = false }
        }
        return true
    }

    /** Sends one event to the Rust machine, acts on what it answers, and arms the timer for what it wants next. */
    private fun dispatch(kind: Int, id: Int, x: Float, y: Float, now: Long) {
        if (handle == 0L) return
        val n = Native.touchEvent(handle, kind, id, x, y, now, actions)
        for (i in 0 until n) act(actions[i * 3], actions[i * 3 + 1], actions[i * 3 + 2], x, y)
        removeCallbacks(tick)
        val d = Native.touchDeadline(handle)
        if (d >= 0) postDelayed(tick, maxOf(0L, d - SystemClock.uptimeMillis()))
    }

    private fun act(code: Int, a: Int, b: Int, x: Float, y: Float) {
        when (code) {
            Act.PRESS -> {
                setPressed(a, true)
                if (layout?.codes?.get(a) != KeyCodes.SHIFT) feedback()
            }
            Act.RELEASE -> setPressed(a, false)
            Act.TAP -> layout?.codes?.get(a)?.let { listener?.onKey(it, x.toInt(), y.toInt()) }
            Act.REPEAT -> listener?.onRepeat(a != 0)
            Act.WORD_SELECT -> { listener?.onWordSwipe(a); performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
            Act.WORD_COMMIT -> listener?.onWordSwipeEnd(a)
            Act.WORD_CANCEL -> listener?.onWordSwipeEnd(0)
            Act.CURSOR -> { listener?.onCursorSteps(a); performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
            Act.POPUP_OPEN -> openPopup(a)
            Act.POPUP_SELECT -> if (a != popupIndex) { popupIndex = a; popupView?.invalidate() }
            Act.POPUP_COMMIT -> commitPopup(b, x, y)
            Act.SHIFT_LETTER -> layout?.codes?.get(a)?.let { listener?.onShiftLetter(it, x.toInt(), y.toInt()) }
            Act.GLIDE_START, Act.GLIDE_MOVE -> { traceCount = Native.touchTrace(handle, traceX, traceY); gesturing = true; lightKeyAt(traceX[traceCount - 1], traceY[traceCount - 1]); invalidate() }
            Act.GLIDE_END -> {
                traceCount = Native.touchTrace(handle, traceX, traceY); gesturing = false; glideLit.clear()
                System.arraycopy(traceX, 0, fadeX, 0, traceCount); System.arraycopy(traceY, 0, fadeY, 0, traceCount)
                fadeCount = traceCount; fadeStart = SystemClock.uptimeMillis()
                invalidate()
                listener?.onGesture(traceX, traceY, traceCount)
            }
            Act.GLIDE_CANCEL -> { gesturing = false; glideLit.clear(); invalidate() }
        }
    }

    /** Keys the swipe passes over light up in the accent colour while the finger is down. */
    private fun lightKeyAt(x: Float, y: Float) {
        val l = layout ?: return
        for (i in 0 until l.count) {
            if (l.kinds[i] != KeyKind.LETTER) continue
            val o = i * 4
            if (x >= l.rects[o] && x < l.rects[o + 2] && y >= l.rects[o + 1] && y < l.rects[o + 3]) { if (!glideLit.get(i)) { glideLit.set(i); invalidateKey(i) }; return }
        }
    }

    override fun onDetachedFromWindow() { removeCallbacks(artTick); release(); renderer.release(); super.onDetachedFromWindow() }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val u = 1f - t
        return ((((a ushr 24) * u + (b ushr 24) * t).toInt()) shl 24) or ((((a shr 16 and 0xFF) * u + (b shr 16 and 0xFF) * t).toInt()) shl 16) or
            ((((a shr 8 and 0xFF) * u + (b shr 8 and 0xFF) * t).toInt()) shl 8) or (((a and 0xFF) * u + (b and 0xFF) * t).toInt())
    }

    private fun setPressed(key: Int, on: Boolean) {
        if (key !in pressed.indices || pressed[key] == on) return
        pressed[key] = on
        releasedAt[key] = if (!on && android.animation.ValueAnimator.areAnimatorsEnabled()) SystemClock.uptimeMillis() else 0L
        invalidateKey(key)
    }

    /** Redraws a key and the glow around it: the pressed look reaches [ThemeRenderer.margin] px past the key, and a smaller area would clip it to a square. */
    private fun invalidateKey(key: Int) {
        val r = layout?.rects ?: return; val o = key * 4; val m = renderer.margin + 1
        invalidate(r[o].toInt() - m, r[o + 1].toInt() - m, r[o + 2].toInt() + m, r[o + 3].toInt() + m)
    }

    companion object {
        // The Claude Design handoff grid.
        const val ROW_HEIGHT_DP = 48f
        const val ROW_GAP_DP = 10f
        const val KEY_GAP_DP = 6f
        const val PAD_X_DP = 4f
        const val PAD_TOP_DP = 8f
        const val PAD_BOTTOM_DP = 10f
        const val PAD_UNIT_DP = 36f
        const val SLOP_DP = 16f
        const val CHAR_STEP_DP = 10f
        const val CURSOR_STEP_DP = 14f
        const val REPEAT_DELAY_MS = 350f
        const val MAX_TRACE = 512
        const val FADE_MS = 260L
        const val RELEASE_FADE_MS = 90L
        const val TRAIL_POINTS = 40
    }
}
