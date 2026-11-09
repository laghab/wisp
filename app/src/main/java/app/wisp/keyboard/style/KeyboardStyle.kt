package app.wisp.keyboard.style

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import app.wisp.keyboard.kbd.Palette
import app.wisp.keyboard.settings.WispSettings

/** Everything the user can change about how the keyboard looks and feels, read from the settings file. */
data class KeyboardStyle(
    val theme: String = "system",
    val accent: String = "coral",
    val shape: String = "round",
    val trailOn: Boolean = true,
    val trailColor: String = "accent",
    val trailWidthDp: Int = 5,
    val heightPortrait: Int = 100,
    val heightLandscape: Int = 100,
    val gap: Int = 100,
    val numberRow: Boolean = false,
    val oneHanded: String = "off",
    val split: Boolean = false,
    val floating: Boolean = false,
    /** Empty space under the keys, in dp (room for the thumb, or the phone's gesture bar). */
    val bottomGap: Int = 0,
    val haptic: String = "light",
    val sound: Boolean = false,
) {
    /** A short string that changes whenever any option changes; views re-style only when it does. */
    val signature: String get() = listOf(theme, accent, shape, trailOn, trailColor, trailWidthDp, heightPortrait, heightLandscape, gap, numberRow, oneHanded, split, floating, bottomGap, haptic, sound).joinToString("|")

    fun heightPercent(landscape: Boolean) = if (landscape) heightLandscape else heightPortrait
    fun cornerDp() = when (shape) { "square" -> 3f; "pill" -> 18f; else -> 9f }

    fun isDark(context: Context): Boolean = when (theme) {
        "light" -> false
        "dark" -> true
        else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    fun accentColor(context: Context): Int = colorOf(context, accent)
    fun trailColorInt(context: Context): Int = if (trailColor == "accent") accentColor(context) else colorOf(context, trailColor)

    fun palette(context: Context): Palette {
        val t = if (isDark(context)) Brand.DARK else Brand.LIGHT
        val a = Brand.accentOn(accentColor(context), t.background)
        return Palette(background = t.background, key = t.key, keyPressed = t.keyPressed, special = t.special, accent = a, onAccent = Brand.onAccent(a), text = t.text, textMuted = t.textMuted)
    }

    /** Writes every option to the settings file. */
    fun save(context: Context) {
        val s = WispSettings.get(context)
        s.putString("style_theme", theme); s.putString("style_accent", accent); s.putString("style_shape", shape)
        s.putBoolean("style_trail_on", trailOn); s.putString("style_trail_color", trailColor); s.putInt("style_trail_width", trailWidthDp)
        s.putInt("style_height_portrait", heightPortrait); s.putInt("style_height_landscape", heightLandscape); s.putInt("style_gap", gap)
        s.putBoolean("style_number_row", numberRow); s.putString("style_one_handed", oneHanded); s.putBoolean("style_split", split); s.putBoolean("style_floating", floating); s.putInt("style_bottom_gap", bottomGap)
        s.putString("style_haptic", haptic); s.putBoolean("style_sound", sound)
    }

    companion object {
        /** Accent names with a fixed colour, in the order the picker shows them; "dynamic" follows the phone's wallpaper colour (Android 12 and later). */
        val ACCENTS get() = Brand.ACCENTS
        val DYNAMIC_AVAILABLE get() = Build.VERSION.SDK_INT >= 31

        fun colorOf(context: Context, name: String): Int =
            if (name == "dynamic" && DYNAMIC_AVAILABLE) context.getColor(android.R.color.system_accent1_400) else ACCENTS[name] ?: ACCENTS.getValue("coral")

        fun load(context: Context): KeyboardStyle {
            val s = WispSettings.get(context)
            return KeyboardStyle(
                theme = s.getString("style_theme", "system")!!, accent = s.getString("style_accent", "coral")!!, shape = s.getString("style_shape", "round")!!,
                trailOn = s.getBoolean("style_trail_on", true), trailColor = s.getString("style_trail_color", "accent")!!, trailWidthDp = s.getInt("style_trail_width", 5).coerceIn(2, 12),
                heightPortrait = s.getInt("style_height_portrait", 100).coerceIn(70, 130), heightLandscape = s.getInt("style_height_landscape", 100).coerceIn(60, 130),
                gap = s.getInt("style_gap", 100).coerceIn(40, 200), numberRow = s.getBoolean("style_number_row", false),
                oneHanded = s.getString("style_one_handed", "off")!!, split = s.getBoolean("style_split", false), floating = s.getBoolean("style_floating", false), bottomGap = s.getInt("style_bottom_gap", 0).coerceIn(0, 80),
                haptic = s.getString("style_haptic", "light")!!, sound = s.getBoolean("style_sound", false),
            )
        }
    }
}
