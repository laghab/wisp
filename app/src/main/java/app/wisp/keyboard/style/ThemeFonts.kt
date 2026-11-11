package app.wisp.keyboard.style

import android.content.Context
import android.graphics.Typeface

/** Fonts the themes ask for by id. Bundled files live in res/font (`inter_w400`, `space_grotesk_w700`, ...); "sans" is the system font. */
object ThemeFonts {
    private val cache = HashMap<String, Typeface>()

    private val families = mapOf("inter" to "inter", "space grotesk" to "space_grotesk", "chakra petch" to "chakra_petch", "orbitron" to "orbitron", "vt323" to "vt323",
        "bangers" to "bangers", "zen maru gothic" to "zen_maru", "jetbrains mono" to "jetbrains_mono",
        "cormorant garamond" to "cormorant_garamond", "eb garamond" to "eb_garamond", "fredoka" to "fredoka", "quicksand" to "quicksand")

    /** A font from a CSS family list ("'Zen Maru Gothic', sans-serif"): the first family we bundle, else the system font. */
    fun byFamily(context: Context, css: String, weight: Int): Typeface =
        typeface(context, css.split(',').map { it.trim().trim('\'', '"').lowercase() }.firstNotNullOfOrNull { families[it] } ?: "sans", weight)

    fun typeface(context: Context, id: String, weight: Int): Typeface = cache.getOrPut("$id/$weight") {
        val name = id.replace('-', '_')
        val res = if (id == "sans") 0 else listOf(weight, 400, 500, 600, 700).map { context.resources.getIdentifier("${name}_w$it", "font", context.packageName) }.firstOrNull { it != 0 } ?: 0
        val base = if (res != 0) runCatching { context.resources.getFont(res) }.getOrNull() else null
        // A bundled file is one weight already; the system font gets the weight applied.
        if (base != null) base else Typeface.create(Typeface.DEFAULT, weight.coerceIn(100, 900), false)
    }
}
