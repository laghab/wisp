package app.wisp.keyboard.emoji

/** One emoji with its Unicode name (what search matches) and category. */
class Emoji(val ch: String, val name: String, val group: Int)

/** The emoji, kaomoji and symbols the panel shows, and search over the emoji names. Parsing and search are plain Kotlin so they run in unit tests. */
class EmojiData(val emoji: List<Emoji>, val kaomoji: List<String>, val symbols: List<String>) {

    /** Emoji whose name contains every word of [query] (a word at the start of a name word ranks first). */
    fun search(query: String, limit: Int = 200): List<Emoji> {
        val words = query.lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<Int, Emoji>>()
        for (e in emoji) {
            val name = e.name
            var score = 0
            var ok = true
            for (raw in words) {
                val w = stem(raw)
                val at = name.indexOf(w)
                if (at < 0) { ok = false; break }
                // Starting a word counts for more than appearing inside one.
                score += if (at == 0) 0 else if (name[at - 1] == ' ') 1 else 3
            }
            if (ok) scored.add(score * 1000 + name.length to e)
        }
        scored.sortBy { it.first }
        return scored.take(limit).map { it.second }
    }

    companion object {
        /** "smile" should find "smiling", "cats" should find "cat": drop a common ending while at least three letters stay. */
        private fun stem(w: String): String {
            for (suffix in listOf("ing", "es", "ed", "e", "s")) if (w.length - suffix.length >= 3 && w.endsWith(suffix)) return w.dropLast(suffix.length)
            return w
        }

        val GROUPS = listOf("Smileys", "People", "Animals", "Food", "Travel", "Activities", "Objects", "Symbols", "Flags")

        /** `emoji TAB name TAB group` lines. [renders] drops emoji the phone's font cannot draw. */
        fun parse(emojiTsv: String, kaomojiTxt: String, symbolsTxt: String, renders: (String) -> Boolean = { true }): EmojiData {
            val emoji = emojiTsv.lineSequence().mapNotNull { l ->
                val f = l.split('\t'); if (f.size < 3) null else Emoji(f[0], f[1], f[2].toIntOrNull() ?: 0)
            }.filter { renders(it.ch) }.toList()
            val kao = kaomojiTxt.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            val sym = symbolsTxt.lineSequence().flatMap { it.split(' ').asSequence() }.filter { it.isNotEmpty() }.toList()
            return EmojiData(emoji, kao, sym)
        }
    }
}

/** The emoji used lately, newest first, kept as a short list in the settings file (never leaves the phone). */
class RecentEmoji(private val read: () -> String, private val write: (String) -> Unit, private val max: Int = 32) {
    fun list(): List<String> = read().split(' ').filter { it.isNotEmpty() }

    fun add(ch: String) { write((listOf(ch) + list().filter { it != ch }).take(max).joinToString(" ")) }

    fun clear() = write("")
}
