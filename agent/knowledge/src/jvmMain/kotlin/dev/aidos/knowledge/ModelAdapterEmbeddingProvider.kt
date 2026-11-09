package dev.aidos.knowledge

import dev.aidos.kernel.EmbeddingModelAdapter
import dev.aidos.kernel.ModelAdapter
import io.github.jsilvanus.gitsema.embedding.EmbeddingProvider

/**
 * Adapter from [ModelAdapter] to [EmbeddingProvider] (RFC-0015, RFC-0022, PIPELINE.md §2.4).
 *
 * Converts a native embedding-capable [EmbeddingModelAdapter] (such as llama.cpp's native embedding backend)
 * to [EmbeddingProvider] used for semantic indexing in gitsema-kotlin.
 *
 * Replaces placeholder/zero vectors with actual native llama.cpp embedding generation so the
 * knowledge index operates with valid vector dimensions and non-zero representations.
 */
class ModelAdapterEmbeddingProvider(
    private val adapter: ModelAdapter,
    override val modelId: String = adapter.modelId,
    override val dimensions: Int = 768,
) : EmbeddingProvider {

    /**
     * Embed texts using the native [EmbeddingModelAdapter] (llama.cpp backend).
     */
    override suspend fun embed(texts: List<String>): List<FloatArray> {
        val embeddingAdapter = adapter as? EmbeddingModelAdapter
            ?: throw UnsupportedOperationException("Model $modelId does not support native embeddings (expected EmbeddingModelAdapter)")

        return texts.map { text ->
            val vector = embeddingAdapter.embed(text).getOrThrow()
            if (vector.isEmpty()) {
                throw IllegalStateException("Embedding model $modelId returned an empty vector")
            }
            if (dimensions > 0 && vector.size != dimensions) {
                throw IllegalStateException(
                    "Embedding dimension mismatch for model $modelId: expected $dimensions, got ${vector.size}"
                )
            }
            vector
        }
    }

    companion object {
        /**
         * Create an embedding provider from a loaded ModelAdapter.
         * Resolves modelId and dimensions from the adapter if known.
         */
        fun fromModelAdapter(
            adapter: ModelAdapter,
            dimensions: Int = 768,
        ): ModelAdapterEmbeddingProvider =
            ModelAdapterEmbeddingProvider(adapter, modelId = adapter.modelId, dimensions = dimensions)
    }
}
