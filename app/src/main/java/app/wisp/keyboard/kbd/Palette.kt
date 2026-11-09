package app.wisp.keyboard.kbd

import app.wisp.keyboard.style.Brand

/**
 * Colours the key surface draws with. One instance is shared by all the keyboard's views; the theme system changes its
 * values in place ([set]) and the views redraw, so a theme change never rebuilds the keyboard.
 */
class Palette(
    var background: Int = Brand.DARK.background,
    var key: Int = Brand.DARK.key,
    var keyPressed: Int = Brand.DARK.keyPressed,
    var special: Int = Brand.DARK.special,
    var accent: Int = Brand.CORAL,
    var onAccent: Int = Brand.onAccent(Brand.CORAL),
    var text: Int = Brand.DARK.text,
    var textMuted: Int = Brand.DARK.textMuted,
) {
    fun set(o: Palette) {
        background = o.background; key = o.key; keyPressed = o.keyPressed; special = o.special
        accent = o.accent; onAccent = o.onAccent; text = o.text; textMuted = o.textMuted
    }
}
