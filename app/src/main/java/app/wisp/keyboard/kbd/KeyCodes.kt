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
    const val NUMPAD = -11
    const val SPACE = ' '.code

    /** Long-press menu items that are actions, not characters (private-use code points in a key's "pop" list). */
    const val POP_GLOBE = 0xE000        // switch to another keyboard
    const val POP_LANGUAGE = 0xE001     // next typing language
}

/** How a key is drawn. */
object KeyKind {
    const val LETTER = 0
    const val SPECIAL = 1
    const val SPACE = 2
    const val ACCENT = 3
}
