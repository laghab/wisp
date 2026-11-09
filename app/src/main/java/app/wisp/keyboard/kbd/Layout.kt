package app.wisp.keyboard.kbd

import org.json.JSONArray
import org.json.JSONObject

/** One key (or spacer) in a layout row, before geometry is applied. */
class KeySpec(
    val code: Int,
    val label: String,
    val labelUpper: String,
    val weight: Float,
    val kind: Int,
    val popups: String?,
    val spacer: Boolean = false,
) {
    fun withWeight(w: Float) = KeySpec(code, label, labelUpper, w, kind, popups, spacer)
}

class LayoutSpec(val name: String, val rows: List<List<KeySpec>>)

class LayoutException(message: String) : Exception(message)

/** Parses the layout JSON format described in docs/layouts.md. */
object LayoutParser {

    private val named = mapOf(
        "shift" to Triple(KeyCodes.SHIFT, "⇧", KeyKind.SPECIAL),
        "backspace" to Triple(KeyCodes.BACKSPACE, "⌫", KeyKind.SPECIAL),
        "enter" to Triple(KeyCodes.ENTER, "⏎", KeyKind.ACCENT),
        "symbols" to Triple(KeyCodes.SYMBOLS, "?123", KeyKind.SPECIAL),
        "language" to Triple(KeyCodes.LANGUAGE, "", KeyKind.SPECIAL),
        "emoji" to Triple(KeyCodes.EMOJI, "", KeyKind.SPECIAL),
        "abc" to Triple(KeyCodes.ABC, "ABC", KeyKind.SPECIAL),
        "more" to Triple(KeyCodes.SYMBOLS_MORE, "=\\<", KeyKind.SPECIAL),
        "switch" to Triple(KeyCodes.SWITCH, "", KeyKind.SPECIAL),
        "space" to Triple(KeyCodes.SPACE, "", KeyKind.SPACE),
        "comma" to Triple(','.code, ",", KeyKind.LETTER),
        "period" to Triple('.'.code, ".", KeyKind.LETTER),
    )

    fun parse(json: String): LayoutSpec {
        val root = try { JSONObject(json) } catch (e: Exception) { throw LayoutException("Invalid JSON: ${e.message}") }
        val name = root.optString("name", "layout")
        val rowsJson = root.optJSONArray("rows") ?: throw LayoutException("Missing \"rows\"")
        val rows = ArrayList<List<KeySpec>>(rowsJson.length())
        for (r in 0 until rowsJson.length()) {
            val row = rowsJson.optJSONArray(r) ?: throw LayoutException("Row ${r + 1} is not an array")
            rows.add(parseRow(row, r + 1))
        }
        if (rows.isEmpty()) throw LayoutException("Layout has no rows")
        return LayoutSpec(name, rows)
    }

    private fun parseRow(row: JSONArray, rowNo: Int): List<KeySpec> {
        val keys = ArrayList<KeySpec>(row.length())
        for (i in 0 until row.length()) {
            val item = row.get(i)
            keys.add(
                when (item) {
                    is String -> single(item, rowNo, i + 1)
                    is JSONObject -> fromObject(item, rowNo, i + 1)
                    else -> throw LayoutException("Row $rowNo key ${i + 1}: expected a string or object")
                },
            )
        }
        if (keys.none { !it.spacer }) throw LayoutException("Row $rowNo has no keys")
        return keys
    }

    private fun single(s: String, row: Int, col: Int): KeySpec {
        if (s.codePointCount(0, s.length) != 1) throw LayoutException("Row $row key $col: \"$s\" must be a single character")
        val cp = s.codePointAt(0)
        return KeySpec(cp, s, s.uppercase(), 1f, KeyKind.LETTER, null)
    }

    private fun fromObject(o: JSONObject, row: Int, col: Int): KeySpec {
        if (o.has("pad")) return KeySpec(0, "", "", o.getDouble("pad").toFloat(), KeyKind.LETTER, null, spacer = true)
        val w = o.optDouble("w", 1.0).toFloat()
        if (w <= 0f) throw LayoutException("Row $row key $col: width must be positive")
        val k = o.optString("k", "")
        val pop = o.optString("pop", "").ifEmpty { null }
        if (k.isNotEmpty()) {
            val (code, label, kind) = named[k] ?: throw LayoutException("Row $row key $col: unknown key \"$k\"")
            val l = o.optString("label", label)
            return KeySpec(code, l, l.uppercase(), w, kind, pop)
        }
        val c = o.optString("c", "")
        if (c.isEmpty()) throw LayoutException("Row $row key $col: needs \"k\", \"c\" or \"pad\"")
        val base = single(c, row, col)
        return KeySpec(base.code, o.optString("label", base.label), base.labelUpper, w, KeyKind.LETTER, pop)
    }
}
