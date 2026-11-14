package app.wisp.keyboard.kbd

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * What a key press sounds like. Every sound is made here, on the phone; nothing is recorded or bundled. Three are clicks ("paper", a soft
 * tap; "thock", a deep mechanical key; "wood", a warm knock), with a lower one for the wide keys. Two are instruments ("kalimba" and
 * "bells") that play the next note of a gentle melody for each key, so typing makes music. A volume above 100 is a boost, softly limited
 * so it stays clean. The sounds are rendered once per setting.
 */
class KeySound(context: android.content.Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var mode = "off"
    private var volume = -1
    private var tracks: List<AudioTrack> = emptyList()      // clicks: the key and the wide key; instruments: one per scale note
    @Volatile private var ready: List<AudioTrack> = emptyList()
    @Volatile private var owed: Int? = null                  // a sample asked for while the sounds were still being rendered
    private var token = 0
    private var at = 0                                       // where the melody is on the scale
    private var count = 0

    /** Plays the sound for [code]. [whenReady]: if the sounds are still being rendered, play as soon as they are (the settings' samples). */
    fun play(mode: String, volume: Int, code: Int, whenReady: Boolean = false) {
        if (mode !in MODES || audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val fresh = prepare(mode, volume)
        val r = ready
        if (fresh || r.isEmpty()) { if (whenReady) owed = code; return }
        sound(r, code)
    }

    private fun sound(r: List<AudioTrack>, code: Int) {
        val wide = code == KeyCodes.SPACE || code == KeyCodes.ENTER || code == KeyCodes.BACKSPACE
        if (mode in CLICKS) { fire(r[if (wide) 1 else 0]); return }
        when (code) {
            KeyCodes.SPACE -> { at = (at / 5) * 5; for (i in intArrayOf(0, 2, 3)) r.getOrNull(i)?.let(::fire) }      // a soft chord
            KeyCodes.BACKSPACE -> { at = (at - 1).coerceAtLeast(0); fire(r[at]) }
            else -> { at = next(at, count++); fire(r[at]) }
        }
    }

    fun release() { token++; owed = null; ready = emptyList(); tracks.forEach { it.release() }; tracks = emptyList(); mode = "off"; volume = -1 }

    private fun fire(t: AudioTrack) { runCatching { t.stop(); t.reloadStaticData(); t.play() } }

    /** Renders the sounds for this setting in the background; returns whether it had to start. Keys are silent until it is done. */
    private fun prepare(m: String, v: Int): Boolean {
        if (m == mode && v == volume) return false
        mode = m; volume = v
        val mine = ++token
        ready = emptyList()
        Thread {
            val made = if (m in CLICKS) listOf(track(click(m, false, v)), track(click(m, true, v))) else NOTES.map { track(instrument(m, it, v)) }
            if (mine != token) { made.forEach { it.release() }; return@Thread }
            val old = tracks; tracks = made; ready = made; old.forEach { it.release() }
            owed?.let { owed = null; sound(made, it) }
        }.start()
        return true
    }

    private fun track(pcm: ShortArray): AudioTrack =
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
            .also { it.write(pcm, 0, pcm.size) }

    private fun limit(x: FloatArray, volume: Int): ShortArray {
        val peak = x.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-6f)
        val g = volume / 100f * 1.4f / peak * 0.7f
        return ShortArray(x.size) { (tanh(x[it] * g) * 32000).toInt().toShort() }
    }

    /** Noise kept between two cut-offs ([hi] and [lo] are one-pole filter coefficients): the body of every click. */
    private fun band(n: Int, seed: Int, hi: Float, lo: Float): FloatArray {
        val rnd = Random(seed); var a = 0f; var b = 0f
        return FloatArray(n) { val w = rnd.nextFloat() * 2 - 1; a += hi * (w - a); b += lo * (w - b); a - b }
    }

    private fun click(m: String, wide: Boolean, volume: Int): ShortArray {
        fun t(i: Int) = i / RATE.toFloat()
        val x = when (m) {
            "paper" -> {                                          // a fingertip on paper: a short breath of mid noise, nothing tonal
                val n = RATE * 45 / 1000; val noise = band(n, 7, if (wide) 0.28f else 0.5f, if (wide) 0.035f else 0.07f)
                FloatArray(n) { i -> noise[i] * (1f - exp(-t(i) / 0.0006f)) * exp(-t(i) / if (wide) 0.010f else 0.006f) }
            }
            "thock" -> {                                          // a lubed mechanical key: a low thump that drops in pitch, under a small tick
                val n = RATE * 130 / 1000; val noise = band(n, 11, 0.35f, 0.05f); val base = if (wide) 78f else 112f
                var phase = 0.0
                FloatArray(n) { i -> phase += 2 * PI * (base + 150f * exp(-t(i) / 0.011f)) / RATE; sin(phase).toFloat() * exp(-t(i) / 0.032f) + 0.45f * noise[i] * exp(-t(i) / 0.0035f) }
            }
            else -> {                                             // "wood": a small block struck once, two partials that die quickly
                val n = RATE * 110 / 1000; val noise = band(n, 13, 0.4f, 0.1f); val f = if (wide) 540f else 800f
                FloatArray(n) { i -> sin(2 * PI * f * t(i)).toFloat() * exp(-t(i) / 0.028f) + 0.35f * sin(2 * PI * f * 2.71 * t(i)).toFloat() * exp(-t(i) / 0.010f) + 0.3f * noise[i] * exp(-t(i) / 0.003f) }
            }
        }
        return limit(x, volume)
    }

    private fun instrument(m: String, midi: Int, volume: Int): ShortArray {
        val bells = m == "bells"
        val f = 440f * 2f.pow((midi + (if (bells) 12 else 0) - 69) / 12f)
        val n = (RATE * if (bells) 1.4f else 0.9f).toInt()
        // kalimba: a metal tine, nearly a pure tone with a faint high ring; bells: a music box comb, brighter and longer
        val (ratios, amps, taus) = if (bells) Triple(floatArrayOf(1f, 3f, 5.9f), floatArrayOf(1f, .35f, .12f), floatArrayOf(.9f, .45f, .2f))
        else Triple(floatArrayOf(1f, 2f, 5.4f), floatArrayOf(1f, .12f, .18f), floatArrayOf(.55f, .3f, .05f))
        val rnd = Random(midi)
        val x = FloatArray(n) { i ->
            val t = i / RATE.toFloat()
            var s = 0f
            for (k in ratios.indices) { val hz = f * ratios[k]; if (hz < 9000f) s += amps[k] * exp(-t / taus[k]) * sin(2 * PI * hz * t).toFloat() }
            s * (1f - exp(-t / 0.002f)) + (rnd.nextFloat() - .5f) * .15f * exp(-t / 0.004f)      // the pluck at the start
        }
        return limit(x, volume)
    }

    companion object {
        val CLICKS = listOf("paper", "thock", "wood")
        /** Every sound, in the order the settings show them, with its name. */
        val CHOICES = listOf("paper" to "Paper", "thock" to "Thock", "wood" to "Wood", "kalimba" to "Kalimba", "bells" to "Bells")
        private val MODES = CHOICES.map { it.first }

        private const val RATE = 44100
        /** A pentatonic scale over two octaves and a bit, from middle C: any run of these notes sounds pleasant. */
        private val NOTES = intArrayOf(60, 62, 64, 67, 69, 72, 74, 76, 79, 81, 84)

        /** The next scale position: mostly small steps, sometimes a leap, and every eighth note home to a C so it feels like phrases. */
        private fun next(at: Int, n: Int): Int {
            if (n % 8 == 7) return intArrayOf(0, 5, 10).minBy { kotlin.math.abs(it - at) }
            val r = Random.nextInt(100)
            val step = when { r < 28 -> -1; r < 58 -> 1; r < 70 -> -2; r < 84 -> 2; r < 88 -> 0; r < 93 -> -3; else -> 3 }
            val to = at + step
            return if (to < 0 || to >= NOTES.size) (at - step).coerceIn(0, NOTES.size - 1) else to
        }
    }
}
