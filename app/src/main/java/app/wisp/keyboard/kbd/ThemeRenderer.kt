package app.wisp.keyboard.kbd

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import app.wisp.keyboard.style.Fill
import app.wisp.keyboard.style.Shadow
import app.wisp.keyboard.style.Theme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws a [Theme]: the board, the band above the keys, and key backgrounds. A key background (shadow, fill, inset
 * shadow, border) is drawn once into a bitmap per size and look and then only blitted, so a frame costs a few
 * `drawBitmap` calls whatever the theme. Not thread-safe; UI thread only.
 */
class ThemeRenderer(val theme: Theme, private val density: Float) {

    enum class Role { KEY, MOD, ACCENT }

    private class Entry(val bitmap: Bitmap)
    private val cache = HashMap<Long, Entry>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val inner = Path()
    private val rect = RectF()
    private val matrix = Matrix()

    /** Room around a key bitmap for outside shadows, in px. */
    val margin: Int = ceil(theme.shadowReach * density).toInt() + 1

    private fun dp(v: Float) = v * density

    // ---- board and band

    /** Centre-crops the photo to the board and darkens it; false when there is no photo. */
    private fun paintPhoto(c: Canvas, w: Int, h: Int): Boolean {
        val p = photo ?: return false
        val scale = maxOf(w / p.width.toFloat(), h / p.height.toFloat())
        val dw = p.width * scale; val dh = p.height * scale
        paint.reset(); paint.isFilterBitmap = true
        c.drawBitmap(p, null, RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), paint)
        paint.reset(); paint.color = (photoDim * 255 / 100) shl 24
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        return true
    }

    /** The photo or its dimming changed: key backgrounds that show the board through them must be redrawn. */
    fun boardChanged() { backdropW = 0; clear() }

    private var boardShaders: List<Shader?> = emptyList()
    private var boardW = 0; private var boardH = 0

    /** A photo drawn instead of the board layers, darkened by [photoDim] percent. */
    var photo: Bitmap? = null
    var photoDim = 0

    fun drawBoard(c: Canvas, w: Int, h: Int, translucentBackdrop: Boolean = false) {
        if (paintPhoto(c, w, h)) return
        if (w != boardW || h != boardH) {
            boardW = w; boardH = h
            boardShaders = theme.board.map { shader(it, 0f, 0f, w.toFloat(), h.toFloat()) }
        }
        if (translucentBackdrop) c.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
        for ((i, f) in theme.board.withIndex()) {
            paint.reset(); paint.isAntiAlias = true
            val s = boardShaders[i]
            if (s != null) paint.shader = s else paint.color = (f as Fill.Solid).color
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        }
    }

    private var bandShader: Shader? = null
    private var bandW = 0

    /** The decorative strip above the keys ([Theme.bandHeight] dp tall). Returns its height in px. */
    fun drawBand(c: Canvas, w: Int): Float {
        val band = theme.band ?: return 0f
        val h = dp(theme.bandHeight)
        if (h <= 0f) return 0f
        if (w != bandW) { bandW = w; bandShader = shader(band, 0f, 0f, w.toFloat(), h) }
        paint.reset(); paint.isAntiAlias = true
        val s = bandShader
        if (s != null) paint.shader = s else paint.color = (band as Fill.Solid).color
        c.drawRect(0f, 0f, w.toFloat(), h, paint)
        return h
    }

    // ---- keys

    /**
     * The background for a key of size [w] x [h] px (plus [margin] on every side). [lit]: on a swipe path or being pressed.
     * Frosted themes ([Theme.blur]) show the blurred board under the key, so they also depend on where the key is ([left], [top]).
     */
    fun key(role: Role, lit: Boolean, w: Int, h: Int, left: Float = 0f, top: Float = 0f): Bitmap {
        val frosted = theme.blur > 0f && backdrop != null
        var id = (role.ordinal.toLong() shl 60) or ((if (lit) 1L else 0L) shl 59) or (w.toLong() shl 29) or h.toLong()
        if (frosted) id = id xor ((left.toInt().toLong() and 0x3FFF) shl 44) xor ((top.toInt().toLong() and 0x3FFF) shl 15) xor (1L shl 58)
        return cache.getOrPut(id) { Entry(render(role, lit, w, h, left, top)) }.bitmap
    }

    // ---- frosted glass: the board, blurred by drawing it small and scaling it back up

    private var backdrop: Bitmap? = null
    private var backdropScale = 1f
    private var backdropW = 0; private var backdropH = 0
    private val frostPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(1.6f) }) }

    /** Call when the board size changes (before drawing keys). */
    fun prepareBackdrop(w: Int, h: Int) {
        if (theme.blur <= 0f || w <= 0 || h <= 0) return
        if (backdrop != null && w == backdropW && h == backdropH) return
        backdropW = w; backdropH = h
        clear()
        val sc = maxOf(2f, theme.blur * density / 2f)
        val small = Bitmap.createBitmap(maxOf(1, (w / sc).toInt()), maxOf(1, (h / sc).toInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(small)
        c.scale(small.width / w.toFloat(), small.height / h.toFloat())
        if (!paintPhoto(c, w, h)) for (f in theme.board) {
            paint.reset(); paint.isAntiAlias = true
            val sh = shader(f, 0f, 0f, w.toFloat(), h.toFloat())
            if (sh != null) paint.shader = sh else paint.color = (f as Fill.Solid).color
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        }
        backdrop = small; backdropScale = w / small.width.toFloat()
    }

    fun fillOf(role: Role, lit: Boolean): Fill = if (lit) theme.accent else when (role) { Role.KEY -> theme.key; Role.MOD -> theme.mod; Role.ACCENT -> theme.accent }

    fun clear() { cache.values.forEach { it.bitmap.recycle() }; cache.clear() }

    fun release() { clear(); backdrop?.recycle(); backdrop = null; backdropW = 0 }

    private fun render(role: Role, lit: Boolean, w: Int, h: Int, left: Float, top: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w + 2 * margin, h + 2 * margin, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val m = margin.toFloat()
        shape(path, m, m, m + w, m + h)
        val shadows = if (lit && theme.lit.isNotEmpty()) theme.lit else if (lit) emptyList() else theme.shadow

        // Outside shadows, last one first (the first in the list is on top), kept out from under the key itself.
        c.save()
        c.clipOutPath(path)
        for (s in shadows.asReversed()) if (!s.inset) drawOutside(c, s, m, m, m + w, m + h)
        c.restore()

        backdrop?.let { bd ->
            c.save(); c.clipPath(path)
            c.translate(m - left, m - top); c.scale(backdropW / bd.width.toFloat(), backdropH / bd.height.toFloat())
            c.drawBitmap(bd, 0f, 0f, frostPaint)
            c.restore()
        }
        paint.reset(); paint.isAntiAlias = true
        val fill = fillOf(role, lit)
        val sh = shader(fill, m, m, m + w, m + h)
        if (sh != null) paint.shader = sh else paint.color = (fill as Fill.Solid).color
        c.drawPath(path, paint)

        c.save()
        c.clipPath(path)
        for (s in shadows.asReversed()) if (s.inset) drawInset(c, s, m, m, m + w, m + h)
        c.restore()

        theme.border?.let { b ->
            val bw = dp(b.width)
            if (bw > 0f) {
                shape(inner, m + bw / 2, m + bw / 2, m + w - bw / 2, m + h - bw / 2, shrink = bw / 2)
                paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.STROKE; paint.strokeWidth = bw; paint.color = b.color
                c.drawPath(inner, paint)
            }
        }
        return bmp
    }

    /** The key outline: a rounded rectangle with per-corner radii, or a chamfered one for angular themes. */
    private fun shape(out: Path, l: Float, t: Float, r: Float, b: Float, shrink: Float = 0f) {
        out.reset()
        val cut = dp(theme.cut) - shrink
        if (cut > 0f) {
            out.moveTo(l, t); out.lineTo(r - cut, t); out.lineTo(r, t + cut); out.lineTo(r, b); out.lineTo(l + cut, b); out.lineTo(l, b - cut); out.close()
            return
        }
        val rad = theme.radius
        val radii = floatArrayOf(rad[0], rad[0], rad[1], rad[1], rad[2], rad[2], rad[3], rad[3]).map { maxOf(0f, dp(it) - shrink) }.toFloatArray()
        rect.set(l, t, r, b)
        out.addRoundRect(rect, radii, Path.Direction.CW)
    }

    private fun drawOutside(c: Canvas, s: Shadow, l: Float, t: Float, r: Float, b: Float) {
        val sp = dp(s.spread)
        shape(inner, l - sp + dp(s.dx), t - sp + dp(s.dy), r + sp + dp(s.dx), b + sp + dp(s.dy), shrink = -sp)
        paint.reset(); paint.isAntiAlias = true; paint.color = s.color
        if (s.blur > 0f) paint.maskFilter = BlurMaskFilter(dp(s.blur), BlurMaskFilter.Blur.NORMAL)
        c.drawPath(inner, paint)
    }

    private fun drawInset(c: Canvas, s: Shadow, l: Float, t: Float, r: Float, b: Float) {
        val sp = dp(s.spread)
        // Everything outside the key shape moved by (dx, dy) and shrunk by the spread is shadow.
        shape(inner, l + sp + dp(s.dx), t + sp + dp(s.dy), r - sp + dp(s.dx), b - sp + dp(s.dy), shrink = sp)
        val big = dp(40f) + dp(s.blur)
        val ring = Path().apply { addRect(l - big, t - big, r + big, b + big, Path.Direction.CW); addPath(inner); fillType = Path.FillType.EVEN_ODD }
        paint.reset(); paint.isAntiAlias = true; paint.color = s.color
        if (s.blur > 0f) paint.maskFilter = BlurMaskFilter(dp(s.blur), BlurMaskFilter.Blur.NORMAL)
        c.drawPath(ring, paint)
    }

    // ---- shaders

    /** A shader for [f] over the box, or null for a flat colour. */
    private fun shader(f: Fill, l: Float, t: Float, r: Float, b: Float): Shader? = when (f) {
        is Fill.Solid -> null
        is Fill.Linear -> linear(f, l, t, r, b)
        is Fill.Radial -> {
            val cx = l + f.x * (r - l); val cy = t + f.y * (b - t)
            RadialGradient(cx, cy, dp(f.rx), f.colors, f.positions, Shader.TileMode.CLAMP).also {
                matrix.setScale(1f, f.ry / f.rx, cx, cy); it.setLocalMatrix(matrix)
            }
        }
        is Fill.Dots -> {
            val step = maxOf(2, dp(f.step).toInt())
            val tile = Bitmap.createBitmap(step, step, Bitmap.Config.ARGB_8888)
            paint.reset(); paint.isAntiAlias = true; paint.color = f.color
            Canvas(tile).drawCircle(step / 2f, step / 2f, dp(f.radius), paint)
            BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }

    private fun linear(f: Fill.Linear, l: Float, t: Float, r: Float, b: Float): Shader {
        val a = Math.toRadians(f.angle.toDouble())
        val dx = sin(a).toFloat(); val dy = (-cos(a)).toFloat()
        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        if (f.length > 0f) {
            // Repeating: one tile of `length` dp along the angle, starting where the gradient line enters the box.
            val len = dp(f.length)
            val boxLen = abs((r - l) * dx) + abs((b - t) * dy)
            val sx = cx - dx * boxLen / 2f; val sy = cy - dy * boxLen / 2f
            return LinearGradient(sx, sy, sx + dx * len, sy + dy * len, f.colors, f.positions, Shader.TileMode.REPEAT)
        }
        val len = abs((r - l) * dx) + abs((b - t) * dy)
        return LinearGradient(cx - dx * len / 2f, cy - dy * len / 2f, cx + dx * len / 2f, cy + dy * len / 2f, f.colors, f.positions, Shader.TileMode.CLAMP)
    }
}
