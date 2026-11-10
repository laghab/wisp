package app.wisp.keyboard.kbd

import android.content.Context
import app.wisp.keyboard.core.Native
import app.wisp.keyboard.settings.WispSettings

/** What the user chose for where the keyboard sits (see `placement.rs`). Stored once for portrait and once for landscape. */
data class Placement(
    val mode: Mode = Mode.FULL,
    val width: Float = 1f,
    val height: Float = 1f,
    val x: Float = 0f,
    val gap: Float = 0f,
    val split: Float = 0.08f,
) {
    enum class Mode { FULL, ONE_HANDED_LEFT, ONE_HANDED_RIGHT, FLOATING, SPLIT }

    /** The placement fitted to this screen, in dp. */
    class Frame(val left: Float, val width: Float, val height: Float, val gap: Float, val rowScale: Float, val splitGap: Float, val maxGap: Float)

    fun solve(screenWDp: Float, screenHDp: Float): Frame {
        val out = FloatArray(7)
        Native.placementSolve(floatArrayOf(mode.ordinal.toFloat(), width, height, x, gap, split), floatArrayOf(screenWDp, screenHDp, STRIP_DP, KEYS_DP), out)
        return Frame(out[0], out[1], out[2], out[3], out[4], out[5], out[6])
    }

    companion object {
        /** The suggestion strip, and the keys at scale 1: four 48 dp rows, three 10 dp gaps, 8 dp above and 10 dp below. */
        const val STRIP_DP = 48f
        const val KEYS_DP = 4 * 48f + 3 * 10f + 8f + 10f

        private fun prefix(landscape: Boolean) = if (landscape) "placement_l_" else "placement_p_"

        fun load(context: Context, landscape: Boolean): Placement {
            val s = WispSettings.get(context); val p = prefix(landscape)
            val d = Placement(height = if (landscape) 0.8f else 1f)
            return Placement(
                mode = Mode.values().getOrElse(s.getInt(p + "mode", 0)) { Mode.FULL },
                width = s.getString(p + "width")?.toFloatOrNull() ?: d.width, height = s.getString(p + "height")?.toFloatOrNull() ?: d.height,
                x = s.getString(p + "x")?.toFloatOrNull() ?: d.x, gap = s.getString(p + "gap")?.toFloatOrNull() ?: d.gap, split = s.getString(p + "split")?.toFloatOrNull() ?: d.split,
            )
        }

        fun save(context: Context, landscape: Boolean, v: Placement) {
            val s = WispSettings.get(context); val p = prefix(landscape)
            s.putInt(p + "mode", v.mode.ordinal)
            s.putString(p + "width", v.width.toString()); s.putString(p + "height", v.height.toString())
            s.putString(p + "x", v.x.toString()); s.putString(p + "gap", v.gap.toString()); s.putString(p + "split", v.split.toString())
        }
    }
}
