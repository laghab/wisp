package app.wisp.keyboard.style

import android.content.Context
import java.io.File

/** The built-in themes (assets/themes) and the ones the user imported (files/themes). */
object Themes {
    const val DEFAULT = "wisp"

    // One list per look; themes with a light and a dark look appear in both, as the same theme.
    private val cache = HashMap<Boolean, List<Theme>>()

    /** Every theme, drawn in the light ([dark] false) or dark look where it has both. */
    fun all(context: Context, dark: Boolean = false): List<Theme> = cache[dark] ?: load(context, dark).also { cache[dark] = it }

    fun get(context: Context, id: String, dark: Boolean = false): Theme = all(context, dark).firstOrNull { it.id == id } ?: all(context, dark).first { it.id == DEFAULT }

    private fun load(context: Context, dark: Boolean): List<Theme> {
        val builtIn = (context.assets.list("themes") ?: emptyArray()).sorted().mapNotNull { f ->
            runCatching { ThemeParser.parse(context.assets.open("themes/$f").bufferedReader().use { it.readText() }, dark) }.getOrNull()
        }
        val order = listOf("wisp", "moon", "dandelion", "sakura", "factory", "cyberpunk", "terminal", "y2k", "material", "glass", "modern", "retro", "brutalist", "comic", "manga",
            "angel", "swan", "dragon", "fire-dragon", "cosmo-deep", "dust-glitter", "forest", "ocean", "mountains", "cat", "dog", "sun", "lanterns", "sunflowers", "lotus")
        val sorted = builtIn.sortedBy { order.indexOf(it.id).let { i -> if (i < 0) order.size else i } }
        val mine = (File(context.filesDir, "themes").listFiles() ?: emptyArray()).sortedBy { it.name }.mapNotNull { f ->
            runCatching { ThemeParser.parse(f.readText(), dark) }.getOrNull()?.takeIf { t -> sorted.none { it.id == t.id } }
        }
        return sorted + mine
    }

    /** Adds the theme in [json] to the picker. Throws [ThemeException] naming what is wrong. */
    fun import(context: Context, json: String): Theme {
        val t = ThemeParser.parse(json)
        if (all(context).any { it.id == t.id && it.isBuiltIn(context) }) throw ThemeException("\"${t.id}\" is a built-in theme id; choose another")
        val dir = File(context.filesDir, "themes").also { it.mkdirs() }
        File(dir, "${t.id}.json").writeText(json)
        cache.clear()
        return t
    }

    fun remove(context: Context, id: String) { File(File(context.filesDir, "themes"), "$id.json").delete(); cache.clear() }

    private fun Theme.isBuiltIn(context: Context) = context.assets.list("themes")?.contains("$id.json") == true
}
