package app.wisp.keyboard.kbd

/**
 * Rules that no layout, built-in or imported, may break. Today: the switch-keyboard key is always
 * present, so the user can never get trapped in Wisp (design D8).
 */
object LayoutGuard {

    fun ensureSwitchKey(spec: LayoutSpec): LayoutSpec {
        if (spec.rows.any { row -> row.any { !it.spacer && it.code == KeyCodes.SWITCH } }) return spec
        val rows = spec.rows.toMutableList()
        val last = rows.last().toMutableList()
        // Take the width from the widest key (normally space) so the other keys keep their size.
        val widest = last.indices.filter { !last[it].spacer }.maxByOrNull { last[it].weight }
        if (widest != null && last[widest].weight >= 3f) last[widest] = last[widest].withWeight(last[widest].weight - 1f)
        val at = if (last.size > 1) 1 else 0
        last.add(at, KeySpec(KeyCodes.SWITCH, "", "", 1f, KeyKind.SPECIAL, null))
        rows[rows.lastIndex] = last
        return LayoutSpec(spec.name, rows)
    }

    /** Puts a row of digits above the letters (the "number row" option). Layouts that already start with digits are left alone. */
    fun withNumberRow(spec: LayoutSpec): LayoutSpec {
        val first = spec.rows.first().firstOrNull { !it.spacer }
        if (first != null && first.code in '0'.code..'9'.code) return spec
        val digits = "1234567890".map { KeySpec(it.code, it.toString(), it.toString(), 1f, KeyKind.LETTER, null) }
        return LayoutSpec(spec.name, listOf(digits) + spec.rows)
    }

    /** Adds the language key just before the space bar when more than one language is enabled (and the layout has a space bar). */
    fun ensureLanguageKey(spec: LayoutSpec): LayoutSpec {
        if (spec.rows.any { row -> row.any { !it.spacer && it.code == KeyCodes.LANGUAGE } }) return spec
        val rows = spec.rows.toMutableList()
        val last = rows.last().toMutableList()
        val space = last.indexOfFirst { !it.spacer && it.code == KeyCodes.SPACE }
        if (space < 0) return spec
        if (last[space].weight >= 3f) last[space] = last[space].withWeight(last[space].weight - 1f)
        last.add(space, KeySpec(KeyCodes.LANGUAGE, "", "", 1f, KeyKind.SPECIAL, null))
        rows[rows.lastIndex] = last
        return LayoutSpec(spec.name, rows)
    }
}
