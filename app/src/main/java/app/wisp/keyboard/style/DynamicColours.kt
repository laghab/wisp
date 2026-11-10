package app.wisp.keyboard.style

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.RequiresApi

/** Material You: gives the Material theme the colours of the phone's wallpaper palette (Android 12 and later). */
object DynamicColours {
    @RequiresApi(Build.VERSION_CODES.S)
    fun apply(context: Context, t: Theme): Theme {
        fun c(id: Int) = context.getColor(id)
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val white = 0xFFFFFFFF.toInt()
        return if (dark) t.copy(
            dark = true,
            board = listOf(Fill.Solid(c(android.R.color.system_neutral1_900))), key = Fill.Solid(c(android.R.color.system_neutral1_800)), mod = Fill.Solid(c(android.R.color.system_accent2_700)),
            fg = c(android.R.color.system_neutral1_50), modFg = c(android.R.color.system_accent2_100), accent = Fill.Solid(c(android.R.color.system_accent1_200)), accentFg = c(android.R.color.system_accent1_800),
            stripFg = c(android.R.color.system_neutral2_200), sep = c(android.R.color.system_neutral2_700),
        ) else t.copy(
            board = listOf(Fill.Solid(c(android.R.color.system_accent2_100))), key = Fill.Solid(c(android.R.color.system_neutral1_10)), mod = Fill.Solid(c(android.R.color.system_accent2_200)),
            fg = c(android.R.color.system_neutral1_900), modFg = c(android.R.color.system_accent2_900), accent = Fill.Solid(c(android.R.color.system_accent1_600)), accentFg = white,
            stripFg = c(android.R.color.system_neutral2_700), sep = c(android.R.color.system_accent2_300),
        )
    }
}
