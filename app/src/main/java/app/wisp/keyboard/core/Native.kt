package app.wisp.keyboard.core

/** The Rust core. Arrays in, arrays out: see `core/wisp-jni`. */
object Native {
    init { System.loadLibrary("wisp_jni") }

    /** Builds a key layout and its touch machine; returns a handle (0 on bad input). */
    external fun touchNew(
        weights: FloatArray, flags: IntArray, rowLens: IntArray, width: Float,
        metrics: FloatArray, keyInfo: IntArray, cfg: FloatArray,
    ): Long
    external fun touchFree(handle: Long)
    external fun touchConfig(handle: Long, cfg: FloatArray)
    /** Fills [out] with left, top, right, bottom per key; returns the key count. */
    external fun touchRects(handle: Long, out: FloatArray): Int
    /** [kind]: DOWN, MOVE, UP, CANCEL, TICK, CANCEL_ALL. Writes `[code, a, b]` triples to [out]; returns how many. */
    external fun touchEvent(handle: Long, kind: Int, id: Int, x: Float, y: Float, now: Long, out: IntArray): Int
    /** When [TICK] next has work (same clock as the events), or -1. */
    external fun touchDeadline(handle: Long): Long
    external fun touchTrace(handle: Long, xs: FloatArray, ys: FloatArray): Int

    /** Opens the lexicon cached at [path], building it from the two lists (word<TAB>freq, prev<TAB>next<TAB>rank) when missing. */
    external fun lexOpen(path: String, words: ByteArray, bigrams: ByteArray): Long
    external fun lexClose(handle: Long)
    /** 0-255, or -1 when [word] is not in the lexicon. */
    external fun lexFreq(handle: Long, word: String): Int
    /** Lines of `word 0x01 score 0x01 kind 0x01 safe`, best first. [prox] 0 = no layout. */
    external fun lexSuggest(handle: Long, prox: Long, typed: String, prev: String?, limit: Int): String
    external fun lexWords(handle: Long): String
    external fun proxNew(chars: IntArray, xs: FloatArray, ys: FloatArray, keyWidth: Float): Long
    external fun proxFree(handle: Long)

    /** Builds the swipe index from lexicons ([mixed] 1 = mixed-in language) on [alphabet] laid out at [xs]/[ys]. */
    external fun glideNew(lexes: LongArray, mixed: IntArray, alphabet: IntArray, xs: FloatArray, ys: FloatArray, keyW: Float, keyH: Float): Long
    external fun glideFree(handle: Long)
    /** New key positions; false when the layout lacks some of the alphabet (e.g. the symbols page). */
    external fun glideSetGeometry(handle: Long, xs: FloatArray, ys: FloatArray, keyW: Float, keyH: Float): Boolean
    /** `word TAB points` lines of personal words the lexicon lacks, a blank line, then `word TAB points` of favoured words. */
    external fun glideSetPersonal(handle: Long, text: String)
    /** Lines `word TAB cost TAB fit TAB prob`, best first. */
    external fun glideDecode(handle: Long, xs: FloatArray, ys: FloatArray, n: Int): String
    external fun glideLiteral(handle: Long, xs: FloatArray, ys: FloatArray, n: Int): String
    external fun glideObserve(handle: Long, xs: FloatArray, ys: FloatArray, n: Int, word: String, weight: Float)
    external fun glidePlausible(handle: Long, xs: FloatArray, ys: FloatArray, n: Int, word: String): Boolean
    /** The swiping habit as text; a non-null [set] replaces it first (blank = neutral). */
    external fun glideStyle(handle: Long, set: String?): String

    /** [p]: mode, width, height, x, gap, split; [screen]: width, height, strip, keys (dp). [out]: left, width, height, gap, rowScale, splitGap, maxGap. */
    external fun placementSolve(p: FloatArray, screen: FloatArray, out: FloatArray)

    const val DOWN = 0; const val MOVE = 1; const val UP = 2; const val CANCEL = 3; const val TICK = 4; const val CANCEL_ALL = 5
}

/** Action codes from `Action::encode` in wisp-core. */
object Act {
    const val PRESS = 1; const val RELEASE = 2; const val TAP = 3; const val REPEAT = 4
    const val WORD_SELECT = 5; const val WORD_COMMIT = 6; const val WORD_CANCEL = 7
    const val CURSOR = 8; const val POPUP_OPEN = 9; const val POPUP_SELECT = 10; const val POPUP_COMMIT = 11
    const val SHIFT_LETTER = 12
    const val GLIDE_START = 13; const val GLIDE_MOVE = 14; const val GLIDE_END = 15; const val GLIDE_CANCEL = 16
}
