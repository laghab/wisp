package app.wisp.keyboard.voice

import app.wisp.keyboard.voice.text.LoopGuard
import org.junit.Assert.assertEquals
import org.junit.Test

class LoopGuardTest {
    private val clause = "Ask not what your country can do for you."

    @Test fun aLoopedClauseIsCutToOneCopy() =
        assertEquals(clause, LoopGuard.clean("$clause $clause $clause $clause Ask not what your", 3.4f))

    @Test fun twoCopiesOfALongClauseCountAsALoop() = assertEquals(clause, LoopGuard.clean("$clause $clause", 6f))

    @Test fun anOrdinarySentenceIsUntouched() = assertEquals("This is a completely normal sentence, with ordinary words.", LoopGuard.clean("This is a completely normal sentence, with ordinary words.", 5f))

    @Test fun realRepeatsStay() {
        assertEquals("no no no", LoopGuard.clean("no no no", 2f))
        assertEquals("it was very very good", LoopGuard.clean("it was very very good", 3f))
        assertEquals("bye bye", LoopGuard.clean("bye bye", 1f))
    }

    @Test fun aLongRunOfOneWordIsCollapsed() = assertEquals("yes", LoopGuard.clean("yes yes yes yes yes yes", 3f))

    @Test fun casingAndPunctuationDoNotHideALoop() = assertEquals("Thank you.", LoopGuard.clean("Thank you. thank you, Thank you thank you", 3f).let { it })

    @Test fun moreWordsThanCouldBeSaidAreCapped() = assertEquals(4, LoopGuard.clean("a b c d e f g h i j k l m n", 0.3f).split(' ').size)

    @Test fun emptyStaysEmpty() = assertEquals("", LoopGuard.clean("   ", 2f))

    @Test fun twoDifferentSentencesAreNotALoop() = assertEquals("$clause Ask what you can do for your country.", LoopGuard.clean("$clause Ask what you can do for your country.", 6f))
}
