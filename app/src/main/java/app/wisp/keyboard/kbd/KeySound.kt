package app.wisp.keyboard.kbd

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * What a key press sounds like. "system" is the phone's own click. The others are made here, on the phone, so they can be louder than
 * the system click (a volume above 100 is a boost, softly limited so it stays clean): a plain "tick", or an instrument that plays the next
 * note of a gentle melody for each key, so typing makes music. Nothing is recorded or bundled; the notes are rendered once per setting.
 */
class KeySound(private val context: android.content.Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var mode = "off"
    private var volume = -1
    private var tracks: List<AudioTrack> = emptyList()      // tick: one; instruments: one per scale note
    @Volatile private var ready: List<AudioTrack> = emptyList()
    private var token = 0
    private var at = 0                                       // where the melody is on the scale
    private var count = 0

    fun play(mode: String, volume: Int, code: Int) {
        if (mode == "off") return
        if (mode == "system") { audio.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, if (volume >= 100) -1f else volume / 100f); return }
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        prepare(mode, volume)
        val r = ready
        if (r.isEmpty()) return
        if (mode == "tick") { fire(r[0]); return }
        when (code) {
            KeyCodes.SPACE -> { at = (at / 5) * 5; for (i in intArrayOf(0, 2, 3)) r.getOrNull(i)?.let(::fire) }      // a soft chord
            KeyCodes.BACKSPACE -> { at = (at - 1).coerceAtLeast(0); fire(r[at]) }
            else -> { at = next(at, count++); fire(r[at]) }
        }
    }

    fun release() { token++; ready = emptyList(); tracks.forEach { it.release() }; tracks = emptyList(); mode = "off"; volume = -1 }

    private fun fire(t: AudioTrack) { runCatching { t.stop(); t.reloadStaticData(); t.play() } }

    /** Renders the sounds for this setting in the background; keys are silent for the first moments, and again while a slider drag re-renders. */
    private fun prepare(m: String, v: Int) {
        if (m == mode && v == volume) return
        mode = m; volume = v
        val mine = ++token
        Thread {
            val made = if (m == "tick") listOf(track(tick(v))) else NOTES.map { track(instrument(m, it, v)) }
            if (mine == token) { val old = tracks; tracks = made; ready = made; old.forEach { it.release() } } else made.forEach { it.release() }
        }.start()
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

    private fun tick(volume: Int): ShortArray {
        val n = RATE * 24 / 1000
        return limit(FloatArray(n) { i -> val t = i / RATE.toFloat(); sin(2 * PI * 2300 * t).toFloat() * exp(-t * 260f) + 0.5f * sin(2 * PI * 900 * t).toFloat() * exp(-t * 140f) }, volume)
    }

    private fun instrument(m: String, midi: Int, volume: Int): ShortArray {
        val f = 440f * 2f.pow((midi + (if (m == "musicbox") 12 else 0) - 69) / 12f)
        val seconds = when (m) { "xylophone" -> 0.5f; "marimba" -> 0.8f; else -> 1.4f }
        val n = (RATE * seconds).toInt()
        val x = FloatArray(n)
        if (m == "harp") {                                      // a plucked string: a short loop of noise that loses a little energy each pass
            val period = (RATE / f).toInt().coerceAtLeast(2); val rnd = Random(midi)
            val line = FloatArray(period) { rnd.nextFloat() * 2 - 1 }
            for (i in 0 until n) { val j = i % period; x[i] = line[j]; line[j] = 0.4975f * (line[j] + line[(j + 1) % period]) }
            return limit(x, volume)
        }
        val (ratios, amps, taus) = when (m) {
            "xylophone" -> Triple(floatArrayOf(1f, 3.9f, 9f), floatArrayOf(1f, .5f, .2f), floatArrayOf(.22f, .08f, .04f))
            "marimba" -> Triple(floatArrayOf(1f, 4f, 10f), floatArrayOf(1f, .45f, .1f), floatArrayOf(.55f, .18f, .06f))
            "musicbox" -> Triple(floatArrayOf(1f, 3f, 5.9f), floatArrayOf(1f, .35f, .12f), floatArrayOf(.9f, .45f, .2f))
            else -> Triple(FloatArray(10) { (it + 1) * sqrt(1f + 0.0003f * (it + 1) * (it + 1)) }, FloatArray(10) { 1f / (it + 1).toFloat().pow(1.1f) }, FloatArray(10) { .95f / (1f + .45f * it) })
        }
        val rnd = Random(midi)
        for (i in 0 until n) {
            val t = i / RATE.toFloat()
            var s = 0f
            for (k in ratios.indices) {
                val hz = f * ratios[k]; if (hz > 9000f) continue
                s += amps[k] * exp(-t / taus[k]) * (sin(2 * PI * hz * t).toFloat() + if (m == "piano") sin(2 * PI * hz * 1.0007 * t).toFloat() else 0f)
            }
            x[i] = s * (1f - exp(-t / 0.002f)) + (rnd.nextFloat() - .5f) * .15f * exp(-t / 0.004f)      // a soft hammer or mallet knock at the start
        }
        return limit(x, volume)
    }

    private companion object {
        const val RATE = 22050
        /** A pentatonic scale over two octaves and a bit, from middle C: any run of these notes sounds pleasant. */
        val NOTES = intArrayOf(60, 62, 64, 67, 69, 72, 74, 76, 79, 81, 84)

        /** The next scale position: mostly small steps, sometimes a leap, and every eighth note home to a C so it feels like phrases. */
        fun next(at: Int, n: Int): Int {
            if (n % 8 == 7) return intArrayOf(0, 5, 10).minBy { kotlin.math.abs(it - at) }
            val r = Random.nextInt(100)
            val step = when { r < 28 -> -1; r < 58 -> 1; r < 70 -> -2; r < 84 -> 2; r < 88 -> 0; r < 93 -> -3; else -> 3 }
            val to = at + step
            return if (to < 0 || to >= NOTES.size) (at - step).coerceIn(0, NOTES.size - 1) else to
        }
    }
}
