package app.wisp.keyboard.kbd

import app.wisp.keyboard.settings.WispSettings

/** The tools on the strip above the keys, as the user arranged them. The switch-keyboard key is on the keys, not here, so nothing here can remove it. The rewrite button exists only while rewrite is switched on. */
object Toolbar {
    private const val KEY = "toolbar"
    val DEFAULT = SuggestionBarView.Tool.values().filter { it != SuggestionBarView.Tool.EMOJI }

    fun load(settings: WispSettings): List<SuggestionBarView.Tool> {
        val saved = settings.getString(KEY)?.split(',')?.mapNotNull { n -> SuggestionBarView.Tool.values().firstOrNull { it.name == n } }?.distinct() ?: DEFAULT
        return if (rewriteOn(settings)) saved else saved - SuggestionBarView.Tool.REWRITE       // rewrite switched off: no button for it
    }

    fun rewriteOn(settings: WispSettings) = settings.getBoolean(app.wisp.keyboard.rewrite.RewriteSettings.ON, false)

    /** Puts [tool] on the strip if the user's arrangement lacks it, after the other everyday tools. */
    fun add(settings: WispSettings, tool: SuggestionBarView.Tool) {
        val now = load(settings)
        if (tool !in now) save(settings, now.filter { !it.more } + tool + now.filter { it.more })
    }

    fun save(settings: WispSettings, tools: List<SuggestionBarView.Tool>) = settings.putString(KEY, tools.joinToString(",") { it.name })
    fun reset(settings: WispSettings) = settings.putString(KEY, null)
}
