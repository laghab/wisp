package app.wisp.keyboard.dict

import android.content.Context
import app.wisp.keyboard.Language
import app.wisp.keyboard.core.Native
import java.io.File

/**
 * Opens the lexicons that make up a language: its main word list (shipped as an asset), the words the user imported,
 * and the languages mixed in. Each is built once into a cache file in the app's files and opened from there afterwards.
 */
class DictionaryProvider(private val context: Context) {

    /** One source of words, and whether it is the language's own (as opposed to mixed in). */
    class Source(val dict: Lexicon, val own: Boolean)

    fun importedFile(lang: Language) = File(context.filesDir, "wordlists/${lang.tag}.tsv")

    /** Opens everything for [lang]. The caller closes each lexicon. */
    fun open(lang: Language): List<Source> {
        val out = ArrayList<Source>()
        primary(lang)?.let { out.add(Source(it, true)) }
        imported(lang)?.let { out.add(Source(it, true)) }
        lang.mixIn?.let { m -> out.addAll(open(m).map { Source(it.dict, false) }) }
        return out
    }

    private fun primary(lang: Language): Lexicon? {
        val asset = lang.wordListAsset ?: return null
        val words = context.assets.open(asset).use { it.readBytes() }
        val pairs = try { context.assets.open(asset.removeSuffix(".tsv") + ".bigrams.tsv").use { it.readBytes() } } catch (e: java.io.FileNotFoundException) { ByteArray(0) }
        // Named by the list's checksum, so a list that changed (even to one of the same size) is built again.
        val sum = java.util.zip.CRC32().apply { update(words); update(pairs) }.value
        return cached(File(context.filesDir, "lex/${lang.tag}-main-$sum.lex"), words, pairs)
    }

    private fun imported(lang: Language): Lexicon? {
        val f = importedFile(lang)
        if (!f.isFile || f.length() == 0L) return null
        return cached(File(context.filesDir, "lex/${lang.tag}-imported-${f.lastModified()}.lex"), f.readBytes(), ByteArray(0))
    }

    /** Opens the cached lexicon, building it from [words] first if needed; builds of older versions of the same list are forgotten. */
    private fun cached(file: File, words: ByteArray, pairs: ByteArray): Lexicon? {
        val existed = file.isFile
        val h = Native.lexOpen(file.path, words, pairs)
        if (h == 0L) return null
        if (!existed) file.parentFile?.listFiles()?.filter { it != file && it.name.substringBeforeLast('-') == file.name.substringBeforeLast('-') }?.forEach { it.delete() }
        return Lexicon(h)
    }
}
