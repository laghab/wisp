package app.wisp.keyboard.kbd

/** One candidate word from the dictionary. [type] is the engine's kind plus flags (see the constants below). */
class Candidate(val word: String, val score: Int, val type: Int) {
    val kind get() = type and KIND_MASK
    val safeToAutoApply get() = type and FLAG_APPROPRIATE_FOR_AUTO_CORRECTION != 0

    companion object {
        const val KIND_MASK = 0xFF
        const val KIND_CORRECTION = 1
        const val KIND_COMPLETION = 2
        const val KIND_WHITELIST = 3
        const val FLAG_APPROPRIATE_FOR_AUTO_CORRECTION = 0x10000000
    }
}

/** The dictionary's answer for [word] (exactly as it was typed). [candidates] are best first. */
class SuggestionResult(val word: String, val candidates: List<Candidate>, val typedInDictionary: Boolean)

/** What the suggestion strip shows: up to three words; [highlight] marks the one an auto-correct would apply. */
/** [quoted]: the word exactly as typed, shown in quotation marks (it is not in the dictionary). An empty [text] is an empty slot. */
class BarItem(val text: String, val highlight: Boolean, val quoted: Boolean = false)
/** [predicting]: the words are guesses at the next word, not about a word being typed, so the tools stay one tap away. */
class BarState(val items: List<BarItem>, val composing: Boolean, val predicting: Boolean = false) {
    companion object { val IDLE = BarState(emptyList(), false) }
}

/**
 * When to replace a typed word on its own. Conservative on purpose: a wrong silent change is worse than a missed one,
 * and one backspace always undoes it.
 */
object AutoCorrectPolicy {
    const val MIN_SCORE = 300_000
    const val MIN_MARGIN = 30_000

    fun choose(typed: String, result: SuggestionResult): Candidate? {
        if (typed.length < 2 || result.typedInDictionary) return null
        if (typed.any { !(it.isLowerCase() || it == '\'') }) return null            // names, acronyms, mixed case: leave alone
        val top = result.candidates.firstOrNull() ?: return null
        if (top.word.equals(typed, ignoreCase = true)) return null
        val whitelisted = top.kind == Candidate.KIND_WHITELIST
        if (!whitelisted && (top.kind != Candidate.KIND_CORRECTION || !top.safeToAutoApply)) return null
        if (!whitelisted && top.score < MIN_SCORE) return null
        val second = result.candidates.getOrNull(1)
        if (!whitelisted && second != null && top.score - second.score < MIN_MARGIN) return null
        return top
    }
}

/**
 * What the gesture decoder concluded. [words] are best first. When [confident] is false the best word is not
 * inserted on its own; the strip offers [literal] (the letters the swipe actually passed over) and the best words.
 */
/** [confident]: clearly one word. [plausible]: not certain, but the best word fits the path well enough to type it anyway (the strip offers the others). */
class GestureOutcome(val words: List<String>, val confident: Boolean, val literal: String?, val plausible: Boolean = confident)
