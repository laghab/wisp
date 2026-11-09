package app.wisp.keyboard.kbd

import android.content.Context
import java.io.File

/** A layout that can be replaced by the user: where its file lives and what the built-in one is called. */
enum class LayoutSlot(val id: String, val title: String, val rawRes: Int) {
    LETTERS("letters", "Letters", app.wisp.keyboard.R.raw.layout_qwerty),
    SYMBOLS("symbols", "Symbols", app.wisp.keyboard.R.raw.layout_symbols),
    SYMBOLS_MORE("symbols_more", "More symbols", app.wisp.keyboard.R.raw.layout_symbols_more);

    // By resource id, not name: looking a name up makes Android index every resource name, which costs well over 100 ms at start.
    companion object { fun forRaw(id: Int) = values().firstOrNull { it.rawRes == id } }
}

/** The user's own layout files (`files/layouts/<slot>.json`). Every file is checked before it is stored, so a bad one can never be active. */
object LayoutStore {
    fun file(context: Context, slot: LayoutSlot) = File(context.filesDir, "layouts/${slot.id}.json")
    fun isCustom(context: Context, slot: LayoutSlot) = file(context, slot).isFile
    fun customText(context: Context, slot: LayoutSlot): String? = file(context, slot).takeIf { it.isFile }?.readText()

    fun defaultText(context: Context, slot: LayoutSlot): String {
        return context.resources.openRawResource(slot.rawRes).bufferedReader().use { it.readText() }
    }

    /** The text of the layout in use: the user's if there is one, otherwise the built-in. */
    fun currentText(context: Context, slot: LayoutSlot) = customText(context, slot) ?: defaultText(context, slot)

    /** Checks [text] and, only if it is a good layout, makes it the layout in use. Returns the problem, or null when it was saved. */
    fun save(context: Context, slot: LayoutSlot, text: String): String? {
        LayoutProblems.check(text)?.let { return it }
        val f = file(context, slot); f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp"); tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        return null
    }

    fun reset(context: Context, slot: LayoutSlot) { file(context, slot).delete() }
}

/** Turns a layout error into "Line 7: Row 3 key 2: unknown key" so the user can find it. */
object LayoutProblems {
    fun check(text: String): String? = try { LayoutParser.parse(text); null } catch (e: LayoutException) { explain(text, e.message ?: "Invalid layout") }

    fun explain(text: String, reason: String): String {
        val line = lineOf(text, reason)
        return if (line != null) "Line $line: $reason" else reason
    }

    private fun lineOf(text: String, reason: String): Int? {
        Regex("""\[character \d+ line (\d+)\]""").find(reason)?.let { return it.groupValues[1].toInt() }
        Regex("""at character (\d+)""").find(reason)?.let { m -> return 1 + text.take(m.groupValues[1].toInt().coerceAtMost(text.length)).count { it == '\n' } }
        Regex("""Row (\d+)""").find(reason)?.let { m -> return rowLine(text, m.groupValues[1].toInt()) }
        return null
    }

    /** The line where the [rowNo]th row (1-based) of "rows" starts. */
    fun rowLine(text: String, rowNo: Int): Int? {
        val start = text.indexOf("\"rows\"").takeIf { it >= 0 } ?: return null
        var depth = 0; var row = 0; var line = 1 + text.take(start).count { it == '\n' }; var inString = false
        for (i in start until text.length) {
            val c = text[i]
            if (c == '\n') line++
            if (c == '"' && (i == 0 || text[i - 1] != '\\')) inString = !inString
            if (inString) continue
            if (c == '[') { depth++; if (depth == 2 && ++row == rowNo) return line }
            if (c == ']') depth--
        }
        return null
    }
}
