package app.wisp.keyboard.personal

import android.content.Context
import app.wisp.keyboard.Language
import java.io.File

/** Where each language's personal dictionary lives on disk (the app's private files; excluded from backups). */
class PersonalStore(private val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, "personal"))

    fun file(lang: Language) = file(lang.tag)
    fun file(tag: String) = File(dir, "$tag.tsv")

    fun load(lang: Language): PersonalDictionary = PersonalDictionary().also { it.load(file(lang)) }

    fun save(lang: Language, dict: PersonalDictionary) {
        val f = file(lang); f.parentFile?.mkdirs(); dict.save(f)
    }
}

/** An immutable copy of what the gesture decoder needs from the personal dictionary. */
class PersonalSnapshot(
    /** Personal words (names and the like) with a ranking bonus, in the dictionary engine's score units. */
    val words: List<kotlin.Pair<String, Int>>,
    /** Lower-case words with a bonus, including ones the main dictionary has. */
    val boosts: Map<String, Int>,
    /** Pairs the user writes one after the other: the word before, the word, and how settled the habit is (0 to 1). */
    val pairs: List<Triple<String, String, Float>> = emptyList(),
) {
    companion object { val EMPTY = PersonalSnapshot(emptyList(), emptyMap()) }
}
