package app.wisp.keyboard.voice.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import app.wisp.keyboard.BuildConfig
import app.wisp.keyboard.voice.ipc.VoiceProtocol.FRAME
import app.wisp.keyboard.voice.ipc.VoiceProtocol.SAMPLE_RATE
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Captures 16 kHz mono audio on a high-priority thread and hands out 32 ms chunks of 16-bit samples (from Handy's AudioRecorder).
 *
 * VOICE_RECOGNITION is used because on most phones it skips the call-style gain control and noise processing that speech
 * models dislike. Debug builds can replay `files/debug_mic.wav` in real time instead of the microphone, for testing without one.
 */
class MicCapture(
    private val onFrame: (ShortArray) -> Unit,
    /** 0..1, roughly -60 to -10 dBFS. */
    private val onLevel: (Float) -> Unit,
    private val debugReplay: File? = null,
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission") // The caller checks RECORD_AUDIO first.
    fun start(): Boolean {
        if (running) return true
        if (BuildConfig.DEBUG && debugReplay?.isFile == true) return startReplay(debugReplay)
        val rec = open(SAMPLE_RATE) ?: open(48_000) ?: return false
        record = rec
        try { rec.startRecording() } catch (e: IllegalStateException) { Log.e(TAG, "startRecording failed", e); rec.release(); record = null; return false }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { rec.release(); record = null; return false }
        running = true
        thread = Thread({ loop(rec) }, "wisp-mic").also { it.start() }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun open(rate: Int): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        return try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, rate / 5 * 2))
                .let { if (it.state == AudioRecord.STATE_INITIALIZED) it else { it.release(); null } }
        } catch (e: Exception) { Log.w(TAG, "AudioRecord @$rate failed", e); null }
    }

    private fun loop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val decimate = rec.sampleRate / SAMPLE_RATE         // 1, or 3 for a 48 kHz fallback
        val raw = ShortArray(FRAME * decimate)
        while (running) {
            val n = rec.read(raw, 0, raw.size)
            if (n <= 0) { if (n == AudioRecord.ERROR_DEAD_OBJECT) break; continue }
            val out = n / decimate
            val frame = ShortArray(out)
            if (decimate == 1) System.arraycopy(raw, 0, frame, 0, out)
            else for (i in 0 until out) { var s = 0; for (k in 0 until decimate) s += raw[i * decimate + k]; frame[i] = (s / decimate).toShort() }   // box filter: fine for speech
            deliver(frame)
        }
    }

    private fun deliver(frame: ShortArray) {
        var acc = 0.0
        for (v in frame) acc += (v / 32768.0) * (v / 32768.0)
        val db = 20f * log10(sqrt(acc / frame.size).toFloat().coerceAtLeast(1e-6f))
        onLevel(((db + 60f) / 50f).coerceIn(0f, 1f))
        onFrame(frame)
    }

    private fun startReplay(file: File): Boolean {
        val samples = WavReader.read(file)
        running = true
        thread = Thread({
            var i = 0
            while (running) {
                val frame = ShortArray(FRAME) { k -> if (i + k < samples.size) samples[i + k] else 0 }
                i += FRAME
                deliver(frame)
                try { Thread.sleep(32) } catch (e: InterruptedException) { break }
            }
        }, "wisp-mic-replay").also { it.start() }
        Log.w(TAG, "DEBUG: replaying ${file.name} instead of the microphone")
        return true
    }

    /** Stops capture; the last frames have been delivered when this returns. */
    fun stop() {
        if (!running && record == null) return
        running = false
        try { record?.stop() } catch (_: IllegalStateException) { }
        thread?.join(500); thread = null
        record?.release(); record = null
    }

    private companion object { const val TAG = "MicCapture" }
}

/** Minimal reader for 16-bit mono PCM WAV files (the debug microphone replay). */
object WavReader {
    fun read(file: File): ShortArray = readBytes(file.readBytes())

    fun readBytes(b: ByteArray): ShortArray {
        var p = 12
        while (p + 8 <= b.size) {
            val id = String(b, p, 4, Charsets.US_ASCII)
            val len = (b[p + 4].toInt() and 0xFF) or ((b[p + 5].toInt() and 0xFF) shl 8) or ((b[p + 6].toInt() and 0xFF) shl 16) or ((b[p + 7].toInt() and 0xFF) shl 24)
            if (id == "data") {
                val n = minOf(len, b.size - p - 8) / 2
                return ShortArray(n) { ((b[p + 8 + it * 2].toInt() and 0xFF) or (b[p + 9 + it * 2].toInt() shl 8)).toShort() }
            }
            p += 8 + len + (len and 1)
        }
        return ShortArray(0)
    }
}
