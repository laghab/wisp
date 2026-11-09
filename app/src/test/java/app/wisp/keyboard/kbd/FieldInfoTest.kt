package app.wisp.keyboard.kbd

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldInfoTest {
    private fun f(type: Int, opts: Int = 0) = FieldInfo.from(type, opts)

    @Test fun passwordsAreNeverLearned() {
        for (v in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            val i = f(InputType.TYPE_CLASS_TEXT or v)
            assertEquals(FieldKind.PASSWORD, i.kind); assertFalse(i.learn); assertFalse(i.suggest)
        }
        assertEquals(FieldKind.PASSWORD, f(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD).kind)
    }

    @Test fun kinds() {
        assertEquals(FieldKind.NUMBER, f(InputType.TYPE_CLASS_NUMBER).kind)
        assertEquals(FieldKind.PHONE, f(InputType.TYPE_CLASS_PHONE).kind)
        assertEquals(FieldKind.EMAIL, f(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).kind)
        assertEquals(FieldKind.URL, f(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).kind)
        assertEquals(FieldKind.TEXT, f(InputType.TYPE_CLASS_TEXT).kind)
        val t = f(InputType.TYPE_CLASS_TEXT); assertTrue(t.learn); assertTrue(t.suggest)
    }

    @Test fun enterMatchesFieldAction() {
        val text = InputType.TYPE_CLASS_TEXT
        assertEquals(EnterAction.SEARCH, f(text, EditorInfo.IME_ACTION_SEARCH).enter)
        assertEquals(EnterAction.SEND, f(text, EditorInfo.IME_ACTION_SEND).enter)
        assertEquals(EnterAction.GO, f(text, EditorInfo.IME_ACTION_GO).enter)
        assertEquals(EnterAction.NEXT, f(text, EditorInfo.IME_ACTION_NEXT).enter)
        assertEquals(EnterAction.DONE, f(text, EditorInfo.IME_ACTION_DONE).enter)
        assertEquals(EnterAction.PREVIOUS, f(text, EditorInfo.IME_ACTION_PREVIOUS).enter)
        assertEquals(EnterAction.NEWLINE, f(text, EditorInfo.IME_ACTION_UNSPECIFIED).enter)
    }

    @Test fun multilineAndNoEnterActionWantANewline() {
        val multi = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        assertEquals(EnterAction.NEWLINE, f(multi, EditorInfo.IME_ACTION_SEND).enter)
        assertEquals(EnterAction.NEWLINE, f(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION).enter)
    }

    @Test fun noLearningFlagsAreHonoured() {
        assertFalse(f(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING).learn)
        assertFalse(f(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).suggest)
    }
}
