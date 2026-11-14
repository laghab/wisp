package app.wisp.keyboard.training

import app.wisp.keyboard.R
import app.wisp.keyboard.kbd.Palette
import app.wisp.keyboard.kbd.LayoutGuard
import app.wisp.keyboard.kbd.LetterLayouts
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.wisp.keyboard.Language
import app.wisp.keyboard.Languages
import app.wisp.keyboard.gesture.Glide
import app.wisp.keyboard.dict.DictionaryProvider
import app.wisp.keyboard.kbd.KeyboardView
import app.wisp.keyboard.kbd.Placement
import app.wisp.keyboard.kbd.LayoutLoader
import java.io.File
import java.util.Random
import kotlin.concurrent.thread

/**
 * Practice swiping. Shows 20 words, one at a time; each swipe teaches Wisp how you swipe (the intended word is known, so
 * it counts strongly), and words that were decoded wrongly come up more often. A few minutes a day is plenty.
 * Everything stays on the phone: a small style profile and per-word counts.
 */
class GestureTrainingActivity : Activity() {
    private val language: Language = Languages.ENGLISH_US
    private val pal by lazy { app.wisp.keyboard.style.KeyboardStyle.load(this).palette(this) }
    private val plum get() = pal.background; private val cream get() = pal.text; private val muted get() = pal.textMuted; private val coral get() = pal.accent

    private lateinit var prompt: TextView
    private lateinit var progress: TextView
    private lateinit var feedback: TextView
    private lateinit var keys: KeyboardView
    private lateinit var again: Button

    private val stats = TrainingStats()
    private var glide: Glide? = null
    private var candidates: List<String> = emptyList()
    private var session: List<String> = emptyList()
    private var position = 0
    private var firstTry = 0
    private val rnd = Random()

    private fun dir() = File(filesDir, "personal")
    private fun styleFile() = File(dir(), "gesture_style.txt")
    private fun statsFile() = File(dir(), "training_${language.tag}.tsv")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stats.load(statsFile())
        title = "Practice swiping"
        fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            textSize = size; setTextColor(color); gravity = Gravity.CENTER; setPadding(32, 8, 32, 8)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        prompt = text(44f, coral, true)
        progress = text(15f, muted)
        feedback = text(15f, cream)
        again = Button(this).apply { text = "Practice again"; setOnClickListener { startSession() }; visibility = android.view.View.GONE }
        // The keyboard to practise on is the one you type with: your letter layout, number row and theme.
        val style = app.wisp.keyboard.style.KeyboardStyle.load(this)
        val letterRes = if (language.layoutRes == R.raw.layout_qwerty) LetterLayouts.res(style.layout) else language.layoutRes
        val base = LayoutLoader.load(this, letterRes)
        val palette = Palette().also { it.set(style.palette(this)) }
        keys = KeyboardView(this, if (style.numberRow) LayoutGuard.withNumberRow(base) else base, palette).apply {
            applyStyle(style)
            listener = object : KeyboardView.Listener {
                override fun onKey(code: Int, x: Int, y: Int) {}
                override fun onGesture(xs: FloatArray, ys: FloatArray, count: Int) = swiped(xs.copyOf(count), ys.copyOf(count))
            }
        }
        // The keyboard sits where it does in everyday use: the same width, side, key height, split and raise.
        val d = resources.displayMetrics.density
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val frame = Placement.load(this, landscape).solve(resources.displayMetrics.widthPixels / d, resources.displayMetrics.heightPixels / d)
        keys.rowScale = frame.rowScale; keys.splitGapPx = frame.splitGap * d
        val keysFrame = android.widget.FrameLayout(this).apply {
            addView(keys, android.widget.FrameLayout.LayoutParams((frame.width * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = (frame.left * d).toInt(); bottomMargin = (frame.gap * d).toInt() })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(plum)
            addView(TextView(this@GestureTrainingActivity).apply { text = "Swipe the word across the letters"; textSize = 14f; setTextColor(muted); gravity = Gravity.CENTER; setPadding(0, 72, 0, 0) })
            addView(prompt, ViewGroup.LayoutParams.MATCH_PARENT, 0); (prompt.layoutParams as LinearLayout.LayoutParams).weight = 1f
            addView(progress, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(feedback, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(again, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(keysFrame, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        prompt.text = "..."; progress.text = "Getting the word list ready"
        thread {
            val sources = DictionaryProvider(this).open(language)
            // Everyday words only: 133 and up is about the 3,000 most used (tools/english_wordlist.py sets the scale).
            candidates = sources.firstOrNull()?.dict?.words().orEmpty().filter { (w, p) -> w.length in 3..9 && p >= 133 && w.all { it in 'a'..'z' } }.map { it.first }
            sources.forEach { it.dict.close() }
            runOnUiThread { if (candidates.isNotEmpty()) startSession() else progress.text = "Could not load the dictionary" }
        }
    }

    override fun onDestroy() { glide?.close(); super.onDestroy() }

    private fun startSession() {
        session = stats.pick(candidates, SESSION, rnd); position = 0; firstTry = 0
        again.visibility = android.view.View.GONE
        feedback.text = "Swipe the word on the keyboard below"
        show()
    }

    private fun show() {
        if (position < session.size) { prompt.text = session[position]; progress.text = "${position + 1} of ${session.size}   -   $firstTry decoded first try" }
        else finish_()
    }

    private fun swiped(xs: FloatArray, ys: FloatArray) {
        if (position >= session.size) return
        val layout = keys.compiledLayout ?: return
        val word = session[position]
        val g = glide ?: Glide.build(this, language, layout)?.also { it.loadStyle(styleFile()); glide = it } ?: return
        val found = g.decode(xs, ys, xs.size)
        val rank = found.indexOfFirst { it.word == word }
        // The word is known, so this swipe teaches the style whether or not it was decoded right.
        g.observe(xs, ys, xs.size, word, 3f)
        stats.record(word, rank == 0)
        if (rank == 0) firstTry++
        feedback.text = if (rank == 0) "Got it" else "Read as: " + found.take(2).joinToString(" or ") { it.word }.ifEmpty { "nothing close" }
        g.saveStyle(styleFile()); stats.save(statsFile())
        position++
        show()
    }

    private fun finish_() {
        prompt.text = "$firstTry / ${session.size}"
        progress.text = "decoded first try"
        val style = glide?.style
        feedback.text = if (style == null || style.isNeutral) "" else "Wisp now knows your style: swipes land %+.2f across and %+.2f down from the ideal line (in key widths), at %d%% of the ideal length.".format(style.biasX, style.biasY, Math.round(style.lengthRatio * 100))
        again.visibility = android.view.View.VISIBLE
    }

    private companion object { const val SESSION = 20 }
}
