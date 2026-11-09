package app.wisp.keyboard.voice.text

/** Removes the sound annotations some models emit ("[BLANK_AUDIO]", "(music)", special tokens), from Handy's DictationSession. */
object NonSpeech {
    private val NON_SPEECH = Regex("""\[(?:[A-Z_ ]+|blank_audio|music|silence)\]|\((?:music|silence|inaudible|blank audio)\)|<\|[^|>]*\|>""", RegexOption.IGNORE_CASE)
    fun strip(text: String): String = NON_SPEECH.replace(text, "").trim()
}
