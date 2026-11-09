package app.wisp.keyboard.personal

import java.io.File
import java.io.OutputStream

/**
 * Everything Wisp has learned, as the learned-data screen sees it: look at the words, forget one, export them, or wipe the lot.
 * [dir] is the app's `personal` folder: one file of words and pairs per language, plus the swipe style and practice counts.
 */
class LearnedData(private val dir: File) {
    private val store = PersonalStore(dir)

    fun words(tag: String): List<PersonalDictionary.WordInfo> = PersonalDictionary().also { it.load(store.file(tag)) }.entries()

    /** Forgets one word (and the pairs it belongs to). Returns false if it was not there. */
    fun forget(tag: String, word: String): Boolean {
        val d = PersonalDictionary().also { it.load(store.file(tag)) }
        val before = d.wordCount
        d.remove(word)
        if (d.wordCount == before) return false
        d.save(store.file(tag)); touch(tag)
        return true
    }

    /** Writes the language's learned words and pairs as plain text (see [PersonalDictionary.save]) to [out]. */
    fun export(tag: String, out: OutputStream) {
        val f = store.file(tag)
        if (f.isFile) f.inputStream().use { it.copyTo(out) }
    }

    /** Forgets how the user swipes and which practice words gave trouble (not the learned words). */
    fun resetSwipeStyle() { File(dir, "gesture_style.txt").delete(); dir.listFiles()?.filter { it.name.startsWith("training_") }?.forEach { it.delete() } }

    /** Forgets everything learned: words, pairs, swipe style and practice counts. Imported word lists are separate. */
    fun wipeEverything() {
        dir.listFiles()?.forEach { it.delete() }
    }

    val isEmpty get() = dir.listFiles().isNullOrEmpty()

    /** Makes running keyboards notice the change. */
    private fun touch(tag: String) { store.file(tag).setLastModified(System.currentTimeMillis()) }
}
