package fi.italeino.aidos.engine.inference

import dev.aidos.kernel.CancellableModelAdapter
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
import kotlinx.coroutines.awaitCancellation
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
            val first = async { manager.execute("test-model") { it.invoke(dummyRequest()).getOrThrow() } }
            delay(50)
            val second = manager.execute("test-model") { it.invoke(dummyRequest()).getOrThrow() }
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
        val adapter = CountingAdapter()
        val manager = InferenceRequestManager(FakeRuntime(adapter), maxConcurrentRequests = 2, maxQueuedRequests = 2)
        coroutineScope {
            val first = async { manager.execute("test-model") { it.invoke(dummyRequest()).getOrThrow() } }
            val second = async { manager.execute("test-model") { it.invoke(dummyRequest()).getOrThrow() } }
            assertTrue(first.await().isSuccess)
            assertTrue(second.await().isSuccess)
        }
        assertEquals(1, adapter.maxConcurrentInvokes)
        assertEquals(2, manager.snapshotMetrics().completedRequests)
    }

    @Test
    fun closeModel_interruptsRunningInferenceAndUnloadsAfterCancellation() = runTest {
        val adapter = CancellationAwareAdapter()
        val runtime = FakeRuntime(adapter)
        val manager = InferenceRequestManager(runtime, maxConcurrentRequests = 1, maxQueuedRequests = 0)

        coroutineScope {
            val request = async {
                manager.execute("test-model") { it.invoke(dummyRequest()).getOrThrow() }
            }
            adapter.started.await()

            val close = async { manager.closeModel("test-model") }
            adapter.cancelled.await()
            assertTrue(!close.isCompleted, "close must wait for the request to leave the manager")
            adapter.finishCancellation()

            assertTrue(close.await().isSuccess)
            assertTrue(request.await().isFailure)
            assertEquals(1, runtime.unloadCalls)
            assertEquals(1, adapter.cancelCalls)
        }
    }

    @Test
    fun closeModel_cancelsQueuedRequestsForOnlyThatModel() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val firstAdapter = CancellationAwareAdapter()
        val otherAdapter = BlockingAdapter(firstGate)
        val runtime = MultiModelRuntime(firstAdapter, otherAdapter)
        val manager = InferenceRequestManager(runtime, maxConcurrentRequests = 2, maxQueuedRequests = 2)

        coroutineScope {
            val first = async { manager.execute("test-model") { it.invoke(dummyRequest()).getOrThrow() } }
            firstAdapter.started.await()
            val queued = async { manager.execute("test-model") { it.invoke(dummyRequest()).getOrThrow() } }
            delay(25)

            assertTrue(manager.closeModel("test-model").isSuccess)
            assertTrue(first.await().isFailure)
            assertTrue(queued.await().isFailure)
            assertEquals(1, runtime.unloadCalls)
            firstGate.complete(Unit)
        }
    }

    @Test
    fun deleteModel_interruptsInferenceBeforeDeleting() = runTest {
        val adapter = CancellationAwareAdapter()
        val runtime = FakeRuntime(adapter)
        val manager = InferenceRequestManager(runtime, maxConcurrentRequests = 1, maxQueuedRequests = 0)
        coroutineScope {
            val request = async { manager.execute("test-model") { it.invoke(dummyRequest()).getOrThrow() } }
            adapter.started.await()
            val deletion = async { manager.deleteModel("test-model") }
            adapter.cancelled.await()
            adapter.finishCancellation()
            assertTrue(deletion.await().isSuccess)
            assertTrue(request.await().isFailure)
            assertEquals(1, runtime.unloadCalls)
            assertEquals(1, runtime.deleteCalls)
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
            val inference = manager.execute("test-model") { it.invoke(dummyRequest()).getOrThrow() }
            assertTrue(inference.isFailure)
            assertTrue(inference.exceptionOrNull() is EngineModelBusyException)
            deleteGate.complete(Unit)
            assertTrue(deletion.await().isSuccess)
            assertEquals(1, runtime.deleteCalls)
        }
    }

    @Test
    fun shutdownAndDrain_waitsUntilRunningRequestsFinish() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(BlockingAdapter(gate))
        val manager = InferenceRequestManager(runtime, maxConcurrentRequests = 1, maxQueuedRequests = 1)
        coroutineScope {
            val request = async { manager.execute("test-model") { it.invoke(dummyRequest()).getOrThrow() } }
            delay(50)
            val shutdown = async { manager.shutdownAndDrain(timeout = 300.milliseconds) }
            delay(50)
            assertTrue(!shutdown.isCompleted)
            gate.complete(Unit)
            assertTrue(shutdown.await())
            request.await()
        }
    }

    @Test
    fun shutdownAndDrain_cancelsRunningRequestAndRecordsCancellation() = runTest {
        val adapter = CancellationAwareAdapter()
        val manager = InferenceRequestManager(FakeRuntime(adapter), maxConcurrentRequests = 1, maxQueuedRequests = 0)
        coroutineScope {
            val request = async { manager.execute("test-model") { it.invoke(dummyRequest()).getOrThrow() } }
            adapter.started.await()
            assertTrue(manager.shutdownAndDrain(timeout = 500.milliseconds))
            assertTrue(request.await().isFailure)
        }
        val metrics = manager.snapshotMetrics()
        assertEquals(1, metrics.totalRequests)
        assertEquals(1, metrics.cancelledRequests)
        assertEquals(0, metrics.runningRequests)
    }

    private fun dummyRequest() = ModelRequest(
        messages = emptyList(), tools = emptyList(),
        toolChoice = dev.aidos.kernel.ToolChoice.None, maxOutputTokens = 16,
    )
}

private open class FakeRuntime(
    private val adapter: ModelAdapter,
    private val deleteStarted: CompletableDeferred<Unit>? = null,
    private val deleteGate: CompletableDeferred<Unit>? = null,
) : ModelRuntime {
    var deleteCalls = 0
        private set
    var unloadCalls = 0
        private set

    override suspend fun catalog() = listOf(ModelDescriptor("test-model", "Test", ModelKind.LLM, "test", true, 2048, 1234, null))
    override suspend fun installed() = catalog()
    override suspend fun load(modelId: String): Result<ModelAdapter> = Result.success(adapter)
    override suspend fun unload(modelId: String) { unloadCalls++ }
    override suspend fun delete(modelId: String) {
        deleteCalls++
        deleteStarted?.complete(Unit)
        deleteGate?.await()
    }
    override fun loaded() = listOf("test-model")
}

private class MultiModelRuntime(
    private val target: CancellableModelAdapter,
    private val other: ModelAdapter,
) : FakeRuntime(target) {
    override suspend fun load(modelId: String): Result<ModelAdapter> = Result.success(if (modelId == "test-model") target else other)
}

private class BlockingAdapter(private val gate: CompletableDeferred<Unit>) : ModelAdapter {
    override val providerId = "test"
    override val modelId = "test-model"
    override val modelVersion = "1"
    override val contextWindow = 2048
    override val isLocal = true
    override fun supportsNativeToolCalls() = false
    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        gate.await()
        return Result.success(response())
    }
    protected fun response() = ModelResponse(listOf(TextOutput("ok")), StopReason.END_TURN, Usage(1, 1, 2), ModelRef(modelId, modelVersion))
}

private class CancellationAwareAdapter : CancellableModelAdapter {
    override val providerId = "test"
    override val modelId = "test-model"
    override val modelVersion = "1"
    override val contextWindow = 2048
    override val isLocal = true
    val started = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
    var cancelCalls = 0
        private set
    private var allowCancellation = false
    override fun supportsNativeToolCalls() = false
    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        started.complete(Unit)
        while (!allowCancellation) awaitCancellation()
        return Result.failure(CancellationException("cancelled"))
    }
    override fun cancelCurrentInference() {
        cancelCalls++
        cancelled.complete(Unit)
    }
    fun finishCancellation() { allowCancellation = true }
}

private class CountingAdapter : ModelAdapter {
    override val providerId = "test"
    override val modelId = "test-model"
    override val modelVersion = "1"
    override val contextWindow = 2048
    override val isLocal = true
    private var activeInvokes = 0
    var maxConcurrentInvokes = 0
        private set
    override fun supportsNativeToolCalls() = false
    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        activeInvokes++
        maxConcurrentInvokes = maxOf(maxConcurrentInvokes, activeInvokes)
        delay(50)
        activeInvokes--
        return Result.success(ModelResponse(listOf(TextOutput("ok")), StopReason.END_TURN, Usage(1, 1, 2), ModelRef(modelId, modelVersion)))
    }
}
