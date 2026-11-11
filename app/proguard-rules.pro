-dontobfuscate

# Classes and members the native libraries look up by name (JNI_OnLoad / RegisterNatives); shrinking them breaks loading.
-keep class com.android.inputmethod.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# The rewrite engine calls Tokens.onToken by name from native code on whatever object it was given; keep the interface and its implementers
# as written (R8 otherwise merges them and makes the method static, and the call dies with NoSuchMethodError).
-keep interface app.wisp.keyboard.voice.llm.LlamaBridge$Tokens { *; }
-keep class * implements app.wisp.keyboard.voice.llm.LlamaBridge$Tokens { *; }
