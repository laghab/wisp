package app.wisp.keyboard.dict

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import app.wisp.keyboard.kbd.Candidate
import app.wisp.keyboard.kbd.CompiledLayout
import app.wisp.keyboard.kbd.InputController
import app.wisp.keyboard.kbd.SuggestionResult
import app.wisp.keyboard.personal.PersonalDictionary
import app.wisp.keyboard.personal.PersonalSnapshot
import app.wisp.keyboard.personal.PersonalStore

/**
 * Runs dictionary queries on one background thread so typing never waits for them. Only the newest request is
 * answered; older ones are dropped. Results are delivered on the main thread through [onResult].
 */
class SuggestionEngine(
    private val context: Context,
    private val onResult: (SuggestionResult) -> Unit,
    /** Called on the main thread when what was learned has changed enough for the gesture decoder to refresh. */
    private val onPersonalChanged: (PersonalSnapshot) -> Unit = {},
) : InputController.Suggester, InputController.Learner {

    private val thread = HandlerThread("wisp-suggest", Process.THREAD_PRIORITY_DEFAULT).apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var generation = 0

    // Touched only on the worker thread.
    private var sources: List<DictionaryProvider.Source> = emptyList()
    private var dictFailed = false
    private val provider = DictionaryProvider(context)
    private var proximity: Proximity? = null
    private var language = app.wisp.keyboard.Languages.ENGLISH_US
    private val store = PersonalStore(context)
    private var personal = PersonalDictionary()
    private var personalLanguage: app.wisp.keyboard.Language? = null
    private var lastSavedVersion = 0
    private var lastPublishedVersion = -1
    private val saveSoon = Runnable { flushNow() }

    /** Call whenever the key geometry changes; the proximity model is rebuilt on the worker. */
    fun setLayout(layout: CompiledLayout) {
        worker.post { proximity?.close(); proximity = Proximity.build(layout) }
    }

    /** Switches the dictionary used for suggestions. Pending answers for the old language are dropped. */
    fun setLanguage(lang: app.wisp.keyboard.Language) {
        generation++
        worker.post {
            if (lang !== language) { closeSources(); dictFailed = false; language = lang }
            loadPersonal()
        }
    }

    override fun request(word: String, xs: IntArray, ys: IntArray, count: Int, previousWord: String?) {
        val gen = ++generation
        worker.post {
            if (gen != generation) return@post     // a newer keystroke has already replaced this request
            val result = query(word, previousWord) ?: return@post
            if (gen == generation) main.post { if (gen == generation) onResult(result) }
        }
    }

    override fun cancel() { generation++ }

    private fun query(word: String, prev: String?): SuggestionResult? {
        val srcs = sources.ifEmpty { open() } ?: return null
        val lower = word.lowercase()
        val pd = personalFor()
        // Ask every lexicon of the language (its own words first, then mixed-in ones) and keep each word's best score.
        val best = LinkedHashMap<String, Candidate>()
        var known = false
        for (src in srcs) {
            for (c in src.dict.suggest(lower, proximity, prev)) {
                val score = c.score + pd.bonus(c.word) + pd.pairBonus(prev, c.word) + if (src.own) OWN_WORDS_BONUS else 0
                val key = c.word.lowercase()
                val cur = best[key]
                if (cur == null || score > cur.score) best[key] = Candidate(c.word, score, c.type)
            }
            if (src.dict.probability(lower) >= 0) known = true
        }
        val list = ArrayList<Candidate>(best.values)
        // Words the user has made their own (names the dictionary lacks) complete what is being typed.
        for (w in pd.wordsWithPrefix(lower, 3)) {
            if (list.none { it.word.equals(w, true) }) list.add(Candidate(w, PERSONAL_BASE_SCORE + pd.bonus(w) + pd.pairBonus(prev, w), Candidate.KIND_COMPLETION))
        }
        // After a space: what this person usually writes next comes before what people in general do.
        if (lower.isEmpty() && prev != null) for (w in pd.after(prev, 3)) {
            if (list.none { it.word.equals(w, true) }) list.add(Candidate(w, NEXT_WORD_SCORE + pd.pairBonus(prev, w), Candidate.KIND_COMPLETION))
        }
        list.sortByDescending { it.score }
        return SuggestionResult(word, list.take(Lexicon.MAX), known || pd.isKnown(lower))
    }

    private fun open(): List<DictionaryProvider.Source>? {
        if (dictFailed) return null
        return try {
            provider.open(language).also { sources = it; if (it.isEmpty()) dictFailed = true }.ifEmpty { null }
        } catch (e: Exception) { dictFailed = true; null }
    }

    private var importedStamp = 0L
    private var personalStamp = 0L

    /** Call when the keyboard opens: if the imported word list changed, reopen the dictionaries on the next query. */
    fun refreshSources() {
        worker.post {
            val stamp = provider.importedFile(language).lastModified()
            if (stamp != importedStamp) { importedStamp = stamp; closeSources(); dictFailed = false }
            // The learned-data screen may have forgotten words behind our back.
            val lang = personalLanguage
            if (lang != null && store.file(lang).lastModified() != personalStamp && personal.version == lastSavedVersion) { personalLanguage = null; loadPersonal() }
        }
    }

    private fun closeSources() { sources.forEach { it.dict.close() }; sources = emptyList() }

    // ---- learning

    override fun learn(word: String, previousWord: String?, strong: Boolean) {
        worker.post {
            val srcs = sources.ifEmpty { open() } ?: emptyList()
            val inMain = srcs.any { it.dict.probability(word.lowercase()) >= 0 }
            personalFor().learn(word, previousWord, inMain, strong)
            worker.removeCallbacks(saveSoon); worker.postDelayed(saveSoon, SAVE_DELAY_MS)
        }
    }

    /** Writes what was learned to disk and tells the gesture decoder. Call when the keyboard is hidden. */
    fun flush() { worker.post { worker.removeCallbacks(saveSoon); flushNow() } }

    private fun flushNow() {
        val lang = personalLanguage ?: return
        if (personal.version != lastSavedVersion) { store.save(lang, personal); lastSavedVersion = personal.version; personalStamp = store.file(lang).lastModified() }
        if (personal.version != lastPublishedVersion) {
            lastPublishedVersion = personal.version
            val snap = PersonalSnapshot(personal.promotedWords(), personal.boosts(), personal.pairStrengths())
            main.post { onPersonalChanged(snap) }
        }
    }

    private fun personalFor(): PersonalDictionary { if (personalLanguage !== language) loadPersonal(); return personal }

    private fun loadPersonal() {
        if (personalLanguage === language && personal.version == lastSavedVersion) return
        if (personalLanguage != null && personal.version != lastSavedVersion) store.save(personalLanguage!!, personal)
        personal = store.load(language); personalLanguage = language; personalStamp = store.file(language).lastModified()
        lastSavedVersion = personal.version; lastPublishedVersion = -1
        flushNow()
    }

    fun close() {
        generation++
        worker.post { flushNow() }
        worker.post { proximity?.close(); proximity = null; closeSources(); thread.quitSafely() }
    }

    private companion object {
        const val NEXT_WORD_SCORE = 420_000       // a pair the person has written themselves, above the word list's own next words (400,000 down)
        const val PERSONAL_BASE_SCORE = 330_000   // where a learned word that completes the typed prefix ranks before its use bonus
        const val SAVE_DELAY_MS = 8_000L
        const val OWN_WORDS_BONUS = 25_000       // a language's own words edge out the English ones mixed in
    }
}
