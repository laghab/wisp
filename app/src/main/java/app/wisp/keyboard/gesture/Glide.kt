package app.wisp.keyboard.gesture

import android.content.Context
import app.wisp.keyboard.Language
import app.wisp.keyboard.core.Native
import app.wisp.keyboard.dict.DictionaryProvider
import app.wisp.keyboard.kbd.CompiledLayout
import app.wisp.keyboard.kbd.KeyKind
import app.wisp.keyboard.kbd.LayoutSpec
import app.wisp.keyboard.personal.PersonalSnapshot
import java.io.Closeable
import java.io.File

/** Swipe typing in the Rust core for one language: ranks words for a traced path and learns how this user swipes. Not thread-safe. */
class Glide private constructor(private var handle: Long, val alphabet: IntArray) : Closeable {

    class Hit(val word: String, val cost: Float, /** Path fit alone, without the word-frequency bonus. */ val fit: Float, val prob: Int)

    /** How one person swipes, in key widths (see `Style` in glide.rs). */
    class Style(val biasX: Float, val biasY: Float, val lengthRatio: Float, val spread: Float, val evidence: Float) {
        val isNeutral get() = evidence == 0f
    }

    /** True while the layout has every letter of the alphabet; false on pages like the symbols page. */
    var geometryOk = false
        private set

    fun setGeometry(layout: CompiledLayout) {
        val (xs, ys, kw, kh) = geometryOf(layout, alphabet)
        geometryOk = Native.glideSetGeometry(handle, xs, ys, kw, kh)
    }

    /** The words the swipe could be, best first. [previous] is the word written just before it: words that often follow it are favoured. */
    fun decode(xs: FloatArray, ys: FloatArray, n: Int, previous: String? = null): List<Hit> {
        val text = Native.glideDecode(handle, xs, ys, n, previous.orEmpty())
        return text.lineSequence().filter { it.isNotEmpty() }.map { l -> val f = l.split('\t'); Hit(f[0], f[1].toFloat(), f[2].toFloat(), f[3].toInt()) }.toList()
    }

    /** The letters the swipe passed over, in order: what to offer when no word fits. */
    fun literal(xs: FloatArray, ys: FloatArray, n: Int): String = Native.glideLiteral(handle, xs, ys, n)

    fun observe(xs: FloatArray, ys: FloatArray, n: Int, word: String, weight: Float) = Native.glideObserve(handle, xs, ys, n, word, weight)

    /** Whether [word] starts and ends near where the swipe did. */
    fun plausible(xs: FloatArray, ys: FloatArray, n: Int, word: String) = Native.glidePlausible(handle, xs, ys, n, word)

    var style: Style
        get() = parse(Native.glideStyle(handle, null))
        set(v) { Native.glideStyle(handle, "${v.biasX} ${v.biasY} ${v.lengthRatio} ${v.spread} ${v.evidence}") }

    fun loadStyle(file: File) { Native.glideStyle(handle, if (file.isFile) file.readText() else "") }

    fun saveStyle(file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(Native.glideStyle(handle, null))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** The words the user has taught Wisp, so names become swipeable and favourite words rank higher. */
    fun setPersonal(p: PersonalSnapshot) {
        val sb = StringBuilder()
        for ((w, bonus) in p.words) sb.append(w).append('\t').append(EXTRA_BASE_PROB + bonus * EXTRA_BONUS_POINTS / MAX_BONUS_UNITS).append('\n')
        sb.append('\n')
        for ((w, bonus) in p.boosts) sb.append(w).append('\t').append(bonus * EXTRA_BONUS_POINTS / MAX_BONUS_UNITS).append('\n')
        sb.append('\n')
        for ((before, w, strength) in p.pairs) sb.append(before).append('\t').append(w).append('\t').append(strength).append('\n')
        Native.glideSetPersonal(handle, sb.toString())
    }

    override fun close() { if (handle != 0L) { Native.glideFree(handle); handle = 0 } }

    private fun parse(t: String): Style {
        val f = t.trim().split(' ').map { it.toFloat() }
        return Style(f[0], f[1], f[2], f[3], f[4])
    }

    private data class Geo(val xs: FloatArray, val ys: FloatArray, val keyW: Float, val keyH: Float)

    companion object {
        const val EXTRA_BASE_PROB = 90          // a personal word swipes about as readily as an everyday word
        const val EXTRA_BONUS_POINTS = 50       // up to this many extra probability points for heavy use
        const val MAX_BONUS_UNITS = 70_000      // PersonalDictionary.MAX_BONUS

        /** The letters a layout can swipe: its letter keys, lower-cased, in code point order. */
        fun alphabetOf(layout: CompiledLayout): IntArray =
            (0 until layout.count).filter { layout.kinds[it] == KeyKind.LETTER && Character.isLetter(layout.codes[it]) }
                .map { Character.toLowerCase(layout.codes[it]) }.distinct().sorted().toIntArray()

        /** Same, from a layout before it has been sized. */
        fun alphabetOf(spec: LayoutSpec): IntArray =
            spec.rows.flatten().filter { !it.spacer && it.kind == KeyKind.LETTER && Character.isLetter(it.code) }
                .map { Character.toLowerCase(it.code) }.distinct().sorted().toIntArray()

        private fun geometryOf(layout: CompiledLayout, alphabet: IntArray): Geo {
            val xs = FloatArray(alphabet.size) { Float.NaN }; val ys = FloatArray(alphabet.size) { Float.NaN }
            var w = 0f; var h = 0f
            for ((i, cp) in alphabet.withIndex()) {
                val k = layout.indexOfCode(cp)
                if (k < 0) continue
                xs[i] = layout.centerX(k); ys[i] = layout.centerY(k)
                w = maxOf(w, layout.rects[k * 4 + 2] - layout.rects[k * 4]); h = maxOf(h, layout.rects[k * 4 + 3] - layout.rects[k * 4 + 1])
            }
            return Geo(xs, ys, w, h)
        }

        /** Builds the swipe index for [lang] on [layout] (the letter layout). Takes a moment the first time; keep it off the main thread. */
        fun build(context: Context, lang: Language, layout: CompiledLayout): Glide? {
            val sources = DictionaryProvider(context).open(lang)
            if (sources.isEmpty()) return null
            try {
                val alphabet = alphabetOf(layout)
                val g = geometryOf(layout, alphabet)
                val h = Native.glideNew(
                    LongArray(sources.size) { sources[it].dict.handleForNative() }, IntArray(sources.size) { if (sources[it].own) 0 else 1 },
                    alphabet, g.xs, g.ys, g.keyW, g.keyH,
                )
                if (h == 0L) return null
                return Glide(h, alphabet).also { it.geometryOk = g.xs.none { x -> x.isNaN() } }
            } finally { sources.forEach { it.dict.close() } }
        }
    }
}
