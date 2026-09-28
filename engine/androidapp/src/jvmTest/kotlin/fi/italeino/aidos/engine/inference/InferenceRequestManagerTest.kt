package fi.italeino.aidos.engine.inference

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRef
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.ModelRuntime
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.Usage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class InferenceRequestManagerTest {

    @Test
    fun execute_rejectsWhenQueueIsSaturated() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(BlockingAdapter(gate))
        val manager = InferenceRequestManager(runtime, maxConcurrentRequests = 1, maxQueuedRequests = 0)

        coroutineScope {
            val first = async {
                manager.execute("test-model") { adapter ->
                    adapter.invoke(dummyRequest()).getOrThrow()
                }
            }
            delay(50)
            val second = manager.execute("test-model") { adapter ->
                adapter.invoke(dummyRequest()).getOrThrow()
            }

            assertTrue(second.isFailure)
            assertTrue(second.exceptionOrNull() is EngineBusyException)
            gate.complete(Unit)
            assertTrue(first.await().isSuccess)
        }

        val metrics = manager.snapshotMetrics()
        assertEquals(1, metrics.totalRequests)
        assertEquals(1, metrics.completedRequests)
    }

    @Test
    fun execute_serializesConcurrentCallsPerModel() = runTest {
        val serializingAdapter = CountingAdapter()
        val runtime = FakeRuntime(serializingAdapter)
        val manager = InferenceRequestManager(runtime, maxConcurrentRequests = 2, maxQueuedRequests = 2)

        coroutineScope {
            val first = async {
                manager.execute("test-model") { adapter ->
                    adapter.invoke(dummyRequest()).getOrThrow()
                }
            }
            val second = async {
                manager.execute("test-model") { adapter ->
                    adapter.invoke(dummyRequest()).getOrThrow()
                }
            }

            assertTrue(first.await().isSuccess)
            assertTrue(second.await().isSuccess)
        }

        assertEquals(1, serializingAdapter.maxConcurrentInvokes)
        val metrics = manager.snapshotMetrics()
        assertEquals(2, metrics.completedRequests)
    }

    @Test
    fun shutdownAndDrain_waitsUntilRunningRequestsFinish() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(BlockingAdapter(gate))
        val manager = InferenceRequestManager(runtime, maxConcurrentRequests = 1, maxQueuedRequests = 1)

        coroutineScope {
            val request = async {
                manager.execute("test-model") { adapter ->
                    adapter.invoke(dummyRequest()).getOrThrow()
                }
            }
            delay(50)

            val shutdown = async { manager.shutdownAndDrain(timeout = 300.milliseconds) }
            delay(50)
            assertTrue(!shutdown.isCompleted, "shutdown should wait while request is still running")
            gate.complete(Unit)
            assertTrue(shutdown.await())
            request.await()
        }
    }

    private fun dummyRequest() = ModelRequest(
        messages = emptyList(),
        tools = emptyList(),
        toolChoice = dev.aidos.kernel.ToolChoice.None,
        maxOutputTokens = 16,
    )
}

private class FakeRuntime(private val adapter: ModelAdapter) : ModelRuntime {
    override suspend fun catalog(): List<ModelDescriptor> = listOf(
        ModelDescriptor(
            id = "test-model",
            name = "Test",
            kind = ModelKind.LLM,
            providerId = "test",
            isLocal = true,
            contextWindow = 2048,
            sizeBytes = 1234,
            digest = null,
        )
    )

    override suspend fun installed(): List<ModelDescriptor> = catalog()

    override suspend fun load(modelId: String): Result<ModelAdapter> = Result.success(adapter)

    override suspend fun unload(modelId: String) = Unit

    override fun loaded(): List<String> = listOf("test-model")
}

private class BlockingAdapter(private val gate: CompletableDeferred<Unit>) : ModelAdapter {
    override val providerId: String = "test"
    override val modelId: String = "test-model"
    override val modelVersion: String = "1"
    override val contextWindow: Int = 2048
    override val isLocal: Boolean = true

    override fun supportsNativeToolCalls(): Boolean = false

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        gate.await()
        return Result.success(response())
    }

    private fun response() = ModelResponse(
        outputs = listOf(TextOutput("ok")),
        stopReason = StopReason.END_TURN,
        usage = Usage(1, 1, 2),
        model = ModelRef(modelId, modelVersion),
    )
}

private class CountingAdapter : ModelAdapter {
    override val providerId: String = "test"
    override val modelId: String = "test-model"
    override val modelVersion: String = "1"
    override val contextWindow: Int = 2048
    override val isLocal: Boolean = true

    private var activeInvokes: Int = 0
    var maxConcurrentInvokes: Int = 0
        private set

    override fun supportsNativeToolCalls(): Boolean = false

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        activeInvokes++
        if (activeInvokes > maxConcurrentInvokes) maxConcurrentInvokes = activeInvokes
        delay(50)
        activeInvokes--
        return Result.success(
            ModelResponse(
                outputs = listOf(TextOutput("ok")),
                stopReason = StopReason.END_TURN,
                usage = Usage(1, 1, 2),
                model = ModelRef(modelId, modelVersion),
            )
        )
    }
}
