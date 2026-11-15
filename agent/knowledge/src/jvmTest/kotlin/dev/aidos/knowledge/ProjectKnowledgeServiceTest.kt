package dev.aidos.knowledge

import dev.aidos.kernel.ContextItemKind
import dev.aidos.kernel.TrustLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectKnowledgeServiceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class FakeIndex(
        val response: SearchResponse,
        val onIndex: suspend () -> Unit = {},
    ) : KnowledgeIndex {
        override suspend fun startIndexing(ref: String, onProgress: (IndexingProgress) -> Unit) {
            onProgress(IndexingProgress(1, 2, 1, 0))
            onIndex()
        }
        override suspend fun search(query: String, topK: Int, branch: String?) = response
        override suspend fun coverage() = response.coverage
    }

    private fun repo(): java.io.File {
        val d = Files.createTempDirectory("kn").toFile()
        java.io.File(d, ".git").mkdirs()
        java.io.File(d, "a.kt").writeText("line1\nline2\nline3\nline4\n")
        return d
    }

    @Test
    fun `search results become tagged context items led by a coverage notice`() = runBlocking {
        val root = repo()
        val resp = SearchResponse(
            matches = listOf(KnowledgeMatch("h", listOf("a.kt"), 2, 3, 0.9, fromVector = false)),
            coverage = IndexCoverage(1, 4),
            degraded = true,
        )
        val svc = ProjectKnowledgeService({ root.path }, scope, indexFactory = { FakeIndex(resp) })
        val items = svc.contextFor("p", "find line2", 5)
        assertEquals(2, items.size)
        assertEquals(ContextItemKind.SEARCH_RESULT, items[0].kind)
        assertTrue(items[0].content.contains("25%") && items[0].content.contains("text-match only"))
        assertEquals(ContextItemKind.CODE_SNIPPET, items[1].kind)
        assertEquals(TrustLevel.PROJECT, items[1].trustLevel)
        assertTrue(items[1].content.contains("line2\nline3"))
    }

    @Test
    fun `rfc-0025 query seam honours the token budget and reports index status`() = runBlocking {
        val root = repo()
        val resp = SearchResponse(
            matches = listOf(KnowledgeMatch("h", listOf("a.kt"), 1, 4, 0.9, fromVector = true)),
            coverage = IndexCoverage(2, 4),
            degraded = false,
        )
        val svc = ProjectKnowledgeService({ root.path }, scope, indexFactory = { FakeIndex(resp) })
        val pid = dev.aidos.kernel.ProjectId("p")
        val q = dev.aidos.kernel.KnowledgeQuery("anything")
        assertEquals(2, svc.query(pid, q, tokenBudget = 10_000).size)
        assertTrue(svc.query(pid, q, tokenBudget = 1).isEmpty()) // snippet does not fit: notice alone is not returned
        val status = svc.indexStatus(pid)
        assertEquals(2L, status.indexedBlobs)
        assertEquals(2L, status.pendingBlobs)
        assertTrue(status.degraded)
    }

    @Test
    fun `paths that escape the project root are never read`() = runBlocking {
        val root = repo()
        val resp = SearchResponse(
            matches = listOf(KnowledgeMatch("h", listOf("../../etc/passwd"), 1, 5, 0.9, false)),
            coverage = IndexCoverage(0, 1),
            degraded = true,
        )
        val svc = ProjectKnowledgeService({ root.path }, scope, indexFactory = { FakeIndex(resp) })
        assertTrue(svc.contextFor("p", "q", 5).isEmpty())
    }

    @Test
    fun `non-git project or unknown project yields nothing`() = runBlocking {
        val plain = Files.createTempDirectory("plain").toFile()
        val svc = ProjectKnowledgeService({ plain.path }, scope, indexFactory = { error("must not build") })
        assertTrue(svc.contextFor("p", "q", 5).isEmpty())
        assertFalse(svc.ensureIndexing("p"))
        assertNull(ProjectKnowledgeService({ null }, scope).coverage("x"))
    }

    @Test
    fun `indexing runs in the background reports progress and is cancellable`() = runBlocking {
        val root = repo()
        val started = CompletableDeferred<Unit>()
        val resp = SearchResponse(emptyList(), IndexCoverage(0, 0), true)
        val svc = ProjectKnowledgeService({ root.path }, scope, indexFactory = {
            FakeIndex(resp) {
                started.complete(Unit)
                awaitCancellation()
            }
        })
        assertTrue(svc.ensureIndexing("p"))
        started.await()
        assertTrue(svc.isIndexing("p"))
        assertEquals(1, svc.lastProgress("p")?.blobsIndexed)
        svc.cancelIndexing("p")
        assertFalse(svc.isIndexing("p"))
    }

    @Test
    fun `an indexing failure such as no embedding model does not break search`() = runBlocking {
        val root = repo()
        val resp = SearchResponse(emptyList(), IndexCoverage(0, 0), true)
        val svc = ProjectKnowledgeService({ root.path }, scope, indexFactory = {
            FakeIndex(resp) { throw IllegalStateException("No local embedding model loaded") }
        })
        assertTrue(svc.ensureIndexing("p"))
        assertTrue(svc.contextFor("p", "q", 3).isEmpty())
    }

    @Test
    fun `the embedding delegate loads the installed embedding model from the model runtime`() = runBlocking {
        val loaded = mutableListOf<String>()
        val embedAdapter = object : dev.aidos.kernel.EmbeddingModelAdapter {
            override val providerId = "local"; override val modelId = "emb-1"; override val modelVersion = "1"
            override val contextWindow = 512; override val isLocal = true
            override fun supportsNativeToolCalls() = false
            override suspend fun invoke(request: dev.aidos.kernel.ModelRequest) = Result.failure<dev.aidos.kernel.ModelResponse>(UnsupportedOperationException())
            override suspend fun embed(text: String) = Result.success(FloatArray(LocalOnlyEmbeddingProvider.NOMIC_DIMENSIONS) { 0.25f })
        }
        val runtime = object : dev.aidos.kernel.ModelRuntime {
            private val d = dev.aidos.kernel.ModelDescriptor("emb-1", "emb", dev.aidos.kernel.ModelKind.EMBEDDING, "local", true, 512, null, null)
            override suspend fun catalog() = listOf(d)
            override suspend fun installed() = listOf(d)
            override suspend fun load(modelId: String): Result<dev.aidos.kernel.ModelAdapter> { loaded += modelId; return Result.success(embedAdapter) }
            override suspend fun unload(modelId: String) {}
            override fun loaded() = loaded.toList()
            override suspend fun delete(modelId: String) {}
        }
        val svc = ProjectKnowledgeService({ null }, scope, modelRuntime = runtime)
        // The delegate is registered asynchronously at construction; wait for it.
        var vectors: List<FloatArray>? = null
        repeat(50) { if (vectors == null) { vectors = runCatching { svc.embeddingProvider.embed(listOf("x")) }.getOrNull(); if (vectors == null) kotlinx.coroutines.delay(20) } }
        assertEquals(LocalOnlyEmbeddingProvider.NOMIC_DIMENSIONS, vectors!!.single().size)
        assertEquals(listOf("emb-1"), loaded)
    }
}
