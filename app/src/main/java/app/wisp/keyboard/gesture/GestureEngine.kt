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
import app.wisp.keyboard.personal.PersonalSnapshot
import java.io.File

/**
 * Decodes swipes on a background thread. The first use of a language builds its swipe index in the Rust core (about a
 * tenth of a second); until it is ready, swipes are ignored. Results come back on the main thread.
 */
class GestureEngine(
    private val context: Context,
    private val onOutcome: (GestureOutcome) -> Unit,
) {
    private val thread = HandlerThread("wisp-gesture", Process.THREAD_PRIORITY_DEFAULT).apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var generation = 0

    // Worker-thread state.
    private var language: Language = Languages.ENGLISH_US
    private var glide: Glide? = null
    private var layout: CompiledLayout? = null
    private var personal = PersonalSnapshot.EMPTY
    private var styleStamp = 0L
    private var styleDirty = 0
    private var lastX = FloatArray(0); private var lastY = FloatArray(0); private var lastN = 0
    private var failedX = FloatArray(0); private var failedY = FloatArray(0); private var failedN = 0

    /**
     * A swipe is inserted on its own only when the best word beats the runner-up by at least this much; otherwise the
     * words are offered in the strip. The margin separates right from wrong far better than the cost itself (see
     * docs/gesture-eval.md): wrong best words win by a median of 0.02, right ones by about 0.10.
     */
    @Volatile var confidentMargin = 0.015f

    /**
     * ...and its path fit (the cost without the word-frequency bonus) must be under this. Margin alone cannot tell that
     * nothing fits: an absurd path still has a nearest word.
     */
    @Volatile var confidentFit = 0.32f
    /** A swipe whose best word fits the path this well is typed even when another word is nearly as good (measured: real swipes 0.03 to 0.1, nonsense 0.2 to 0.4); worse than this, nothing is typed and the strip offers choices. */
    @Volatile var plausibleFit = 0.25f

    private var importedStamp = 0L

    private fun styleFile() = File(context.filesDir, "personal/gesture_style.txt")
    private fun importedStamp() = app.wisp.keyboard.dict.DictionaryProvider(context).importedFile(language).lastModified()

    /** Call when the keyboard opens: a changed imported word list means a new swipe index. */
    fun refreshSources() {
        worker.post { val s = importedStamp(); if (s != importedStamp) { importedStamp = s; rebuild() } }
    }

    fun setLanguage(lang: Language) {
        worker.post { if (lang !== language || glide == null) { language = lang; rebuild() } }
    }

    /** Call whenever the letter layout is (re)built. */
    fun setLayout(l: CompiledLayout) {
        worker.post { layout = l; val g = glide; if (g == null) ensure() else g.setGeometry(l) }
    }

    /** The words the user has taught Wisp, so names become swipeable and favourite words rank higher. */
    fun setPersonal(snapshot: PersonalSnapshot) {
        worker.post { personal = snapshot; glide?.setPersonal(snapshot) }
    }

    private fun rebuild() { glide?.close(); glide = null; ensure() }

    private fun ensure(): Glide? {
        glide?.let { return it }
        val l = layout ?: return null
        return try {
            Glide.build(context, language, l)?.also {
                it.loadStyle(styleFile()); styleStamp = styleFile().lastModified(); it.setPersonal(personal); glide = it
            }
        } catch (e: Exception) { null }
    }

    private fun saveStyleNow() { glide?.saveStyle(styleFile()); styleStamp = styleFile().lastModified(); styleDirty = 0 }
    private fun styleChanged() { if (++styleDirty >= 10) saveStyleNow() }

    /**
     * The user's last swipe turned out to be [word]: learn how they swipe from it. A swipe the user chose from the
     * alternatives is a strong signal; a confident one that went unchallenged is a weak one.
     */
    fun learnFromChoice(word: String, strong: Boolean) {
        worker.post {
            val g = glide ?: return@post
            if (lastN < 3) return@post
            g.observe(lastX, lastY, lastN, word, if (strong) 1f else 0.2f)
            styleChanged()
        }
    }

    /** The user deleted the word this swipe produced: keep the path until they say what they meant. */
    fun rememberFailedSwipe() { worker.post { failedX = lastX; failedY = lastY; failedN = lastN } }

    /**
     * The user typed [word] right after deleting a swiped word. If it plausibly is that swipe's word (it starts and ends near where
     * the finger did), the swipe becomes a certain training example, the strongest kind.
     */
    fun learnFromCorrection(word: String) {
        worker.post {
            val g = glide ?: return@post
            if (failedN < 3 || !g.plausible(failedX, failedY, failedN, word)) return@post
            g.observe(failedX, failedY, failedN, word, 2f)
            failedN = 0
            styleChanged()
        }
    }

    fun saveStyle() { worker.post { if (styleDirty > 0) saveStyleNow() } }

    /** Forgets how the user swipes (the practice screen's reset). */
    fun resetStyle() { worker.post { glide?.resetStyle(); saveStyleNow() } }

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

    private fun run(xs: FloatArray, ys: FloatArray, n: Int): GestureOutcome? {
        // The practice screen runs in this process too and may have updated the profile: pick that up.
        val stamp = styleFile().lastModified()
        if (stamp != styleStamp && styleDirty == 0) { glide?.loadStyle(styleFile()); styleStamp = stamp }
        val g = ensure() ?: return null
        if (!g.geometryOk) return null                       // not the letter layout (e.g. symbols)
        val hits = g.decode(xs, ys, n)
        val literal = g.literal(xs, ys, n).ifEmpty { null }
        if (hits.isEmpty()) return if (literal != null) GestureOutcome(emptyList(), false, literal) else null
        val margin = if (hits.size > 1) hits[1].cost - hits[0].cost else Float.MAX_VALUE
        return GestureOutcome(hits.map { it.word }, margin >= confidentMargin && hits[0].fit <= confidentFit, literal, plausible = hits[0].fit <= plausibleFit)
    }

    fun close() {
        generation++
        worker.post { if (styleDirty > 0) saveStyleNow(); glide?.close(); glide = null; thread.quitSafely() }
    }
}
