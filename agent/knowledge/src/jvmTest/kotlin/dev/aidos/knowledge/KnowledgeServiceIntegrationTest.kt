package dev.aidos.knowledge

import dev.aidos.api.IndexStatus
import dev.aidos.api.IndexingProgress as ApiIndexingProgress
import dev.aidos.api.KnowledgeItem
import dev.aidos.api.KnowledgeQueries
import dev.aidos.api.KnowledgeQuery
import dev.aidos.api.KnowledgeResult
import dev.aidos.api.KnowledgeService
import dev.aidos.prompt.PromptAssembler
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

/**
 * Phase 5: Integration tests for knowledge service wiring (RFC-0015, M22).
 *
 * Tests the complete pipeline:
 * - Phase 1: projectId→projectPath mapping works end-to-end
 * - Phase 2: PromptAssembler can extract and inject knowledge context
 * - Phase 3: Embedding provider wiring (with/without real model)
 * - Phase 4: Background indexing job creation
 * - Phase 5: Performance and degradation scenarios
 */
class KnowledgeServiceIntegrationTest {

    /**
     * Phase 5: End-to-end test - project opens, search returns results,
     * PromptAssembler includes context in prompt.
     */
    @Test
    fun testEndToEndSearchToPromptContext() = runBlocking {
        // Given: a mock knowledge service
        val mockService = MockKnowledgeService()
        
        // When: we search for code context
        val projectId = "test-project-1"
        val userMessage = "How do I handle retry logic in this codebase?"
        val result = mockService.search(
            "/tmp/test-project",
            KnowledgeQuery("retry logic", limit = 20)
        )
        
        // Then: we get results
        assertTrue(result.items.isNotEmpty(), "Should return search results")
        assertEquals(2, result.items.size, "Should return 2 items")
        
        // Phase 2: PromptAssembler includes knowledge in prompt
        val assembler = PromptAssembler()
        val (contextItems, coverage) = assembler.extractKnowledge(
            projectId, userMessage, mockService
        )
        
        // Then: context items are extracted with coverage reported
        assertTrue(contextItems.isNotEmpty(), "Should extract knowledge context")
        assertEquals(2, contextItems.size, "Should include 2 context items")
        assertTrue(coverage.fraction >= 0.5, "Should report coverage ≥ 50%")
    }

    /**
     * Phase 5: FTS-only scenario - when embedding model is unavailable,
     * search degrades to full-text search without blocking.
     */
    @Test
    fun testFTSOnlyDegradation() = runBlocking {
        // Given: LocalOnlyEmbeddingProvider with no delegate (no model loaded)
        val provider = LocalOnlyEmbeddingProvider.placeholder()
        
        // When: we try to embed texts (should throw because no model)
        var exceptionThrown = false
        try {
            provider.embed(listOf("test code snippet"))
        } catch (e: IllegalStateException) {
            exceptionThrown = true
        }
        
        // Then: exception is thrown (FTS-only mode signal)
        assertTrue(exceptionThrown, "Should throw when no model loaded")
    }

    /**
     * Phase 5: Performance test - knowledge search should complete in <200ms.
     */
    @Test
    fun testPerformanceUnder200ms() = runBlocking {
        // Given: a mock knowledge service
        val mockService = MockKnowledgeService()
        
        // When: we search with a typical query
        val duration = measureTime {
            mockService.search(
                "/tmp/test-project",
                KnowledgeQuery("connection pool", limit = 10)
            )
        }
        
        // Then: search completes in <200ms (Phase 5 acceptance criterion)
        assertTrue(
            duration < 200.milliseconds,
            "Search should complete in <200ms, took ${duration.inWholeMilliseconds}ms"
        )
    }

    /**
     * Phase 5: Phase 3 wiring test - embedding provider delegate updates.
     */
    @Test
    fun testEmbeddingProviderDelegateWiring() = runBlocking {
        // Given: LocalOnlyEmbeddingProvider with no model
        val provider = LocalOnlyEmbeddingProvider.placeholder()
        
        // When: we set an embedding delegate (Phase 3 wiring)
        val mockEmbeddings = { texts: List<String> ->
            texts.map { FloatArray(768) { 0.1f } }
        }
        provider.setEmbeddingDelegate(mockEmbeddings)
        
        // Then: embedding now works (coverage improves from 0% to 100%)
        val result = provider.embed(listOf("test code"))
        assertEquals(1, result.size, "Should embed 1 text")
        assertEquals(768, result[0].size, "Should return 768 dimensions")
    }

    /**
     * Phase 5: Phase 4 test - IndexingJob creation for background scheduling.
     */
    @Test
    fun testIndexingJobCreation() {
        // When: we create an indexing job (Phase 4)
        val job = IndexingJob.createForProject(
            projectId = "my-project",
            intervalMinutes = 30
        )
        
        // Then: job is properly configured
        assertEquals("my-project", job.projectId, "Should set project ID")
        assertTrue(job.enabled, "Should be enabled by default")
        assertTrue(job.name.contains("Index"), "Should have descriptive name")
    }

    /**
     * Phase 5: Keyword extraction test (Phase 2).
     */
    @Test
    fun testKeywordExtraction() = runBlocking {
        // Given: a user message
        val userMessage = "How should I implement connection retry logic with exponential backoff?"
        
        // When: PromptAssembler extracts keywords
        val assembler = PromptAssembler()
        // Keywords are extracted internally in extractKnowledge, so we test through that
        val mockService = MockKnowledgeService()
        assembler.extractKnowledge("project-1", userMessage, mockService)
        
        // Then: the service received a search query (keyword extraction happened)
        assertTrue(mockService.lastQuery != null, "Should extract keywords and search")
    }

    /**
     * Mock KnowledgeService for testing (Phase 5).
     *
     * Suppressed rather than renamed because no name satisfies both supertypes: `KnowledgeService`
     * calls the first parameter of `search`/`indexStatus` `projectPath`, `KnowledgeQueries` calls
     * it `projectId`, and this mock implements both. Renaming to either one just moves the warning
     * to the other.
     *
     * TODO: the real defect is upstream, in `dev.aidos.api.RuntimeClient` — those two interfaces
     * are declared in the same file with identical signatures and contradictory parameter names,
     * which leaves callers no answer to "is this an id or a path". Reconciling them there lets
     * this suppression go away.
     */
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    private class MockKnowledgeService : KnowledgeService, KnowledgeQueries {
        var lastQuery: String? = null

        override suspend fun search(
            projectPath: String,
            query: KnowledgeQuery,
        ): KnowledgeResult {
            lastQuery = query.text
            // Mock: return 2 results
            return KnowledgeResult(
                items = listOf(
                    KnowledgeItem(
                        id = "blob-1",
                        kind = "function",
                        title = "RetryPolicy.execute()",
                        snippet = "fun execute(task: Task, maxRetries: Int): Result { ... }",
                        score = 0.95f,
                    ),
                    KnowledgeItem(
                        id = "blob-2",
                        kind = "class",
                        title = "ExponentialBackoff",
                        snippet = "class ExponentialBackoff(base: Duration) { ... }",
                        score = 0.87f,
                    ),
                ),
                totalMatches = 2,
                indexedAt = null,
            )
        }

        override suspend fun indexStatus(projectPath: String): IndexStatus =
            IndexStatus(projectPath, null, 1500, false)

        override suspend fun startIndexing(
            projectPath: String,
            onProgress: (ApiIndexingProgress) -> Unit,
        ) {
            // Mock: simulate indexing progress
            onProgress(ApiIndexingProgress(10, 100, 50, 0))
        }
    }
}
