package app.wisp.keyboard.gesture

import org.json.JSONArray
import org.json.JSONObject

/**
 * One recorded swipe: the word that was asked for, the points the finger actually went through, and the size of the
 * keyboard they were drawn on (key positions depend on it, so a trace can only be replayed on the same geometry).
 */
class RecordedTrace(
    val word: String, val language: String,
    val width: Int, val height: Int, val density: Float,
    val xs: FloatArray, val ys: FloatArray,
) {
    val count get() = xs.size

    fun toJson(): String = JSONObject().apply {
        put("word", word); put("lang", language); put("w", width); put("h", height); put("d", density.toDouble())
        put("x", JSONArray().also { a -> xs.forEach { a.put(Math.round(it * 10) / 10.0) } })
        put("y", JSONArray().also { a -> ys.forEach { a.put(Math.round(it * 10) / 10.0) } })
    }.toString()

    companion object {
        fun parse(line: String): RecordedTrace {
            val o = JSONObject(line)
            val x = o.getJSONArray("x"); val y = o.getJSONArray("y")
            return RecordedTrace(
                o.getString("word"), o.getString("lang"), o.getInt("w"), o.getInt("h"), o.getDouble("d").toFloat(),
                FloatArray(x.length()) { x.getDouble(it).toFloat() }, FloatArray(y.length()) { y.getDouble(it).toFloat() },
            )
        }
    }
}
