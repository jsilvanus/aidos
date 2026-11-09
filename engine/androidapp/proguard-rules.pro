# java-llama.cpp calls into native JNI methods reflectively.
-keep class de.kherud.llama.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve native JNI library names and entrypoints
-keepclassmembers class * {
    native <methods>;
}

# ONNX Runtime (typed decisions, RFC-0022 D45) loads its JNI bindings reflectively.
-keep class ai.onnxruntime.** { *; }
