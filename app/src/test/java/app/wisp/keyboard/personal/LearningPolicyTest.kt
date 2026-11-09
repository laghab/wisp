package app.wisp.keyboard.personal

import android.text.InputType
import android.view.inputmethod.EditorInfo
import app.wisp.keyboard.kbd.FieldInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningPolicyTest {
    private val text = FieldInfo.from(InputType.TYPE_CLASS_TEXT, 0)

    @Test fun ordinaryFieldsAreLearnedFrom() = assertTrue(LearningPolicy.allowed(text, false, "com.example.chat", emptySet()))

    @Test fun passwordsNever() {
        for (v in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
            assertFalse(LearningPolicy.allowed(FieldInfo.from(InputType.TYPE_CLASS_TEXT or v, 0), false, "x", emptySet()))
    }

    @Test fun fieldsThatAskForNoLearningAreRespected() =
        assertFalse(LearningPolicy.allowed(FieldInfo.from(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING), false, "x", emptySet()))

    @Test fun incognitoStopsLearning() = assertFalse(LearningPolicy.allowed(text, true, "x", emptySet()))

    @Test fun excludedAppsAreNotLearnedFrom() {
        assertFalse(LearningPolicy.allowed(text, false, "com.bank.app", setOf("com.bank.app")))
        assertTrue(LearningPolicy.allowed(text, false, "com.other", setOf("com.bank.app")))
    }

    @Test fun anUnknownAppIsStillLearnedFrom() = assertTrue(LearningPolicy.allowed(text, false, null, setOf("com.bank.app")))
}
