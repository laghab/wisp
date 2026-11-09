/*
 * Bridge to the native dictionary engine (HeliBoard / AOSP LatinIME, Apache-2.0 and GPL-3.0).
 * The class names and signatures are fixed by the native registration tables in
 * app/src/main/cpp/latinime; do not rename or change them.
 */

package com.android.inputmethod.keyboard;

public final class ProximityInfo {
    private ProximityInfo() {}

    static { System.loadLibrary("jni_latinime"); }

    public static native long setProximityInfoNative(int displayWidth, int displayHeight, int gridWidth,
            int gridHeight, int mostCommonKeyWidth, int mostCommonKeyHeight, int[] proximityCharsArray,
            int keyCount, int[] keyXCoordinates, int[] keyYCoordinates, int[] keyWidths, int[] keyHeights,
            int[] keyCharCodes, float[] sweetSpotCenterXs, float[] sweetSpotCenterYs, float[] sweetSpotRadii);
    public static native void releaseProximityInfoNative(long nativeProximityInfo);
}
