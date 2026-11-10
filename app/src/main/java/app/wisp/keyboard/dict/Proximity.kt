package app.wisp.keyboard.dict

import app.wisp.keyboard.core.Native
import app.wisp.keyboard.kbd.CompiledLayout
import app.wisp.keyboard.kbd.KeyKind
import java.io.Closeable

/** Where the letter keys are, so a slip to a neighbouring key costs less than an unrelated letter. Closing frees the native object. */
class Proximity private constructor(var handle: Long) : Closeable {
    override fun close() { if (handle != 0L) { Native.proxFree(handle); handle = 0 } }

    companion object {
        fun build(layout: CompiledLayout): Proximity {
            val keys = (0 until layout.count).filter { layout.codes[it] in 1..127 && layout.kinds[it] == KeyKind.LETTER }
            val widest = keys.maxOfOrNull { layout.rects[it * 4 + 2] - layout.rects[it * 4] } ?: 1f
            return Proximity(Native.proxNew(
                IntArray(keys.size) { Character.toLowerCase(layout.codes[keys[it]]) },
                FloatArray(keys.size) { layout.centerX(keys[it]) }, FloatArray(keys.size) { layout.centerY(keys[it]) }, widest,
            ))
        }
    }
}
