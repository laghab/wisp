package app.wisp.keyboard.ui

import android.app.Activity
import app.wisp.keyboard.personal.LearnedDataActivity
import app.wisp.keyboard.training.GestureTrainingActivity

/** Where a settings entry leads. */
sealed interface Target {
    data class Screen(val id: String) : Target
    data class Open(val activity: Class<out Activity>) : Target
    data object ImportWords : Target
}

class Entry(val title: String, val summary: String, val keywords: String, val target: Target)

/** Every setting a person might look for, with the words they might use. Search matches any of them. */
object SettingsIndex {
    const val HOME = "home"; const val LOOK = "look"; const val SIZE = "size"; const val FEEDBACK = "feedback"; const val LAYOUTS = "layouts"
    const val VOICE = "voice"; const val BACKUP = "backup"; const val ABOUT = "about"; const val EXCLUDED = "excluded"; const val WELCOME = "welcome"

    val titles = mapOf(HOME to "Wisp", LOOK to "Look", SIZE to "Size and layout", FEEDBACK to "Feedback", LAYOUTS to "Layouts and toolbar", VOICE to "Voice typing", BACKUP to "Backup and restore", ABOUT to "About and privacy", EXCLUDED to "Apps Wisp never learns from", WELCOME to "Welcome")

    val all: List<Entry> = listOf(
        Entry("Theme", "Light, dark, or follow the phone", "colour color dark light night mode appearance system", Target.Screen(LOOK)),
        Entry("Accent colour", "The colour of the enter key and highlights", "color wallpaper dynamic material you", Target.Screen(LOOK)),
        Entry("Key shape", "Square, rounded or pill keys", "corner radius round square", Target.Screen(LOOK)),
        Entry("Swipe trail", "Colour, thickness, or turn it off", "glide gesture line draw", Target.Screen(LOOK)),
        Entry("Keyboard height", "Taller or shorter, in portrait and landscape", "size big small orientation landscape portrait", Target.Screen(SIZE)),
        Entry("Space between keys", "Tighter or looser keys", "gap spacing padding", Target.Screen(SIZE)),
        Entry("Gap below the keyboard", "Empty space under the keys for your thumb or the gesture bar", "bottom margin padding space navigation", Target.Screen(SIZE)),
        Entry("Number row", "Digits above the letters", "numbers digits", Target.Screen(SIZE)),
        Entry("One-handed mode", "Shrink the keyboard to one side", "left right thumb", Target.Screen(SIZE)),
        Entry("Split keyboard", "A gap down the middle for two thumbs", "tablet two thumbs", Target.Screen(SIZE)),
        Entry("Vibration", "Haptic feedback when you press a key", "haptic buzz vibrate feel touch", Target.Screen(FEEDBACK)),
        Entry("Key sound", "A click when you press a key", "audio click noise volume", Target.Screen(FEEDBACK)),
        Entry("Edit a layout", "Change keys, long-press characters, import or export", "keys qwerty symbols long press popup accents file json", Target.Screen(LAYOUTS)),
        Entry("Toolbar", "Choose and reorder select all, cut, copy, paste", "strip tools reorder remove", Target.Screen(LAYOUTS)),
        Entry("Speech models", "Download, choose and test voice typing models", "voice dictation microphone ai whisper parakeet moonshine speed accuracy", Target.Screen(VOICE)),
        Entry("Free the model when idle", "How long the speech model stays in memory", "unload timeout memory ram battery minutes", Target.Screen(VOICE)),
        Entry("Internet for downloads", "Off by default; used only to download models and updates", "network online wifi data offline kill switch update", Target.Screen(VOICE)),
        Entry("Back up now", "Save your learned data to a folder you choose", "backup export save folder", Target.Screen(BACKUP)),
        Entry("Automatic backups", "Daily or weekly, to your folder", "schedule daily weekly retention", Target.Screen(BACKUP)),
        Entry("Restore a backup", "Bring back words and settings, for example on a new phone", "import recover new phone", Target.Screen(BACKUP)),
        Entry("Practice swiping", "A few minutes a day teaches Wisp how you glide", "train gesture learn swipe", Target.Open(GestureTrainingActivity::class.java)),
        Entry("What Wisp learned", "See, remove, export or erase your words", "learned data dictionary personal delete wipe privacy words", Target.Open(LearnedDataActivity::class.java)),
        Entry("Apps to never learn from", "Banking, notes or any app where nothing should be remembered", "exclude excluded ignore private incognito privacy block", Target.Screen(EXCLUDED)),
        Entry("Import a word list", "Add words to a language's dictionary", "dictionary hinglish english vocabulary", Target.ImportWords),
        Entry("Privacy", "What Wisp stores and what it never does", "data telemetry analytics permissions trust", Target.Screen(ABOUT)),
    )

    fun search(query: String): List<Entry> {
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return all.filter { e -> val hay = (e.title + " " + e.summary + " " + e.keywords).lowercase(); words.all { it in hay } }
    }
}
