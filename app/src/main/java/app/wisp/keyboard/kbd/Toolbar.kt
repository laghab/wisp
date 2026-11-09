package app.wisp.keyboard.kbd

import app.wisp.keyboard.settings.WispSettings

/** The editing tools on the strip above the keys, as the user arranged them. The switch-keyboard key is on the keys, not here, so nothing here can remove it. */
object Toolbar {
    private const val KEY = "toolbar"
    val DEFAULT = SuggestionBarView.Tool.values().toList()

    fun load(settings: WispSettings): List<SuggestionBarView.Tool> {
        val saved = settings.getString(KEY) ?: return DEFAULT
        return saved.split(',').mapNotNull { n -> SuggestionBarView.Tool.values().firstOrNull { it.name == n } }.distinct()
    }

    fun save(settings: WispSettings, tools: List<SuggestionBarView.Tool>) = settings.putString(KEY, tools.joinToString(",") { it.name })
    fun reset(settings: WispSettings) = settings.putString(KEY, null)
}
