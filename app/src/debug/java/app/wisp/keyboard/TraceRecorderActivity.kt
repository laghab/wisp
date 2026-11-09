package app.wisp.keyboard

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.wisp.keyboard.gesture.GestureDecoder
import app.wisp.keyboard.gesture.GestureGeometry
import app.wisp.keyboard.gesture.GestureIndex
import app.wisp.keyboard.gesture.GestureIndexStore
import app.wisp.keyboard.gesture.RecordedTrace
import app.wisp.keyboard.gesture.alphabetOf
import app.wisp.keyboard.kbd.KeyboardView
import app.wisp.keyboard.kbd.LayoutLoader
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug tool: asks for common words one at a time, records how you really swipe them (with the exact keyboard
 * geometry) to files/traces_<lang>.jsonl, and tells you whether the decoder got each one. Pull the file to
 * eval/ and `GestureRecordedTraceTest` replays it on the desktop. Swipes only; typing is ignored here.
 */
class TraceRecorderActivity : Activity() {
    private val words = "hello world keyboard thanks because people should please school friend morning evening tomorrow yesterday between another through might never about great think still where really would could first after other their there these those while house water night phone message coffee garden window".split(' ')
    private var next = 0
    private lateinit var prompt: TextView
    private lateinit var result: TextView
    private lateinit var keys: KeyboardView
    @Volatile private var index: GestureIndex? = null
    private var decoder: GestureDecoder? = null
    private val out = GestureDecoder.Results(10)
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
        show("Preparing the word index...")
        thread {
            val idx = GestureIndexStore(this).get(lang, alphabetOf(LayoutLoader.load(this, lang.layoutRes)))
            index = idx
            runOnUiThread { show(null) }
        }
    }

    private fun show(status: String?) {
        prompt.text = if (status != null) status else if (next < words.size) "swipe: ${words[next]}\n(${next + 1}/${words.size})" else "done: $hits/$total first, $top3/$total in the top 3\nfile: ${file.name}"
    }

    private fun record(lang: Language, xs: FloatArray, ys: FloatArray) {
        val idx = index ?: return
        if (next >= words.size) return
        val word = words[next]
        val layout = keys.compiledLayout ?: return
        val trace = RecordedTrace(word, lang.tag, layout.width.toInt(), layout.height.toInt(), resources.displayMetrics.density, xs, ys)
        file.appendText(trace.toJson() + "\n")
        val dec = decoder ?: GestureDecoder(idx, GestureGeometry.from(layout, idx.alphabet)).also { decoder = it }
        dec.decode(xs, ys, xs.size, out)
        val rank = (0 until out.count).firstOrNull { out.word(it).equals(word, true) } ?: -1
        total++; if (rank == 0) hits++; if (rank in 0..2) top3++
        result.text = "${(0 until minOf(3, out.count)).joinToString { out.word(it) }}   ->   " + if (rank == 0) "right" else if (rank > 0) "rank ${rank + 1}" else "not found"
        next++
        show(null)
    }
}
