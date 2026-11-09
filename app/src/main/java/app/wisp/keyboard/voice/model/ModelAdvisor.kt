package app.wisp.keyboard.voice.model

/** What a phone can offer a speech model. */
class DeviceFacts(val totalRamMb: Long, val availableRamMb: Long, val performanceCores: Int)

/** Plain-language notes on each model: what it is good at and what it costs you. Written for people, not for a spec sheet. */
object ModelInfo {
    class Notes(val pros: List<String>, val cons: List<String>)

    private val notes = mapOf(
        "moonshine-tiny-en" to Notes(
            pros = listOf("Smallest and fastest: a 30 MB download", "Runs on almost any phone and barely touches the battery", "Text appears almost as you speak"),
            cons = listOf("English only", "Makes more mistakes with accents, names and noisy rooms"),
        ),
        "moonshine-base-en" to Notes(
            pros = listOf("Noticeably more accurate than Tiny", "Still fast, a good everyday choice for English"),
            cons = listOf("English only", "A 106 MB download and about 300 MB of memory while dictating"),
        ),
        "parakeet-110m-en" to Notes(
            pros = listOf("Very good English accuracy for its size", "Punctuation and capital letters come out right"),
            cons = listOf("English only", "About 300 MB of memory while dictating"),
        ),
        "parakeet-v3" to Notes(
            pros = listOf("The most accurate model here", "Understands 25 European languages and works out which one you are speaking"),
            cons = listOf("A 640 MB download and about 1.1 GB of memory while dictating: only for phones with 8 GB of RAM or more", "Slower to load", "No Hindi"),
        ),
        "whisper-base" to Notes(
            pros = listOf("99 languages, including Hindi", "Small and light"),
            cons = listOf("Less accurate than the others for English", "Hindi comes out in Devanagari letters, not Roman (\"Hinglish\") letters"),
        ),
        "whisper-small" to Notes(
            pros = listOf("99 languages, including Hindi, with good accuracy", "The best choice here if you speak Hindi"),
            cons = listOf("Slower: noticeable wait after you stop on mid-range phones", "A 358 MB download and about 800 MB of memory while dictating", "Hindi comes out in Devanagari letters, not Roman (\"Hinglish\") letters"),
        ),
        "sense-voice" to Notes(
            pros = listOf("Very fast", "Chinese, Cantonese, Japanese and Korean as well as English"),
            cons = listOf("No Hindi", "English is not its strongest language"),
        ),
    )

    fun notesFor(id: String) = notes[id] ?: Notes(emptyList(), emptyList())
}

/** How well a model suits this phone. */
enum class Fit { GOOD, HEAVY, TOO_HEAVY }
class Assessment(val fit: Fit, val reason: String)
class Recommendation(val model: ModelSpec, val reason: String)

/**
 * Picks and judges models for a phone from its memory. A model needs roughly [ModelSpec.ramMb] while dictating, and Android and the
 * other apps need the rest, so a model taking more than about an eighth of the phone's RAM is called heavy, and more than a fifth too heavy.
 */
object ModelAdvisor {
    private const val COMFORTABLE_SHARE = 0.12
    private const val HEAVY_SHARE = 0.20


    fun assess(spec: ModelSpec, device: DeviceFacts): Assessment {
        val share = spec.ramMb.toDouble() / device.totalRamMb.coerceAtLeast(1)
        return when {
            share > HEAVY_SHARE ->
                Assessment(Fit.TOO_HEAVY, "Needs about ${spec.ramMb} MB while dictating; this phone has ${gb(device.totalRamMb)} in total, so it is likely to be slow or get closed by Android.")
            share > COMFORTABLE_SHARE ->
                Assessment(Fit.HEAVY, "Needs about ${spec.ramMb} MB while dictating, a big share of this phone's ${gb(device.totalRamMb)}. It will work, but other apps may be closed to make room.")
            else -> Assessment(Fit.GOOD, "Needs about ${spec.ramMb} MB while dictating, which this phone can spare.")
        }
    }

    /** The most accurate model that fits comfortably. English-only unless [wantsMultilingual]. */
    fun recommend(device: DeviceFacts, wantsMultilingual: Boolean = false): Recommendation {
        val fitting = ModelCatalog.all.filter { assess(it, device).fit == Fit.GOOD && (!wantsMultilingual || it.selectableLanguages.isNotEmpty() || it.languages.contains("languages")) }
        val best = fitting.maxWithOrNull(compareBy<ModelSpec> { it.accuracy }.thenBy { it.speed }.thenBy { -it.ramMb })
            ?: ModelCatalog.all.minBy { it.ramMb }
        val why = when {
            device.totalRamMb < 3_500 -> "Your phone has ${gb(device.totalRamMb)} of memory, so a small, fast model is the right fit."
            device.totalRamMb < 7_500 -> "Your phone has ${gb(device.totalRamMb)} of memory: this is the most accurate model that leaves plenty of room for other apps."
            else -> "Your phone has ${gb(device.totalRamMb)} of memory, enough for the most accurate model, at the cost of a bigger download."
        }
        return Recommendation(best, why)
    }

    private fun gb(mb: Long) = if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "$mb MB"
}
