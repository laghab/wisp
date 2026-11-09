package app.wisp.keyboard.kbd

/** The word being typed, with where each letter was touched (for the dictionary's spatial model). No allocation per key. */
class WordComposer {
    private val chars = StringBuilder()
    val xs = IntArray(MAX)
    val ys = IntArray(MAX)
    var size = 0
        private set

    val text: String get() = chars.toString()
    val isEmpty get() = chars.isEmpty()
    val isNotEmpty get() = chars.isNotEmpty()

    fun add(codePoint: Int, x: Int, y: Int) {
        if (size < MAX) { xs[size] = x; ys[size] = y; size++ }
        chars.appendCodePoint(codePoint)
    }

    fun backspace() {
        if (chars.isEmpty()) return
        val cp = chars.codePointBefore(chars.length)
        chars.setLength(chars.length - Character.charCount(cp))
        if (size > 0) size = minOf(size, chars.codePointCount(0, chars.length))
    }

    /** Replaces the word, e.g. when an auto-correction is taken back. Positions are unknown, so they are marked as such. */
    fun set(word: String) {
        chars.setLength(0); chars.append(word)
        size = minOf(MAX, word.codePointCount(0, word.length))
        for (i in 0 until size) { xs[i] = -1; ys[i] = -1 }
    }

    fun clear() { chars.setLength(0); size = 0 }

    companion object { const val MAX = 48 }
}
