package app.wisp.keyboard.voice.engine

import app.wisp.keyboard.voice.ipc.VoiceProtocol

/** Growable mono float PCM at 16 kHz (from Handy). Used by one thread. */
class PcmBuffer(initialSeconds: Int = 30) {
    private var data = FloatArray(VoiceProtocol.SAMPLE_RATE * initialSeconds)
    var size = 0
        private set

    fun append(shorts: ShortArray) {
        if (size + shorts.size > data.size) {
            var cap = data.size * 2
            while (cap < size + shorts.size) cap *= 2
            data = data.copyOf(cap)
        }
        for (i in shorts.indices) data[size + i] = shorts[i] / 32768f
        size += shorts.size
    }

    fun copy(from: Int = 0, to: Int = size): FloatArray {
        val a = from.coerceIn(0, size); val b = to.coerceIn(a, size)
        return data.copyOfRange(a, b)
    }
}
