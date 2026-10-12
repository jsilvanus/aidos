# java-llama.cpp calls into native JNI methods reflectively.
-keep class de.kherud.llama.** { *; }

# ONNX Runtime (typed decisions, RFC-0022 D45) loads its JNI bindings reflectively.
-keep class ai.onnxruntime.** { *; }
