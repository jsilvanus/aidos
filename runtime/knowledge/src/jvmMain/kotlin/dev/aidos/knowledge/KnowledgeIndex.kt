package dev.aidos.knowledge

/**
 * Aidos knowledge index (RFC-0015, M22).
 *
 * This interface is the seam between Aidos and the gitsema-kotlin retrieval core.
 * Every query carries [IndexCoverage] — the fraction of blobs that have a vector
 * for the active model. The caller can decide how to communicate this to the user;
 * Aidos never hides degraded results behind a blocking wait (D29).
 *
 * Indexing is a background job, cancellable mid-run. Search degrades to FTS-only
 * when no embeddings exist yet (constraint 5 from the gitsema porting brief).
 */
interface KnowledgeIndex {
    /**
     * Index [ref] in the caller's coroutine. Cancellable — cancel the surrounding
     * coroutine (or its scope) to interrupt mid-run. On next call, resumes from the
     * last fully committed checkpoint (ancestry-aware cursor, never insertion-order).
     *
     * Call from a `launch {}` in the host's background scope to avoid blocking the caller.
     */
    suspend fun startIndexing(ref: String, onProgress: (IndexingProgress) -> Unit = {})

    /**
     * Search for [query] and return the top results.
     *
     * Never blocks on indexing: if no vectors exist, degrades to FTS-only.
     * [SearchResponse.coverage] always reflects coverage at query time.
     */
    suspend fun search(query: String, topK: Int = 10, branch: String? = null): SearchResponse

    /**
     * Coverage snapshot for the current model: how many blobs are indexed vs. known.
     */
    suspend fun coverage(): IndexCoverage
}

/** Coverage of a knowledge index for the active embedding model (RFC-0015, D29). */
data class IndexCoverage(
    val blobsEmbedded: Long,
    val blobsKnown: Long,
) {
    val fraction: Double get() = if (blobsKnown == 0L) 0.0 else blobsEmbedded.toDouble() / blobsKnown.toDouble()
    val isComplete: Boolean get() = blobsKnown > 0L && blobsEmbedded >= blobsKnown
}

/** One result from a knowledge search. */
data class KnowledgeMatch(
    val blobHash: String,
    val paths: List<String>,
    val startLine: Int,
    val endLine: Int,
    val score: Double,
    /** Whether this match came from vector search (true) or FTS-only degraded mode (false). */
    val fromVector: Boolean,
)

/** Response from a knowledge search, always including coverage. */
data class SearchResponse(
    val matches: List<KnowledgeMatch>,
    val coverage: IndexCoverage,
    /** True when the search ran without any vectors for the active model (FTS-only path). */
    val degraded: Boolean,
)

/** Progress update while indexing. */
data class IndexingProgress(
    val commitsProcessed: Int,
    val blobsSeen: Int,
    val blobsIndexed: Int,
    val blobsFailed: Int,
)
