-dontobfuscate

# Classes and members the native libraries look up by name (JNI_OnLoad / RegisterNatives); shrinking them breaks loading.
-keep class com.android.inputmethod.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
