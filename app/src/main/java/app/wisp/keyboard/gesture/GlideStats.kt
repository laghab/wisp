package app.wisp.keyboard.gesture

import android.content.Context
import app.wisp.keyboard.settings.WispSettings
import java.util.Calendar

/** How many words were swiped this week, kept as one number and the week it belongs to, on the phone only (shown on the home screen). */
object GlideStats {
    private fun week(now: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = now; firstDayOfWeek = Calendar.MONDAY; minimalDaysInFirstWeek = 4 }
        return "${c.get(Calendar.YEAR)}-${c.get(Calendar.WEEK_OF_YEAR)}"
    }

    fun record(context: Context, now: Long = System.currentTimeMillis()) {
        val s = WispSettings.get(context)
        val w = week(now)
        val n = if (s.getString("glide_week") == w) s.getInt("glide_count", 0) else 0
        s.putString("glide_week", w); s.putInt("glide_count", n + 1)
    }

    fun thisWeek(context: Context, now: Long = System.currentTimeMillis()): Int {
        val s = WispSettings.get(context)
        return if (s.getString("glide_week") == week(now)) s.getInt("glide_count", 0) else 0
    }
}
