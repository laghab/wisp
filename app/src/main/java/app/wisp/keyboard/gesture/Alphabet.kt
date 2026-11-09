package app.wisp.keyboard.gesture

import app.wisp.keyboard.kbd.CompiledLayout
import app.wisp.keyboard.kbd.KeyKind

/** The letters a layout can swipe: its letter keys, lower-cased, in code point order. */
fun alphabetOf(layout: CompiledLayout): IntArray =
    (0 until layout.count)
        .filter { layout.kinds[it] == KeyKind.LETTER && Character.isLetter(layout.codes[it]) }
        .map { Character.toLowerCase(layout.codes[it]) }
        .distinct().sorted().toIntArray()

/** Same, from a layout before it has been sized (used to build a language's gesture index ahead of time). */
fun alphabetOf(spec: app.wisp.keyboard.kbd.LayoutSpec): IntArray =
    spec.rows.flatten()
        .filter { !it.spacer && it.kind == KeyKind.LETTER && Character.isLetter(it.code) }
        .map { Character.toLowerCase(it.code) }
        .distinct().sorted().toIntArray()
