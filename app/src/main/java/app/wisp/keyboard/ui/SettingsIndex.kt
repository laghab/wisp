package app.wisp.keyboard.ui

import android.app.Activity

/** Where a settings entry leads. */
sealed interface Target {
    data class Screen(val id: String) : Target
    data class Open(val activity: Class<out Activity>) : Target
    data object ImportWords : Target
}

/** The settings screens and what they are called. */
object SettingsIndex {
    const val HOME = "home"; const val LOOK = "look"; const val SIZE = "size"; const val FEEDBACK = "feedback"; const val LAYOUTS = "layouts"
    const val VOICE = "voice"; const val BACKUP = "backup"; const val ABOUT = "about"; const val EXCLUDED = "excluded"; const val WELCOME = "welcome"; const val TYPING = "typing"

    val titles = mapOf(HOME to "Wisp", LOOK to "Look", SIZE to "Size and layout", FEEDBACK to "Feedback", LAYOUTS to "Layouts and toolbar", VOICE to "Voice typing", BACKUP to "Backup and restore", ABOUT to "About and privacy", EXCLUDED to "Apps Wisp never learns from", WELCOME to "Welcome", TYPING to "Typing")
}
