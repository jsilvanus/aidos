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
) {
    val sizeBytes: Long get() = files.sumOf { it.sizeBytes }

    fun downloadUrl(file: RepoFile, revision: String = "main"): String =
        "https://huggingface.co/$modelId/resolve/$revision/${HuggingFaceClient.encodePath(file.path)}"

    companion object {
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
