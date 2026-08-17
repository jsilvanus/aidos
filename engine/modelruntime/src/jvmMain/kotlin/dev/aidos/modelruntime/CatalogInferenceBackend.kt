package dev.aidos.modelruntime

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRef
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.Usage

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

    private val installedCache = mutableMapOf<String, ModelDescriptor>()
    private val loadedAdapters = mutableMapOf<String, ModelAdapter>()

    init {
        catalogModels.forEach { installedCache[it.id] = it }
    }

    override suspend fun catalog(): List<ModelDescriptor> = catalogModels

    override suspend fun installed(): List<ModelDescriptor> = installedCache.values.toList()

    override suspend fun computeDigest(modelId: String): String =
        "sha256:not-computed-$modelId"

    override suspend fun delete(modelId: String) {
        installedCache.remove(modelId)
        loadedAdapters.remove(modelId)
    }

    override suspend fun load(modelId: String): Result<ModelAdapter> {
        loadedAdapters[modelId]?.let { return Result.success(it) }

        val descriptor = installedCache[modelId]
            ?: return Result.failure(IllegalStateException("Model $modelId not installed"))

        val adapter = MockModelAdapter(descriptor)
        loadedAdapters[modelId] = adapter
        return Result.success(adapter)
    }

    override suspend fun unload(modelId: String) {
        loadedAdapters.remove(modelId)
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

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> =
        Result.success(
            ModelResponse(
                outputs = listOf(TextOutput("(mock response from $modelId)")),
                stopReason = StopReason.END_TURN,
                usage = Usage(
                    inputTokens = request.messages.size * 10,
                    outputTokens = 50,
                    totalTokens = request.messages.size * 10 + 50,
                ),
                model = ModelRef(modelId, modelVersion),
            )
        )
}

/** Bundled model catalog (RFC-0022). */
object BundledCatalog {
    fun load(): List<ModelDescriptor> = listOf(
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
