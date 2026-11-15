# ==============================================================================
# Stress Detector & Clinical Companion - ProGuard / R8 Hardening Rules
# Aligned with OWASP MASVS-RESILIENCE & Android Performance Best Practices
# ==============================================================================

# ------------------------------------------------------------------------------
# 1. Optimizations & Aggressive Obfuscation
# ------------------------------------------------------------------------------
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''
-dontusemixedcaseclassnames
-verbose

# Preserve essential runtime metadata
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# Strip Android debug/verbose logging in release builds to prevent information leakage
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# ------------------------------------------------------------------------------
# 2. Native JNI & Security Layer
# ------------------------------------------------------------------------------
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep security, keystore, and RASP native bindings
-keep class com.stressdetector.mobile.security.** { *; }

# Keep openSMILE verbatim prosody JNI bindings
-keep class com.stressdetector.mobile.prosody.** { *; }
-keep class com.stressdetector.mobile.audio.OpenSmileProsodyExtractor { *; }

# ------------------------------------------------------------------------------
# 3. ONNX Runtime & Sherpa ONNX Speech Engine
# ------------------------------------------------------------------------------
-keep class com.microsoft.onnxruntime.** { *; }
-keepclassmembers class com.microsoft.onnxruntime.** { *; }
-dontwarn com.microsoft.onnxruntime.**

-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# ------------------------------------------------------------------------------
# 4. SQLCipher Database Security
# ------------------------------------------------------------------------------
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }
-keep class net.zetetic.** { *; }
-dontwarn net.sqlcipher.**
-dontwarn net.zetetic.**

# ------------------------------------------------------------------------------
# 5. Room Database & DAOs
# ------------------------------------------------------------------------------
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keepclassmembers class * {
    @androidx.room.TypeConverter *;
}
-keep class com.stressdetector.mobile.data.db.** { *; }

# ------------------------------------------------------------------------------
# 6. JSON Data Models & Catalog (Gson Serialization)
# ------------------------------------------------------------------------------
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
    @com.google.gson.annotations.Expose <fields>;
}

# Preserve all data wire and contract classes instantiated via Gson
-keep class com.stressdetector.mobile.signal.** { *; }
-keep class com.stressdetector.mobile.ml.artifacts.** { *; }
-keep class com.stressdetector.inference.** { *; }
-keep class com.stressdetector.mobile.domain.engine.** { *; }
-keep class com.stressdetector.mobile.domain.assessment.** { *; }
-keep class com.stressdetector.mobile.data.repository.** { *; }
-keep class com.stressdetector.mobile.ml.rag.** { *; }

# ------------------------------------------------------------------------------
# 7. Android Architecture Components & Lifecycle
# ------------------------------------------------------------------------------
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep class androidx.lifecycle.** { *; }
-keep class androidx.navigation.** { *; }
-keep class androidx.security.crypto.** { *; }

# ------------------------------------------------------------------------------
# 8. Jetpack Compose
# ------------------------------------------------------------------------------
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.material3.** { *; }

# ------------------------------------------------------------------------------
# 9. Wearable, WorkManager & Third-Party Library Suppressions
# ------------------------------------------------------------------------------
-keep class com.google.android.gms.wearable.** { *; }
-dontwarn com.google.android.gms.wearable.**
-keep class androidx.work.** { *; }

# PDFBox Android optional decoders
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.pdfbox.**

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Apache Commons Compress optional archivers
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**

# LiteRT-LM & ML Kit JNI Bindings
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

-keep class com.google.mlkit.** { *; }
-keepclassmembers class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
