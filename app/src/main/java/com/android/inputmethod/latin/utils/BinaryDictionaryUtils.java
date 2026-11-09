/*
 * Bridge to the native dictionary engine (HeliBoard / AOSP LatinIME, Apache-2.0 and GPL-3.0).
 * The class names and signatures are fixed by the native registration tables in
 * app/src/main/cpp/latinime; do not rename or change them.
 */

package com.android.inputmethod.latin.utils;

public final class BinaryDictionaryUtils {
    private BinaryDictionaryUtils() {}

    static { System.loadLibrary("jni_latinime"); }

    public static native boolean createEmptyDictFileNative(String filePath, long dictVersion, String locale,
            String[] attributeKeyStringArray, String[] attributeValueStringArray);
    public static native float calcNormalizedScoreNative(int[] before, int[] after, int score);
    public static native int setCurrentTimeForTestNative(int currentTime);
}
