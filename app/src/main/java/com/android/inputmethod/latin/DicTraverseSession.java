/*
 * Bridge to the native dictionary engine (HeliBoard / AOSP LatinIME, Apache-2.0 and GPL-3.0).
 * The class names and signatures are fixed by the native registration tables in
 * app/src/main/cpp/latinime; do not rename or change them.
 */

package com.android.inputmethod.latin;

public final class DicTraverseSession {
    private DicTraverseSession() {}

    static { System.loadLibrary("jni_latinime"); }

    public static native long setDicTraverseSessionNative(String locale, long dictSize);
    public static native void initDicTraverseSessionNative(long nativeDicTraverseSession, long dictionary,
            int[] previousWord, int previousWordLength);
    public static native void releaseDicTraverseSessionNative(long nativeDicTraverseSession);
}
