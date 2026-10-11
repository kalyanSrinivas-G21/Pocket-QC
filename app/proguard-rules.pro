# Pocket QC R8 / ProGuard Configuration

# 1. Native methods and JNI bindings
-keepclasseswithmembernames class * {
    native <methods>;
}

# 2. ONNX Runtime & Qualcomm QNN JNI
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Preserve reflection calls for addQnn and SessionOptions
-keepclassmembers class ai.onnxruntime.OrtSession$SessionOptions {
    public void addQnn(java.util.Map);
    *** add*(...);
}

# 3. ZXing QR Code Generation
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# 4. NanoHTTPD (Local Distributed Server)
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# 5. OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature
-keepattributes *Annotation*

# 6. Core Data Models and Persistence
-keep class com.pocketqc.core.model.** { *; }
-keep class com.pocketqc.distributed.model.** { *; }
-keep class com.pocketqc.inference.** { *; }
-keep class com.pocketqc.settings.** { *; }
-keep class com.pocketqc.volume3d.** { *; }
-keep class com.pocketqc.uv.** { *; }
-keep class com.pocketqc.passport.** { *; }

# 7. Kotlin Coroutines & Reflection
-keepattributes EnclosingMethod
-keepattributes InnerClasses
