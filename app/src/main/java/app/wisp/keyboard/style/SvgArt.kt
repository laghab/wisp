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

    private class Style(var fill: Int?, var stroke: Int?, var strokeWidth: Float, var cap: Paint.Cap, var join: Paint.Join, var opacity: Float, var dash: FloatArray?)

    /**
     * Draws the picture into [into] (a rectangle on [canvas]) at time [seconds]. [slice] fills the rectangle and crops what overhangs;
     * otherwise the whole picture fits inside it.
     */
    fun draw(canvas: Canvas, into: RectF, seconds: Float, slice: Boolean, fonts: (String, Int) -> android.graphics.Typeface) {
        val sx = into.width() / vw; val sy = into.height() / vh
        val s = if (slice) maxOf(sx, sy) else minOf(sx, sy)
        canvas.save()
        canvas.translate(into.left + (into.width() - vw * s) / 2f, into.top + (into.height() - vh * s) / 2f)
        canvas.scale(s, s)
        canvas.clipRect(0f, 0f, vw, vh)
        drawNode(canvas, root, Style(0xFF000000.toInt(), null, 1f, Paint.Cap.BUTT, Paint.Join.MITER, 1f, null), seconds, fonts)
        canvas.restore()
    }

    // ---- drawing

    private fun drawNode(c: Canvas, n: Node, parent: Style, t: Float, fonts: (String, Int) -> android.graphics.Typeface) {
        fun attr(name: String): String? = animated(n, name, t) ?: n.attrs[name]
        val st = Style(parent.fill, parent.stroke, parent.strokeWidth, parent.cap, parent.join, parent.opacity, parent.dash)
        attr("fill")?.let { st.fill = colour(it) }
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
                if (n.tag == "svg" && n !== root) c.translate(num(n, "x", t), num(n, "y", t))
                for (ch in n.children) drawNode(c, ch, st, t, fonts)
            }
            "rect" -> { rect.set(num(n, "x", t), num(n, "y", t), num(n, "x", t) + num(n, "width", t), num(n, "y", t) + num(n, "height", t)); val r = num(n, "rx", t); shape(c, st, r, r, rect) { p -> if (r > 0f) c.drawRoundRect(rect, r, r, p) else c.drawRect(rect, p) } }
            "circle" -> { val r = num(n, "r", t); rect.set(num(n, "cx", t) - r, num(n, "cy", t) - r, num(n, "cx", t) + r, num(n, "cy", t) + r); shape(c, st, r, r, rect) { p -> c.drawCircle(num(n, "cx", t), num(n, "cy", t), r, p) } }
            "ellipse" -> { rect.set(num(n, "cx", t) - num(n, "rx", t), num(n, "cy", t) - num(n, "ry", t), num(n, "cx", t) + num(n, "rx", t), num(n, "cy", t) + num(n, "ry", t)); shape(c, st, 0f, 0f, rect) { p -> c.drawOval(rect, p) } }
            "path" -> { path.reset(); path.set(parsePath(n.attrs["d"] ?: "")); val fo = attr("fill-opacity")?.toFloatOrNull() ?: 1f; shape(c, st, 0f, 0f, rect, fo) { p -> c.drawPath(path, p) } }
            "text" -> drawText(c, n, st, t, fonts)
        }
        c.restore()
    }

    private fun shape(c: Canvas, st: Style, rx: Float, ry: Float, box: RectF, fillOpacity: Float = 1f, draw: (Paint) -> Unit) {
        st.fill?.let { paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.FILL; paint.color = withAlpha(it, st.opacity * fillOpacity); draw(paint) }
        st.stroke?.let { paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.STROKE; paint.color = withAlpha(it, st.opacity); paint.strokeWidth = st.strokeWidth; paint.strokeCap = st.cap; paint.strokeJoin = st.join
            st.dash?.let { d -> paint.pathEffect = android.graphics.DashPathEffect(d, 0f) }; draw(paint) }
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

        /** The art for [themeId] in the light or dark look, or null when the theme has none. */
        fun load(context: Context, themeId: String, dark: Boolean): SvgArt? = cache.getOrPut("$themeId-$dark") {
            val names = listOf("$themeId-${if (dark) "dark" else "light"}.svg", "$themeId-${if (dark) "light" else "dark"}.svg")      // a one-look theme has one file
            val name = names.firstOrNull { f -> runCatching { context.assets.open("art/$f").close(); true }.getOrDefault(false) } ?: return@getOrPut null
            runCatching { parse(context.assets.open("art/$name").bufferedReader().use { it.readText() }) }.getOrNull()
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
