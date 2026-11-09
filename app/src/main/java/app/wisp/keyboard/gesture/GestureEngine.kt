package app.wisp.keyboard.gesture

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import app.wisp.keyboard.Language
import app.wisp.keyboard.Languages
import app.wisp.keyboard.kbd.CompiledLayout
import app.wisp.keyboard.kbd.GestureOutcome
import app.wisp.keyboard.kbd.LayoutLoader
import app.wisp.keyboard.personal.PersonalSnapshot

/**
 * Decodes swipes on a background thread. The first use of a language builds its word index from the dictionary
 * (a second or two, once); until it is ready, swipes are ignored. Results come back on the main thread.
 */
class GestureEngine(
    private val context: Context,
    private val onOutcome: (GestureOutcome) -> Unit,
) {
    private val thread = HandlerThread("wisp-gesture", Process.THREAD_PRIORITY_DEFAULT).apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val store = GestureIndexStore(context)

    @Volatile private var generation = 0

    // Worker-thread state.
    private var language: Language = Languages.ENGLISH_US
    private var index: GestureIndex? = null
    private var layout: CompiledLayout? = null
    private var geo: GestureGeometry? = null
    private var decoder: GestureDecoder? = null
    private val results = GestureDecoder.Results(10)
    private var personal = PersonalSnapshot.EMPTY
    private val style = GestureStyle().also { it.load(styleFile()) }
    private var styleStamp = styleFile().lastModified()
    private var styleDirty = 0
    private var lastX = FloatArray(0); private var lastY = FloatArray(0); private var lastN = 0
    private var failedX = FloatArray(0); private var failedY = FloatArray(0); private var failedN = 0
    private var extras: Array<GestureDecoder.ExtraWord> = emptyArray()
    private var boostPoints: Map<Int, Int> = emptyMap()

    /**
     * A swipe is inserted on its own only when the best word beats the runner-up by at least this much; otherwise the
     * words are offered in the strip. The margin separates right from wrong far better than the cost itself (see
     * docs/gesture-eval.md): wrong best words win by a median of 0.02, right ones by about 0.10.
     */
    @Volatile var confidentMargin = 0.015f

    /**
     * ...and its path fit (the cost without the word-frequency bonus) must be under this. Margin alone cannot tell that
     * nothing fits: an absurd path still has a nearest word. Real words at normal noise have a fit of about 0.18
     * (90% under 0.30); paths of words the dictionary lacks are mostly worse, but sloppy swipes overlap, so this is loose.
     */
    @Volatile var confidentFit = 0.32f

    private var importedStamp = 0L

    /** Call when the keyboard opens: a changed imported word list means a new swipe index. */
    fun refreshSources() {
        worker.post {
            val stamp = store.stampFor(language)
            if (stamp != importedStamp) { importedStamp = stamp; index = null; decoder = null; ensureIndex(); rebuildPersonal(); decoder?.let { it.extras = extras; it.boostPoints = boostPoints } }
        }
    }

    fun setLanguage(lang: Language) {
        worker.post { if (lang !== language || index == null) { language = lang; index = null; decoder = null; ensureIndex() } }
    }

    /** Builds the index for the current language ahead of the first swipe. */
    fun prepare() { worker.post { ensureIndex() } }

    /** Call whenever the letter layout is (re)built. Layouts without the language's letters (symbols) are ignored. */
    fun setLayout(l: CompiledLayout) {
        worker.post {
            layout = l; decoder = null
            ensureIndex()
        }
    }

    /** The words the user has taught Wisp, so names become swipeable and favourite words rank higher. */
    fun setPersonal(snapshot: PersonalSnapshot) {
        worker.post { personal = snapshot; rebuildPersonal(); decoder?.let { it.extras = extras; it.boostPoints = boostPoints } }
    }

    private fun rebuildPersonal() {
        val idx = ensureIndex()
        if (idx == null) { extras = emptyArray(); boostPoints = emptyMap(); return }
        val aIdx = idx.alphabet.withIndex().associate { it.value to it.index }
        val tmp = ByteArray(256)
        extras = personal.words.mapNotNull { (word, bonus) ->
            val n = GestureIndex.fold(word, aIdx, tmp)
            if (n < 2) null else GestureDecoder.ExtraWord(word, IntArray(n) { tmp[it].toInt() and 0xFF }, EXTRA_BASE_PROB + (bonus * EXTRA_BONUS_POINTS / MAX_BONUS_UNITS))
        }.toTypedArray()
        val boosts = personal.boosts
        boostPoints = if (boosts.isEmpty()) emptyMap() else HashMap<Int, Int>().also { m ->
            for (i in 0 until idx.count) boosts[idx.original(i).lowercase()]?.let { m[i] = it * EXTRA_BONUS_POINTS / MAX_BONUS_UNITS }
        }
    }

    private fun styleFile() = java.io.File(context.filesDir, "personal/gesture_style.txt")

    /**
     * The user's last swipe turned out to be [word]: learn how they swipe from it. A swipe the user chose from the
     * alternatives is a strong signal; a confident one that went unchallenged is a weak one.
     */
    fun learnFromChoice(word: String, strong: Boolean) {
        worker.post {
            val g = geo ?: return@post; val idx = index ?: return@post
            if (lastN < 3) return@post
            val tmp = ByteArray(256)
            val aIdx = idx.alphabet.withIndex().associate { it.value to it.index }
            val n = GestureIndex.fold(word, aIdx, tmp)
            if (n < 2) return@post
            style.observe(lastX, lastY, lastN, IntArray(n) { tmp[it].toInt() and 0xFF }, g, if (strong) 1f else 0.2f)
            if (++styleDirty >= 10) { style.save(styleFile()); styleStamp = styleFile().lastModified(); styleDirty = 0 }
        }
    }

    /** The user deleted the word this swipe produced: keep the path until they say what they meant. */
    fun rememberFailedSwipe() {
        worker.post { failedX = lastX; failedY = lastY; failedN = lastN }
    }

    /**
     * The user typed [word] right after deleting a swiped word. If it plausibly is that swipe's word (it starts and ends near where
     * the finger did), the swipe becomes a certain training example, the strongest kind.
     */
    fun learnFromCorrection(word: String) {
        worker.post {
            val g = geo ?: return@post; val idx = index ?: return@post
            if (failedN < 3) return@post
            val tmp = ByteArray(256); val aIdx = idx.alphabet.withIndex().associate { it.value to it.index }
            val n = GestureIndex.fold(word, aIdx, tmp)
            if (n < 2) return@post
            val letters = IntArray(n) { tmp[it].toInt() and 0xFF }
            val near = 1.5f * g.keyW
            fun close(a: Int, x: Float, y: Float) = g.has(a) && Math.hypot((x - g.x[a]).toDouble(), (y - g.y[a]).toDouble()) <= near
            if (!close(letters[0], failedX[0], failedY[0]) || !close(letters[n - 1], failedX[failedN - 1], failedY[failedN - 1])) return@post   // unrelated word
            style.observe(failedX, failedY, failedN, letters, g, 2f)
            failedN = 0
            if (++styleDirty >= 10) { style.save(styleFile()); styleStamp = styleFile().lastModified(); styleDirty = 0 }
        }
    }

    fun saveStyle() { worker.post { if (styleDirty > 0) { style.save(styleFile()); styleStamp = styleFile().lastModified(); styleDirty = 0 } } }

    /** Forgets how the user swipes (the practice screen's reset). */
    fun resetStyle() { worker.post { style.reset(); style.save(styleFile()); styleDirty = 0 } }

    fun decode(xs: FloatArray, ys: FloatArray, n: Int, previousWord: String?) {
        val gen = ++generation
        val x = xs.copyOf(n); val y = ys.copyOf(n)
        worker.post {
            lastX = x; lastY = y; lastN = n
            if (gen != generation) return@post
            val outcome = run(x, y, n) ?: return@post
            main.post { if (gen == generation) onOutcome(outcome) }
        }
    }

    private fun ensureIndex(): GestureIndex? {
        index?.let { return it }
        val spec = LayoutLoader.load(context, language.layoutRes)
        return try { store.get(language, alphabetOf(spec)).also { index = it } } catch (e: Exception) { null }
    }

    private fun ensureDecoder(): GestureDecoder? {
        decoder?.let { return it }
        val idx = ensureIndex() ?: return null
        val lay = layout ?: return null
        val g = GestureGeometry.from(lay, idx.alphabet)
        if ((0 until g.size).count { g.has(it) } < idx.alphabet.size) return null   // not the letter layout (e.g. symbols)
        geo = g
        return GestureDecoder(idx, g).also { it.extras = extras; it.boostPoints = boostPoints; it.style = style; decoder = it }
    }

    private fun run(xs: FloatArray, ys: FloatArray, n: Int): GestureOutcome? {
        // The practice screen runs in this process too and may have updated the profile: pick that up.
        val stamp = styleFile().lastModified()
        if (stamp != styleStamp && styleDirty == 0) { style.load(styleFile()); styleStamp = stamp }
        val d = ensureDecoder() ?: return null
        val g = geo ?: return null
        val found = d.decode(xs, ys, n, results)
        val literal = GestureLiteral.of(xs, ys, n, g).ifEmpty { null }
        if (found == 0) return if (literal != null) GestureOutcome(emptyList(), false, literal) else null
        val words = (0 until found).map { results.word(it) }
        val margin = if (found > 1) results.cost[1] - results.cost[0] else Float.MAX_VALUE
        val fit = results.cost[0] + d.priorWeight * results.prob(0) / 255f
        return GestureOutcome(words, margin >= confidentMargin && fit <= confidentFit, literal)
    }

    private companion object {
        const val EXTRA_BASE_PROB = 90          // a personal word swipes about as readily as an everyday word
        const val EXTRA_BONUS_POINTS = 50       // up to this many extra probability points for heavy use
        const val MAX_BONUS_UNITS = 70_000      // PersonalDictionary.MAX_BONUS
    }

    fun close() {
        generation++
        worker.post { if (styleDirty > 0) style.save(styleFile()); thread.quitSafely() }
    }
}
