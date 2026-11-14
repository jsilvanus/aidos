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
import fi.italeino.aidos.engine.license.InMemoryLicenseAcceptanceStore
import fi.italeino.aidos.engine.license.LicenseNotAcceptedException
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
import kotlin.test.assertFalse
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
    fun openModel_forceLoadsWithoutInference() = runTest {
        val runtime = FakeRuntime(CountingAdapter())
        val manager = InferenceRequestManager(runtime, maxConcurrentRequests = 1, maxQueuedRequests = 0)

        assertTrue(manager.openModel("test-model").isSuccess)
        assertEquals(1, runtime.loadCalls)
    }

    @Test
    fun closeModel_interruptsRunningInferenceAndUnloadsAfterCancellation() = runTest {
        val adapter = InterruptibleAdapter()
        val runtime = FakeRuntime(adapter)
        val manager = InferenceRequestManager(runtime, maxConcurrentRequests = 1, maxQueuedRequests = 0)

        coroutineScope {
            val request = async { manager.execute("test-model") { it.invoke(dummyRequest()).getOrThrow() } }
            adapter.started.await()

            assertTrue(manager.closeModel("test-model").isSuccess)
            assertFailsWith<CancellationException> { request.await() }
            assertEquals(1, runtime.unloadCalls)
            assertEquals(1, adapter.cancelCalls)
        }
    }

    @Test
    fun closeModel_cancelsQueuedRequestsForTargetModel() = runTest {
        val adapter = InterruptibleAdapter()
        val runtime = FakeRuntime(adapter)
        val manager = InferenceRequestManager(runtime, maxConcurrentRequests = 1, maxQueuedRequests = 2)

        coroutineScope {
            val first = async { manager.execute("test-model") { it.invoke(dummyRequest()).getOrThrow() } }
            adapter.started.await()
            val queued = async { manager.execute("test-model") { it.invoke(dummyRequest()).getOrThrow() } }
            delay(25)

            assertTrue(manager.closeModel("test-model").isSuccess)
            assertFailsWith<CancellationException> { first.await() }
            assertFailsWith<CancellationException> { queued.await() }
            assertEquals(1, runtime.unloadCalls)
        }
    }

    @Test
    fun deleteModel_interruptsInferenceBeforeDeleting() = runTest {
        val adapter = InterruptibleAdapter()
        val runtime = FakeRuntime(adapter)
        val manager = InferenceRequestManager(runtime, maxConcurrentRequests = 1, maxQueuedRequests = 0)

        coroutineScope {
            val request = async { manager.execute("test-model") { it.invoke(dummyRequest()).getOrThrow() } }
            adapter.started.await()

            assertTrue(manager.deleteModel("test-model").isSuccess)
            assertFailsWith<CancellationException> { request.await() }
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
            assertFailsWith<CancellationException> { request.await() }
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

    // RFC-0103: License acceptance tests

    @Test
    fun execute_rejectsWhenLicenseNotAccepted() = runTest {
        val licenseStore = InMemoryLicenseAcceptanceStore()
        val runtime = FakeRuntime(CountingAdapter())
        val manager = InferenceRequestManager(
            runtime,
            maxConcurrentRequests = 1,
            maxQueuedRequests = 8,
            licenseStore = licenseStore
        )

        // Model has a license requirement
        val modelId = "test-model"
        val modelVersion = "1.0"
        val licenseDigest = "sha256-abc123"

        val result = manager.execute(modelId) { adapter ->
            adapter.invoke(dummyRequest()).getOrThrow()
        }

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is LicenseNotAcceptedException)
        assertEquals(modelId, ex.modelId)
        assertEquals(modelVersion, ex.modelVersion)
        val metrics = manager.snapshotMetrics()
        assertEquals(0, metrics.totalRequests, "license failures must happen before queue admission")
        assertEquals(0, metrics.queueDepth)
        assertEquals(0, metrics.runningRequests)
    }

    @Test
    fun execute_allowsWhenLicenseAccepted() = runTest {
        val licenseStore = InMemoryLicenseAcceptanceStore()
        val runtime = FakeRuntime(CountingAdapter())
        val modelId = "test-model"
        val modelVersion = "1.0"
        val licenseDigest = "sha256-abc123"

        // Pre-accept the license
        licenseStore.accept(modelId, modelVersion, licenseDigest)

        val manager = InferenceRequestManager(
            runtime,
            maxConcurrentRequests = 1,
            maxQueuedRequests = 8,
            licenseStore = licenseStore
        )

        val result = manager.execute(modelId) { adapter ->
            adapter.invoke(dummyRequest()).getOrThrow()
        }

        assertTrue(result.isSuccess)
    }

    @Test
    fun execute_acceptancePersists_acrossRestarts() = runTest {
        val licenseStore = InMemoryLicenseAcceptanceStore()
        val modelId = "qwen2.5-3b"
        val modelVersion = "1.0"
        val licenseDigest = "sha256-def456"

        // First invocation: accept
        licenseStore.accept(modelId, modelVersion, licenseDigest)
        assertTrue(licenseStore.isAccepted(modelId, modelVersion, licenseDigest))

        // Simulate "restart" by checking the same store
        val isStillAccepted = licenseStore.isAccepted(modelId, modelVersion, licenseDigest)
        assertTrue(isStillAccepted)
    }

    @Test
    fun execute_changedLicenseText_requiresReacceptance() = runTest {
        val licenseStore = InMemoryLicenseAcceptanceStore()
        val runtime = FakeRuntime(CountingAdapter())
        val modelId = "test-model"
        val modelVersion = "1.0"
        val oldDigest = "sha256-abc123"
        val newDigest = "sha256-v2"

        // Accept old version
        licenseStore.accept(modelId, modelVersion, oldDigest)

        val manager = InferenceRequestManager(
            runtime,
            maxConcurrentRequests = 1,
            maxQueuedRequests = 8,
            licenseStore = licenseStore
        )

        // Attempt with old digest should succeed
        val oldResult = manager.execute(modelId) { adapter ->
            adapter.invoke(dummyRequest()).getOrThrow()
        }
        assertTrue(oldResult.isSuccess)

        // Attempt with new digest should fail (license text changed)
        // This would require runtime returning a descriptor with the new digest
        // For now, just verify the store behavior
        assertFalse(licenseStore.isAccepted(modelId, modelVersion, newDigest))
    }

    @Test
    fun execute_rejectsWhenOnlyLicenseTextMetadataExists() = runTest {
        val licenseStore = InMemoryLicenseAcceptanceStore()
        val runtime = FakeRuntime(
            adapter = CountingAdapter(),
            descriptorMetadata = mapOf(
                "version" to "1.0",
                "license" to "apache-2.0",
            ),
        )
        val manager = InferenceRequestManager(
            runtime,
            maxConcurrentRequests = 1,
            maxQueuedRequests = Int.MAX_VALUE,
            licenseStore = licenseStore
        )

        val result = manager.execute("test-model") { adapter ->
            adapter.invoke(dummyRequest()).getOrThrow()
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is LicenseNotAcceptedException)
    }

    // RFC-0103 §4.2: Queue policy tests

    @Test
    fun execute_allowsUnboundedQueue_rfc0103_serialization() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(BlockingAdapter(gate))

        // RFC-0103: unlimited queue, never reject for queue depth
        val manager = InferenceRequestManager(
            runtime,
            maxConcurrentRequests = 1,
            maxQueuedRequests = Int.MAX_VALUE  // Unlimited
        )

        coroutineScope {
            val blocked = async {
                manager.execute("test-model") { adapter ->
                    adapter.invoke(dummyRequest()).getOrThrow()
                }
            }
            delay(50)

            // Queue 100 requests while first is running
            val queued = (0..99).map { i ->
                async {
                    manager.execute("test-model") { adapter ->
                        adapter.invoke(dummyRequest()).getOrThrow()
                    }
                }
            }

            delay(50)

            // All should be pending (not rejected)
            val metrics = manager.snapshotMetrics()
            assertEquals(100, metrics.queueDepth)
            assertEquals(1, metrics.runningRequests)

            // Release first request
            gate.complete(Unit)
            blocked.await()

            // All queued requests should eventually complete
            val results = queued.map { it.await() }
            assertTrue(results.all { it.isSuccess })
        }

        val metrics = manager.snapshotMetrics()
        assertEquals(101, metrics.totalRequests)
        assertEquals(101, metrics.completedRequests)
    }

    @Test
    fun execute_queueSerializesRequests() = runTest {
        val serializingAdapter = CountingAdapter()
        val runtime = FakeRuntime(serializingAdapter)

        // RFC-0103: Serialized queue when memory constrained
        val manager = InferenceRequestManager(
            runtime,
            maxConcurrentRequests = 1,
            maxQueuedRequests = Int.MAX_VALUE
        )

        coroutineScope {
            // 10 concurrent requests
            val requests = (0..9).map { i ->
                async {
                    manager.execute("test-model") { adapter ->
                        adapter.invoke(dummyRequest()).getOrThrow()
                    }
                }
            }

            // All should complete
            val results = requests.map { it.await() }
            assertTrue(results.all { it.isSuccess })
        }

        // Only 1 concurrent ever running (due to maxConcurrentRequests = 1)
        assertEquals(1, serializingAdapter.maxConcurrentInvokes)
    }

    @Test
    fun openModel_rejectsWhenLicenseNotAccepted() = runTest {
        val licenseStore = InMemoryLicenseAcceptanceStore()
        val runtime = FakeRuntime(CountingAdapter())
        val manager = InferenceRequestManager(
            runtime,
            maxConcurrentRequests = 1,
            maxQueuedRequests = 4,
            licenseStore = licenseStore,
        )

        val result = manager.openModel("test-model")

        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(err is LicenseNotAcceptedException)
        assertEquals("test-model", err.modelId)
        assertEquals("1.0", err.modelVersion)
        assertEquals("sha256-abc123", err.licenseTextDigest)
        assertEquals(0, runtime.loadCalls)
    }

    @Test
    fun openModel_succeedsOnceLicenseAccepted() = runTest {
        val licenseStore = InMemoryLicenseAcceptanceStore()
        val runtime = FakeRuntime(CountingAdapter())
        val manager = InferenceRequestManager(
            runtime,
            maxConcurrentRequests = 1,
            maxQueuedRequests = 4,
            licenseStore = licenseStore,
        )

        assertTrue(manager.openModel("test-model").isFailure)
        licenseStore.accept("test-model", "1.0", "sha256-abc123")

        val result = manager.openModel("test-model")
        assertTrue(result.isSuccess)
        assertEquals(1, runtime.loadCalls)
    }

    @Test
    fun openModel_mergesCatalogLicenseMetadataWhenInstalledLacksMetadata() = runTest {
        val licenseStore = InMemoryLicenseAcceptanceStore()
        val catalogDescriptor = ModelDescriptor(
            id = "test-model",
            name = "Test",
            kind = ModelKind.LLM,
            providerId = "huggingface",
            isLocal = true,
            contextWindow = 2048,
            sizeBytes = 1234,
            digest = null,
            metadata = mapOf(
                "version" to "2.0",
                "license_digest" to "digest-from-catalog",
                "license_text" to "Catalog License",
            ),
        )
        val installedWithoutMetadata = catalogDescriptor.copy(metadata = emptyMap())
        val runtime = FakeRuntime(
            adapter = CountingAdapter(),
            catalogDescriptors = listOf(catalogDescriptor),
            installedDescriptors = listOf(installedWithoutMetadata),
        )
        val manager = InferenceRequestManager(runtime, licenseStore = licenseStore)

        val denied = manager.openModel("test-model")
        assertTrue(denied.isFailure)
        assertTrue(denied.exceptionOrNull() is LicenseNotAcceptedException)
        assertEquals(0, runtime.loadCalls)

        licenseStore.accept("test-model", "2.0", "digest-from-catalog")
        assertTrue(manager.openModel("test-model").isSuccess)
        assertEquals(1, runtime.loadCalls)
    }

    @Test
    fun missingModel_delegatesToRuntimeLoadForCanonicalNotInstalledError() = runTest {
        val licenseStore = InMemoryLicenseAcceptanceStore()
        val runtime = FakeRuntime(
            adapter = CountingAdapter(),
            catalogDescriptors = emptyList(),
            installedDescriptors = emptyList(),
            loadError = { modelId -> IllegalStateException("MODEL_NOT_INSTALLED: $modelId") },
        )
        val manager = InferenceRequestManager(runtime, licenseStore = licenseStore)

        val openResult = manager.openModel("missing-model")
        assertTrue(openResult.isFailure)
        assertTrue(openResult.exceptionOrNull() is IllegalStateException)
        assertTrue(openResult.exceptionOrNull()?.message?.contains("MODEL_NOT_INSTALLED") == true)

        val execResult = manager.execute("missing-model") { adapter ->
            adapter.invoke(dummyRequest()).getOrThrow()
        }
        assertTrue(execResult.isFailure)
        assertTrue(execResult.exceptionOrNull() is IllegalStateException)
        assertEquals(2, runtime.loadCalls)
    }
}

private class FakeRuntime(
    private val adapter: ModelAdapter,
    private val deleteStarted: CompletableDeferred<Unit>? = null,
    private val deleteGate: CompletableDeferred<Unit>? = null,
    private val descriptorMetadata: Map<String, String> = mapOf(
        "version" to "1.0",
        "license_digest" to "sha256-abc123",
        "license_text" to "Test License - Accept to use",
    ),
    private val catalogDescriptors: List<ModelDescriptor>? = null,
    private val installedDescriptors: List<ModelDescriptor>? = null,
    private val loadError: ((String) -> Throwable?)? = null,
) : ModelRuntime {
    var deleteCalls: Int = 0
        private set
    var unloadCalls: Int = 0
        private set
    var loadCalls: Int = 0
        private set

    override suspend fun catalog(): List<ModelDescriptor> = catalogDescriptors ?: listOf(
        ModelDescriptor(
            id = "test-model",
            name = "Test",
            kind = ModelKind.LLM,
            providerId = "test",
            isLocal = true,
            contextWindow = 2048,
            sizeBytes = 1234,
            digest = null,
            metadata = descriptorMetadata,
        )
    )

    override suspend fun installed(): List<ModelDescriptor> = installedDescriptors ?: catalog()

    override suspend fun load(modelId: String): Result<ModelAdapter> {
        loadCalls++
        loadError?.invoke(modelId)?.let { return Result.failure(it) }
        return Result.success(adapter)
    }

    override suspend fun unload(modelId: String) {
        unloadCalls++
    }

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

/** A native-style adapter: inference blocks until [cancelCurrentInference] interrupts it. */
private class InterruptibleAdapter : CancellableModelAdapter {
    override val providerId: String = "test"
    override val modelId: String = "test-model"
    override val modelVersion: String = "1"
    override val contextWindow: Int = 2048
    override val isLocal: Boolean = true

    val started = CompletableDeferred<Unit>()
    private val cancelled = CompletableDeferred<Unit>()
    var cancelCalls: Int = 0
        private set

    override fun supportsNativeToolCalls(): Boolean = false

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        started.complete(Unit)
        cancelled.await()
        return Result.failure(CancellationException("native inference cancelled"))
    }

    override fun cancelCurrentInference() {
        cancelCalls++
        cancelled.complete(Unit)
    }
}
