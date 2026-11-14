# Keep rules for the on-device inference runtimes (they use reflection / JNI).
-keep class ai.onnxruntime.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text.** { *; }
-keepattributes *Annotation*
-keepattributes Signature
