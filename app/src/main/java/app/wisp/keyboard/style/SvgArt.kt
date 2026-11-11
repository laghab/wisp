package app.wisp.keyboard.style

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Xml
import androidx.core.graphics.PathParser
import org.xmlpull.v1.XmlPullParser
import kotlin.math.tan

/**
 * The space bar's animated picture. The designs are small SVG files (`assets/art/<theme>-<light|dark>.svg`) that use shapes, text and a few
 * SMIL animations (fades, moves, turns, scales). Android cannot play those, so this reads the file and draws it, frame by frame, with the
 * same timings. It understands only what the designs use: svg, g, rect, circle, ellipse, path and text; animate (opacity, x, y) and
 * animateTransform (translate, rotate, scale). Colours are already filled in per mode, so the file is the whole spec.
 */
class SvgArt private constructor(private val root: Node, private val vw: Float, private val vh: Float) {

    private class Anim(val attr: String, val type: String?, val values: List<FloatArray>, val dur: Float, val begin: Float, val sum: Boolean)
    private class Node(val tag: String, val attrs: Map<String, String>, val text: String, val children: List<Node>, val anims: List<Anim>)

    /** True when something in the picture moves (a still picture is drawn once). */
    val animated: Boolean = hasAnim(root)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val matrix = Matrix()

    private class Style(var fill: Int?, var stroke: Int?, var strokeWidth: Float, var cap: Paint.Cap, var join: Paint.Join, var opacity: Float, var dash: FloatArray?, var gradient: String? = null, var blur: Float = 0f)

    /**
     * Draws the picture into [into] (a rectangle on [canvas]) at time [seconds]. [slice] fills the rectangle and crops what overhangs;
     * otherwise the whole picture fits inside it.
     */
    fun draw(canvas: Canvas, into: RectF, seconds: Float, slice: Boolean, fonts: (String, Int) -> android.graphics.Typeface, overlay: Boolean = false) {
        skipBackground = overlay
        val sx = into.width() / vw; val sy = into.height() / vh
        // An overlay is as wide as the keyboard and starts at its top (the branch is there); the space bar's picture is centred.
        val s = if (overlay) sx else if (slice) maxOf(sx, sy) else minOf(sx, sy)
        canvas.save()
        canvas.translate(into.left + (into.width() - vw * s) / 2f, if (overlay) into.top else into.top + (into.height() - vh * s) / 2f)
        if (overlay) canvas.clipRect(0f, 0f, vw, into.height() / s)
        canvas.scale(s, s)
        canvas.clipRect(0f, 0f, vw, vh)
        drawNode(canvas, root, Style(0xFF000000.toInt(), null, 1f, Paint.Cap.BUTT, Paint.Join.MITER, 1f, null), seconds, fonts)
        canvas.restore()
    }

    // ---- drawing

    private var skipBackground = false
    /** The full-size backdrop rectangle of the file, which an overlay leaves out so only the branch and petals are painted. */
    /** Gradients and filters the file defines (`defs`), by id. Gradients are in fractions of the shape they fill. */
    private val defs: Map<String, Node> = HashMap<String, Node>().also { m -> fun walk(n: Node) { n.attrs["id"]?.let { m[it] = n }; n.children.forEach(::walk) }; walk(root) }
    private val gradients: Map<String, Gradient> = defs.filterValues { it.tag == "linearGradient" }.mapValues { (_, g) ->
        val stops = g.children.filter { it.tag == "stop" }
        fun f(n: String, d: Float) = g.attrs[n]?.toFloatOrNull() ?: d
        Gradient(f("x1", 0f), f("y1", 0f), f("x2", 1f), f("y2", 0f), IntArray(stops.size) { colour(stops[it].attrs["stop-color"] ?: "#000")?.let { c -> withAlpha(c, stops[it].attrs["stop-opacity"]?.toFloatOrNull() ?: 1f) } ?: 0 },
            FloatArray(stops.size) { stops[it].attrs["offset"]?.removeSuffix("%")?.toFloatOrNull()?.let { o -> if (stops[it].attrs["offset"]!!.endsWith("%")) o / 100f else o } ?: 0f })
    }.filterValues { it.colors.size >= 2 }

    private val backdrop: Node? = root.children.firstOrNull { it.tag == "rect" && it.attrs["width"]?.toFloatOrNull() == vw && it.attrs["height"]?.toFloatOrNull() == vh }

    private fun drawNode(c: Canvas, n: Node, parent: Style, t: Float, fonts: (String, Int) -> android.graphics.Typeface) {
        if (skipBackground && n === backdrop) return
        fun attr(name: String): String? = animated(n, name, t) ?: n.attrs[name]
        val st = Style(parent.fill, parent.stroke, parent.strokeWidth, parent.cap, parent.join, parent.opacity, parent.dash, parent.gradient, parent.blur)
        attr("fill")?.let { if (it.startsWith("url(#")) st.gradient = it.substring(5, it.indexOf(')')) else { st.gradient = null; st.fill = colour(it) } }
        attr("filter")?.let { f -> st.blur = if (f.startsWith("url(#")) defs[f.substring(5, f.indexOf(')'))]?.children?.firstOrNull { it.tag == "feGaussianBlur" }?.attrs?.get("stdDeviation")?.toFloatOrNull() ?: 0f else 0f }
        attr("stroke")?.let { st.stroke = colour(it) }
        attr("stroke-width")?.toFloatOrNull()?.let { st.strokeWidth = it }
        attr("stroke-linecap")?.let { st.cap = when (it) { "round" -> Paint.Cap.ROUND; "square" -> Paint.Cap.SQUARE; else -> Paint.Cap.BUTT } }
        attr("stroke-linejoin")?.let { st.join = when (it) { "round" -> Paint.Join.ROUND; "bevel" -> Paint.Join.BEVEL; else -> Paint.Join.MITER } }
        attr("stroke-dasharray")?.let { d -> st.dash = d.split(' ', ',').mapNotNull { it.toFloatOrNull() }.toFloatArray().takeIf { it.size >= 2 } }
        attr("opacity")?.toFloatOrNull()?.let { st.opacity = parent.opacity * it }

        c.save()
        transformOf(n, t)?.let { c.concat(it) }
        when (n.tag) {
            "svg", "g" -> {
                if (n.tag == "svg" && n !== root) {      // a nested svg is a window: it moves its content to x, y and clips what overhangs its width and height
                    c.translate(num(n, "x", t), num(n, "y", t))
                    val w = n.attrs["width"]?.toFloatOrNull(); val h = n.attrs["height"]?.toFloatOrNull()
                    if (w != null && h != null) c.clipRect(0f, 0f, w, h)
                }
                for (ch in n.children) drawNode(c, ch, st, t, fonts)
            }
            "rect" -> { rect.set(num(n, "x", t), num(n, "y", t), num(n, "x", t) + num(n, "width", t), num(n, "y", t) + num(n, "height", t)); val r = num(n, "rx", t); shape(c, st, r, r, rect) { p -> if (r > 0f) c.drawRoundRect(rect, r, r, p) else c.drawRect(rect, p) } }
            "circle" -> { val r = num(n, "r", t); rect.set(num(n, "cx", t) - r, num(n, "cy", t) - r, num(n, "cx", t) + r, num(n, "cy", t) + r); shape(c, st, r, r, rect) { p -> c.drawCircle(num(n, "cx", t), num(n, "cy", t), r, p) } }
            "ellipse" -> { rect.set(num(n, "cx", t) - num(n, "rx", t), num(n, "cy", t) - num(n, "ry", t), num(n, "cx", t) + num(n, "rx", t), num(n, "cy", t) + num(n, "ry", t)); shape(c, st, 0f, 0f, rect) { p -> c.drawOval(rect, p) } }
            "path" -> { path.reset(); path.set(parsePath(n.attrs["d"] ?: "")); val fo = attr("fill-opacity")?.toFloatOrNull() ?: 1f; path.computeBounds(rect, true); shape(c, st, 0f, 0f, rect, fo) { p -> c.drawPath(path, p) } }
            "text" -> drawText(c, n, st, t, fonts)
        }
        c.restore()
    }

    private class Gradient(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val colors: IntArray, val positions: FloatArray)

    private fun shape(c: Canvas, st: Style, rx: Float, ry: Float, box: RectF, fillOpacity: Float = 1f, draw: (Paint) -> Unit) {
        // a soft edge (the designs' blur filters): a mask blur, about 1.7 radius per unit of standard deviation
        fun soften() { if (st.blur > 0f) paint.maskFilter = android.graphics.BlurMaskFilter(st.blur * 1.7f, android.graphics.BlurMaskFilter.Blur.NORMAL) }
        val grad = st.gradient?.let { gradients[it] }
        if (grad != null) {
            paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.FILL; paint.color = withAlpha(0xFFFFFFFF.toInt(), st.opacity * fillOpacity)
            paint.shader = android.graphics.LinearGradient(box.left + grad.x1 * box.width(), box.top + grad.y1 * box.height(), box.left + grad.x2 * box.width(), box.top + grad.y2 * box.height(), grad.colors, grad.positions, android.graphics.Shader.TileMode.CLAMP)
            soften(); draw(paint)
        } else st.fill?.let { paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.FILL; paint.color = withAlpha(it, st.opacity * fillOpacity); soften(); draw(paint) }
        st.stroke?.let { paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.STROKE; paint.color = withAlpha(it, st.opacity); paint.strokeWidth = st.strokeWidth; paint.strokeCap = st.cap; paint.strokeJoin = st.join
            st.dash?.let { d -> paint.pathEffect = android.graphics.DashPathEffect(d, 0f) }; soften(); draw(paint) }
    }

    private fun drawText(c: Canvas, n: Node, st: Style, t: Float, fonts: (String, Int) -> android.graphics.Typeface) {
        val size = n.attrs["font-size"]?.toFloatOrNull() ?: 10f
        paint.reset(); paint.isAntiAlias = true
        paint.typeface = fonts(n.attrs["font-family"] ?: "", n.attrs["font-weight"]?.toIntOrNull() ?: 400)
        paint.textSize = size
        n.attrs["letter-spacing"]?.toFloatOrNull()?.let { paint.letterSpacing = it / size }
        paint.textAlign = if (n.attrs["text-anchor"] == "end") Paint.Align.RIGHT else if (n.attrs["text-anchor"] == "middle") Paint.Align.CENTER else Paint.Align.LEFT
        val x = num(n, "x", t); val y = num(n, "y", t)
        val squeeze = n.attrs["textLength"]?.toFloatOrNull()?.let { it / maxOf(paint.measureText(n.text), 1f) }
        c.save(); if (squeeze != null) c.scale(squeeze, 1f, x, y)
        st.fill?.let { paint.style = Paint.Style.FILL; paint.color = withAlpha(it, st.opacity); c.drawText(n.text, x, y, paint) }
        st.stroke?.let { paint.style = Paint.Style.STROKE; paint.strokeWidth = st.strokeWidth; paint.color = withAlpha(it, st.opacity); c.drawText(n.text, x, y, paint) }
        c.restore()
    }

    // ---- values at a time

    private fun progress(a: Anim, t: Float): Float {
        if (t < a.begin) return 0f
        return (((t - a.begin) % a.dur) / a.dur)
    }

    private fun sample(a: Anim, t: Float): FloatArray {
        val p = progress(a, t); val v = a.values
        if (v.size == 1) return v[0]
        val pos = p * (v.size - 1); val i = pos.toInt().coerceIn(0, v.size - 2); val f = pos - i
        return FloatArray(v[i].size) { k -> v[i][k] + (v[i + 1].getOrElse(k) { v[i][k] } - v[i][k]) * f }
    }

    private fun animated(n: Node, name: String, t: Float): String? {
        val a = n.anims.firstOrNull { it.attr == name && it.type == null } ?: return null
        return sample(a, t)[0].toString()
    }

    private fun num(n: Node, name: String, t: Float): Float = (animated(n, name, t) ?: n.attrs[name])?.toFloatOrNull() ?: 0f

    /** The element's own transform, then its animated transforms (added on, or replacing it, as the file says). */
    private fun transformOf(n: Node, t: Float): Matrix? {
        val base = n.attrs["transform"]
        val anims = n.anims.filter { it.attr == "transform" }
        if (base == null && anims.isEmpty()) return null
        matrix.reset()
        val m = Matrix()
        if (base != null && (anims.isEmpty() || anims.any { it.sum })) m.preConcat(parseTransform(base))
        for (a in anims) {
            val v = sample(a, t); val step = Matrix()
            when (a.type) {
                "translate" -> step.setTranslate(v[0], v.getOrElse(1) { 0f })
                "scale" -> step.setScale(v[0], v.getOrElse(1) { v[0] })
                "rotate" -> step.setRotate(v[0], v.getOrElse(1) { 0f }, v.getOrElse(2) { 0f })
            }
            m.preConcat(step)
        }
        return m
    }

    private fun parseTransform(s: String): Matrix {
        val m = Matrix()
        for (g in Regex("(translate|scale|rotate|skewX|skewY)\\(([^)]*)\\)").findAll(s)) {
            val v = g.groupValues[2].split(' ', ',').filter { it.isNotBlank() }.map { it.toFloat() }; val step = Matrix()
            when (g.groupValues[1]) {
                "translate" -> step.setTranslate(v[0], v.getOrElse(1) { 0f })
                "scale" -> step.setScale(v[0], v.getOrElse(1) { v[0] })
                "rotate" -> step.setRotate(v[0], v.getOrElse(1) { 0f }, v.getOrElse(2) { 0f })
                "skewX" -> step.setSkew(tan(Math.toRadians(v[0].toDouble())).toFloat(), 0f)
                "skewY" -> step.setSkew(0f, tan(Math.toRadians(v[0].toDouble())).toFloat())
            }
            m.preConcat(step)
        }
        return m
    }

    private fun parsePath(d: String): Path = PathParser.createPathFromPathData(d) ?: Path()

    private fun colour(s: String): Int? = if (s == "none" || s == "transparent") null else runCatching { ThemeParser.color(s) }.getOrNull()

    private fun withAlpha(c: Int, a: Float): Int = ((((c ushr 24) * a.coerceIn(0f, 1f)).toInt()) shl 24) or (c and 0xFFFFFF)

    private fun hasAnim(n: Node): Boolean = n.anims.isNotEmpty() || n.children.any { hasAnim(it) }

    companion object {
        private val cache = HashMap<String, SvgArt?>()

        /** The space-bar art for [themeId] in the light or dark look, or null when the theme has none. */
        fun load(context: Context, themeId: String, dark: Boolean): SvgArt? = find(context, "art", themeId, dark)

        /** The full-keyboard overlay for [themeId] (Sakura Storm's petals), or null. */
        fun loadOverlay(context: Context, themeId: String, dark: Boolean): SvgArt? = find(context, "fx", themeId, dark)

        private fun find(context: Context, dir: String, themeId: String, dark: Boolean): SvgArt? = cache.getOrPut("$dir/$themeId-$dark") {
            val names = listOf("$themeId-${if (dark) "dark" else "light"}.svg", "$themeId-${if (dark) "light" else "dark"}.svg")      // a one-look theme has one file
            val name = names.firstOrNull { f -> runCatching { context.assets.open("$dir/$f").close(); true }.getOrDefault(false) } ?: return@getOrPut null
            runCatching { parse(context.assets.open("$dir/$name").bufferedReader().use { it.readText() }) }.getOrNull()
        }

        fun parse(svg: String): SvgArt {
            val p = Xml.newPullParser(); p.setInput(svg.reader())
            while (p.eventType != XmlPullParser.START_TAG) p.next()
            val root = readNode(p)
            val vb = root.attrs["viewBox"]?.split(' ')?.map { it.toFloat() } ?: listOf(0f, 0f, root.attrs["width"]?.toFloat() ?: 154f, root.attrs["height"]?.toFloat() ?: 48f)
            return SvgArt(root, vb[2], vb[3])
        }

        private fun readNode(p: XmlPullParser): Node {
            val tag = p.name
            val attrs = HashMap<String, String>(); for (i in 0 until p.attributeCount) attrs[p.getAttributeName(i)] = p.getAttributeValue(i)
            val children = ArrayList<Node>(); val anims = ArrayList<Anim>(); var text = ""
            while (true) {
                when (p.next()) {
                    XmlPullParser.TEXT -> text += p.text
                    XmlPullParser.START_TAG -> {
                        val child = readNode(p)
                        if (child.tag == "animate" || child.tag == "animateTransform") anim(child)?.let { anims.add(it) } else children.add(child)
                    }
                    XmlPullParser.END_TAG, XmlPullParser.END_DOCUMENT -> return Node(tag, attrs, text.trim(), children, anims)
                }
            }
        }

        private fun anim(n: Node): Anim? {
            val a = n.attrs
            val attr = a["attributeName"] ?: return null
            fun vals(s: String) = s.split(';').filter { it.isNotBlank() }.map { v -> v.trim().split(' ', ',').filter { it.isNotBlank() }.map { it.toFloat() }.toFloatArray() }
            val values = a["values"]?.let(::vals) ?: listOfNotNull(a["from"]?.let { vals(it).firstOrNull() }, a["to"]?.let { vals(it).firstOrNull() }).takeIf { it.isNotEmpty() } ?: return null
            fun secs(s: String?): Float = when { s == null -> 0f; s.endsWith("ms") -> s.dropLast(2).toFloat() / 1000f; s.endsWith("s") -> s.dropLast(1).toFloat(); else -> s.toFloatOrNull() ?: 0f }
            val dur = secs(a["dur"]).takeIf { it > 0f } ?: return null
            return Anim(if (n.tag == "animateTransform") "transform" else attr, if (n.tag == "animateTransform") a["type"] else null, values, dur, secs(a["begin"]), a["additive"] == "sum")
        }
    }
}
