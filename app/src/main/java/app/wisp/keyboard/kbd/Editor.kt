package app.wisp.keyboard.kbd

/** What the input controller needs from the focused text field. Implemented over InputConnection in the IME. */
interface Editor {
    fun commit(text: CharSequence)
    /** Shows [text] as the underlined word being typed, replacing any previous composing text. */
    fun setComposing(text: CharSequence)
    /** Keeps the composing text as ordinary text. */
    fun finishComposing()
    /** Deletes the selection, or one character before the cursor. */
    fun backspace()
    fun deleteBefore(count: Int)
    fun textBefore(count: Int): CharSequence?
    fun textAfter(count: Int): CharSequence?
    /** Makes the word around the cursor ([before] characters before it, [after] after it) the underlined word being edited. False when the field cannot do it. */
    fun markComposing(before: Int, after: Int): Boolean
    fun moveCursor(steps: Int)
    /** Selects [count] characters before the cursor (the selection's end stays put); 0 collapses to the cursor. */
    fun selectBefore(count: Int)
    /** The text that is selected right now, or null when nothing is. */
    fun selectedText(): CharSequence?
    fun enter()
    /** Non-zero when the next typed letter should be capitalised (sentence/word/all caps). */
    fun capsMode(): Int
    fun selectAll()
    fun cut()
    fun copy()
    fun paste()
    fun undo()
    fun redo()
}
