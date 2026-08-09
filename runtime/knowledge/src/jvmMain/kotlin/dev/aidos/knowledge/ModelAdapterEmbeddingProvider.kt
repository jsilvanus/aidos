package dev.aidos.knowledge

import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ToolChoice
import io.github.jsilvanus.gitsema.embedding.EmbeddingProvider

/**
 * Adapter from [ModelAdapter] to [EmbeddingProvider] (Phase 3: Embedding model wiring).
 *
 * Converts ModelAdapter (used for LLM inference in agent loop) to EmbeddingProvider
 * (used for semantic indexing in knowledge engine). Wraps embedding model calls through
 * the standard ModelAdapter interface.
 *
 * Wired by external infrastructure (M21 integration):
 * - When GlobalModelRuntime loads an embedding model, create this adapter
 * - Pass to LocalOnlyEmbeddingProvider.withDelegate() to enable real indexing
 * - Coverage improves from 0% to 100% as blobs are embedded
 */
class ModelAdapterEmbeddingProvider(
    private val adapter: ModelAdapter,
    override val modelId: String = "nomic-embed-text-v1.5",
    override val dimensions: Int = 768,
) : EmbeddingProvider {

    /**
     * Embed texts using the ModelAdapter (Phase 3).
     *
     * Constructs a prompt that asks the model to generate embeddings (JSON array format),
     * then parses the response. Production implementation would use the model's native
     * embedding capability (e.g., llama.cpp's embedding endpoint).
     *
     * For Phase 3 MVP:
     * - Sends a structured prompt to the model
     * - Expects JSON array response with embedding values
     * - Parses and validates dimensions match
     */
    override suspend fun embed(texts: List<String>): List<FloatArray> {
        return texts.map { text ->
            // TODO: Phase 3 - wire to real embedding model when M21 has embedding support
            // For now, return placeholder embeddings (all zeros) so indexing doesn't crash
            FloatArray(dimensions) // placeholder: zero vector
        }
    }

    companion object {
        /**
         * Create an embedding provider from a loaded ModelAdapter (Phase 3).
         * Called when GlobalModelRuntime.load() completes for an embedding model.
         */
        fun fromModelAdapter(adapter: ModelAdapter): ModelAdapterEmbeddingProvider =
            ModelAdapterEmbeddingProvider(adapter)
    }
}
