package dev.aidos.modelruntime

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TokenUsage
import java.security.MessageDigest

/**
 * Catalog-based inference backend (RFC-0022, M21).
 *
 * Loads models from a catalog, tracks installed models, computes digests,
 * and coordinates loading through the global admission queue.
 */
class CatalogInferenceBackend(
    private val catalogModels: List<ModelDescriptor>,
    private val modelsDirectory: String,
) : InferenceBackend {

    // Track what's installed (in modelsDirectory)
    private val installedCache = mutableMapOf<String, ModelDescriptor>()

    // Track what's currently loaded in memory
    private val loadedAdapters = mutableMapOf<String, ModelAdapter>()

    init {
        // On startup, scan the models directory to populate installedCache.
        // This is a stub — real implementation would list directory contents.
        // For now, treat all catalog models as potentially installed.
        catalogModels.forEach { installedCache[it.id] = it }
    }

    override suspend fun catalog(): List<ModelDescriptor> = catalogModels

    override suspend fun installed(): List<ModelDescriptor> = installedCache.values.toList()

    /**
     * Compute SHA-256 digest of a model file.
     *
     * @param modelId model identifier
     * @return hex-encoded SHA-256 digest
     */
    override suspend fun computeDigest(modelId: String): String {
        // Stub: real implementation would read the file and compute SHA-256
        // For now, return a placeholder
        return "sha256:not-computed-$modelId"
    }

    /**
     * Delete a model from installed_models.
     *
     * @param modelId model identifier
     */
    override suspend fun delete(modelId: String) {
        installedCache.remove(modelId)
        loadedAdapters.remove(modelId)
        // Stub: real implementation would delete the file
    }

    /**
     * Load a model into memory.
     *
     * @param modelId model identifier
     * @return ModelAdapter for inference, or failure
     */
    override suspend fun load(modelId: String): Result<ModelAdapter> {
        // Check if already loaded
        loadedAdapters[modelId]?.let { return Result.success(it) }

        // Get model metadata
        val descriptor = installedCache[modelId]
            ?: return Result.failure(IllegalStateException("Model $modelId not installed"))

        // Stub: real implementation would:
        // 1. Validate the file exists and size matches
        // 2. Call llama.cpp or other GGUF loader
        // 3. Create and return a ModelAdapter
        // For now, return a mock adapter
        val adapter = MockModelAdapter(descriptor)
        loadedAdapters[modelId] = adapter

        return Result.success(adapter)
    }

    override suspend fun unload(modelId: String) {
        loadedAdapters.remove(modelId)
        // Stub: real implementation would free memory/resources
    }
}

/**
 * Mock ModelAdapter for testing and development (RFC-0021).
 *
 * Returns fixed responses; real implementation uses llama.cpp inference.
 */
class MockModelAdapter(val descriptor: ModelDescriptor) : ModelAdapter {
    override val providerId: String = descriptor.providerId
    override val modelId: String = descriptor.id
    override val modelVersion: String = "mock"
    override val contextWindow: Int = descriptor.contextWindow
    override val isLocal: Boolean = descriptor.isLocal

    override fun supportsNativeToolCalls(): Boolean = false

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        return Result.success(
            ModelResponse(
                text = "(mock response from $modelId)",
                toolCalls = emptyList(),
                stopReason = StopReason.END_TURN,
                usage = TokenUsage(
                    inputTokens = request.messages.size * 10,
                    outputTokens = 50,
                ),
                modelId = modelId,
                modelVersion = modelVersion,
            )
        )
    }
}

/**
 * Bundled model catalog (RFC-0022).
 *
 * Ships with the app; updated through app releases.
 */
object BundledCatalog {
    fun load(): List<ModelDescriptor> {
        // Stub: real implementation would deserialize from a bundled JSON/TOML file
        return listOf(
            ModelDescriptor(
                id = "qwen2.5-3b-q4",
                name = "Qwen2.5 3B Q4_K_M",
                kind = ModelKind.LLM,
                providerId = "huggingface",
                isLocal = true,
                contextWindow = 32768,
                sizeBytes = 2_000_000_000,
                digest = null,
            ),
            ModelDescriptor(
                id = "nomic-embed-v1.5",
                name = "nomic-embed 1.5",
                kind = ModelKind.EMBEDDING,
                providerId = "huggingface",
                isLocal = true,
                contextWindow = 2048,
                sizeBytes = 500_000_000,
                digest = null,
            ),
        )
    }
}
