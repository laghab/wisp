package app.wisp.keyboard.kbd

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.PathParser

/** The handoff's line icons (24 x 24 boxes, round caps and joins), drawn from their SVG path data so they look the same everywhere. */
object Icons {
    enum class Icon(val paths: List<String>, val circle: Boolean = false, val dots: List<Triple<Float, Float, Float>> = emptyList(), val filled: Boolean = false) {
        SHIFT(listOf("M12 4 4 12h4.5v7h7v-7H20z")),
        /** The shift arrow with a bar under it: caps lock, as opposed to shift for one letter. */
        CAPS(listOf("M12 3 4 11h4.5v6h7v-6H20z", "M8.5 20.5h7")),
        BACK(listOf("M21 5H8.5L3 12l5.5 7H21z", "m12 9 5 6M17 9l-5 6")),
        ENTER(listOf("M20 5v7a3 3 0 0 1-3 3H5", "m9 11-4 4 4 4")),
        EMOJI(listOf("M8.5 14.5a4 4 0 0 0 7 0"), circle = true, dots = listOf(Triple(9f, 10f, 1f), Triple(15f, 10f, 1f))),
        GLOBE(listOf("M3 12h18", "M12 3c2.5 2.7 3.8 5.7 3.8 9s-1.3 6.3-3.8 9c-2.5-2.7-3.8-5.7-3.8-9S9.5 5.7 12 3z"), circle = true),
        GRID(listOf("M5.5 4h3a1.5 1.5 0 0 1 1.5 1.5v3A1.5 1.5 0 0 1 8.5 10h-3A1.5 1.5 0 0 1 4 8.5v-3A1.5 1.5 0 0 1 5.5 4z", "M15.5 4h3A1.5 1.5 0 0 1 20 5.5v3a1.5 1.5 0 0 1-1.5 1.5h-3A1.5 1.5 0 0 1 14 8.5v-3A1.5 1.5 0 0 1 15.5 4z",
            "M5.5 14h3a1.5 1.5 0 0 1 1.5 1.5v3A1.5 1.5 0 0 1 8.5 20h-3A1.5 1.5 0 0 1 4 18.5v-3A1.5 1.5 0 0 1 5.5 14z", "M15.5 14h3a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1-1.5 1.5h-3a1.5 1.5 0 0 1-1.5-1.5v-3a1.5 1.5 0 0 1 1.5-1.5z")),
        CLIPBOARD(listOf("M8 4h8a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M9 4V3h6v1M9 10h6M9 14h4")),
        SPARKLE(listOf("M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z", "M19 15.5l.7 1.8 1.8.7-1.8.7-.7 1.8-.7-1.8-1.8-.7 1.8-.7z")),
        THEME(listOf("M12 3a9 9 0 1 0 0 18c1.1 0 1.6-.8 1.3-1.7-.4-1.1.3-2.3 1.5-2.3H17a4 4 0 0 0 4-4c0-5.5-4-10-9-10z"), dots = listOf(Triple(7.5f, 11f, 1f), Triple(10f, 7f, 1f), Triple(15f, 7.5f, 1f))),
        SLIDERS(listOf("M4 7h10M18 7h2M4 17h4M12 17h8", "M16 5a2 2 0 1 0 0 4 2 2 0 0 0 0-4z", "M10 15a2 2 0 1 0 0 4 2 2 0 0 0 0-4z")),
        CLOSE(listOf("M6 6l12 12M18 6 6 18")),
        REFRESH(listOf("M20 12a8 8 0 1 1-2.5-5.8", "M20 4v5h-5")),
        SEARCH(listOf("M17.5 11a6.5 6.5 0 1 1-13 0 6.5 6.5 0 0 1 13 0z", "m16 16 4.5 4.5")),
        BACK_CHEVRON(listOf("m15 5-7 7 7 7")),
        PIN(listOf("M9 4h6l-1 6 3 3H7l3-3z", "M12 13v7")),
        LINK(listOf("M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1", "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1")),
        CLOCK(listOf("M12 9v4l2.5 2M10 2h4"), circle = false),
        SHIELD(listOf("M12 3 5 6v5c0 4.5 3 8 7 10 4-2 7-5.5 7-10V6z", "m9 12 2 2 4-4")),
        TRASH(listOf("M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3")),
        CROP(listOf("M6 2v14a2 2 0 0 0 2 2h14", "M2 6h14a2 2 0 0 1 2 2v14")),
        KEYBOARD(listOf("M3 6h18a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1z", "M6 10h.01M10 10h.01M14 10h.01M18 10h.01M7 14h10")),
        DATABASE(listOf("M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3z", "M4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6", "M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6")),
        ARCHIVE(listOf("M3 5h18v4H3z", "M5 9v10h14V9", "M10 13h4")),
        SWIPE(listOf("M3 17c3-9 6 3 9-5s5 1 9-5", "m17 5 4 2-2 4")),
        VIBRATE(listOf("M8 4h8a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "M3 9v6M21 9v6M1 11v2M23 11v2")),
        IMAGE(listOf("M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "m4 16 5-5 4 4 3-3 4 4", "M9 8.5h.01")),
        PEN(listOf("M4 20h4L19 9l-4-4L4 16z", "m13.5 6.5 4 4")),
        BOLT(listOf("M13 3 5 13h6l-1 8 8-10h-6z")),
        FOLDER(listOf("M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z")),
        PHONE(listOf("M8 3h8a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z", "M11 18h2")),
        LOCK(listOf("M6 11h12a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1z", "M8 11V8a4 4 0 0 1 8 0v3")),
        MOON(listOf("M20 14.5A8 8 0 0 1 9.5 4 8 8 0 1 0 20 14.5z")),
        MIC(listOf("M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z", "M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21")),
    }

    private val cache = HashMap<String, Path>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    /** Draws [icon] centred at ([cx], [cy]), [size] px wide. [fill] fills the first shape (the shift arrow when caps are on). */
    fun draw(c: Canvas, icon: Icon, cx: Float, cy: Float, size: Float, color: Int, strokeWidth: Float = 1.8f, fill: Boolean = false) {
        val s = size / 24f
        paint.color = color
        c.save(); c.translate(cx - size / 2f, cy - size / 2f); c.scale(s, s)
        paint.strokeWidth = strokeWidth
        if (icon.circle) { paint.style = Paint.Style.STROKE; c.drawCircle(12f, 12f, 9f, paint) }
        for ((k, d) in icon.paths.withIndex()) {
            val p = cache.getOrPut(d) { PathParser.createPathFromPathData(d) }
            paint.style = if (fill && k == 0) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
            c.drawPath(p, paint)
        }
        paint.style = Paint.Style.FILL
        for ((x, y, r) in icon.dots) c.drawCircle(x, y, r, paint)
        paint.style = Paint.Style.STROKE
        c.restore()
    }
}
