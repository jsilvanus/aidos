package dev.aidos.modelruntime

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TokenUsage
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
                text = "response",
                toolCalls = emptyList(),
                stopReason = StopReason.END_TURN,
                usage = TokenUsage(0, 0),
                modelId = modelId,
                modelVersion = modelVersion,
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
}
