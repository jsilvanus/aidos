package dev.aidos.knowledge

import io.github.jsilvanus.gitsema.SemanticIndex
import io.github.jsilvanus.gitsema.GitsemaSemanticIndex
import io.github.jsilvanus.gitsema.model.IndexCoverage as GitsemaCoverage
import io.github.jsilvanus.gitsema.model.IndexProgress
import io.github.jsilvanus.gitsema.model.IndexResult
import io.github.jsilvanus.gitsema.model.IndexStatus
import io.github.jsilvanus.gitsema.model.Match
import io.github.jsilvanus.gitsema.model.MatchProvenance
import io.github.jsilvanus.gitsema.model.BlobHash
import io.github.jsilvanus.gitsema.model.RepoPath
import io.github.jsilvanus.gitsema.model.Query
import io.github.jsilvanus.gitsema.model.SearchResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M22 knowledge index tests (RFC-0015).
 *
 * Uses a fake [SemanticIndex] to test the adapter without a real model or repository.
 * Real end-to-end indexing on a real repository is verified at M26 (G3 on device).
 */
class KnowledgeIndexTest {

    // ── IndexCoverage arithmetic ───────────────────────────────────────────────

    @Test
    fun `coverage fraction is zero when nothing is known`() {
        val c = IndexCoverage(blobsEmbedded = 0, blobsKnown = 0)
        assertEquals(0.0, c.fraction)
        assertFalse(c.isComplete)
    }

    @Test
    fun `coverage fraction is 1_0 when all blobs are embedded`() {
        val c = IndexCoverage(blobsEmbedded = 42, blobsKnown = 42)
        assertEquals(1.0, c.fraction)
        assertTrue(c.isComplete)
    }

    @Test
    fun `coverage fraction is partial when some blobs are embedded`() {
        val c = IndexCoverage(blobsEmbedded = 50, blobsKnown = 200)
        assertEquals(0.25, c.fraction)
        assertFalse(c.isComplete)
    }

    // ── LocalOnlyEmbeddingProvider ─────────────────────────────────────────────

    @Test
    fun `placeholder returns correct model id and dimensions`() {
        val p = LocalOnlyEmbeddingProvider.placeholder()
        assertEquals(LocalOnlyEmbeddingProvider.NOMIC_MODEL_ID, p.modelId)
        assertEquals(LocalOnlyEmbeddingProvider.NOMIC_DIMENSIONS, p.dimensions)
    }

    @Test
    fun `placeholder embed throws when no delegate`() = runTest {
        val p = LocalOnlyEmbeddingProvider.placeholder()
        var threw = false
        try {
            p.embed(listOf("hello"))
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw, "placeholder with no delegate must throw — no model loaded yet (M21 gate)")
    }

    @Test
    fun `provider with delegate returns embedded vectors`() = runTest {
        val fakeEmbed: suspend (List<String>) -> List<FloatArray> = { texts ->
            texts.map { FloatArray(4) { 0.5f } }
        }
        val p = LocalOnlyEmbeddingProvider("test-model", 4, fakeEmbed)
        val result = p.embed(listOf("hello", "world"))
        assertEquals(2, result.size)
        assertEquals(4, result[0].size)
    }

    // ── GitsemaKnowledgeIndex adapter ─────────────────────────────────────────

    @Test
    fun `search response maps degraded flag from gitsema SearchResult`() = runTest {
        val fakeIndex = FakeSemanticIndex(degraded = true, blobsKnown = 100, blobsEmbedded = 0)
        val adapter = GitsemaKnowledgeIndex(fakeIndex)

        val response = adapter.search("some query")

        assertTrue(response.degraded, "degraded must be forwarded from gitsema SearchResult")
        assertEquals(0L, response.coverage.blobsEmbedded)
        assertEquals(100L, response.coverage.blobsKnown)
    }

    @Test
    fun `search response reports coverage even when no matches`() = runTest {
        val fakeIndex = FakeSemanticIndex(degraded = false, blobsKnown = 50, blobsEmbedded = 50)
        val adapter = GitsemaKnowledgeIndex(fakeIndex)

        val response = adapter.search("nothing here")

        assertFalse(response.degraded)
        assertTrue(response.coverage.isComplete)
    }

    @Test
    fun `search match fromVector is true for VECTOR provenance`() = runTest {
        val match = Match(
            blobHash = BlobHash("abc123"),
            paths = listOf(RepoPath("src/Foo.kt")),
            startLine = 1,
            endLine = 10,
            score = 0.95,
            provenance = MatchProvenance.VECTOR,
        )
        val fakeIndex = FakeSemanticIndex(matches = listOf(match), blobsKnown = 10, blobsEmbedded = 10)
        val adapter = GitsemaKnowledgeIndex(fakeIndex)

        val response = adapter.search("query")

        assertEquals(1, response.matches.size)
        assertTrue(response.matches[0].fromVector)
        assertEquals("abc123", response.matches[0].blobHash)
        assertEquals("src/Foo.kt", response.matches[0].paths[0])
    }

    @Test
    fun `search match fromVector is false for FTS provenance`() = runTest {
        val match = Match(
            blobHash = BlobHash("def456"),
            paths = listOf(RepoPath("src/Bar.kt")),
            startLine = 5,
            endLine = 20,
            score = 0.8,
            provenance = MatchProvenance.FTS,
        )
        val fakeIndex = FakeSemanticIndex(matches = listOf(match), blobsKnown = 10, blobsEmbedded = 0)
        val adapter = GitsemaKnowledgeIndex(fakeIndex)

        val response = adapter.search("bar")

        assertFalse(response.matches[0].fromVector, "FTS provenance must not be reported as fromVector")
    }

    @Test
    fun `coverage delegates to gitsema status`() = runTest {
        val fakeIndex = FakeSemanticIndex(blobsKnown = 1000, blobsEmbedded = 750)
        val adapter = GitsemaKnowledgeIndex(fakeIndex)

        val cov = adapter.coverage()

        assertEquals(750L, cov.blobsEmbedded)
        assertEquals(1000L, cov.blobsKnown)
        assertEquals(0.75, cov.fraction)
    }
}

/**
 * Test double for [SemanticIndex] — avoids a real repository, model, and database.
 */
private class FakeSemanticIndex(
    private val matches: List<Match> = emptyList(),
    private val degraded: Boolean = false,
    private val blobsKnown: Long = 0,
    private val blobsEmbedded: Long = 0,
) : SemanticIndex {

    private val coverage = GitsemaCoverage(blobsEmbedded, blobsKnown)

    override suspend fun index(ref: String, onProgress: (IndexProgress) -> Unit): IndexResult =
        IndexResult(0, 0, 0, 0, 0, 0)

    override suspend fun search(query: Query): SearchResult =
        SearchResult(matches = matches, coverage = coverage, degraded = degraded)

    override suspend fun status(): IndexStatus =
        IndexStatus(coverage = coverage, lastIndexedCommit = null, embeddingModel = null, embeddingDimensions = null)
}
