package app.wisp.keyboard.gesture

import android.content.Context
import app.wisp.keyboard.Language
import app.wisp.keyboard.dict.DictionaryProvider
import java.io.File

/**
 * Finds or builds the gesture index of a language. The index is derived from the language's dictionary once, stored
 * under the app's private files, and from then on only memory-mapped. Building takes a second or two and must not run
 * on the main thread.
 */
class GestureIndexStore(private val context: Context) {

    private fun file(lang: Language): File {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).let { @Suppress("DEPRECATION") it.versionCode }
        // Changing the imported word list changes the index, so its stamp is part of the name.
        val stamp = DictionaryProvider(context).importedFile(lang).lastModified()
        return File(context.filesDir, "gesture/${lang.tag}-v$version-i${INDEX_FORMAT}-u$stamp.idx")
    }

    /** Changes whenever the language's imported word list does. */
    fun stampFor(lang: Language) = DictionaryProvider(context).importedFile(lang).lastModified()

    fun isBuilt(lang: Language) = file(lang).isFile

    /** Returns the index for [lang], building it from the dictionary if needed; null when the dictionary cannot be read. */
    fun get(lang: Language, alphabet: IntArray): GestureIndex? {
        val f = file(lang)
        if (!f.isFile) {
            val sources = DictionaryProvider(context).open(lang)
            if (sources.isEmpty()) return null
            try {
                val words = ArrayList<Pair<String, Int>>(200_000)
                // A language's own words are preferred over mixed-in ones that happen to be spelt the same.
                for (src in sources) src.dict.forEachWord { w, p -> words.add(w to if (src.own) p else p - MIXED_IN_PENALTY) }
                GestureIndex.build(words.asSequence(), alphabet, MIN_PROBABILITY, f)
            } finally { sources.forEach { it.dict.close() } }
            // Remove indexes of older app versions or older word lists.
            f.parentFile?.listFiles()?.filter { it.name.startsWith("${lang.tag}-") && it != f }?.forEach { it.delete() }
        }
        return GestureIndex.open(f)
    }

    companion object {
        const val INDEX_FORMAT = 3
        const val MIN_PROBABILITY = 0
        const val MIXED_IN_PENALTY = 20
    }
}
