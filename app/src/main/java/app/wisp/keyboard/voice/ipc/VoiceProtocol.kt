package app.wisp.keyboard.voice.ipc

/**
 * Messages between the keyboard process and the voice process (a Messenger carries them). Audio goes one way as 16-bit PCM
 * chunks of 32 ms; text comes back. The voice process loads the speech model only in answer to START and exits by itself
 * after an idle timeout (design.md D2, D3).
 */
object VoiceProtocol {
    // keyboard -> voice
    const val START = 1      // modelId, modelDir, language, idleMs, threads, live
    const val PCM = 2        // pcm: ShortArray
    const val STOP = 3       // finish: decode what is left and answer with FINAL
    const val CANCEL = 4
    const val BENCH = 5      // same parameters as START: load the model and time it on a short clip

    // voice -> keyboard
    const val READY = 10     // loadMs
    const val PARTIAL = 11   // text: what has been recognised so far, while speaking
    const val FINAL = 12     // text, finishMs
    const val ERROR = 13     // message
    const val BENCH_RESULT = 15   // loadMs, decodeMs, audioMs
    const val EXITING = 14   // the voice process is about to exit: unbind and bind again when needed

    const val K_MODEL_ID = "modelId"
    const val K_MODEL_DIR = "modelDir"
    const val K_LANGUAGE = "language"
    const val K_IDLE_MS = "idleMs"
    const val K_THREADS = "threads"
    const val K_LIVE = "live"
    const val K_PCM = "pcm"
    const val K_TEXT = "text"
    const val K_MS = "ms"
    const val K_DECODE_MS = "decodeMs"
    const val K_AUDIO_MS = "audioMs"
    const val K_MESSAGE = "message"

    const val SAMPLE_RATE = 16_000
    const val FRAME = 512      // samples per chunk: 32 ms, the size the voice detector works on
}
