package app.wisp.keyboard.dict

import android.content.Context
import app.wisp.keyboard.Language
import com.android.inputmethod.latin.BinaryDictionary
import java.io.File

/**
 * Opens the dictionaries that make up a language: its main one (shipped, or built from a word list on first use), the words
 * the user imported, and the languages mixed in. The built dictionaries are cached in the app's files.
 */
class DictionaryProvider(private val context: Context) {

    /** One source of words, with the language it belongs to and whether it is the language's own (as opposed to mixed in). */
    class Source(val dict: NativeDictionary, val own: Boolean)

    fun importedFile(lang: Language) = File(context.filesDir, "wordlists/${lang.tag}.tsv")

    /** Opens everything for [lang]. The caller closes each dictionary. */
    fun open(lang: Language): List<Source> {
        val out = ArrayList<Source>()
        primary(lang)?.let { out.add(Source(it, true)) }
        imported(lang)?.let { out.add(Source(it, true)) }
        lang.mixIn?.let { m -> out.addAll(open(m).map { Source(it.dict, false) }) }
        return out
    }

    private fun primary(lang: Language): NativeDictionary? {
        lang.dictAsset?.let { asset ->
            val fd = context.assets.openFd(asset)
            return NativeDictionary.open(context.applicationInfo.sourceDir, fd.startOffset, fd.length, lang.tag)
        }
        val list = lang.wordListAsset ?: return null
        val bytes = context.assets.open(list).use { it.readBytes() }     // small, and compressed in the APK, so no file descriptor
        return generated(File(context.filesDir, "dicts/${lang.tag}-seed-${bytes.size}.dict"), lang.tag, lang.name) {
            WordList.read(bytes.inputStream())
        }
    }

    private fun imported(lang: Language): NativeDictionary? {
        val f = importedFile(lang)
        if (!f.isFile || f.length() == 0L) return null
        return generated(File(context.filesDir, "dicts/${lang.tag}-imported-${f.lastModified()}.dict"), lang.tag, "${lang.name} (imported)") {
            f.inputStream().use { WordList.read(it) }
        }
    }

    /** Opens the cached dictionary at [file], or builds it from [words] first. */
    private fun generated(file: File, tag: String, description: String, words: () -> List<Pair<String, Int>>): NativeDictionary? {
        if (!file.exists()) {
            if (!write(file, tag, description, words())) return null
            // Forget builds of older versions of the same list.
            file.parentFile?.listFiles()?.filter { it != file && it.name.startsWith(file.name.substringBefore("-")) && it.name.substringAfter("-").substringBefore("-") == file.name.substringAfter("-").substringBefore("-") }?.forEach { it.deleteRecursively() }
        }
        return NativeDictionary.open(file.path, 0, 0, tag, updatable = true)
    }

    companion object {
        private const val FORMAT_VERSION = 403L

        /** Writes a dictionary from a word list with the native engine's own writer. Returns false if it fails. */
        fun write(target: File, tag: String, description: String, words: List<Pair<String, Int>>): Boolean {
            target.parentFile?.mkdirs()
            target.deleteRecursively()
            val keys = arrayOf("dictionary", "locale", "description", "version")
            val values = arrayOf("main:${tag.lowercase()}", tag, description, "1")
            val handle = BinaryDictionary.createOnMemoryNative(FORMAT_VERSION, tag, keys, values)
            if (handle == 0L) return false
            try {
                for ((w, p) in words) BinaryDictionary.addUnigramEntryNative(handle, w.codePoints().toArray(), p, null, 0, false, false, false, -1)
                val ok = BinaryDictionary.flushWithGCNative(handle, target.path)
                if (!ok) target.deleteRecursively()
                return ok
            } finally { BinaryDictionary.closeNative(handle) }
        }
    }
}
