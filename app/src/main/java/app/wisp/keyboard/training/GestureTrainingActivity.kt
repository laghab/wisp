package app.wisp.keyboard.training

import android.app.Activity
import android.app.AlertDialog
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
import app.wisp.keyboard.gesture.GestureDecoder
import app.wisp.keyboard.gesture.GestureGeometry
import app.wisp.keyboard.gesture.GestureIndex
import app.wisp.keyboard.gesture.GestureIndexStore
import app.wisp.keyboard.gesture.GestureStyle
import app.wisp.keyboard.gesture.alphabetOf
import app.wisp.keyboard.kbd.KeyboardView
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
    private lateinit var reset: Button

    private val style = GestureStyle()
    private val stats = TrainingStats()
    private var index: GestureIndex? = null
    private var decoder: GestureDecoder? = null
    private var candidates: List<String> = emptyList()
    private var session: List<String> = emptyList()
    private var position = 0
    private var firstTry = 0
    private val out = GestureDecoder.Results(10)
    private val rnd = Random()

    private fun dir() = File(filesDir, "personal")
    private fun styleFile() = File(dir(), "gesture_style.txt")
    private fun statsFile() = File(dir(), "training_${language.tag}.tsv")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        style.load(styleFile()); stats.load(statsFile())
        title = "Practice swiping"
        fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            textSize = size; setTextColor(color); gravity = Gravity.CENTER; setPadding(32, 8, 32, 8)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        prompt = text(44f, coral, true)
        progress = text(15f, muted)
        feedback = text(15f, cream)
        again = Button(this).apply { text = "Practice again"; setOnClickListener { startSession() }; visibility = android.view.View.GONE }
        reset = Button(this).apply { text = "Reset what Wisp learned about my swiping"; setOnClickListener { confirmReset() } }
        keys = KeyboardView(this, LayoutLoader.load(this, language.layoutRes)).apply {
            listener = object : KeyboardView.Listener {
                override fun onKey(code: Int, x: Int, y: Int) {}
                override fun onGesture(xs: FloatArray, ys: FloatArray, count: Int) = swiped(xs.copyOf(count), ys.copyOf(count))
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(plum)
            addView(TextView(this@GestureTrainingActivity).apply { text = "Swipe the word across the letters"; textSize = 14f; setTextColor(muted); gravity = Gravity.CENTER; setPadding(0, 72, 0, 0) })
            addView(prompt, ViewGroup.LayoutParams.MATCH_PARENT, 0); (prompt.layoutParams as LinearLayout.LayoutParams).weight = 1f
            addView(progress, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(feedback, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(again, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(reset, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(keys, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        prompt.text = "..."; progress.text = "Getting the word list ready"
        thread {
            val idx = GestureIndexStore(this).get(language, alphabetOf(LayoutLoader.load(this, language.layoutRes)))
            if (idx != null) {
                index = idx
                candidates = (0 until idx.count).filter { val n = idx.foldedLen(it); n in 3..9 && idx.prob(it) >= 105 }.map { idx.original(it) }.filter { w -> w.all { it.isLowerCase() } }
            }
            runOnUiThread { if (index != null) startSession() else progress.text = "Could not load the dictionary" }
        }
    }

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
        val idx = index ?: return
        if (position >= session.size) return
        val layout = keys.compiledLayout ?: return
        val word = session[position]
        val dec = decoder ?: GestureDecoder(idx, GestureGeometry.from(layout, idx.alphabet)).also { decoder = it }
        dec.style = style
        dec.decode(xs, ys, xs.size, out)
        val rank = (0 until out.count).firstOrNull { out.word(it) == word } ?: -1
        // The word is known, so this swipe teaches the style whether or not it was decoded right.
        val aIdx = idx.alphabet.withIndex().associate { it.value to it.index }
        val tmp = ByteArray(64); val n = GestureIndex.fold(word, aIdx, tmp)
        if (n >= 2) style.observe(xs, ys, xs.size, IntArray(n) { tmp[it].toInt() and 0xFF }, GestureGeometry.from(layout, idx.alphabet), 3f)
        stats.record(word, rank == 0)
        if (rank == 0) firstTry++
        feedback.text = if (rank == 0) "Got it" else "Read as: " + (0 until minOf(2, out.count)).joinToString(" or ") { out.word(it) }.ifEmpty { "nothing close" }
        style.save(styleFile()); stats.save(statsFile())
        position++
        show()
    }

    private fun finish_() {
        prompt.text = "$firstTry / ${session.size}"
        progress.text = "decoded first try"
        feedback.text = if (style.isNeutral) "" else "Wisp now knows your style: swipes land %+.2f across and %+.2f down from the ideal line (in key widths), at %d%% of the ideal length.".format(style.biasX, style.biasY, Math.round(style.lengthRatio * 100))
        again.visibility = android.view.View.VISIBLE
    }

    private fun confirmReset() {
        AlertDialog.Builder(this).setTitle("Reset swiping style?").setMessage("This forgets how you swipe and which words gave you trouble. Your learned words stay.")
            .setPositiveButton("Reset") { _, _ -> style.reset(); stats.clear(); styleFile().delete(); statsFile().delete(); startSession() }
            .setNegativeButton("Cancel", null).show()
    }

    private companion object { const val SESSION = 20 }
}
