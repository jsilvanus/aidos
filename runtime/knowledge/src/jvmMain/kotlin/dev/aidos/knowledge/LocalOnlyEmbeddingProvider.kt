package dev.aidos.knowledge

import io.github.jsilvanus.gitsema.embedding.EmbeddingProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Embedding provider that is called from the gitsema-kotlin indexer (RFC-0015, M22).
 *
 * The real implementation delegates to the llama.cpp model loaded by [GlobalModelRuntime]
 * (M21). Until M21 is running on real hardware, this placeholder throws on indexing
 * (so the index stays empty and search degrades to FTS-only) but returns a valid
 * dimensionality — gitsema-kotlin needs [dimensions] at construction time to allocate
 * the flat-file vector store.
 *
 * Wiring to M21 (Phase 3): replace [embed] with a call to the admission-queued inference adapter
 * once the phone has a loaded embedding model. The signature and contract are unchanged.
 *
 * Phase 3 enhancement: supports registering a delegate dynamically via [setEmbeddingDelegate]
 * when a model loads, enabling coverage to improve from 0% (FTS-only) to 100% (with vectors).
 */
class LocalOnlyEmbeddingProvider(
    override val modelId: String,
    override val dimensions: Int,
    private var delegate: (suspend (List<String>) -> List<FloatArray>)? = null,
) : EmbeddingProvider {

    private val delegateLock = Mutex()

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        val currentDelegate = delegateLock.withLock { delegate }
        if (currentDelegate != null) return currentDelegate.invoke(texts)
        // No model loaded yet — indexing is not possible. Search degrades to FTS-only (D29).
        throw IllegalStateException(
            "No local embedding model loaded. Indexing requires an installed model (M21). " +
            "Search degrades to FTS-only until a model is installed."
        )
    }

    /**
     * Register an embedding delegate (Phase 3: Embedding model wiring).
     * 
     * Called when GlobalModelRuntime loads an embedding model. Allows coverage
     * to improve from 0% (FTS-only) to 100% (semantic) as blobs get embedded.
     * 
     * Thread-safe: multiple calls to setEmbeddingDelegate() are serialized;
     * concurrent embed() calls see consistent delegate behavior.
     */
    suspend fun setEmbeddingDelegate(newDelegate: (suspend (List<String>) -> List<FloatArray>)?) {
        delegateLock.withLock {
            delegate = newDelegate
        }
    }

    companion object {
        /** Dimensions for nomic-embed-text (the planned Aidos embedding model). */
        const val NOMIC_MODEL_ID = "nomic-embed-text-v1.5"
        const val NOMIC_DIMENSIONS = 768

        /** Creates a placeholder provider with the Aidos default model spec and no delegate. */
        fun placeholder(): LocalOnlyEmbeddingProvider =
            LocalOnlyEmbeddingProvider(NOMIC_MODEL_ID, NOMIC_DIMENSIONS)

        /**
         * Create a provider with an initial delegate (Phase 3: used when model already loaded).
         */
        fun withDelegate(
            delegate: suspend (List<String>) -> List<FloatArray>,
        ): LocalOnlyEmbeddingProvider =
            LocalOnlyEmbeddingProvider(NOMIC_MODEL_ID, NOMIC_DIMENSIONS, delegate)
    }
}

