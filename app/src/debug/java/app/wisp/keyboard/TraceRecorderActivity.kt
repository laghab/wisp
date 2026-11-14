package app.wisp.keyboard

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.wisp.keyboard.gesture.RecordedTrace
import app.wisp.keyboard.gesture.Glide
import app.wisp.keyboard.kbd.KeyboardView
import app.wisp.keyboard.kbd.LayoutLoader
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug tool: asks for common words one at a time, records how you really swipe them (with the exact keyboard
 * geometry) to files/traces_<lang>.jsonl, and tells you whether the decoder got each one. Pull the file to
 * eval/ and the `real_swipes` test in core/wisp-core/tests/glide_eval.rs replays it on the desktop. Swipes only; typing is ignored here.
 */
class TraceRecorderActivity : Activity() {
    // Everyday words, short ones first: they are swiped most and are the easiest to confuse with each other.
    private val words = ("you the that this what with have your not for are was but all can out get just know like from they want here when one "
        + "going about good time think right would there well really come back something because people should please thanks hello tomorrow "
        + "tonight morning never still where could first other their these those while house water night phone message coffee friend work").split(' ')
    private var next = 0
    private lateinit var prompt: TextView
    private lateinit var result: TextView
    private lateinit var keys: KeyboardView
    private var glide: Glide? = null
    private lateinit var file: File
    private var hits = 0; private var top3 = 0; private var total = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lang = Languages.ENGLISH_US
        file = File(getExternalFilesDir(null), "traces_${lang.tag}.jsonl")
        prompt = TextView(this).apply { textSize = 28f; gravity = Gravity.CENTER; setPadding(0, 80, 0, 20); setTextColor(Color.BLACK) }
        result = TextView(this).apply { textSize = 15f; gravity = Gravity.CENTER; setPadding(24, 0, 24, 0); setTextColor(Color.DKGRAY) }
        keys = KeyboardView(this, LayoutLoader.load(this, lang.layoutRes)).apply {
            listener = object : KeyboardView.Listener {
                override fun onKey(code: Int, x: Int, y: Int) {}
                override fun onGesture(xs: FloatArray, ys: FloatArray, count: Int) = record(lang, xs.copyOf(count), ys.copyOf(count))
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(prompt, ViewGroup.LayoutParams.MATCH_PARENT, 0)
            (prompt.layoutParams as LinearLayout.LayoutParams).weight = 1f
            addView(result, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(keys, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(Color.WHITE)
        })
        show(null)
    }

    override fun onDestroy() { glide?.close(); super.onDestroy() }

    private fun show(status: String?) {
        prompt.text = if (status != null) status else if (next < words.size) "swipe: ${words[next]}\n(${next + 1}/${words.size})" else "done: $hits/$total first, $top3/$total in the top 3\nfile: ${file.name}"
    }

    private fun record(lang: Language, xs: FloatArray, ys: FloatArray) {
        if (next >= words.size) return
        val word = words[next]
        val layout = keys.compiledLayout ?: return
        val at = ('a'..'z').map { layout.indexOfCode(it.code) }
        fun side(k: Int, lo: Int) = layout.rects[k * 4 + lo + 2] - layout.rects[k * 4 + lo]
        val trace = RecordedTrace(
            word, lang.tag,
            FloatArray(26) { if (at[it] < 0) Float.NaN else layout.centerX(at[it]) }, FloatArray(26) { if (at[it] < 0) Float.NaN else layout.centerY(at[it]) },
            at.filter { it >= 0 }.maxOf { side(it, 0) }, at.filter { it >= 0 }.maxOf { side(it, 1) }, xs, ys,
        )
        file.appendText(trace.toJson() + "\n")
        val g = glide ?: Glide.build(this, lang, layout)?.also { glide = it } ?: return
        val found = g.decode(xs, ys, xs.size)
        val rank = found.indexOfFirst { it.word.equals(word, true) }
        total++; if (rank == 0) hits++; if (rank in 0..2) top3++
        result.text = "${found.take(3).joinToString { it.word }}   ->   " + if (rank == 0) "right" else if (rank > 0) "rank ${rank + 1}" else "not found"
        next++
        show(null)
    }
}
