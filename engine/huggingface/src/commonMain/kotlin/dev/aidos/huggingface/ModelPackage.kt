package dev.aidos.huggingface

/** One file in a Hugging Face repo (any file, not only model weights). */
data class RepoFile(
    val path: String,
    val sizeBytes: Long = 0,
    val sha256Digest: String? = null,
)

/**
 * A set of repo files that only works together, installed into one directory of the Engine's
 * models folder. A single-file download cannot express this: an ONNX decision model such as Laya
 * needs its graph, its tokenizer and its config side by side (RFC-0022 D45, `LayaPackage`).
 *
 * @property directory the repo folder the graph lives in ("" for the repo root, "onnx" for the
 *   Optimum/transformers.js layout). Files are installed flat, under their base names.
 */
data class ModelPackage(
    val modelId: String,
    val name: String,
    val format: ModelFormat,
    val directory: String,
    val files: List<RepoFile>,
    /** True when it has everything the Engine's Laya/decision loader reads: `.onnx`, tokenizer and `rl_agent_config.json`. */
    val isDecisionPackage: Boolean,
    /** True for a Whisper speech-to-text package: encoder + decoder graphs and `tokenizer.json` (`WhisperPackage`). */
    val isSpeechPackage: Boolean = false,
) {
    val sizeBytes: Long get() = files.sumOf { it.sizeBytes }

    fun downloadUrl(file: RepoFile, revision: String = "main"): String =
        "https://huggingface.co/$modelId/resolve/$revision/${HuggingFaceClient.encodePath(file.path)}"

    companion object {
        /** Graph-name suffixes in the order a Whisper install prefers them: int8 first, the size phones can afford. */
        private val whisperPrecisions = listOf("_quantized", "_int8", "", "_fp16")

        /**
         * The Whisper package in an `onnx-community/whisper-*` (or Optimum export) repo: one encoder and one
         * KV-cached decoder of the same precision, plus tokenizer and config files. [detectOnnx] would
         * split these into one package per graph, none of which is loadable alone. Null when the repo is not
         * a Whisper export (no `encoder_model*` beside a `decoder_model_merged*`, or no `tokenizer.json`).
         */
        fun detectWhisper(modelId: String, files: List<RepoFile>): ModelPackage? {
            val byPath = files.associateBy { it.path }
            for (suffix in whisperPrecisions) {
                val enc = files.firstOrNull { it.path.substringAfterLast('/') == "encoder_model$suffix.onnx" } ?: continue
                val dir = enc.path.substringBeforeLast('/', "")
                fun inDir(name: String) = if (dir.isEmpty()) name else "$dir/$name"
                val dec = byPath[inDir("decoder_model_merged$suffix.onnx")] ?: continue
                val tokenizer = byPath[inDir("tokenizer.json")] ?: byPath["tokenizer.json"] ?: return null
                val side = listOf("generation_config.json", "config.json", "preprocessor_config.json")
                    .mapNotNull { byPath[inDir(it)] ?: byPath[it] }
                // Large exports keep weights in `<graph>.onnx_data` beside the graph.
                val data = files.filter { f ->
                    f.path.substringBeforeLast('/', "") == dir &&
                        f.path.substringAfterLast('/').let { it == "encoder_model$suffix.onnx_data" || it == "decoder_model_merged$suffix.onnx_data" }
                }
                return ModelPackage(
                    modelId = modelId,
                    name = "whisper${suffix.ifEmpty { "_fp32" }}",
                    format = ModelFormat.ONNX,
                    directory = dir,
                    files = listOf(enc, dec, tokenizer) + side + data,
                    isDecisionPackage = false,
                    isSpeechPackage = true,
                )
            }
            return null
        }

        /** Files a tokenizer/runtime reads beside an ONNX graph. */
        private val companions = setOf(
            "tokenizer.json", "tokenizer_config.json", "special_tokens_map.json",
            "config.json", "generation_config.json", "rl_agent_config.json",
            "vocab.txt", "vocab.json", "merges.txt", "sentencepiece.bpe.model", "tokenizer.model",
        )

        /**
         * ONNX packages in [files]: one per `.onnx` graph, with its `.onnx_data` sidecar and the
         * companion files from the same folder, falling back to the repo root (where transformers.js
         * repos keep the tokenizer while the graph sits in `onnx/`). Repos with several graphs in
         * one folder (`model.onnx`, `model_quantized.onnx`, ...) yield several packages that share
         * the companions.
         */
        fun detectOnnx(modelId: String, files: List<RepoFile>): List<ModelPackage> {
            val byPath = files.associateBy { it.path }
            return files.filter { it.path.lowercase().endsWith(".onnx") }.map { graph ->
                val dir = graph.path.substringBeforeLast('/', "")
                val base = graph.path.substringAfterLast('/')
                fun inDir(name: String) = if (dir.isEmpty()) name else "$dir/$name"
                val data = files.filter {
                    it.path.substringBeforeLast('/', "") == dir &&
                        it.path.substringAfterLast('/').let { n -> n == "${base}_data" || n == "$base.data" }
                }
                val side = companions.mapNotNull { name -> byPath[inDir(name)] ?: byPath[name] }
                val all = (listOf(graph) + data + side).distinctBy { it.path.substringAfterLast('/') }
                val names = all.map { it.path.substringAfterLast('/') }.toSet()
                ModelPackage(
                    modelId = modelId,
                    name = base.removeSuffix(".onnx"),
                    format = ModelFormat.ONNX,
                    directory = dir,
                    files = all,
                    isDecisionPackage = "tokenizer.json" in names && "rl_agent_config.json" in names,
                )
            }
        }
    }
}
