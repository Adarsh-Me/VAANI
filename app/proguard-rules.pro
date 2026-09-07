# iTantra ProGuard rules. ONNX Runtime + Room + data models must survive shrinking.
-keep class ai.onnxruntime.** { *; }
# sherpa-onnx JNI binds native methods on these Kotlin classes (Piper/VITS TTS).
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.itantra.models.** { *; }
-keep class com.itantra.data.** { *; }
-keep class com.itantra.network.Packet { *; }
-keep class androidx.room.** { *; }
-dontwarn ai.onnxruntime.**
