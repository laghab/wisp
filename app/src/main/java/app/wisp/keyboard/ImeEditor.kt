package app.wisp.keyboard

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import app.wisp.keyboard.kbd.Editor

/** [Editor] backed by the IME's current InputConnection. */
class ImeEditor(private val ime: InputMethodService) : Editor {
    var inputType = 0
    var enterAction = app.wisp.keyboard.kbd.EnterAction.NEWLINE

    private val ic get() = ime.currentInputConnection

    override fun commit(text: CharSequence) { ic?.commitText(text, 1) }

    override fun setComposing(text: CharSequence) { ic?.setComposingText(text, 1) }
    override fun finishComposing() { ic?.finishComposingText() }

    override fun backspace() {
        val c = ic ?: return
        if (!c.getSelectedText(0).isNullOrEmpty()) c.commitText("", 1) else ime.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    override fun deleteBefore(count: Int) { ic?.deleteSurroundingText(count, 0) }
    override fun textBefore(count: Int): CharSequence? = ic?.getTextBeforeCursor(count, 0)
    override fun textAfter(count: Int): CharSequence? = ic?.getTextAfterCursor(count, 0)

    override fun markComposing(word: CharSequence) {
        val c = ic ?: return
        val et = c.getExtractedText(android.view.inputmethod.ExtractedTextRequest().apply { hintMaxChars = 0 }, 0)
        if (et != null) { val end = maxOf(et.selectionStart, et.selectionEnd) + et.startOffset; c.setComposingRegion(end - word.length, end) }
        else { c.deleteSurroundingText(word.length, 0); c.setComposingText(word, 1) }      // an app that will not say where the cursor is: put the word back as composing text
    }

    override fun moveCursor(steps: Int) {
        val key = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps)) { ime.sendDownUpKeyEvents(key) }
    }

    override fun selectedText(): CharSequence? = ic?.getSelectedText(0)?.takeIf { it.isNotEmpty() }

    override fun selectBefore(count: Int) {
        val c = ic ?: return
        val et = c.getExtractedText(android.view.inputmethod.ExtractedTextRequest().apply { hintMaxChars = 0 }, 0) ?: return
        val end = maxOf(et.selectionStart, et.selectionEnd) + et.startOffset
        c.setSelection((end - count).coerceAtLeast(0), end)
    }

    override fun enter() {
        val action = enterAction.imeAction
        if (action != null) ic?.performEditorAction(action) else ime.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }
    override fun capsMode(): Int = if (inputType == 0) 0 else ic?.getCursorCapsMode(inputType) ?: 0

    override fun selectAll() { ic?.performContextMenuAction(android.R.id.selectAll) }
    override fun cut() { ic?.performContextMenuAction(android.R.id.cut) }
    override fun copy() { ic?.performContextMenuAction(android.R.id.copy) }
    override fun paste() { ic?.performContextMenuAction(android.R.id.paste) }
}
