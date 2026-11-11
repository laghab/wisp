package app.wisp.keyboard.style

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import app.wisp.keyboard.kbd.Palette
import app.wisp.keyboard.settings.WispSettings

/** Everything the user can change about how the keyboard looks and feels, read from the settings file. */
data class KeyboardStyle(
    /** Id of the skin (see [Themes]). */
    val theme: String = Themes.DEFAULT,
    /** Which look of a theme that has both: "system" follows the phone, or "light" / "dark". */
    val colors: String = "system",
    /** The space bar's picture, on the themes that have one: "off", "still", "subtle" (busy pictures slowed right down) or "full" (as designed). */
    val spaceArt: String = "subtle",
    /** Material You: take the colours from the wallpaper (Android 12 and later). */
    val wallpaperColours: Boolean = false,
    val keyBorders: Boolean = true,
    val keyHints: Boolean = true,
    /** A photo behind the keys (a file in the app's storage), with [photoDim] percent darkening; empty for none. */
    val photo: String = "",
    val photoDim: Int = 40,
    val trailOn: Boolean = true,
    val trailColor: String = "accent",
    val trailWidthDp: Int = 5,
    val gap: Int = 100,
    val numberRow: Boolean = false,
    /** "off", "system" (the phone's own key haptics) or "custom" (a tick of [hapticLevel] strength). */
    val haptic: String = "system",
    val hapticLevel: Int = 60,
    /** "off", "system" (the phone's own click), "tick" (a louder click) or an instrument: piano, xylophone, marimba, musicbox, harp. */
    val sound: String = "off",
    val soundVolume: Int = 100,
    /** Touch gestures, each switchable: swipe left from backspace deletes words; slide on the space bar moves the cursor. */
    val wordSwipe: Boolean = true,
    val cursorSlide: Boolean = true,
    /** Typing behaviour, applied to the open keyboard the next time it is shown. */
    /** Letter arrangement: one of [app.wisp.keyboard.kbd.LetterLayouts]. */
    val layout: String = "qwerty",
    val glide: Boolean = true,
    val autoCorrect: Boolean = true,
    val autoCaps: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
    /** How long a key is held before its extra characters appear, 150 to 700 ms. */
    val longPressMs: Int = 350,
    /** `word=expansion` per line: typing the word and a space types the expansion. */
    val shortcuts: String = "",
) {
    /** A short string that changes whenever any option changes; views re-style only when it does. */
    val signature: String get() = listOf(theme, colors, spaceArt, wallpaperColours, keyBorders, keyHints, photo, photoDim, trailOn, trailColor, trailWidthDp, gap, numberRow, haptic, hapticLevel, sound, soundVolume, wordSwipe, cursorSlide, layout, glide, autoCorrect, autoCaps, doubleSpacePeriod, longPressMs, shortcuts).joinToString("|")

    /** The skin to draw: the chosen theme, with wallpaper colours, border and photo options applied. */
    fun themeFor(context: Context): Theme {
        var t = Themes.get(context, theme, wantsDark(context))
        if (t.dynamic && wallpaperColours && DYNAMIC_AVAILABLE) t = DynamicColours.apply(context, t)
        if (!keyBorders && t.border != null) t = t.copy(border = null)
        return t
    }

    /** Whether the dark look is wanted: as chosen, or as the phone is when it is left on "system". */
    fun wantsDark(context: Context): Boolean = when (colors) {
        "light" -> false
        "dark" -> true
        else -> (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    fun isDark(context: Context): Boolean = themeFor(context).dark

    fun trailColorInt(context: Context): Int = if (trailColor == "accent") themeFor(context).accent.base() else colorOf(context, trailColor)

    fun palette(context: Context): Palette = themeFor(context).toPalette()

    /** Writes every option to the settings file. */
    fun save(context: Context) {
        val s = WispSettings.get(context)
        s.putString("style_theme", theme); s.putString("style_colors", colors); s.putString("style_art", spaceArt); s.putBoolean("style_wallpaper", wallpaperColours); s.putBoolean("style_borders", keyBorders); s.putBoolean("style_hints", keyHints)
        s.putString("style_photo", photo); s.putInt("style_photo_dim", photoDim)
        s.putBoolean("style_trail_on", trailOn); s.putString("style_trail_color", trailColor); s.putInt("style_trail_width", trailWidthDp)
        s.putInt("style_gap", gap)
        s.putBoolean("style_number_row", numberRow)
        s.putString("style_haptic", haptic); s.putInt("style_haptic_level", hapticLevel); s.putString("style_key_sound", sound); s.putInt("style_sound_volume", soundVolume)
        s.putBoolean("gesture_word_swipe", wordSwipe); s.putBoolean("gesture_cursor_slide", cursorSlide)
        s.putBoolean("typing_glide", glide); s.putBoolean("typing_autocorrect", autoCorrect); s.putBoolean("typing_autocaps", autoCaps); s.putBoolean("double_space_period", doubleSpacePeriod)
        s.putString("letter_layout", layout); s.putInt("long_press_ms", longPressMs); s.putString("text_shortcuts", shortcuts)
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
                theme = s.getString("style_theme", Themes.DEFAULT)!!, colors = s.getString("style_colors", "system")!!, spaceArt = s.getString("style_art", "subtle")!!, wallpaperColours = s.getBoolean("style_wallpaper", false), keyBorders = s.getBoolean("style_borders", true), keyHints = s.getBoolean("style_hints", true),
                photo = s.getString("style_photo", "")!!, photoDim = s.getInt("style_photo_dim", 40).coerceIn(0, 80),
                trailOn = s.getBoolean("style_trail_on", true), trailColor = s.getString("style_trail_color", "accent")!!, trailWidthDp = s.getInt("style_trail_width", 5).coerceIn(2, 12),
                gap = s.getInt("style_gap", 100).coerceIn(40, 200), numberRow = s.getBoolean("style_number_row", false),
                haptic = when (val h = s.getString("style_haptic", "system")) { "off", "custom" -> h; "strong" -> "custom"; else -> "system" }, hapticLevel = s.getInt("style_haptic_level", 60).coerceIn(1, 100),
                sound = s.getString("style_key_sound", null) ?: if (s.getBoolean("style_sound", false)) "system" else "off", soundVolume = s.getInt("style_sound_volume", 100).coerceIn(5, 300),
                wordSwipe = s.getBoolean("gesture_word_swipe", true), cursorSlide = s.getBoolean("gesture_cursor_slide", true),
                layout = s.getString("letter_layout", "qwerty")!!, glide = s.getBoolean("typing_glide", true), autoCorrect = s.getBoolean("typing_autocorrect", true), autoCaps = s.getBoolean("typing_autocaps", true), doubleSpacePeriod = s.getBoolean("double_space_period", true),
                longPressMs = s.getInt("long_press_ms", 350).coerceIn(150, 700), shortcuts = s.getString("text_shortcuts", "").orEmpty(),
            )
        }
    }
}
