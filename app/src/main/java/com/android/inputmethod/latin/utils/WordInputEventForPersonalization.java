/*
 * Bridge to the native dictionary engine (HeliBoard / AOSP LatinIME, Apache-2.0 and GPL-3.0).
 * The class names and signatures are fixed by the native registration tables in
 * app/src/main/cpp/latinime; do not rename or change them.
 */

package com.android.inputmethod.latin.utils;

/** Field names are read by the native engine (updateEntriesForInputEventsNative); keep them as they are. */
public final class WordInputEventForPersonalization {
    public final int[] mTargetWord;
    public final int mPrevWordsCount;
    public final int[][] mPrevWordArray = new int[3][];
    public final boolean[] mIsPrevWordBeginningOfSentenceArray = new boolean[3];
    public final boolean mIsValid;
    public final int mTimestamp;

    public WordInputEventForPersonalization(int[] targetWord, int timestamp, boolean isValid) {
        mTargetWord = targetWord;
        mPrevWordsCount = 0;
        mIsValid = isValid;
        mTimestamp = timestamp;
    }
}
