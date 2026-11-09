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
    fun moveCursor(steps: Int)
    fun enter()
    /** Non-zero when the next typed letter should be capitalised (sentence/word/all caps). */
    fun capsMode(): Int
    fun selectAll()
    fun cut()
    fun copy()
    fun paste()
}
