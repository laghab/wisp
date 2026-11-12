package app.wisp.keyboard.voice.llm

/** What the user wants done to the text: the chips on the rewrite panel and the instruction given to the model. */
enum class Tone(val label: String, val instruction: String) {
    POLISH("Polish", "Fix grammar, spelling, punctuation and capitalisation and make it read clearly, keeping the meaning, the language and the writer's voice."),
    FRIENDLY("Friendly", "Rewrite it in a warm, friendly, casual tone, keeping the meaning and the language."),
    FORMAL("Formal", "Rewrite it in a polite, formal, professional tone, keeping the meaning and the language."),
    PREMIUM("Premium English", "Rewrite it in premium English: a richer, more distinguished vocabulary and elegant, polished phrasing, like a well-read writer, without becoming stiff or archaic. Keep the meaning, the facts and the length roughly the same, and do not add anything new."),
    SHORTER("Shorter", "Rewrite it as short as possible while keeping the meaning and the language."),
    BULLETS("Bullets", "Rewrite it as a short bulleted list, one point per line starting with \"- \", keeping the meaning and the language."),
    GENZ("Gen Z", "Rewrite it the way a Gen Z person would text it: casual, lowercase, current slang used lightly (like fr, ngl, lowkey, no cap, bet, vibes), a few emoji at most, keeping the meaning and the language. Do not add new facts."),
    HINDI("Hindi", "Translate it into natural Hindi written in Devanagari script.");

    /** The full instruction for the model. */
    fun system(): String = "You rewrite text for a phone keyboard. $instruction Reply with the rewritten text only: no explanation, no quotation marks, no preface."

    companion object { fun byName(n: String?) = values().firstOrNull { it.name == n } ?: POLISH }
}
