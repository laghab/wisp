package app.wisp.keyboard

/**
 * A typing language: where its words come from, which key layout it uses, and how it behaves.
 *
 * A language's words come from a shipped word list ([wordListAsset], `word<TAB>frequency`, with an optional `.bigrams.tsv`
 * beside it) that is turned into a lexicon on first use. [mixIn] names a language whose words are understood too (people mix English into
 * Hinglish all the time), and the user can import more words ([importedFile] in the app's files).
 */
class Language(
    val tag: String, val code: String, val name: String, val layoutRes: Int,
    val wordListAsset: String? = null,
    val mixIn: Language? = null,
    /** Replace unknown words automatically. Off where spelling is not standardised (Hinglish). */
    val autoCorrect: Boolean = true,
)

object Languages {
    val ENGLISH_US = Language("en_US", "EN", "English (US)", R.raw.layout_qwerty, wordListAsset = "wordlists/en_US.tsv")

    /** Hindi written in Latin letters. English words are understood, nothing is auto-corrected, and the user's own words are learned. */
    val HINGLISH = Language("hi_Latn", "HI", "Hinglish", R.raw.layout_qwerty, wordListAsset = "wordlists/hinglish.tsv", mixIn = ENGLISH_US, autoCorrect = false)

    /** Everything Wisp ships; the language key cycles through these. More languages are added by a dictionary or word list and a layout file. */
    val all = listOf(ENGLISH_US, HINGLISH)

    fun byTag(tag: String?) = all.firstOrNull { it.tag == tag }
}
