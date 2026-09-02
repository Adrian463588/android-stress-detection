# ONNX Runtime Java API and JNI entry points used through native registration.
-keep class ai.onnxruntime.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-dontwarn ai.onnxruntime.providers.**
