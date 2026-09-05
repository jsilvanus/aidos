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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun deleteModel_rejectsWhileInferenceIsAdmitted() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(BlockingAdapter(gate))
        val manager = InferenceRequestManager(runtime, maxConcurrentRequests = 1, maxQueuedRequests = 0)

        coroutineScope {
            val request = async {
                manager.execute("test-model") { adapter ->
                    adapter.invoke(dummyRequest()).getOrThrow()
                }
            }
            delay(50)

            val deletion = manager.deleteModel("test-model")
            assertTrue(deletion.isFailure)
            assertTrue(deletion.exceptionOrNull() is EngineModelBusyException)
            assertEquals(0, runtime.deleteCalls)

            gate.complete(Unit)
            assertTrue(request.await().isSuccess)
        }
    }

    @Test
    fun deleteModel_blocksNewInferenceUntilDeletionFinishes() = runTest {
        val deleteStarted = CompletableDeferred<Unit>()
        val deleteGate = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(CountingAdapter(), deleteStarted, deleteGate)
        val manager = InferenceRequestManager(runtime, maxConcurrentRequests = 1, maxQueuedRequests = 0)

        coroutineScope {
            val deletion = async { manager.deleteModel("test-model") }
            deleteStarted.await()

            val inference = manager.execute("test-model") { adapter ->
                adapter.invoke(dummyRequest()).getOrThrow()
            }
            assertTrue(inference.isFailure)
            assertTrue(inference.exceptionOrNull() is EngineModelBusyException)

            deleteGate.complete(Unit)
            assertTrue(deletion.await().isSuccess)
            assertEquals(1, runtime.deleteCalls)
        }
    }

    @Test
    fun shutdownAndDrain_cancelsRunningRequestAndReleasesItsSlot() = runTest {
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

            // The gate never opens: shutdown must cancel the request rather than wait for it.
            assertTrue(manager.shutdownAndDrain(timeout = 300.milliseconds))
            assertFailsWith<CancellationException> { request.await() }
        }

        val metrics = manager.snapshotMetrics()
        assertEquals(0, metrics.runningRequests)
        assertEquals(0, metrics.queueDepth)
        assertEquals(1, metrics.cancelledRequests)
        assertEquals(0, metrics.completedRequests)
        assertTrue(manager.waitUntilModelIdle("test-model"))
        val rejected = manager.execute("test-model") { "unreachable" }
        assertTrue(rejected.exceptionOrNull() is EngineShuttingDownException)
    }

    @Test
    fun shutdownAndDrain_waitsForAdapterThatStopsOnlyAtStepBoundary() = runTest {
        // Models a native adapter that observes cancellation only between generation steps
        // (see InferenceRequestManager.cancelAll): the step in flight has to finish first.
        val stepDone = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(StepBoundaryAdapter(stepDone))
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
            assertTrue(!shutdown.isCompleted, "shutdown should wait while the in-flight step runs")
            stepDone.complete(Unit)
            assertTrue(shutdown.await())
            assertFailsWith<CancellationException> { request.await() }
        }

        val metrics = manager.snapshotMetrics()
        assertEquals(0, metrics.runningRequests)
        assertEquals(0, metrics.queueDepth)
    }

    @Test
    fun shutdownAndDrain_cancelsRunningRequestAndRecordsCancellation() = runTest {
        val started = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(CancellationAwareAdapter(started))
        val manager = InferenceRequestManager(runtime, maxConcurrentRequests = 1, maxQueuedRequests = 0)

        coroutineScope {
            val request = async {
                manager.execute("test-model") { adapter ->
                    adapter.invoke(dummyRequest()).getOrThrow()
                }
            }
            started.await()

            assertTrue(manager.shutdownAndDrain(timeout = 500.milliseconds))
            assertTrue(request.await().isFailure)
        }

        val metrics = manager.snapshotMetrics()
        assertEquals(1, metrics.totalRequests)
        assertEquals(1, metrics.cancelledRequests)
        assertEquals(0, metrics.runningRequests)
    }

    private fun dummyRequest() = ModelRequest(
        messages = emptyList(),
        tools = emptyList(),
        toolChoice = dev.aidos.kernel.ToolChoice.None,
        maxOutputTokens = 16,
    )
}

private class FakeRuntime(
    private val adapter: ModelAdapter,
    private val deleteStarted: CompletableDeferred<Unit>? = null,
    private val deleteGate: CompletableDeferred<Unit>? = null,
) : ModelRuntime {
    var deleteCalls: Int = 0
        private set

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

    override suspend fun delete(modelId: String) {
        deleteCalls++
        deleteStarted?.complete(Unit)
        deleteGate?.await()
    }

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

private class StepBoundaryAdapter(private val stepDone: CompletableDeferred<Unit>) : ModelAdapter {
    override val providerId: String = "test"
    override val modelId: String = "test-model"
    override val modelVersion: String = "1"
    override val contextWindow: Int = 2048
    override val isLocal: Boolean = true

    override fun supportsNativeToolCalls(): Boolean = false

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        // The current step cannot be interrupted; cancellation is seen at the next boundary.
        withContext(NonCancellable) { stepDone.await() }
        currentCoroutineContext().ensureActive()
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

private class CancellationAwareAdapter(private val started: CompletableDeferred<Unit>) : ModelAdapter {
    override val providerId: String = "test"
    override val modelId: String = "test-model"
    override val modelVersion: String = "1"
    override val contextWindow: Int = 2048
    override val isLocal: Boolean = true

    override fun supportsNativeToolCalls(): Boolean = false

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        started.complete(Unit)
        kotlinx.coroutines.awaitCancellation()
    }
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
