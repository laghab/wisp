package app.wisp.keyboard.kbd

import android.content.Context

object LayoutLoader {
    private val cache = HashMap<String, LayoutSpec>()

    /**
     * Loads a layout: the user's own file if they replaced this one, otherwise the built-in from res/raw.
     * [withLanguageKey] adds the language-cycling key (used when 2+ languages are enabled). The switch-keyboard key is always added if missing.
     */
    fun load(context: Context, rawRes: Int, withLanguageKey: Boolean = false): LayoutSpec {
        val slot = LayoutSlot.forRaw(rawRes)
        val custom = slot?.let { LayoutStore.file(context, it) }?.takeIf { it.isFile }
        val key = "$rawRes|$withLanguageKey|${custom?.lastModified() ?: 0}|${custom?.length() ?: 0}"
        return cache.getOrPut(key) {
            val text = (if (custom != null) runCatching { custom.readText() }.getOrNull() else null)
                ?: context.resources.openRawResource(rawRes).bufferedReader().use { it.readText() }
            val spec = try { LayoutGuard.ensureSwitchKey(LayoutParser.parse(text)) }
            catch (e: LayoutException) { LayoutGuard.ensureSwitchKey(LayoutParser.parse(context.resources.openRawResource(rawRes).bufferedReader().use { it.readText() })) }
            if (withLanguageKey) LayoutGuard.ensureLanguageKey(spec) else spec
        }
    }
}
