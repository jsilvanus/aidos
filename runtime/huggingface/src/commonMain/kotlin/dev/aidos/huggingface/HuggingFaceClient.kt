package dev.aidos.huggingface

import dev.aidos.kernel.ModelKind

/**
 * Hugging Face model metadata from the API (RFC-0022).
 *
 * Fetched from the Hugging Face Hub API and cached locally.
 */
data class HuggingFaceModel(
    val modelId: String,
    val author: String,
    val displayName: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val downloads: Long = 0,
    val likes: Long = 0,
    val pipeline: String? = null,
    val modelSize: Long? = null,
    val quantizations: List<Quantization> = emptyList(),
)

/**
 * A quantized variant of a model.
 */
data class Quantization(
    val name: String, // e.g., "Q4_K_M", "Q5_K_M", "fp16"
    val sizeBytes: Long,
    val downloadUrl: String,
    val sha256Digest: String? = null,
)

/**
 * Search result from HuggingFace API.
 */
data class HuggingFaceSearchResult(
    val total: Int,
    val models: List<HuggingFaceModel>,
)

/**
 * Hugging Face API client (RFC-0022).
 *
 * No external dependencies — uses HTTP via the broker's egress system (RFC-0030).
 * Implements model discovery and metadata fetching.
 */
class HuggingFaceClient(
    private val apiBaseUrl: String = "https://huggingface.co/api/models",
) {

    /**
     * Search for models on Hugging Face.
     *
     * @param query search query (e.g., "qwen2.5 3b gguf")
     * @param filter optional filter (e.g., task:text-generation, library:gguf)
     * @param sort sort order (e.g., "downloads", "trending")
     * @param limit max results to return
     * @return search results
     */
    suspend fun search(
        query: String,
        filter: String? = null,
        sort: String = "downloads",
        limit: Int = 10,
    ): Result<HuggingFaceSearchResult> {
        return try {
            // In a real implementation, this would call an HTTP client via the broker.
            // For now, return a placeholder result structure.
            Result.success(
                HuggingFaceSearchResult(
                    total = 0,
                    models = emptyList(),
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch metadata for a specific model.
     *
     * @param modelId HuggingFace model ID (e.g., "TheBloke/Qwen2.5-3B-Instruct-GGUF")
     * @return model metadata
     */
    suspend fun getModel(modelId: String): Result<HuggingFaceModel> {
        return try {
            // Real implementation would fetch from /repos/{modelId}
            Result.success(
                HuggingFaceModel(
                    modelId = modelId,
                    author = "unknown",
                    displayName = modelId,
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * List quantizations for a model (files in the repo).
     *
     * @param modelId HuggingFace model ID
     * @return list of quantized variants
     */
    suspend fun listQuantizations(modelId: String): Result<List<Quantization>> {
        return try {
            // Real implementation would parse repo structure
            Result.success(emptyList())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Map HuggingFace model tags to ModelKind.
     */
    fun inferModelKind(tags: List<String>, pipeline: String?): ModelKind {
        val tagString = tags.joinToString(" ").lowercase()
        val pipelineStr = pipeline?.lowercase() ?: ""

        return when {
            "text-generation" in tagString || "causal-lm" in tagString ||
                    "text-generation" in pipelineStr -> ModelKind.LLM

            "embedding" in tagString || "sentence-transformers" in tagString ||
                    "feature-extraction" in pipelineStr -> ModelKind.EMBEDDING

            "speech-recognition" in tagString || "automatic-speech-recognition" in tagString ||
                    "speech-recognition" in pipelineStr -> ModelKind.STT

            "text-to-speech" in tagString || "text-to-speech" in pipelineStr -> ModelKind.TTS

            "image-to-text" in tagString || "visual-question-answering" in tagString ||
                    "image-classification" in tagString -> ModelKind.VISION

            "ocr" in tagString || "object-detection" in tagString -> ModelKind.OCR

            "reranker" in tagString || "cross-encoder" in tagString -> ModelKind.RERANKER

            "translation" in tagString || "machine-translation" in tagString -> ModelKind.TRANSLATION

            else -> ModelKind.LLM // default to LLM
        }
    }
}

/**
 * Configuration for a user-registered custom endpoint (RFC-0021).
 *
 * Allows users to point Aidos at their own OpenAI-compatible API.
 */
data class CustomEndpointConfig(
    val name: String,
    val baseUrl: String,
    val modelName: String,
    val apiKeyId: String? = null, // Reference to vault.db secret ID
)
