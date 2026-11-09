package app.wisp.keyboard.dict

import android.content.Context
import app.wisp.keyboard.Language
import java.io.File
import java.io.InputStream

/**
 * Adds the user's own word list (`word<TAB>frequency`, one per line) to a language. The words are merged with any list imported
 * before (the higher frequency wins), stored as plain text in the app's files, and picked up by suggestions and swipe typing.
 */
object WordListImporter {

    class Result(val read: Int, val total: Int)

    fun import(context: Context, lang: Language, input: InputStream): Result {
        val file = DictionaryProvider(context).importedFile(lang)
        val merged = HashMap<String, Int>()
        if (file.isFile) file.inputStream().use { WordList.read(it) }.forEach { (w, p) -> merged[w] = p }
        val incoming = WordList.read(input)
        if (incoming.isEmpty()) throw IllegalArgumentException("No words found. Each line should be a word, a tab, and a number from 0 to 255.")
        for ((w, p) in incoming) { val cur = merged[w]; if (cur == null || p > cur) merged[w] = p }
        if (merged.size > MAX_WORDS) throw IllegalArgumentException("Too many words (the limit is $MAX_WORDS).")
        write(file, merged)
        return Result(incoming.size, merged.size)
    }

    /** Forgets all imported words of [lang]. */
    fun clear(context: Context, lang: Language) { DictionaryProvider(context).importedFile(lang).delete() }

    private fun write(file: File, words: Map<String, Int>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.bufferedWriter().use { w -> for ((word, p) in words) { w.write(word); w.write("\t"); w.write(p.toString()); w.write("\n") } }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        file.setLastModified(System.currentTimeMillis())
    }

    const val MAX_WORDS = 300_000
}
