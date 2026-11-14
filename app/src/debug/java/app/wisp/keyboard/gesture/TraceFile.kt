package app.wisp.keyboard.gesture

import org.json.JSONArray
import org.json.JSONObject

/**
 * One recorded swipe: the word that was asked for, the points the finger actually went through, and the size of the
 * keyboard they were drawn on (key positions depend on it, so a trace can only be replayed on the same geometry).
 */
class RecordedTrace(
    val word: String, val language: String,
    /** Where the letters a to z sit (key centres, NaN for a letter the layout lacks) and how big a key is, in the same pixels as the path. */
    val keyXs: FloatArray, val keyYs: FloatArray, val keyW: Float, val keyH: Float,
    val xs: FloatArray, val ys: FloatArray,
) {
    fun toJson(): String = JSONObject().apply {
        fun list(v: FloatArray) = JSONArray().also { a -> v.forEach { a.put(if (it.isNaN()) -1.0 else Math.round(it * 10) / 10.0) } }
        put("word", word); put("lang", language); put("kw", keyW.toDouble()); put("kh", keyH.toDouble())
        put("kx", list(keyXs)); put("ky", list(keyYs)); put("x", list(xs)); put("y", list(ys))
    }.toString()
}
