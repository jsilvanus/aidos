package fi.italeino.aidos.sdk

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelKind

/**
 * Factory for creating Aidos Engine ModelAdapter instances (RFC-0103, RFC-0021).
 *
 * Creates LLM, Embedding, and STT adapters that bridge between Aidos Engine's
 * OpenAI-compatible HTTP API and the RFC-0021 ModelAdapter interface.
 *
 * This factory is the primary entry point for consuming apps to get adapters
 * for Engine models. It encapsulates the knowledge of which endpoints exist,
 * which models are available, and how to construct the appropriate adapter type.
 *
 * Usage:
 * ```
 * val factory = EngineModelAdapterFactory(httpClient, capabilities)
 * val llmAdapter = factory.createLlmAdapter("qwen2.5-3b-q4", 2048)
 * ```
 */
class EngineModelAdapterFactory(
    private val httpClient: EngineHttpClient,
    private val capabilities: EngineCapabilities,
) {
    /**
     * Create an LLM ModelAdapter for the given model.
     *
     * Returns a new adapter instance, or null if the model is not available
     * in the capabilities list.
     *
     * Pre-condition: The model must be listed in capabilities.models with
     * modelKind = "LLM" and a present contextWindow.
     */
    fun createLlmAdapter(modelId: String, contextWindow: Int?): ModelAdapter? {
        val modelStatus = capabilities.models.firstOrNull {
            it.modelId == modelId && it.modelKind == "LLM"
        } ?: return null

        // Use provided context window or fall back to status's window
        val window = contextWindow ?: modelStatus.contextWindow ?: 2048

        return EngineLlmAdapter(
            modelId = modelId,
            modelVersion = "engine-v1",
            contextWindow = window,
            httpClient = httpClient,
        )
    }

    /**
     * Create an Embedding ModelAdapter for the given model.
     *
     * Pre-condition: The model must be listed in capabilities.models with
     * modelKind = "EMBEDDING".
     */
    fun createEmbeddingAdapter(modelId: String): ModelAdapter? {
        val modelStatus = capabilities.models.firstOrNull {
            it.modelId == modelId && it.modelKind == "EMBEDDING"
        } ?: return null

        return EngineEmbeddingAdapter(
            modelId = modelId,
            modelVersion = "engine-v1",
            contextWindow = modelStatus.contextWindow ?: 2048,
            httpClient = httpClient,
        )
    }

    /**
     * Create an STT ModelAdapter for the given model.
     *
     * Pre-condition: The model must be listed in capabilities.models with
     * modelKind = "STT".
     */
    fun createSttAdapter(modelId: String): ModelAdapter? {
        val modelStatus = capabilities.models.firstOrNull {
            it.modelId == modelId && it.modelKind == "STT"
        } ?: return null

        return EngineStlAdapter(
            modelId = modelId,
            modelVersion = "engine-v1",
            contextWindow = modelStatus.contextWindow ?: 2048,
            httpClient = httpClient,
        )
    }

    /**
     * Get a ModelAdapter for any kind, looking up the model in capabilities.
     *
     * Returns the appropriate adapter type based on the model's kind field,
     * or null if the model is not available.
     */
    fun createAdapter(modelId: String): ModelAdapter? {
        val modelStatus = capabilities.models.firstOrNull { it.modelId == modelId }
            ?: return null

        return when (modelStatus.modelKind) {
            "LLM" -> createLlmAdapter(modelId, modelStatus.contextWindow)
            "EMBEDDING" -> createEmbeddingAdapter(modelId)
            "STT" -> createSttAdapter(modelId)
            else -> null  // Unsupported model kind for now
        }
    }

    /**
     * Get all available LLM models.
     */
    fun listLlmModels(): List<String> {
        return capabilities.models
            .filter { it.modelKind == "LLM" }
            .map { it.modelId }
    }

    /**
     * Get all available Embedding models.
     */
    fun listEmbeddingModels(): List<String> {
        return capabilities.models
            .filter { it.modelKind == "EMBEDDING" }
            .map { it.modelId }
    }

    /**
     * Get all available STT models.
     */
    fun listSttModels(): List<String> {
        return capabilities.models
            .filter { it.modelKind == "STT" }
            .map { it.modelId }
    }

    /**
     * Check if a specific endpoint is available.
     */
    fun hasEndpoint(endpoint: String): Boolean {
        return capabilities.endpoints.contains(endpoint)
    }
}
