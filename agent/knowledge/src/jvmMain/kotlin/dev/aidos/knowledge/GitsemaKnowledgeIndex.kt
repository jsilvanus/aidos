package dev.aidos.knowledge

import io.github.jsilvanus.gitsema.SemanticIndex
import io.github.jsilvanus.gitsema.model.MatchProvenance
import io.github.jsilvanus.gitsema.model.Query

/**
 * [KnowledgeIndex] implementation backed by a [SemanticIndex] from gitsema-kotlin (RFC-0015, M22).
 *
 * Wraps the gitsema-kotlin retrieval core and maps its domain types to Aidos's domain
 * types. This adapter is the only place in Aidos that imports gitsema-kotlin directly.
 *
 * Coverage is always reported (never hidden). Search never blocks on indexing: if no
 * vectors exist, gitsema's [SemanticIndex.search] returns FTS-only results with
 * [SearchResponse.degraded] = true.
 */
class GitsemaKnowledgeIndex(
    private val inner: SemanticIndex,
) : KnowledgeIndex {

    override suspend fun startIndexing(ref: String, onProgress: (IndexingProgress) -> Unit) {
        inner.index(ref, onProgress = { progress ->
            onProgress(IndexingProgress(
                commitsProcessed = progress.commitsProcessed,
                blobsSeen = progress.blobsSeen,
                blobsIndexed = progress.blobsIndexed,
                blobsFailed = progress.blobsFailed,
            ))
        })
    }

    override suspend fun search(query: String, topK: Int, branch: String?): SearchResponse {
        val result = inner.search(Query(text = query, topK = topK, branch = branch))
        return SearchResponse(
            matches = result.matches.map { m ->
                KnowledgeMatch(
                    blobHash = m.blobHash.value,
                    paths = m.paths.map { it.value },
                    startLine = m.startLine,
                    endLine = m.endLine,
                    score = m.score,
                    fromVector = m.provenance == MatchProvenance.VECTOR || m.provenance == MatchProvenance.HYBRID,
                )
            },
            coverage = IndexCoverage(
                blobsEmbedded = result.coverage.blobsEmbedded,
                blobsKnown = result.coverage.blobsKnown,
            ),
            degraded = result.degraded,
        )
    }

    override suspend fun coverage(): IndexCoverage {
        val status = inner.status()
        return IndexCoverage(
            blobsEmbedded = status.coverage.blobsEmbedded,
            blobsKnown = status.coverage.blobsKnown,
        )
    }
}
