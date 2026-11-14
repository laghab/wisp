package app.wisp.keyboard.kbd

/** Shift key state. */
enum class Shift { OFF, ONCE, CAPS }

/**
 * Turns key presses into edits on the [Editor]: letters and symbols, shift with caps lock and
 * auto-capitalisation, backspace with accelerating repeat, space-bar cursor slide, and the word being
 * typed (composing text, suggestions, reversible auto-correct).
 */
class InputController(private val editor: Editor, private val clock: () -> Long = System::currentTimeMillis) {

    /** Where suggestions come from; the answer comes back through [onSuggestions] (possibly on another turn of the event loop). */
    interface Suggester {
        fun request(word: String, xs: IntArray, ys: IntArray, count: Int, previousWord: String?)
        fun cancel()
    }

    var shift = Shift.OFF
        private set
    var onShiftChanged: ((Shift) -> Unit)? = null
    var onBarChanged: ((BarState) -> Unit)? = null

    /** Receives the words the user actually used, to learn from them (see personal/PersonalDictionary). */
    interface Learner { fun learn(word: String, previousWord: String?, strong: Boolean) }

    var suggester: Suggester? = null
    var learner: Learner? = null
    /** False in fields and modes where nothing may be learned (passwords, no-learning fields, incognito, excluded apps). */
    var learningEnabled = true

    /** Tells the swipe decoder which word the user's last swipe turned out to be ([strong]: they chose it from the alternatives). */
    var onGestureWord: ((word: String, strong: Boolean) -> Unit)? = null

    /** The user deleted a swiped word straight away: that swipe was wrong. */
    var onGestureRejected: (() -> Unit)? = null
    /** ...and then typed (or picked) [word] instead: that is what the rejected swipe was meant to be. */
    var onGestureCorrected: ((word: String) -> Unit)? = null
    val isComposing get() = composer.isNotEmpty
    /** False in fields that must not be suggested for or learned from (passwords). Letters are then committed directly. */
    var suggestionsEnabled = true
    var autoCorrect = true
    /** Capitalise the first letter of a sentence when the field asks for it. */
    var autoCapitalize = true
    /** A swiped word was inserted on its own (counted for the home screen). */
    var onGestureInserted: (() -> Unit)? = null

    private var lastShiftTapAt = 0L
    private var editing = 0
    private var lastEditAt = 0L
    private inline fun <T> edit(block: () -> T): T { editing++; lastEditAt = clock(); try { return block() } finally { editing-- } }
    private val composer = WordComposer()
    private var previousWord: String? = null
    /** The last finished word before the cursor (lower case), for anything that ranks the next word by what came before. */
    val wordBefore get() = previousWord
    private var result: SuggestionResult? = null
    private var correction: Correction? = null
    private var keepAsTyped: String? = null     // a word whose auto-correction the user just took back

    // Swipe typing: the last swiped word we inserted (so one backspace can remove all of it) and the words the strip offers.
    private var lastGesture: String? = null
    private var gestureItems: List<String>? = null
    private var gestureInserted = false
    private var gestureSelected = 0

    // After a swiped word is deleted at once, the next word the user types is probably what they meant.
    private var rejectedSwipe: String? = null
    private var gesturePrevious: String? = null     // the word before the swiped one, to go back to if the swiped word is replaced or deleted

    private class Correction(val original: String, val applied: String, val separator: String, val previousBefore: String? = null)

    /**
     * Call when a field gets focus, so the first letter capitalises if the field wants it. A restart of the same field
     * (apps do this often) keeps what we remember about the last word: taking an auto-correction back is verified
     * against the text before the cursor, so a stale memory is harmless.
     */
    fun onFieldStarted(restarting: Boolean = false) {
        if (!restarting) { clearGesture(); forgetRejection() }      // a restart of the same field keeps these: they are checked against the real text before use
        if (restarting) {
            if (composer.isNotEmpty) resetWord(clearCorrection = false)
        } else {
            resetWord(); previousWord = null; correction = null
            shift = Shift.OFF
        }
        autoShift()
        publishBar()
    }

    // After a word and its space, the strip offers what usually comes next. Only for the word still right before the cursor.
    private var predictions: List<String> = emptyList()
    private var predictingAfter: String? = null

    private fun predict() {
        predictions = emptyList(); predictingAfter = null
        val prev = previousWord ?: return
        if (!suggestionsEnabled || composer.isNotEmpty) return
        predictingAfter = prev
        suggester?.request("", composer.xs, composer.ys, 0, prev)
    }

    private fun dropPredictions() { predictingAfter = null; if (predictions.isNotEmpty()) { predictions = emptyList(); publishBar() } }

    /** Whether the word the predictions were made for is still what stands before the cursor. */
    private fun predictionsFit(): Boolean {
        val prev = predictingAfter ?: return false
        return editor.textBefore(prev.length + 1)?.toString().equals("$prev ", ignoreCase = true)
    }

    private var adoptedAs: String? = null    // the word taken from the text as it was; learning it only happens once the user changes it
    private var touching = false             // the adopted word has letters after the cursor: any key lets go of it and types plainly

    /** The IME asks again for the word at the cursor a moment after this is called (a key was typed inside a word). */
    var onAdoptLater: (() -> Unit)? = null

    /**
     * The cursor came to rest somewhere (a tap in the text, a field gaining focus, a slide along the space bar). If it touches a word, at its
     * end or inside it, that word becomes the one being checked: the strip shows it with its corrections, and tapping one replaces the whole
     * word. Skipped while typing, with a selection, and right after the keyboard's own edits. Returns whether a word was taken.
     */
    fun adoptWordAtCursor(): Boolean {
        if (predictions.isNotEmpty() && !predictionsFit()) dropPredictions()      // the cursor went somewhere else
        if (!suggestionsEnabled || composer.isNotEmpty || editing > 0 || dictation != null || clock() - lastEditAt < ADOPT_QUIET_MS) return false
        return adopt()
    }

    private fun adopt(): Boolean {
        if (editor.selectedText() != null) return false
        val before = editor.textBefore(WORD_SCAN)?.toString() ?: return false
        var start = before.length
        while (start > 0) { val cp = before.codePointBefore(start); if (!isWordChar(cp, start < before.length)) break; start -= Character.charCount(cp) }
        while (start < before.length && before[start] == '\'') start++
        val after = editor.textAfter(WordComposer.MAX)?.toString().orEmpty()
        var end = 0
        while (end < after.length) { val cp = after.codePointAt(end); if (!isWordChar(cp, start < before.length || end > 0)) break; end += Character.charCount(cp) }
        while (end > 0 && after[end - 1] == '\'') end--
        val word = before.substring(start) + after.substring(0, end)
        if (word.codePointCount(0, word.length) !in 2..WordComposer.MAX) return false
        if (!editor.markComposing(before.length - start, end)) return false
        clearGesture(); correction = null; keepAsTyped = null
        previousWord = before.substring(0, start).trimEnd().takeLastWhile { isWordChar(it.code, true) }.lowercase().ifEmpty { null }
        composer.set(word); result = null; adoptedAs = word; touching = end > 0
        suggester?.request(word, composer.xs, composer.ys, composer.size, previousWord)
        publishBar()
        return true
    }

    /** True while the word being checked has the cursor inside it, not at its end. */
    val isTouching get() = touching

    /** A key is about to act inside a word: the word stops being the one checked, and is looked at again once the typing pauses. */
    private fun letGo() {
        if (!touching) return
        editor.finishComposing(); resetWord(); publishBar()
        onAdoptLater?.invoke()
    }

    /** Ends the word being typed as it is (no correction), e.g. before the language changes. */
    fun finishWord() { endWord(null, allowCorrection = false) }

    /** The text cursor moved outside our control (the user tapped in the text): forget the word being typed. */
    fun onExternalCursorMove() {
        // When the keyboard and the app share a process, Android reports the selection change of our own edit from inside that edit.
        if (editing > 0) return
        clearGesture(); forgetRejection(); if (composer.isNotEmpty) { resetWord(); publishBar() } else publishBar() }

    /** Words that expand when followed by a space ("omw" to "On my way"). */
    var shortcuts: Map<String, String> = emptyMap()
    var doubleSpacePeriod = true
    private var lastSpaceAt = 0L

    private fun doubleSpace(): Boolean {
        val now = clock()
        val quick = now - lastSpaceAt <= DOUBLE_SPACE_MS
        lastSpaceAt = now
        if (!doubleSpacePeriod || !quick || composer.isNotEmpty) return false
        val t = editor.textBefore(2)?.toString() ?: return false
        if (t.length < 2 || t[1] != ' ' || !(t[0].isLetterOrDigit() || t[0] == ')' || t[0] == '"')) return false
        editor.deleteBefore(1)
        editor.commit(". ")
        correction = null; previousWord = null; lastSpaceAt = 0L
        autoShift()
        return true
    }

    fun onKey(code: Int, x: Int = -1, y: Int = -1) = edit {
        if (code != KeyCodes.SHIFT) { dropDictation(); letGo() }      // any key (a space too) means the dictation is kept
        if (code == KeyCodes.SPACE && doubleSpace()) return@edit
        if (code == KeyCodes.SPACE && composer.isNotEmpty) shortcuts[composer.text]?.let { composer.set(it); editor.setComposing(it) }
        when {
            code == KeyCodes.SHIFT -> tapShift()
            code == KeyCodes.BACKSPACE -> backspace()
            code == KeyCodes.ENTER -> { endWord(null, allowCorrection = true); clearGesture(); forgetRejection(); editor.enter(); previousWord = null; correction = null; autoShift() }
            code > 0 -> typeCodePoint(code, x, y)
            // SYMBOLS, SWITCH, EMOJI, MIC ... are handled by the IME itself.
        }
    }

    /** Held backspace: one character per repeat, or a whole word once the touch machine says the hold has run long. */
    fun onRepeat(word: Boolean) = edit {
        letGo()
        if (composer.isNotEmpty) { if (word) clearWord() else backspace(); return@edit }
        if (word) editor.deleteBefore(wordChars(1)) else editor.backspace()
        correction = null
        autoShift()
    }

    private var wordSwiping = false
    /** The text before the cursor when the swipe began: once words are selected, the editor's "before" shrinks with them. */
    private var swipeBefore: CharSequence = ""

    /** A backspace swipe now covers [chars] characters before the cursor, one per finger step: show them selected (0 clears the selection). */
    fun onWordSwipe(chars: Int) = edit {
        if (!wordSwiping) {
            endWord(null, allowCorrection = false); clearGesture(); forgetRejection()
            wordSwiping = true; swipeBefore = editor.textBefore(WORD_SCAN) ?: ""
        }
        editor.selectBefore(unitsBack(chars, swipeBefore))
    }

    /** The finger lifted: delete what is selected, or nothing when the swipe came back to its start. */
    fun onWordSwipeEnd(chars: Int) = edit {
        if (!wordSwiping) return@edit
        wordSwiping = false
        if (chars > 0) { editor.backspace(); correction = null; autoShift() } else editor.selectBefore(0)
    }

    /** Shift held, finger slid onto a letter: that letter once in capitals, shift state unchanged. */
    fun onShiftLetter(code: Int, x: Int, y: Int) = edit {
        val keep = shift
        setShift(Shift.ONCE)
        onKey(code, x, y)
        if (shift == Shift.ONCE) setShift(keep)
    }

    fun onCursorSteps(steps: Int) = edit {
        if (steps == 0) return@edit
        clearGesture(); forgetRejection()
        endWord(null, allowCorrection = false)
        editor.moveCursor(steps)
    }

    fun selectAll() = edit { endWord(null, false); editor.selectAll() }
    fun cut() = edit { endWord(null, false); editor.cut() }
    fun copy() = edit { endWord(null, false); editor.copy() }
    fun paste() = edit { endWord(null, false); editor.paste(); autoShift() }
    fun undo() = edit { endWord(null, false); clearGesture(); editor.undo(); autoShift() }
    fun redo() = edit { endWord(null, false); clearGesture(); editor.redo(); autoShift() }

    /** The dictionary's answer. Ignored when it is for a word that is no longer being typed. */
    fun onSuggestions(r: SuggestionResult) {
        if (composer.isEmpty) {
            if (r.word.isEmpty() && predictingAfter != null && gestureItems == null && dictation == null) { predictions = r.candidates.take(3).map { it.word }; publishBar() }
            return
        }
        if (r.word != composer.text) return
        result = r
        publishBar()
    }

    /** The user tapped item [index] of the suggestion strip. */
    fun onSuggestionTapped(index: Int) = edit {
        if (composer.isEmpty && dictation != null) { undoDictation(); return@edit }
        if (composer.isEmpty && gestureItems != null) { onGestureItemTapped(index); return@edit }
        if (composer.isEmpty) {
            val word = predictions.getOrNull(intArrayOf(1, 0, 2).getOrElse(index) { -1 }) ?: return@edit      // the best one is in the middle slot
            if (!predictionsFit()) { dropPredictions(); return@edit }
            editor.commit("$word ")
            learn(word, previousWord, strong = false)
            previousWord = word.lowercase(); correction = null
            predict(); publishBar(); autoShift()
            return@edit
        }
        val item = currentItems().getOrNull(index)?.takeIf { it.text.isNotEmpty() } ?: return
        val typed = composer.text
        editor.setComposing(item.text)
        editor.finishComposing()
        val next = editor.textAfter(1)?.firstOrNull()
        val alreadySpaced = adoptedAs != null && next != null && (next.isWhitespace() || !next.isLetterOrDigit())      // correcting a word in the middle of text: its space is already there
        if (!alreadySpaced) editor.commit(" ")
        val before = previousWord
        val retyped = completeRetype(item.text)
        learn(item.text, before, strong = retyped || index != 0 || item.text == typed)
        previousWord = item.text.lowercase()
        correction = if (item.text != typed) Correction(typed, item.text, " ", before) else null
        if (item.text == typed) keepAsTyped = typed
        resetWord(clearCorrection = false)
        predict()
        publishBar()
        autoShift()
    }

    // ------------------------------------------------------------------ typing

    private fun typeCodePoint(code: Int, x: Int, y: Int) {
        val c = if (shift != Shift.OFF) Character.toUpperCase(code) else code
        if (suggestionsEnabled && composer.isEmpty && isWordChar(c, false) && editor.textAfter(1)?.firstOrNull()?.isLetter() == true) {
            // In front of or inside a word: the letter goes in as it is, and the whole word is checked once the typing pauses.
            clearGesture(); correction = null
            editor.commit(String(Character.toChars(c)))
            onAdoptLater?.invoke()
        } else if (suggestionsEnabled && isWordChar(c, composer.isNotEmpty)) {
            clearGesture()
            if (composer.isEmpty && editor.textBefore(1)?.lastOrNull()?.isLetter() == true) adopt()      // carrying on a word that is already there: it is that word being typed
            composer.add(c, x, y)
            correction = null
            editor.setComposing(composer.text)
            result = null
            suggester?.request(composer.text, composer.xs, composer.ys, composer.size, previousWord)
            publishBar()
        } else {
            val sep = String(Character.toChars(c))
            if (lastGesture != null && CLOSING_PUNCTUATION.indexOf(c.toChar()) >= 0 && editor.textBefore(1)?.toString() == " ") editor.deleteBefore(1)   // "word ." becomes "word."
            clearGesture()
            endWord(sep, allowCorrection = true)
            if (isSentenceEnd(c)) previousWord = null
        }
        if (shift == Shift.ONCE) setShift(Shift.OFF)
        autoShift()
    }

    private fun backspace() {
        dropPredictions()
        if (composer.isNotEmpty) {
            composer.backspace()
            result = null
            if (composer.isEmpty) { editor.setComposing(""); editor.finishComposing(); suggester?.cancel() }
            else { editor.setComposing(composer.text); suggester?.request(composer.text, composer.xs, composer.ys, composer.size, previousWord) }
            publishBar(); autoShift()
            return
        }
        val g = lastGesture
        if (g != null) {
            clearGesture()
            if (editor.textBefore(g.length)?.toString() == g) {
                editor.deleteBefore(g.length); publishBar(); autoShift()
                previousWord = gesturePrevious
                if (learningEnabled && suggestionsEnabled) { rejectedSwipe = g.trim(); onGestureRejected?.invoke() }
                return
            }
        }
        val c = correction
        correction = null
        if (c != null) {
            val expected = c.applied + c.separator
            if (editor.textBefore(expected.length)?.toString() == expected) {
                // Take the auto-correction back: the original word comes back as the word being typed.
                editor.deleteBefore(expected.length)
                previousWord = c.previousBefore
                composer.set(c.original)
                keepAsTyped = c.original
                editor.setComposing(c.original)
                suggester?.request(c.original, composer.xs, composer.ys, composer.size, previousWord)
                publishBar()
                return
            }
        }
        editor.backspace()
        autoShift()
    }

    /**
     * Ends the word being typed. If [allowCorrection] and the dictionary is sure, the word is replaced first. [separator]
     * (a space or punctuation mark) is then committed.
     */
    private fun endWord(separator: String?, allowCorrection: Boolean) {
        if (composer.isEmpty) {
            if (separator != null) { editor.commit(separator); correction = null }
            return
        }
        val typed = composer.text
        val r = result
        val target = if (allowCorrection && autoCorrect && r != null && keepAsTyped != typed && adoptedAs != typed) AutoCorrectPolicy.choose(typed, r) else null      // a word that was already there is only changed when the user picks a suggestion
        val finalWord = if (target != null) applyCase(typed, target.word) else typed
        if (target != null) editor.setComposing(finalWord)
        editor.finishComposing()
        val before = previousWord
        // Taking a correction back with backspace is a hint, not a decision: it needs a second use before the word becomes the user's own.
        // Typing a word right after deleting a swiped one is a decision, though: they meant exactly this.
        val retyped = completeRetype(finalWord)
        if (adoptedAs != finalWord) learn(finalWord, before, strong = retyped)
        previousWord = finalWord.lowercase()
        correction = if (target != null && separator != null) Correction(typed, finalWord, separator, before) else null
        resetWord(clearCorrection = false)
        if (separator != null) editor.commit(separator)
        if (separator == " ") predict()
        publishBar()
    }

    private fun clearWord() { editor.setComposing(""); editor.finishComposing(); resetWord(); publishBar() }

    private fun resetWord(clearCorrection: Boolean = true) {
        if (composer.isNotEmpty) editor.finishComposing()
        composer.clear(); result = null; adoptedAs = null; touching = false; suggester?.cancel()
        if (clearCorrection) correction = null
    }

    // ------------------------------------------------------------------ suggestion strip

    /** The three strip slots, left to right: the best word goes in the middle, the typed word (in quotes) on its left when it is not a word. */
    private fun currentItems(): List<BarItem> {
        if (composer.isEmpty) return emptyList()
        val typed = composer.text
        val r = result
        val target = if (r != null && autoCorrect && keepAsTyped != typed && adoptedAs != typed) AutoCorrectPolicy.choose(typed, r) else null
        val words = ArrayList<BarItem>(3)
        if (r != null) for (cand in r.candidates) {
            if (words.size >= 3) break
            val w = applyCase(typed, cand.word)
            if (words.any { it.text.equals(w, ignoreCase = true) }) continue
            words.add(BarItem(w, cand === target))
        }
        val empty = BarItem("", false)
        return if (r == null || !r.typedInDictionary) {
            listOf(BarItem(typed, false, quoted = r != null), words.getOrNull(0) ?: empty, words.getOrNull(1) ?: empty)
        } else {
            listOf(words.getOrNull(1) ?: empty, words.getOrNull(0) ?: BarItem(typed, false), words.getOrNull(2) ?: empty)
        }
    }

    private fun publishBar() {
        val offered = gestureItems
        onBarChanged?.invoke(
            when {
                composer.isNotEmpty -> BarState(currentItems(), true)
                dictation != null -> BarState(listOf(BarItem("Undo dictation", true)), true)
                offered != null -> BarState(offered.mapIndexed { i, w -> BarItem(w, gestureInserted && i == gestureSelected) }, true)
                predictions.isNotEmpty() -> BarState(listOf(predictions.getOrNull(1), predictions[0], predictions.getOrNull(2)).map { BarItem(it.orEmpty(), false) }, true, predicting = true)
                else -> BarState.IDLE
            },
        )
    }

    // ------------------------------------------------------------------ dictation

    private var dictation: String? = null

    /** Turns the transcript into what is typed (filler words, custom words...); set by the IME. */
    var dictationProcessor: (String) -> String = { it }

    /**
     * Types a dictation: a space before it when the text before the cursor needs one, and a space after it so typing can go on.
     * One tap on the strip's "Undo dictation" removes exactly what was typed.
     */
    fun onDictation(raw: String) = edit {
        endWord(null, allowCorrection = false)
        clearGesture(); forgetRejection()
        val text = dictationProcessor(raw).trim()
        if (text.isEmpty()) return@edit
        val before = editor.textBefore(1)?.lastOrNull()
        val lead = if (before != null && !before.isWhitespace() && before !in "([{\"'\u201C\u2018/-@#") " " else ""
        val typed = "$lead$text "
        editor.commit(typed)
        dictation = typed
        previousWord = text.split(' ').lastOrNull()?.lowercase()?.filter { it.isLetter() || it == '\'' }?.ifEmpty { null }
        correction = null
        autoShift()
        publishBar()
    }

    /** The "Undo dictation" offer goes away: the dictation stays as typed. */
    fun dropDictation() { if (dictation != null) { dictation = null; publishBar() } }

    /** Removes the last dictation if the cursor is still right after it. */
    fun undoDictation() = edit {
        val d = dictation ?: return@edit
        dictation = null
        if (editor.textBefore(d.length)?.toString() == d) editor.deleteBefore(d.length)
        publishBar(); autoShift()
    }

    // ------------------------------------------------------------------ swipe typing

    /** The decoder's answer for a swipe. */
    fun onGestureOutcome(o: GestureOutcome) = edit {
        endWord(null, allowCorrection = false)
        clearGesture(); forgetRejection()
        val offered = LinkedHashSet<String>()
        // The best word is typed whenever it plausibly fits (like every other swipe keyboard), with the runners-up in the strip to swap to.
        // Only a swipe that fits nothing well leaves the field alone and offers choices, the letters it passed over first.
        if (o.plausible) { offered.addAll(o.words); o.literal?.let { offered.add(it) } } else { o.literal?.let { offered.add(it) }; offered.addAll(o.words) }
        val items = offered.take(3)
        if (items.isEmpty()) { publishBar(); return@edit }
        gesturePrevious = previousWord
        gestureItems = items
        // Whatever the decoder thinks fits best is always typed, even when it is a poor fit, so a swipe never leaves the field empty; the others stay in the strip.
        val best = if (o.plausible) 0 else (o.words.firstOrNull()?.let { items.indexOf(it) }?.takeIf { it >= 0 } ?: 0)
        insertGestureWord(items[best]); gestureInserted = true; gestureSelected = best; onGestureInserted?.invoke()
        publishBar()
    }

    private fun onGestureItemTapped(index: Int) {
        val items = gestureItems ?: return
        val word = items.getOrNull(index) ?: return
        if (gestureInserted) {
            if (index == gestureSelected) return
            val prev = lastGesture
            if (prev != null && editor.textBefore(prev.length)?.toString() == prev) editor.deleteBefore(prev.length)
            previousWord = gesturePrevious
        }
        insertGestureWord(word, strong = true)
        gestureInserted = true; gestureSelected = index
        publishBar()
    }

    /** Types [word] after a swipe: capitalised when shift asks for it, with a space before it when the text before the cursor needs one, and a space after. */
    private fun insertGestureWord(word: String, strong: Boolean = false) {
        var w = word
        if (shift == Shift.CAPS) w = w.uppercase() else if (shift == Shift.ONCE) w = w.replaceFirstChar { it.uppercase() }
        val before = editor.textBefore(1)?.lastOrNull()
        val lead = if (before != null && !before.isWhitespace() && before !in "([{\"'\u201C\u2018/-@#") " " else ""
        val text = "$lead$w "
        editor.commit(text)
        lastGesture = text
        learn(w, previousWord, strong)
        if (learningEnabled && suggestionsEnabled) onGestureWord?.invoke(word, strong)
        previousWord = w.lowercase()
        correction = null
        if (shift == Shift.ONCE) setShift(Shift.OFF)
        autoShift()
    }

    /** If a swiped word was just deleted, [word] is what the user meant: tell the swipe decoder, once. Returns whether it was. */
    private fun completeRetype(word: String): Boolean {
        val rejected = rejectedSwipe ?: return false
        rejectedSwipe = null
        if (word.equals(rejected, ignoreCase = true)) return false      // they wanted the same word after all
        onGestureCorrected?.invoke(word)
        return true
    }

    private fun learn(word: String, previous: String?, strong: Boolean) {
        if (learningEnabled && suggestionsEnabled) learner?.learn(word, previous, strong)
    }

    private fun forgetRejection() { rejectedSwipe = null }

    private fun clearGesture() { predictions = emptyList(); predictingAfter = null; dictation = null; lastGesture = null; gestureItems = null; gestureInserted = false; gestureSelected = 0 }

    // ------------------------------------------------------------------ shift

    private fun tapShift() {
        val now = clock()
        val double = now - lastShiftTapAt < DOUBLE_TAP_MS
        lastShiftTapAt = now
        setShift(
            when {
                shift == Shift.CAPS -> Shift.OFF
                double -> Shift.CAPS
                shift == Shift.ONCE -> Shift.OFF
                else -> Shift.ONCE
            },
        )
    }

    private fun autoShift() { if (autoCapitalize && shift == Shift.OFF && editor.capsMode() != 0) setShift(Shift.ONCE) }

    private fun setShift(s: Shift) { if (s != shift) { shift = s; onShiftChanged?.invoke(s) } }

    /** How many UTF-16 units the last [n] characters before the cursor take up (an emoji is one character, not two). */
    private fun unitsBack(n: Int, before: CharSequence): Int {
        var i = before.length
        repeat(n) { if (i > 0) i -= Character.charCount(Character.codePointBefore(before, i)) }
        return before.length - i
    }

    /** How many characters before the cursor the last [words] words (with the spaces after them) take up. */
    private fun wordChars(words: Int, before: CharSequence = editor.textBefore(WORD_SCAN) ?: ""): Int {
        if (words <= 0) return 0
        var i = before.length
        repeat(words) {
            while (i > 0 && before[i - 1].isWhitespace()) i--
            while (i > 0 && !before[i - 1].isWhitespace()) i--
        }
        return before.length - i
    }

    companion object {
        private const val WORD_SCAN = 400
        private const val ADOPT_QUIET_MS = 250L
        const val DOUBLE_SPACE_MS = 450L
        const val DOUBLE_TAP_MS = 350L

        private const val CLOSING_PUNCTUATION = ".,!?;:)"

        private fun isWordChar(cp: Int, inWord: Boolean) = Character.isLetter(cp) || (cp == '\''.code && inWord)
        private fun isSentenceEnd(cp: Int) = cp == '.'.code || cp == '!'.code || cp == '?'.code

        /** Makes [candidate] follow the capitalisation the user typed: "Teh" gives "The", "TEH" gives "THE". */
        fun applyCase(typed: String, candidate: String): String = when {
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } && typed.any { it.isUpperCase() } -> candidate.uppercase()
            typed.isNotEmpty() && typed[0].isUpperCase() && candidate.isNotEmpty() && candidate[0].isLowerCase() ->
                candidate.replaceFirstChar { it.uppercase() }
            else -> candidate
        }
    }
}
