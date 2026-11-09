package app.wisp.keyboard.kbd

/** Letters and symbols use their Unicode code point; functional keys use negative codes. */
object KeyCodes {
    const val SHIFT = -1
    const val BACKSPACE = -2
    const val ENTER = -3
    const val SYMBOLS = -4
    const val SWITCH = -5
    const val LANGUAGE = -6
    const val MIC = -7
    const val EMOJI = -8
    const val ABC = -9
    const val SYMBOLS_MORE = -10
    const val SPACE = ' '.code
}

/** How a key is drawn. */
object KeyKind {
    const val LETTER = 0
    const val SPECIAL = 1
    const val SPACE = 2
    const val ACCENT = 3
}
