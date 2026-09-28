package fi.italeino.aidos.engine.suggestions

/**
 * A model the Engine offers for one-tap install on the Models screen (RFC-0103, RFC-0022).
 *
 * Suggestions point at one exact artifact in a Hugging Face repo rather than at a repo to browse,
 * so installing one needs no quantization choice. [runnable] is false for formats this Engine
 * build has no backend for: the artifact can still be installed, but the UI must say it cannot
 * be loaded instead of pretending otherwise.
 */
data class SuggestedModel(
    val id: String,
    val name: String,
    val description: String,
    val repoId: String,
    val filename: String,
    val format: String,
    val backend: String,
    val approxSizeBytes: Long,
    val runnable: Boolean,
) {
    val downloadUrl: String get() = "https://huggingface.co/$repoId/resolve/main/$filename"
}

object SuggestedModels {

    /**
     * The smoke-test fixtures from `models/` (see models/README.md), published by jsilvanus.
     * Their output is known in advance for every input, so they confirm the install -> load ->
     * generate path works end to end before a user commits to a multi-GB download.
     */
    val all: List<SuggestedModel> = listOf(
        SuggestedModel(
            id = "jsilvanus/echo-gguf",
            name = "Echo (GGUF)",
            description = "2.3 MB llama-architecture smoke-test model that repeats its input byte for byte. " +
                "Runs on llama.cpp.",
            repoId = "jsilvanus/echo-gguf",
            filename = "echo.gguf",
            format = "gguf",
            backend = "llama.cpp",
            approxSizeBytes = 2_367_680,
            runnable = true,
        ),
        SuggestedModel(
            id = "jsilvanus/echo-onnx",
            name = "Echo (ONNX)",
            description = "258 KB ONNX identity model. Installs, but this Engine build has no ONNX " +
                "runtime yet, so it cannot be loaded.",
            repoId = "jsilvanus/echo-onnx",
            filename = "echo.onnx",
            format = "onnx",
            backend = "onnx-runtime",
            approxSizeBytes = 264_192,
            runnable = false,
        ),
    )

    /** Suggestions still shown after the user dismissed [dismissedIds]. */
    fun visible(dismissedIds: Set<String>): List<SuggestedModel> = all.filterNot { it.id in dismissedIds }

    /** File name the artifact is stored under in the Engine's models directory. */
    fun artifactName(model: SuggestedModel): String =
        model.repoId.replace(Regex("[^A-Za-z0-9._-]"), "_") + "_" + model.filename
}
