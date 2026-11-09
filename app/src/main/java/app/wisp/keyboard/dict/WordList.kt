package app.wisp.keyboard.dict

import java.io.InputStream

/** Reads `word<TAB>frequency` lines (frequency 0-255; a missing or bad one counts as the middle of the range). */
object WordList {
    fun read(input: InputStream): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        input.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val t = line.indexOf('\t')
                val word = (if (t < 0) line else line.substring(0, t)).trim()
                if (word.isEmpty() || word.length > MAX_WORD || word.any { it.isWhitespace() }) continue
                val p = if (t < 0) DEFAULT else line.substring(t + 1).trim().toIntOrNull() ?: DEFAULT
                out.add(word to p.coerceIn(0, 255))
            }
        }
        return out
    }

    const val DEFAULT = 100
    const val MAX_WORD = 40
}
