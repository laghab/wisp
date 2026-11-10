package app.wisp.keyboard.kbd

import app.wisp.keyboard.R

/** The letter arrangements Wisp ships. The one chosen applies to every Latin-script language. */
object LetterLayouts {
    class Option(val id: String, val name: String, val res: Int, val preview: String)

    val all = listOf(
        Option("qwerty", "QWERTY", R.raw.layout_qwerty, "QWERTYUIOP"),
        Option("azerty", "AZERTY", R.raw.layout_azerty, "AZERTYUIOP"),
        Option("qwertz", "QWERTZ", R.raw.layout_qwertz, "QWERTZUIOP"),
        Option("dvorak", "Dvorak", R.raw.layout_dvorak, "',.PYFGCRL"),
        Option("colemak", "Colemak", R.raw.layout_colemak, "QWFPGJLUY;"),
        Option("workman", "Workman", R.raw.layout_workman, "QDRWBJFUP;"),
    )

    /** The layout file for [id] (QWERTY when unknown). */
    fun res(id: String) = (all.firstOrNull { it.id == id } ?: all.first()).res
}
