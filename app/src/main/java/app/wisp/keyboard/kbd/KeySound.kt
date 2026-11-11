package app.wisp.keyboard.kbd

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The key click, made here so it can be louder than the system's own (which is quiet and capped by the system sound volume):
 * a short tick rendered once per loudness setting, where 100 is about the system click and more than 100 is a boost, softly limited so it stays clean.
 */
class KeySound {
    private var track: AudioTrack? = null
    private var level = -1

    fun play(volume: Int) {
        if (volume != level) build(volume)
        val t = track ?: return
        runCatching { t.stop(); t.reloadStaticData(); t.play() }
    }

    fun release() { track?.release(); track = null; level = -1 }

    private fun build(volume: Int) {
        track?.release()
        level = volume
        val rate = 44100
        val n = rate * 24 / 1000
        val gain = volume / 100f * 1.4f
        val pcm = ShortArray(n) { i ->
            val t = i / rate.toFloat()
            val tick = sin(2 * PI * 2300 * t).toFloat() * exp(-t * 260f) + 0.5f * sin(2 * PI * 900 * t).toFloat() * exp(-t * 140f)
            (tanh(tick * gain) * 32000).toInt().toShort()
        }
        track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
                .also { it.write(pcm, 0, pcm.size) }
        }.getOrNull()
    }
}
