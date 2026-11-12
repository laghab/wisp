package app.wisp.keyboard.kbd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputControllerTest {

    /** Committed text plus the word being composed, with a cursor at the end. */
    private class FakeEditor : Editor {
        val committed = StringBuilder()
        var composing = ""
        var caps = 0
        var cursorMoves = 0
        var enters = 0
        val shown get() = committed.toString() + composing
        /** Like an app in the keyboard's own process: it reports the selection change from inside our edit. */
        var onSelectionChanged: (() -> Unit)? = null

        override fun commit(text: CharSequence) { finishComposing(); committed.append(text); caps = if (committed.endsWith(". ")) 1 else 0 }
        override fun setComposing(text: CharSequence) { composing = text.toString() }
        override fun finishComposing() { committed.append(composing); composing = ""; onSelectionChanged?.invoke() }
        override fun backspace() { if (selected > 0) { committed.setLength(committed.length - selected); selected = 0 } else if (composing.isNotEmpty()) composing = composing.dropLast(1) else if (committed.isNotEmpty()) committed.setLength(committed.length - 1) }
        override fun deleteBefore(count: Int) { finishComposing(); committed.setLength((committed.length - count).coerceAtLeast(0)) }
        override fun textBefore(count: Int): CharSequence = shown.takeLast(count)
        var after = ""
        override fun textAfter(count: Int): CharSequence = after.take(count)
        override fun markComposing(word: CharSequence) { composing = word.toString(); committed.setLength(committed.length - word.length) }
        override fun moveCursor(steps: Int) { cursorMoves += steps }
        var selected = 0
        override fun selectBefore(count: Int) { selected = count }
        override fun selectedText(): CharSequence? = null
        override fun enter() { enters++ }
        override fun capsMode(): Int = caps
        override fun selectAll() {}
        override fun cut() {}
        override fun copy() {}
        override fun paste() {}
    }

    private class FakeSuggester : InputController.Suggester {
        val requests = ArrayList<String>()
        val previous = ArrayList<String?>()
        override fun request(word: String, xs: IntArray, ys: IntArray, count: Int, previousWord: String?) { requests.add(word); previous.add(previousWord) }
        override fun cancel() {}
    }

    private var now = 1_000L
    private fun controller(e: FakeEditor, s: FakeSuggester? = null) = InputController(e) { now }.also { it.suggester = s }
    private fun InputController.type(text: String) = text.forEach { onKey(it.code) }
    private fun cand(w: String, score: Int, type: Int = 0x10000001) = Candidate(w, score, type)

    // ---- keys, shift, backspace, cursor (as before)

    @Test fun typesLetters() { val e = FakeEditor(); controller(e).type("hello"); assertEquals("hello", e.shown) }

    @Test fun shiftOnceCapitalisesOneLetter() {
        val e = FakeEditor(); val c = controller(e)
        c.onKey(KeyCodes.SHIFT); c.onKey('a'.code); c.onKey('b'.code)
        assertEquals("Ab", e.shown); assertEquals(Shift.OFF, c.shift)
    }

    @Test fun shiftTappedTwiceQuicklyIsCapsLock() {
        val e = FakeEditor(); val c = controller(e)
        c.onKey(KeyCodes.SHIFT); now += 100; c.onKey(KeyCodes.SHIFT)
        assertEquals(Shift.CAPS, c.shift)
        c.type("ab"); assertEquals("AB", e.shown)
        now += 1000; c.onKey(KeyCodes.SHIFT); assertEquals(Shift.OFF, c.shift)
    }

    @Test fun slowSecondTapTurnsShiftOff() {
        val c = controller(FakeEditor()); c.onKey(KeyCodes.SHIFT); now += 1000; c.onKey(KeyCodes.SHIFT)
        assertEquals(Shift.OFF, c.shift)
    }

    @Test fun autoCapitalisesAfterSentenceEnd() {
        val e = FakeEditor(); val c = controller(e)
        c.type("a. "); assertEquals(Shift.ONCE, c.shift)
        c.type("b"); assertEquals("a. B", e.shown)
    }

    @Test fun fieldStartCapitalisesWhenFieldAsks() {
        val e = FakeEditor(); e.caps = 1; val c = controller(e)
        c.onFieldStarted(); assertEquals(Shift.ONCE, c.shift)
    }

    @Test fun backspaceDeletesThenWordsWhenHeldLong() {
        val e = FakeEditor(); val c = controller(e)
        c.type("one two three ")
        c.onKey(KeyCodes.BACKSPACE); assertEquals("one two three", e.shown)
        c.onRepeat(false); assertEquals("one two thre", e.shown)
        c.onRepeat(true); assertEquals("one two ", e.shown)
        c.onRepeat(true); assertEquals("one ", e.shown)
    }

    private fun swipeSetup(): Pair<FakeEditor, InputController> {
        val e = FakeEditor(); val c = controller(e)
        c.type("see you tomorrow morning")
        c.onKey(KeyCodes.SPACE); now += 1_000   // not a double space later
        e.committed.setLength(e.committed.length - 1)
        return e to c
    }

    @Test fun backspaceSwipeSelectsThenDeletesLetterByLetter() {
        val (e, c) = swipeSetup()
        c.onWordSwipe(1); assertEquals(1, e.selected)
        c.onWordSwipe(3); assertEquals(3, e.selected)
        c.onWordSwipeEnd(3); assertEquals("see you tomorrow morn", e.shown); assertEquals(0, e.selected)
    }

    @Test fun backspaceSwipeBackDeletesFewerLetters() {
        val (e, c) = swipeSetup()
        c.onWordSwipe(5); c.onWordSwipe(3)
        c.onWordSwipeEnd(3); assertEquals("see you tomorrow morn", e.shown)
    }

    @Test fun backspaceSwipeBackToStartDeletesNothing() {
        val (e, c) = swipeSetup()
        c.onWordSwipe(2); c.onWordSwipe(0); c.onWordSwipeEnd(0)
        assertEquals("see you tomorrow morning", e.shown); assertEquals(0, e.selected)
    }

    @Test fun doubleSpaceMakesAPeriod() {
        val e = FakeEditor(); val c = controller(e)
        c.type("done"); c.onKey(KeyCodes.SPACE); now += 200; c.onKey(KeyCodes.SPACE)
        assertEquals("done. ", e.shown)
    }

    @Test fun slowSecondSpaceStaysASpace() {
        val e = FakeEditor(); val c = controller(e)
        c.type("done"); c.onKey(KeyCodes.SPACE); now += 2_000; c.onKey(KeyCodes.SPACE)
        assertEquals("done  ", e.shown)
    }

    @Test fun textShortcutExpandsOnSpace() {
        val e = FakeEditor(); val c = controller(e)
        c.shortcuts = mapOf("omw" to "On my way")
        c.type("omw"); c.onKey(KeyCodes.SPACE)
        assertEquals("On my way ", e.shown)
    }

    @Test fun cursorStepsGoToEditor() {
        val e = FakeEditor(); val c = controller(e)
        c.onCursorSteps(3); c.onCursorSteps(-1); c.onCursorSteps(0)
        assertEquals(2, e.cursorMoves)
    }

    @Test fun enterIsForwarded() { val e = FakeEditor(); controller(e).onKey(KeyCodes.ENTER); assertEquals(1, e.enters) }

    // ---- the word being typed

    @Test fun lettersAreComposedAndRequestSuggestions() {
        val e = FakeEditor(); val s = FakeSuggester(); val c = controller(e, s)
        c.type("te"); assertEquals("te", e.composing); assertEquals(listOf("t", "te"), s.requests)
    }

    @Test fun passwordFieldsCommitDirectlyAndAskNothing() {
        val e = FakeEditor(); val s = FakeSuggester(); val c = controller(e, s); c.suggestionsEnabled = false
        c.type("secret"); assertEquals("", e.composing); assertEquals("secret", e.committed.toString()); assertTrue(s.requests.isEmpty())
    }

    @Test fun obviousTypoIsCorrectedWhenTheWordEnds() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester())
        c.type("teh")
        c.onSuggestions(SuggestionResult("teh", listOf(cand("the", 477_893), cand("ten", 400_787), cand("try", 223_367)), false))
        c.type(" ")
        assertEquals("the ", e.shown)
    }

    @Test fun oneBackspaceTakesTheCorrectionBackAndItIsNotRepeated() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester())
        c.type("teh")
        val r = SuggestionResult("teh", listOf(cand("the", 477_893), cand("ten", 400_787)), false)
        c.onSuggestions(r); c.type(" ")
        assertEquals("the ", e.shown)
        c.onKey(KeyCodes.BACKSPACE)
        assertEquals("teh", e.shown); assertEquals("teh", e.composing)
        c.onSuggestions(r); c.type(" ")
        assertEquals("teh ", e.shown)               // the user said "as typed": not corrected again
    }

    @Test fun noCorrectionWhenTheWordIsInTheDictionary() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester())
        c.type("hello"); c.onSuggestions(SuggestionResult("hello", listOf(cand("hello", 1_700_000, 0x70000001), cand("jello", 239_842)), true)); c.type(" ")
        assertEquals("hello ", e.shown)
    }

    @Test fun noCorrectionWhenAutoCorrectIsOff() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester()); c.autoCorrect = false
        c.type("teh"); c.onSuggestions(SuggestionResult("teh", listOf(cand("the", 477_893)), false)); c.type(" ")
        assertEquals("teh ", e.shown)
    }

    @Test fun capitalisedWordsAreNeverCorrected() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester())
        c.onKey(KeyCodes.SHIFT); c.type("raghav")
        c.onSuggestions(SuggestionResult("Raghav", listOf(cand("Raghavan", 400_000)), false)); c.type(" ")
        assertEquals("Raghav ", e.shown)
    }

    @Test fun closeCallsAreNotCorrected() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester())
        c.type("thnks")   // thanks 328910 vs thinks 312637: too close to call
        c.onSuggestions(SuggestionResult("thnks", listOf(cand("thanks", 328_910), cand("thinks", 312_637)), false)); c.type(" ")
        assertEquals("thnks ", e.shown)
    }

    @Test fun staleSuggestionsAreIgnored() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester())
        c.type("teh"); c.type("n")   // now "tehn"
        c.onSuggestions(SuggestionResult("teh", listOf(cand("the", 477_893)), false))
        c.type(" ")
        assertEquals("tehn ", e.shown)
    }

    @Test fun tappingASuggestionReplacesTheWordAndAddsASpace() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester()); var bar = BarState.IDLE; c.onBarChanged = { bar = it }
        c.type("wrold")
        c.onSuggestions(SuggestionResult("wrold", listOf(cand("world", 333_451), cand("stood", 189_149)), false))
        assertEquals(listOf("wrold", "world", "stood"), bar.items.map { it.text })
        assertTrue(bar.items[1].highlight); assertFalse(bar.items[0].highlight)
        c.onSuggestionTapped(1)
        assertEquals("world ", e.shown)
    }

    @Test fun tappingTheTypedWordKeepsIt() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester())
        c.type("wrold"); c.onSuggestions(SuggestionResult("wrold", listOf(cand("world", 333_451)), false))
        c.onSuggestionTapped(0)
        assertEquals("wrold ", e.shown)
    }

    @Test fun barIsIdleWhenNothingIsBeingTyped() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester()); var bar: BarState? = null; c.onBarChanged = { bar = it }
        c.type("a "); assertFalse(bar!!.composing); assertTrue(bar!!.items.isEmpty())
    }

    @Test fun nextRequestKnowsThePreviousWord() {
        val e = FakeEditor(); val s = FakeSuggester(); val c = controller(e, s)
        c.type("how "); c.type("a")
        assertEquals("how", s.previous.last())
        c.type(". "); c.type("b"); assertNull(s.previous.last())     // a sentence end forgets the context
    }

    @Test fun aRestartOfTheSameFieldKeepsTheUndoOfTheLastCorrection() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester())
        c.type("teh"); c.onSuggestions(SuggestionResult("teh", listOf(cand("the", 477_893)), false)); c.type(" ")
        c.onFieldStarted(restarting = true)
        c.onKey(KeyCodes.BACKSPACE)
        assertEquals("teh", e.shown)
    }

    // ---- swipe typing

    private fun outcome(vararg words: String, confident: Boolean = true, literal: String? = null) = GestureOutcome(words.toList(), confident, literal)

    @Test fun aConfidentSwipeInsertsTheWordAndASpace() {
        val e = FakeEditor(); val c = controller(e); var bar = BarState.IDLE; c.onBarChanged = { bar = it }
        c.onGestureOutcome(outcome("hello", "help", "held"))
        assertEquals("hello ", e.shown)
        assertEquals(listOf("hello", "help", "held"), bar.items.map { it.text })
        assertTrue(bar.items[0].highlight)
    }

    @Test fun swipedWordsFollowEachOtherWithOneSpace() {
        val e = FakeEditor(); val c = controller(e)
        c.onGestureOutcome(outcome("hello")); c.onGestureOutcome(outcome("world"))
        assertEquals("hello world ", e.shown)
    }

    @Test fun aSwipeAfterTypedTextGetsALeadingSpace() {
        val e = FakeEditor(); val c = controller(e)
        c.type("hi"); c.onGestureOutcome(outcome("there"))
        assertEquals("hi there ", e.shown)
    }

    @Test fun tappingAnAlternativeReplacesTheSwipedWord() {
        val e = FakeEditor(); val c = controller(e); var bar = BarState.IDLE; c.onBarChanged = { bar = it }
        c.onGestureOutcome(outcome("hello", "help", "held"))
        c.onSuggestionTapped(1)
        assertEquals("help ", e.shown)
        assertTrue(bar.items[1].highlight)
        c.onSuggestionTapped(2)
        assertEquals("held ", e.shown)
    }

    @Test fun oneBackspaceRemovesTheWholeSwipedWord() {
        val e = FakeEditor(); val c = controller(e)
        c.type("so "); c.onGestureOutcome(outcome("hello"))
        assertEquals("so hello ", e.shown)
        c.onKey(KeyCodes.BACKSPACE)
        assertEquals("so ", e.shown)
        c.onKey(KeyCodes.BACKSPACE)       // the next backspace is an ordinary one
        assertEquals("so", e.shown)
    }

    @Test fun punctuationAfterASwipedWordTakesItsTrailingSpace() {
        val e = FakeEditor(); val c = controller(e)
        c.onGestureOutcome(outcome("hello")); c.type(".")
        assertEquals("hello.", e.shown)
    }

    @Test fun aSwipeCapitalisesWhenShiftIsOn() {
        val e = FakeEditor(); val c = controller(e)
        c.onKey(KeyCodes.SHIFT); c.onGestureOutcome(outcome("hello"))
        assertEquals("Hello ", e.shown); assertEquals(Shift.OFF, c.shift)
    }

    @Test fun anUnsureSwipeStillTypesTheBestGuessAndOffersTheLiteralLetters() {
        val e = FakeEditor(); val c = controller(e); var bar = BarState.IDLE; c.onBarChanged = { bar = it }
        c.onGestureOutcome(outcome("rag", "raga", confident = false, literal = "raghav"))
        assertEquals("rag ", e.shown)
        assertEquals(listOf("raghav", "rag", "raga"), bar.items.map { it.text })
        c.onSuggestionTapped(0)
        assertEquals("raghav ", e.shown)
    }

    @Test fun aSwipeEndsAWordBeingTyped() {
        val e = FakeEditor(); val c = controller(e)
        c.type("hel"); c.onGestureOutcome(outcome("world"))
        assertEquals("hel world ", e.shown)
    }

    @Test fun typingAfterASwipeForgetsItsAlternatives() {
        val e = FakeEditor(); val c = controller(e); var bar = BarState.IDLE; c.onBarChanged = { bar = it }
        c.onGestureOutcome(outcome("hello", "help")); c.type("a")
        assertTrue(bar.items.none { it.text == "help" })
        c.onKey(KeyCodes.BACKSPACE); c.onKey(KeyCodes.BACKSPACE)
        assertEquals("hello", e.shown)     // ordinary backspaces now, not whole-word removal
    }

    // ---- learning

    private class FakeLearner : InputController.Learner {
        val seen = ArrayList<Triple<String, String?, Boolean>>()
        override fun learn(word: String, previousWord: String?, strong: Boolean) { seen.add(Triple(word, previousWord, strong)) }
    }

    @Test fun wordsAreLearnedWithTheirPredecessor() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester()); val l = FakeLearner(); c.learner = l
        c.type("good "); c.type("morning ")
        assertEquals(listOf(Triple("good", null, false), Triple("morning", "good", false)), l.seen)
    }

    @Test fun nothingIsLearnedWhenLearningIsOff() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester()); val l = FakeLearner(); c.learner = l; c.learningEnabled = false
        c.type("secret "); c.onGestureOutcome(outcome("hello"))
        assertTrue(l.seen.isEmpty())
    }

    @Test fun nothingIsLearnedWhereSuggestionsAreOff() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester()); val l = FakeLearner(); c.learner = l; c.suggestionsEnabled = false
        c.type("password1 ")
        assertTrue(l.seen.isEmpty())
    }

    @Test fun takingACorrectionBackIsRememberedButNotYetDecisive() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester()); val l = FakeLearner(); c.learner = l
        c.type("teh"); c.onSuggestions(SuggestionResult("teh", listOf(cand("the", 477_893)), false)); c.type(" ")
        c.onKey(KeyCodes.BACKSPACE); c.type(" ")
        assertEquals(Triple("teh", null, false), l.seen.last())
    }

    @Test fun pickingANonFirstSuggestionIsAStrongSignal() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester()); val l = FakeLearner(); c.learner = l
        c.type("wrold"); c.onSuggestions(SuggestionResult("wrold", listOf(cand("world", 333_451), cand("stood", 189_149)), false))
        c.onSuggestionTapped(2)
        assertEquals(Triple("stood", null, true), l.seen.last())
    }

    @Test fun swipedWordsAreLearned() {
        val e = FakeEditor(); val c = controller(e); val l = FakeLearner(); c.learner = l
        c.onGestureOutcome(outcome("hello")); c.onGestureOutcome(outcome("world"))
        assertEquals(listOf(Triple("hello", null, false), Triple("world", "hello", false)), l.seen)
    }

    // ---- a swipe that failed: deleted, then typed by hand

    @Test fun typingTheWordAfterDeletingASwipeTeachesTheDecoder() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester()); val l = FakeLearner(); c.learner = l
        var rejected = 0; val corrected = ArrayList<String>()
        c.onGestureRejected = { rejected++ }; c.onGestureCorrected = { corrected.add(it) }
        c.onGestureOutcome(outcome("world"))
        c.onKey(KeyCodes.BACKSPACE)                       // the swipe was wrong
        c.type("wild "); 
        assertEquals(1, rejected); assertEquals(listOf("wild"), corrected)
        assertEquals(Triple("wild", null, true), l.seen.last())     // and the word counts as a deliberate choice
    }

    @Test fun typingTheSameWordAgainIsNotACorrection() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester()); val corrected = ArrayList<String>(); c.onGestureCorrected = { corrected.add(it) }
        c.onGestureOutcome(outcome("world")); c.onKey(KeyCodes.BACKSPACE); c.type("world ")
        assertTrue(corrected.isEmpty())
    }

    @Test fun onlyTheFirstWordAfterTheDeletionCounts() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester()); val corrected = ArrayList<String>(); c.onGestureCorrected = { corrected.add(it) }
        c.onGestureOutcome(outcome("world")); c.onKey(KeyCodes.BACKSPACE); c.type("wild "); c.type("again ")
        assertEquals(listOf("wild"), corrected)
    }

    @Test fun aRestartOfTheSameFieldKeepsTheLink() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester()); val corrected = ArrayList<String>(); c.onGestureCorrected = { corrected.add(it) }
        c.onGestureOutcome(outcome("world")); c.onKey(KeyCodes.BACKSPACE); c.onFieldStarted(restarting = true); c.type("wild ")
        assertEquals(listOf("wild"), corrected)
    }

    @Test fun movingTheCursorBreaksTheLink() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester()); val corrected = ArrayList<String>(); c.onGestureCorrected = { corrected.add(it) }
        c.onGestureOutcome(outcome("world")); c.onKey(KeyCodes.BACKSPACE); c.onCursorSteps(-1); c.type("wild ")
        assertTrue(corrected.isEmpty())
    }

    @Test fun deletingANormalWordIsNotARejectedSwipe() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester()); var rejected = 0; c.onGestureRejected = { rejected++ }
        c.type("hello "); c.onKey(KeyCodes.BACKSPACE); c.onKey(KeyCodes.BACKSPACE)
        assertEquals(0, rejected)
    }

    @Test fun nothingIsReportedWhereLearningIsOff() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester()); c.learningEnabled = false; var rejected = 0; c.onGestureRejected = { rejected++ }
        c.onGestureOutcome(outcome("world")); c.onKey(KeyCodes.BACKSPACE)
        assertEquals(0, rejected)
    }

    @Test fun selectionReportsFromInsideOurOwnEditsAreIgnored() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester()); val corrected = ArrayList<String>(); c.onGestureCorrected = { corrected.add(it) }
        e.onSelectionChanged = { c.onExternalCursorMove() }              // what WispIme.onUpdateSelection does
        c.onGestureOutcome(outcome("world")); c.onKey(KeyCodes.BACKSPACE); c.type("wild ")
        assertEquals("wild ", e.shown); assertEquals(listOf("wild"), corrected)
    }

    @Test fun aRealCursorMoveBetweenEditsStillResetsTheWord() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester())
        c.type("hel"); c.onExternalCursorMove()
        assertEquals("", e.composing)
    }

    @Test fun capsPattern() {
        assertEquals("The", InputController.applyCase("Teh", "the"))
        assertEquals("THE", InputController.applyCase("TEH", "the"))
        assertEquals("the", InputController.applyCase("teh", "the"))
    }

    @Test fun movingTheCursorEndsTheWordWithoutCorrecting() {
        val e = FakeEditor(); val c = controller(e, FakeSuggester())
        c.type("teh"); c.onSuggestions(SuggestionResult("teh", listOf(cand("the", 477_893)), false))
        c.onCursorSteps(-1)
        assertEquals("teh", e.shown); assertEquals("", e.composing)
    }

    // ---- dictation

    @Test fun undoDictationOfferGoesAwayOnAKeyOrWhenTheKeyboardCloses() {
        val e = FakeEditor(); val c = controller(e); var bar = BarState.IDLE; c.onBarChanged = { bar = it }
        c.onDictation("hello there")
        assertEquals("Undo dictation", bar.items.single().text)
        c.onKey(' '.code)
        assertEquals(BarState.IDLE, bar)
        c.onDictation("again"); c.dropDictation()
        assertEquals(BarState.IDLE, bar)
        val e2 = FakeEditor(); val c2 = controller(e2); c2.onDictation("gone"); c2.onSuggestionTapped(0)
        assertEquals("", e2.shown)
    }

    // ---- tapping into existing text

    @Test fun cursorAtTheEndOfAWordMakesItTheWordBeingChecked() {
        val e = FakeEditor(); e.committed.append("see teh"); val s = FakeSuggester(); val c = controller(e, s)
        now += 1_000; c.adoptWordBeforeCursor()
        assertEquals("teh", e.composing); assertEquals(listOf("teh"), s.requests); assertEquals("see", s.previous.last())
        assertTrue(c.isComposing)
    }

    @Test fun nothingIsAdoptedInTheMiddleOfAWordAfterASpaceOrRightAfterTyping() {
        val mid = FakeEditor(); mid.committed.append("see teh"); mid.after = "ing"; val c1 = controller(mid, FakeSuggester()); now += 1_000; c1.adoptWordBeforeCursor()
        assertFalse(c1.isComposing)
        val spaced = FakeEditor(); spaced.committed.append("see teh "); val c2 = controller(spaced, FakeSuggester()); now += 1_000; c2.adoptWordBeforeCursor()
        assertFalse(c2.isComposing)
        val typing = FakeEditor(); val c3 = controller(typing, FakeSuggester()); c3.type("hi "); c3.adoptWordBeforeCursor()
        assertFalse(c3.isComposing)
    }

    @Test fun correctingAWordInTheMiddleOfTextKeepsItsSpaceAndDoesNotLearnTheWordAsTyped() {
        val e = FakeEditor(); e.committed.append("see teh"); e.after = " cat"; val c = controller(e, FakeSuggester())
        now += 1_000; c.adoptWordBeforeCursor()
        c.onSuggestions(SuggestionResult("teh", listOf(cand("the", 477_893)), false))
        c.onSuggestionTapped(1)
        assertEquals("see the", e.shown)
    }
}
