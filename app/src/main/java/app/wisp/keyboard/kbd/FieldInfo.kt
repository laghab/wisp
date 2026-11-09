package app.wisp.keyboard.kbd

/** What the focused field is for. Drives the layout, the enter key and what may be learned. */
enum class FieldKind { TEXT, PASSWORD, NUMBER, PHONE, DATETIME, EMAIL, URL }

/** The enter key's job and label for the focused field. */
enum class EnterAction(val label: String, val imeAction: Int?) {
    NEWLINE("↵", null), GO("Go", IME_GO), SEARCH("Search", IME_SEARCH), SEND("Send", IME_SEND),
    NEXT("Next", IME_NEXT), DONE("Done", IME_DONE), PREVIOUS("Prev", IME_PREVIOUS)
}

private const val IME_GO = 2
private const val IME_SEARCH = 3
private const val IME_SEND = 4
private const val IME_NEXT = 5
private const val IME_DONE = 6
private const val IME_PREVIOUS = 7

class FieldInfo(val kind: FieldKind, val enter: EnterAction, val learn: Boolean, val suggest: Boolean) {

    companion object {
        // Values of android.text.InputType / EditorInfo constants, kept here so this class tests on the JVM.
        private const val CLASS_MASK = 0xf
        private const val VARIATION_MASK = 0xff0
        private const val CLASS_TEXT = 1
        private const val CLASS_NUMBER = 2
        private const val CLASS_PHONE = 3
        private const val CLASS_DATETIME = 4
        private const val FLAG_MULTI_LINE = 0x20000
        private const val FLAG_NO_SUGGESTIONS = 0x80000
        private const val VAR_URI = 0x10
        private const val VAR_EMAIL = 0x20
        private const val VAR_EMAIL_SUBJECT = 0x30
        private const val VAR_PASSWORD = 0x80
        private const val VAR_VISIBLE_PASSWORD = 0x90
        private const val VAR_EMAIL_WEB = 0xd0
        private const val VAR_WEB_PASSWORD = 0xe0
        private const val NUMBER_VAR_PASSWORD = 0x10
        private const val IME_MASK_ACTION = 0xff
        private const val IME_FLAG_NO_ENTER_ACTION = 0x40000000
        private const val IME_FLAG_NO_PERSONALIZED_LEARNING = 0x1000000

        fun from(inputType: Int, imeOptions: Int): FieldInfo {
            val cls = inputType and CLASS_MASK
            val variation = inputType and VARIATION_MASK
            val kind = when (cls) {
                CLASS_NUMBER -> if (variation == NUMBER_VAR_PASSWORD) FieldKind.PASSWORD else FieldKind.NUMBER
                CLASS_PHONE -> FieldKind.PHONE
                CLASS_DATETIME -> FieldKind.DATETIME
                CLASS_TEXT -> when (variation) {
                    VAR_PASSWORD, VAR_VISIBLE_PASSWORD, VAR_WEB_PASSWORD -> FieldKind.PASSWORD
                    VAR_EMAIL, VAR_EMAIL_SUBJECT, VAR_EMAIL_WEB -> FieldKind.EMAIL
                    VAR_URI -> FieldKind.URL
                    else -> FieldKind.TEXT
                }
                else -> FieldKind.TEXT
            }
            val multiline = cls == CLASS_TEXT && inputType and FLAG_MULTI_LINE != 0
            val enter = when {
                imeOptions and IME_FLAG_NO_ENTER_ACTION != 0 || multiline -> EnterAction.NEWLINE
                else -> when (imeOptions and IME_MASK_ACTION) {
                    IME_GO -> EnterAction.GO
                    IME_SEARCH -> EnterAction.SEARCH
                    IME_SEND -> EnterAction.SEND
                    IME_NEXT -> EnterAction.NEXT
                    IME_DONE -> EnterAction.DONE
                    IME_PREVIOUS -> EnterAction.PREVIOUS
                    else -> EnterAction.NEWLINE
                }
            }
            val secret = kind == FieldKind.PASSWORD
            val learn = !secret && imeOptions and IME_FLAG_NO_PERSONALIZED_LEARNING == 0
            val suggest = !secret && inputType and FLAG_NO_SUGGESTIONS == 0
            return FieldInfo(kind, enter, learn, suggest)
        }
    }
}
