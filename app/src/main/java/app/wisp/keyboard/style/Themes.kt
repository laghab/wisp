package app.wisp.keyboard.style

import android.content.Context
import java.io.File

/** The built-in themes (assets/themes) and the ones the user imported (files/themes). */
object Themes {
    const val DEFAULT = "midnight"

    private var cache: List<Theme>? = null

    fun all(context: Context): List<Theme> = cache ?: load(context).also { cache = it }

    fun get(context: Context, id: String): Theme = all(context).firstOrNull { it.id == id } ?: all(context).first { it.id == DEFAULT }

    private fun load(context: Context): List<Theme> {
        val builtIn = (context.assets.list("themes") ?: emptyArray()).sorted().mapNotNull { f ->
            runCatching { ThemeParser.parse(context.assets.open("themes/$f").bufferedReader().use { it.readText() }) }.getOrNull()
        }
        val order = listOf("midnight", "factory", "cyberpunk", "material", "glass", "modern", "y2k", "retro", "brutalist", "comic", "manga")
        val sorted = builtIn.sortedBy { order.indexOf(it.id).let { i -> if (i < 0) order.size else i } }
        val mine = (File(context.filesDir, "themes").listFiles() ?: emptyArray()).sortedBy { it.name }.mapNotNull { f ->
            runCatching { ThemeParser.parse(f.readText()) }.getOrNull()?.takeIf { t -> sorted.none { it.id == t.id } }
        }
        return sorted + mine
    }

    /** Adds the theme in [json] to the picker. Throws [ThemeException] naming what is wrong. */
    fun import(context: Context, json: String): Theme {
        val t = ThemeParser.parse(json)
        if (all(context).any { it.id == t.id && it.isBuiltIn(context) }) throw ThemeException("\"${t.id}\" is a built-in theme id; choose another")
        val dir = File(context.filesDir, "themes").also { it.mkdirs() }
        File(dir, "${t.id}.json").writeText(json)
        cache = null
        return t
    }

    fun remove(context: Context, id: String) { File(File(context.filesDir, "themes"), "$id.json").delete(); cache = null }

    private fun Theme.isBuiltIn(context: Context) = context.assets.list("themes")?.contains("$id.json") == true
}
