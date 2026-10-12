package dev.aidos.modelruntime

/**
 * How a GGUF/llama.cpp model is run, chosen per model by the user (RFC-0022, RFC-0103).
 * A null field means "the backend's default", so an untouched model behaves exactly as before.
 *
 * All fields are read when the model is loaded, so a change reaches a resident model only after it
 * is reloaded. Models that are not run by llama.cpp (ONNX decision and speech packages) ignore them.
 */
data class ModelRunSettings(
    val contextLength: Int? = null,
    val threads: Int? = null,
    val batchSize: Int? = null,
    val gpuLayers: Int? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
) {
    /** Values clamped into what llama.cpp accepts, so a stored or typed-in value can never break a load. */
    fun coerced(): ModelRunSettings = ModelRunSettings(
        contextLength = contextLength?.coerceIn(CONTEXT_RANGE),
        threads = threads?.coerceIn(THREADS_RANGE),
        batchSize = batchSize?.coerceIn(BATCH_RANGE),
        gpuLayers = gpuLayers?.coerceIn(GPU_LAYERS_RANGE),
        temperature = temperature?.coerceIn(TEMPERATURE_RANGE),
        topP = topP?.coerceIn(TOP_P_RANGE),
        topK = topK?.coerceIn(TOP_K_RANGE),
    )

    /** True when nothing is overridden. */
    val isDefault: Boolean get() = this == DEFAULT

    companion object {
        val DEFAULT = ModelRunSettings()
        val CONTEXT_RANGE = 256..131072
        val THREADS_RANGE = 1..64
        val BATCH_RANGE = 32..4096
        val GPU_LAYERS_RANGE = 0..999
        val TEMPERATURE_RANGE = 0f..2f
        val TOP_P_RANGE = 0.05f..1f
        val TOP_K_RANGE = 0..200

        // What the backends used before settings existed; shown as the placeholder on the card.
        const val DEFAULT_THREADS = 4
        const val DEFAULT_BATCH = 512
        const val DEFAULT_GPU_LAYERS = 0
        const val DEFAULT_TEMPERATURE = 0.7f
        const val DEFAULT_TOP_P = 0.95f
        const val DEFAULT_TOP_K = 40
    }
}

/** Where the runtime looks up a model's settings at load time; the host backs it with its preferences. */
fun interface RunSettingsSource {
    fun settingsFor(modelId: String): ModelRunSettings

    companion object {
        val Defaults = RunSettingsSource { ModelRunSettings.DEFAULT }
    }
}
