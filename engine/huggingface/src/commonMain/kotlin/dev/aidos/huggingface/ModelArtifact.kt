package dev.aidos.huggingface

/** A model artifact published on the Hugging Face Hub. */
data class ModelArtifact(
    val filename: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val sha256Digest: String? = null,
    val format: ModelFormat,
    /** Backends that can consume this artifact, in preferred order. */
    val compatibleBackends: List<String> = format.defaultBackends,
)

enum class ModelFormat(
    val extensions: Set<String>,
    val defaultBackends: List<String>,
) {
    GGUF(setOf("gguf"), listOf("llama.cpp")),
    ONNX(setOf("onnx"), listOf("onnx-runtime")),
    EXECUTORCH(setOf("pte"), listOf("executorch")),
    TFLITE(setOf("tflite"), listOf("litert")),
    OPENVINO(setOf("xml"), listOf("openvino")),
    CORE_ML(setOf("mlmodel", "mlpackage"), listOf("coreml")),
    SAFETENSORS(setOf("safetensors"), emptyList()),
    PYTORCH(setOf("bin", "pt", "pth"), emptyList()),
    UNKNOWN(emptySet(), emptyList());

    companion object {
        fun fromFilename(filename: String): ModelFormat {
            val extension = filename.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { extension in it.extensions } ?: UNKNOWN
        }
    }
}
