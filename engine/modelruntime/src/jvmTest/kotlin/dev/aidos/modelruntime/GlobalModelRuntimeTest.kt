package dev.aidos.modelruntime

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.ModelRef
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.Usage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M20 done-when (RFC-0022):
 *
 * 1. Weights are user-scope — the runtime is a singleton, not per-project.
 * 2. Loading is globally serialized through one admission queue.
 * 3. Digest is verified on install; mismatch deletes the file and fails.
 */
class GlobalModelRuntimeTest {

    private val fakeAdapter = object : ModelAdapter {
        override val providerId = "local"
        override val modelId = "test-7b"
        override val modelVersion = "q4_0"
        override val contextWindow = 4096
        override val isLocal = true
        override fun supportsNativeToolCalls() = false
        override suspend fun invoke(request: ModelRequest) =
            Result.success(ModelResponse(
                outputs = listOf(TextOutput("response")),
                stopReason = StopReason.END_TURN,
                usage = Usage(inputTokens = 0, outputTokens = 0, totalTokens = 0),
                model = ModelRef(modelId, modelVersion),
            ))
    }

    private fun descriptor(id: String, digest: String? = null) = ModelDescriptor(
        id = id,
        name = id,
        kind = ModelKind.LLM,
        providerId = "local",
        isLocal = true,
        contextWindow = 4096,
        sizeBytes = 4_000_000_000L,
        digest = digest,
    )

    private fun fakeBackend(
        models: List<ModelDescriptor>,
        digestFor: (String) -> String = { "" },
    ): InferenceBackend = object : InferenceBackend {
        private val deletedModels = mutableSetOf<String>()
        private val loadedModels = mutableSetOf<String>()

        override suspend fun catalog() = models
        override suspend fun installed() = models.filter { it.id !in deletedModels }
        override suspend fun computeDigest(modelId: String) = digestFor(modelId)
        override suspend fun delete(modelId: String) { deletedModels.add(modelId) }
        override suspend fun load(modelId: String): Result<ModelAdapter> {
            loadedModels.add(modelId)
            return Result.success(fakeAdapter)
        }
        override suspend fun unload(modelId: String) { loadedModels.remove(modelId) }
    }

    @Test
    fun `load returns adapter for installed model`() = runTest {
        val backend = fakeBackend(listOf(descriptor("test-7b")))
        val runtime = GlobalModelRuntime(backend)
        val result = runtime.load("test-7b")
        assertTrue(result.isSuccess)
        assertEquals("test-7b", result.getOrThrow().modelId)
    }

    @Test
    fun `load is idempotent - second call returns cached adapter`() = runTest {
        val backend = fakeBackend(listOf(descriptor("test-7b")))
        val runtime = GlobalModelRuntime(backend)
        val a1 = runtime.load("test-7b").getOrThrow()
        val a2 = runtime.load("test-7b").getOrThrow()
        // Same object — loaded once, returned from cache.
        assertTrue(a1 === a2)
    }

    @Test
    fun `load fails for uninstalled model`() = runTest {
        val backend = fakeBackend(emptyList())
        val runtime = GlobalModelRuntime(backend)
        val result = runtime.load("missing-model")
        assertTrue(result.isFailure)
    }

    @Test
    fun `digest mismatch deletes weights and returns failure`() = runTest {
        val backend = fakeBackend(
            models = listOf(descriptor("test-7b", digest = "expected-hash")),
            digestFor = { "wrong-hash" },
        )
        val runtime = GlobalModelRuntime(backend)
        val result = runtime.load("test-7b")
        assertTrue(result.isFailure)
        assertIs<DigestMismatchException>(result.exceptionOrNull())
        // Verify the model is no longer installed after deletion.
        assertTrue(backend.installed().isEmpty())
    }

    @Test
    fun `digest match allows load`() = runTest {
        val correctDigest = "sha256:abc123"
        val backend = fakeBackend(
            models = listOf(descriptor("test-7b", digest = correctDigest)),
            digestFor = { correctDigest },
        )
        val runtime = GlobalModelRuntime(backend)
        val result = runtime.load("test-7b")
        assertTrue(result.isSuccess)
    }

    // ─── M20: verification compares against the catalog's pinned digest, not a second hash of
    // the same installed file (the audit's Part 3 finding — see LlamaCppInferenceBackendTest for
    // the corresponding real-catalog coverage). ────────────────────────────────────────────────

    private fun fakeBackendWithDivergentCatalog(
        catalogDigest: String?,
        installedDigest: String?,
        digestFor: (String) -> String,
    ): InferenceBackend = object : InferenceBackend {
        private val deletedModels = mutableSetOf<String>()
        override suspend fun catalog() = listOf(descriptor("test-7b", digest = catalogDigest))
        override suspend fun installed() =
            if ("test-7b" in deletedModels) emptyList() else listOf(descriptor("test-7b", digest = installedDigest))
        override suspend fun computeDigest(modelId: String) = digestFor(modelId)
        override suspend fun delete(modelId: String) { deletedModels.add(modelId) }
        override suspend fun load(modelId: String): Result<ModelAdapter> = Result.success(fakeAdapter)
        override suspend fun unload(modelId: String) {}
    }

    @Test
    fun `load succeeds when the actual file matches the catalog digest, even if installed() reports a different value`() = runTest {
        // Pre-M20-fix, this exact case was untestable as a distinct scenario: the check compared
        // installed()'s own digest field against computeDigest() of the same file, so the two
        // were definitionally equal (or the file didn't exist). Now the catalog is the source of
        // truth -- installed()'s own (differing, e.g. stale-cache) digest field must not matter.
        val backend = fakeBackendWithDivergentCatalog(
            catalogDigest = "catalog-pinned-hash",
            installedDigest = "stale-installed-field",
            digestFor = { "catalog-pinned-hash" },
        )
        val runtime = GlobalModelRuntime(backend)
        assertTrue(runtime.load("test-7b").isSuccess)
    }

    @Test
    fun `load fails against the catalog digest even when installed()'s own digest field would have matched`() = runTest {
        // This is the exact tautology the audit flagged: installed()'s digest field is computed
        // from the same file computeDigest() re-hashes, so the two always agreed with each other
        // regardless of whether the file actually matches anything known-good. A real corrupted/
        // substituted download must now be caught by comparing against the catalog instead.
        val backend = fakeBackendWithDivergentCatalog(
            catalogDigest = "catalog-pinned-hash",
            installedDigest = "actual-file-hash",
            digestFor = { "actual-file-hash" },
        )
        val runtime = GlobalModelRuntime(backend)
        val result = runtime.load("test-7b")
        assertTrue(result.isFailure)
        assertIs<DigestMismatchException>(result.exceptionOrNull())
        assertTrue(backend.installed().isEmpty(), "mismatched weights must be deleted")
    }

    @Test
    fun `unload removes model from loaded list`() = runTest {
        val backend = fakeBackend(listOf(descriptor("test-7b")))
        val runtime = GlobalModelRuntime(backend)
        runtime.load("test-7b")
        assertTrue("test-7b" in runtime.loaded())
        runtime.unload("test-7b")
        assertTrue("test-7b" !in runtime.loaded())
    }

    @Test
    fun `loaded returns empty list initially`() = runTest {
        val backend = fakeBackend(listOf(descriptor("test-7b")))
        val runtime = GlobalModelRuntime(backend)
        assertEquals(emptyList(), runtime.loaded())
    }

    @Test
    fun `loadedAtMillis is null before load and set to the clock's time after`() = runTest {
        val backend = fakeBackend(listOf(descriptor("test-7b")))
        var clock = 1_000L
        val runtime = GlobalModelRuntime(backend, nowMillis = { clock })

        assertEquals(null, runtime.loadedAtMillis("test-7b"))

        runtime.load("test-7b")
        assertEquals(1_000L, runtime.loadedAtMillis("test-7b"))
    }

    @Test
    fun `loadedAtMillis does not change on a cache-hit reload`() = runTest {
        val backend = fakeBackend(listOf(descriptor("test-7b")))
        var clock = 1_000L
        val runtime = GlobalModelRuntime(backend, nowMillis = { clock })

        runtime.load("test-7b")
        clock = 5_000L
        runtime.load("test-7b") // Already loaded -- the fast path must not re-stamp the time.

        assertEquals(1_000L, runtime.loadedAtMillis("test-7b"))
    }

    @Test
    fun `loadedAtMillis is cleared on unload`() = runTest {
        val backend = fakeBackend(listOf(descriptor("test-7b")))
        val runtime = GlobalModelRuntime(backend, nowMillis = { 1_000L })

        runtime.load("test-7b")
        runtime.unload("test-7b")

        assertEquals(null, runtime.loadedAtMillis("test-7b"))
    }
}
