package app.wisp.keyboard.style

import org.json.JSONArray
import org.json.JSONObject

/** One paint layer: a flat colour, a gradient (optionally repeating), a soft radial blob, or a dot pattern. */
sealed class Fill {
    class Solid(val color: Int) : Fill()
    /** CSS-style angle: 180 runs top to bottom, 90 left to right. [length] in dp makes the gradient repeat. */
    class Linear(val angle: Float, val length: Float, val positions: FloatArray, val colors: IntArray) : Fill()
    /** An ellipse of [rx] by [ry] dp centred at ([x], [y]) as fractions of the board. */
    class Radial(val x: Float, val y: Float, val rx: Float, val ry: Float, val positions: FloatArray, val colors: IntArray, val relative: Boolean = false) : Fill()
    class Dots(val step: Float, val radius: Float, val color: Int) : Fill()
    /** Tiles of [w] by [h] dp, each with a ring of radius [r] centred on its top edge (Dragon's scales). */
    class Scales(val w: Float, val h: Float, val r: Float, val width: Float, val color: Int) : Fill()
    /** Four small studs [inset] dp in from the corners (Factory's rivets). */
    class Rivets(val inset: Float, val radius: Float, val color: Int) : Fill()
    /** Several fills painted one over the other, the first at the bottom. */
    class Layers(val layers: List<Fill>) : Fill()

    /** A single representative colour, for the views that draw plain colours (the strip, panels). */
    fun base(): Int = when (this) {
        is Solid -> color
        is Linear -> colors[colors.size / 2]
        is Radial -> colors[0]
        is Dots -> color
        is Scales -> color
        is Rivets -> color
        is Layers -> layers.first().base()
    }
}

/** A box shadow. [inset] shadows draw inside the key; [spread] grows (or shrinks) the shape the shadow comes from. */
class Shadow(val inset: Boolean, val dx: Float, val dy: Float, val blur: Float, val spread: Float, val color: Int) {
    /** How far outside the key this shadow can reach, in dp. */
    val reach: Float get() = if (inset) 0f else maxOf(Math.abs(dx), Math.abs(dy)) + blur + maxOf(spread, 0f)
}

class Border(val width: Float, val color: Int)

/** The space bar's animated picture: which one, what is painted behind it, and its three colours. */
class Art(val id: String, val bg: Fill?, val colors: IntArray)

/**
 * A full keyboard skin, described by data (see docs/themes.md). Lengths are dp. A theme is immutable; the renderer
 * caches key backgrounds per theme, so switching themes just swaps the object.
 */
data class Theme(
    val id: String,
    val name: String,
    val dark: Boolean,
    /** Board background layers, bottom first. */
    val board: List<Fill>,
    val key: Fill, val mod: Fill, val accent: Fill,
    val fg: Int, val modFg: Int, val accentFg: Int,
    /** Corner radii: top-left, top-right, bottom-right, bottom-left. */
    val radius: FloatArray,
    val border: Border?,
    val shadow: List<Shadow>,
    /** Drawn instead of [shadow] on a key that is lit (on a swipe path, or pressed). */
    val lit: List<Shadow>,
    val font: String, val weight: Int, val size: Float, val upper: Boolean, val track: Float,
    /** Corner cut (dp) for angular keys: top-right and bottom-left are chamfered. */
    val cut: Float,
    val band: Fill?, val bandHeight: Float,
    val stripFg: Int, val sep: Int,
    val micGlow: Shadow?,
    val spaceLabel: String,
    /** Background blur (dp) behind translucent keys, where the system supports it. */
    val blur: Float,
    /** True for the theme that takes its colours from the wallpaper when that is switched on. */
    val dynamic: Boolean,
    /** True when the theme has both a light and a dark look, so the keyboard-colours setting applies to it. */
    val hasVariants: Boolean = false,
    /** A glow or shadow behind the key labels. */
    val textShadow: Shadow? = null,
    /** Each key is turned a little, differently (Comic). */
    val tilt: Boolean = false,
    /** The space bar's animated art, where the theme has one. */
    val art: Art? = null,
    /** A picture drawn over the whole keyboard ("petals": Sakura Storm's branch and falling blossom). */
    val fx: String? = null,
) {
    /** The reach of the largest outside shadow, so a cached key bitmap has room for it. */
    val shadowReach: Float get() = (shadow + lit + listOfNotNull(micGlow)).maxOfOrNull { it.reach } ?: 0f
}

class ThemeException(message: String) : Exception(message)

/** Reads the theme JSON format described in docs/themes.md. Every required token is checked and named in the error. */
object ThemeParser {
    val REQUIRED = listOf("id", "name", "board", "key", "mod", "accent", "fg", "modFg", "accentFg", "radius", "font", "size", "stripFg", "sep")

    /**
     * Reads a theme. A theme with a light and a dark look is ONE theme: its file holds the shared tokens and a `"variants"` object with
     * `"light"` and `"dark"` entries, each listing the tokens that change. [dark] picks which look to build (null: the file's own).
     */
    fun parse(json: String, dark: Boolean? = null): Theme {
        val root = try { JSONObject(json) } catch (e: Exception) { throw ThemeException("Not valid JSON: ${e.message}") }
        val o = merged(root, dark)
        for (k in REQUIRED) if (!o.has(k)) throw ThemeException("Missing required token \"$k\"")
        return try { build(o, root) } catch (e: ThemeException) { throw e } catch (e: Exception) { throw ThemeException("Bad theme: ${e.message}") }
    }

    /** Whether the theme in [json] has both a light and a dark look. */
    fun hasVariants(json: String): Boolean = runCatching { JSONObject(json).optJSONObject("variants")?.let { it.has("light") && it.has("dark") } == true }.getOrDefault(false)

    private fun merged(root: JSONObject, dark: Boolean?): JSONObject {
        val variants = root.optJSONObject("variants") ?: return root
        val mode = if (dark == null) return root else if (dark) "dark" else "light"
        val change = variants.optJSONObject(mode) ?: return root
        val out = JSONObject(root.toString()); out.remove("variants")
        for (k in change.keys()) out.put(k, change.get(k))
        out.put("dark", dark)
        return out
    }

    private fun build(o: JSONObject, root: JSONObject = o): Theme {
        val id = o.getString("id")
        if (!id.matches(Regex("[a-z0-9-]{1,32}"))) throw ThemeException("\"id\" must be 1-32 lowercase letters, digits or dashes")
        val radius = o.get("radius").let { r ->
            when (r) {
                is JSONArray -> { if (r.length() != 4) throw ThemeException("\"radius\" needs 1 or 4 numbers"); FloatArray(4) { r.getDouble(it).toFloat() } }
                else -> FloatArray(4) { (r as Number).toFloat() }
            }
        }
        val size = o.getDouble("size").toFloat()
        if (size < 8f || size > 40f) throw ThemeException("\"size\" must be between 8 and 40")
        return Theme(
            id = id, name = o.getString("name"), dark = o.optBoolean("dark", false),
            board = layers(o.get("board"), "board"), key = fill(o.get("key"), "key"), mod = fill(o.get("mod"), "mod"), accent = fill(o.get("accent"), "accent"),
            fg = color(o.getString("fg"), "fg"), modFg = color(o.getString("modFg"), "modFg"), accentFg = color(o.getString("accentFg"), "accentFg"),
            radius = radius,
            border = o.optJSONObject("border")?.let { Border(it.getDouble("width").toFloat(), color(it.getString("color"), "border.color")) },
            shadow = shadows(o.optJSONArray("shadow"), "shadow"), lit = shadows(o.optJSONArray("lit"), "lit"),
            font = o.getString("font"), weight = o.optInt("weight", 400), size = size, upper = o.optBoolean("upper", false), track = o.optDouble("track", 0.0).toFloat(),
            cut = o.optDouble("cut", 0.0).toFloat(),
            band = if (o.has("band")) fill(o.get("band"), "band") else null, bandHeight = o.optDouble("bandHeight", 0.0).toFloat(),
            stripFg = color(o.getString("stripFg"), "stripFg"), sep = color(o.getString("sep"), "sep"),
            micGlow = o.optJSONObject("micGlow")?.let { shadow(it, "micGlow") },
            spaceLabel = o.optString("space", "English"),
            blur = o.optDouble("blur", 0.0).toFloat(),
            dynamic = o.optBoolean("dynamic", false),
            hasVariants = root.optJSONObject("variants")?.let { it.has("light") && it.has("dark") } == true,
            textShadow = o.optJSONObject("textShadow")?.let { shadow(it, "textShadow") },
            tilt = o.optBoolean("tilt", false),
            fx = o.optString("fx").ifEmpty { null },
            art = o.optJSONObject("art")?.let { a -> Art(a.getString("id"), if (a.has("bg")) fill(a.get("bg"), "art.bg") else null, a.getJSONArray("colors").let { c -> IntArray(c.length()) { i -> color(c.getString(i), "art.colors") } }) },
        )
    }

    private fun layers(v: Any, what: String): List<Fill> = if (v is JSONArray) (0 until v.length()).map { fill(v.get(it), what) } else listOf(fill(v, what))

    private fun fill(v: Any, what: String): Fill {
        if (v is String) return Fill.Solid(color(v, what))
        if (v is JSONArray) return Fill.Layers((0 until v.length()).map { fill(v.get(it), what) })
        (v as? JSONObject)?.optJSONObject("rivets")?.let { r -> return Fill.Rivets(r.optDouble("inset", 4.0).toFloat(), r.optDouble("radius", 1.2).toFloat(), color(r.getString("color"), what)) }
        val o = v as? JSONObject ?: throw ThemeException("\"$what\" must be a colour or a fill object")
        o.optJSONObject("linear")?.let { l ->
            val (pos, cols) = stops(l.getJSONArray("stops"), what)
            return Fill.Linear(l.optDouble("angle", 180.0).toFloat(), l.optDouble("length", 0.0).toFloat(), pos, cols)
        }
        o.optJSONObject("radial")?.let { r ->
            val (pos, cols) = stops(r.getJSONArray("stops"), what)
            val rx = r.getDouble("rx").toFloat()
            return Fill.Radial(r.getDouble("x").toFloat(), r.getDouble("y").toFloat(), rx, r.optDouble("ry", rx.toDouble()).toFloat(), pos, cols, r.optBoolean("rel", false))
        }
        o.optJSONObject("dots")?.let { d -> return Fill.Dots(d.getDouble("step").toFloat(), d.getDouble("radius").toFloat(), color(d.getString("color"), what)) }
        o.optJSONObject("scales")?.let { d -> return Fill.Scales(d.getDouble("w").toFloat(), d.getDouble("h").toFloat(), d.getDouble("r").toFloat(), d.getDouble("width").toFloat(), color(d.getString("color"), what)) }
        throw ThemeException("\"$what\": a fill object needs \"linear\", \"radial\" or \"dots\"")
    }

    private fun stops(a: JSONArray, what: String): Pair<FloatArray, IntArray> {
        if (a.length() < 2) throw ThemeException("\"$what\": a gradient needs at least two stops")
        val pos = FloatArray(a.length()); val cols = IntArray(a.length())
        for (i in 0 until a.length()) { val s = a.getJSONArray(i); pos[i] = s.getDouble(0).toFloat(); cols[i] = color(s.getString(1), what) }
        return pos to cols
    }

    private fun shadows(a: JSONArray?, what: String): List<Shadow> = if (a == null) emptyList() else (0 until a.length()).map { shadow(a.getJSONObject(it), what) }

    private fun shadow(o: JSONObject, what: String) = Shadow(
        inset = o.optBoolean("inset", false), dx = o.optDouble("dx", 0.0).toFloat(), dy = o.optDouble("dy", 0.0).toFloat(),
        blur = o.optDouble("blur", 0.0).toFloat(), spread = o.optDouble("spread", 0.0).toFloat(), color = color(o.getString("color"), what),
    )

    /** `#rgb`, `#rrggbb`, `#rrggbbaa`, `rgba(r,g,b,a)` or `transparent`. */
    fun color(s: String, what: String = "colour"): Int {
        val t = s.trim().lowercase()
        if (t == "transparent") return 0
        if (t.startsWith("#")) {
            val h = t.substring(1)
            val v = h.toLongOrNull(16) ?: throw ThemeException("\"$what\": bad colour \"$s\"")
            return when (h.length) {
                3 -> { val r = (v shr 8 and 0xF).toInt() * 17; val g = (v shr 4 and 0xF).toInt() * 17; val b = (v and 0xF).toInt() * 17; (0xFF shl 24) or (r shl 16) or (g shl 8) or b }
                6 -> (0xFF000000 or v).toInt()
                8 -> ((v and 0xFF) shl 24 or (v shr 8)).toInt()
                else -> throw ThemeException("\"$what\": bad colour \"$s\"")
            }
        }
        val m = Regex("rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*(?:,\\s*([0-9.]+)\\s*)?\\)").matchEntire(t) ?: throw ThemeException("\"$what\": bad colour \"$s\"")
        val a = ((m.groupValues[4].ifEmpty { "1" }.toFloat()) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (a shl 24) or (m.groupValues[1].toInt().coerceIn(0, 255) shl 16) or (m.groupValues[2].toInt().coerceIn(0, 255) shl 8) or m.groupValues[3].toInt().coerceIn(0, 255)
    }
}

/** The flat colours the strip, panels and voice ribbon draw with, taken from a theme. */
fun Theme.toPalette(): app.wisp.keyboard.kbd.Palette {
    val bg = board.first().base() or (0xFF shl 24)
    val keyBase = key.base()
    val acc = accent.base() or (0xFF shl 24)
    return app.wisp.keyboard.kbd.Palette(
        background = bg, key = keyBase, keyPressed = Brand.mixColours(keyBase or (0xFF shl 24), acc, 0.28f), special = mod.base(),
        accent = acc, onAccent = accentFg, text = fg, textMuted = stripFg,
    )
}
