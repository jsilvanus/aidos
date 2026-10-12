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
    /** What the file is for. Only [ArtifactRole.WEIGHTS] is a model a user can pick on its own. */
    val role: ArtifactRole = ArtifactRole.of(filename),
)

/**
 * Why a file is in a repo. A GGUF repo routinely holds `mmproj-*.gguf` vision projectors and
 * `-00001-of-00003.gguf` shards beside the quantizations; offering either as a standalone model
 * installs a file llama.cpp cannot use on its own.
 */
enum class ArtifactRole {
    WEIGHTS,
    /** Multimodal projector (`mmproj`), only useful next to a language model. */
    PROJECTOR,
    /** One part of a split GGUF; all parts must be installed together. */
    SHARD,
    /** Tensors stored beside an ONNX graph (`model.onnx_data`); the graph does not load without it. */
    EXTERNAL_DATA;

    companion object {
        private val shardName = Regex(".*-\\d{5}-of-\\d{5}\\.gguf")

        fun of(filename: String): ArtifactRole {
            val name = filename.substringAfterLast('/').lowercase()
            return when {
                name.endsWith(".onnx_data") -> EXTERNAL_DATA
                name.endsWith(".gguf") && name.contains("mmproj") -> PROJECTOR
                shardName.matches(name) -> SHARD
                else -> WEIGHTS
            }
        }
    }
}

enum class ModelFormat(
    val extensions: Set<String>,
    val defaultBackends: List<String>,
) {
    GGUF(setOf("gguf"), listOf("llama.cpp")),
    ONNX(setOf("onnx", "onnx_data"),listOf("onnx-runtime")),
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
