package fi.italeino.aidos.engine.inference

import dev.aidos.kernel.CancellableModelAdapter
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelRuntime
import fi.italeino.aidos.engine.license.LicenseAcceptanceStore
import fi.italeino.aidos.engine.license.LicenseNotAcceptedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

enum class InferenceRequestState { QUEUED, LOADING, RUNNING, COMPLETED, CANCELLED, FAILED }

data class InferenceLifecycleMetrics(
    val queueDepth: Int,
    val runningRequests: Int,
    val totalRequests: Long,
    val completedRequests: Long,
    val cancelledRequests: Long,
    val failedRequests: Long,
)

class EngineBusyException(message: String) : RuntimeException(message)
class EngineShuttingDownException(message: String) : RuntimeException(message)
class EngineModelBusyException(message: String) : RuntimeException(message)

/**
 * Coordinates bounded inference admission with safe model open/close/delete lifecycle operations.
 *
 * @param modelRuntime The model runtime to load/execute models
 * @param maxConcurrentRequests Maximum concurrent requests allowed (default 1)
 * @param maxQueuedRequests Maximum requests allowed in queue. Per RFC-0103 §4.2 "Concurrency and memory policy",
 *                          requests are "serialized through a request queue instead of rejected" when memory
 *                          constraints prevent concurrent execution. Set to Int.MAX_VALUE for unlimited queue;
 *                          bounded values may be appropriate for memory-constrained devices but violate RFC intent.
 * @param licenseStore Optional license acceptance store; if present, license is checked before execution
 */
class InferenceRequestManager(
    private val modelRuntime: ModelRuntime,
    private val maxConcurrentRequests: Int = 1,
    private val maxQueuedRequests: Int = Int.MAX_VALUE,  // RFC-0103: unlimited queue, reject never for queue depth
    private val licenseStore: LicenseAcceptanceStore? = null,
    /**
     * RFC-0103 item 4: requests beyond [maxConcurrentRequests] (up to [maxParallelRequests]) run in
     * parallel only while this returns true -- i.e. the resident working set leaves memory headroom.
     * Otherwise they wait in the serialized queue; they are never rejected for it. Defaults to no
     * headroom, which is the plain serialized behaviour.
     */
    private val maxParallelRequests: Int = maxConcurrentRequests,
    private val parallelHeadroom: () -> Boolean = { false },
) {
    private val waiters = ArrayDeque<CompletableDeferred<Unit>>()
    private val stateMutex = Mutex()
    private val modelLocks = mutableMapOf<String, Mutex>()
    private val activeByModel = mutableMapOf<String, Int>()
    private val admittedByModel = mutableMapOf<String, Int>()
    private val closingModels = mutableSetOf<String>()
    private val deletingModels = mutableSetOf<String>()
    private val activeAdapters = mutableMapOf<String, CancellableModelAdapter>()
    private val requestJobsByModel = mutableMapOf<String, MutableSet<Job>>()

    // A snapshot of admittedByModel's keys, replaced under stateMutex and read without it.
    @Volatile private var busyModels: Set<String> = emptySet()

    private var queueDepth = 0
    private var runningRequests = 0
    private var totalRequests = 0L
    private var completedRequests = 0L
    private var cancelledRequests = 0L
    private var failedRequests = 0L
    private var shuttingDown = false

    suspend fun <T> execute(modelId: String, block: suspend (ModelAdapter) -> T): Result<T> =
        execute(modelId, Locality.ANY) { adapter, _ -> block(adapter) }

    /**
     * [execute] under a [Locality] requirement (RFC-0104). The check runs before the model is
     * loaded, so a refused request never loads (or contacts) a model it may not use, and again
     * against the loaded adapter, so a runtime whose descriptor says local but whose adapter says
     * otherwise is still refused. [block] receives the [Placement] the request is served at.
     * A refusal is a [LocalityViolationException] result; one caught before loading takes no admission slot.
     *
     * RFC-0103: License acceptance is checked before admission to avoid taking a queue slot
     * for a request that will be rejected anyway.
     */
    suspend fun <T> execute(
        modelId: String,
        locality: Locality,
        block: suspend (ModelAdapter, Placement) -> T,
    ): Result<T> {
        // RFC-0103: Check license acceptance before queuing/loading the model.
        // This must happen before admission to avoid taking a queue slot for a refused request.
        // The descriptor is looked up once and shared by the licence and locality checks: each
        // lookup lists the installed models and the catalog, which is not free on any host.
        val described = if (licenseStore != null || locality != Locality.ANY) describe(modelId) else null
        val licenseCheck = checkLicenseAcceptance(described)
        if (licenseCheck.isFailure) return Result.failure(licenseCheck.exceptionOrNull()!!)

        val descriptor = if (locality == Locality.ANY) null else described
        if (descriptor != null) {
            val declared = placementOf(descriptor)
            if (!locality.permits(declared)) return Result.failure(LocalityViolationException(modelId, locality, declared))
        }
        val requestJob = currentCoroutineContext()[Job]
            ?: return Result.failure(IllegalStateException("Inference request requires a coroutine Job"))
        val admitted = acquireAdmissionSlot(modelId, requestJob)
        if (admitted.isFailure) return Result.failure(admitted.exceptionOrNull()!!)

        try {
            updateState(InferenceRequestState.LOADING)
            val adapter = modelRuntime.load(modelId).getOrElse {
                updateState(InferenceRequestState.FAILED)
                return Result.failure(it)
            }
            val placement = placementOf(described ?: describe(modelId), adapter)
            if (!locality.permits(placement)) {
                updateState(InferenceRequestState.FAILED)
                return Result.failure(LocalityViolationException(modelId, locality, placement))
            }
            val modelMutex = stateMutex.withLock { modelLocks.getOrPut(modelId) { Mutex() } }
            return modelMutex.withLock {
                markActive(modelId, +1, adapter)
                try {
                    updateState(InferenceRequestState.RUNNING)
                    Result.success(block(adapter, placement)).also { updateState(InferenceRequestState.COMPLETED) }
                } catch (e: CancellationException) {
                    updateState(InferenceRequestState.CANCELLED)
                    Result.failure(e)
                } catch (e: Exception) {
                    updateState(InferenceRequestState.FAILED)
                    Result.failure(e)
                } finally {
                    markActive(modelId, -1, adapter)
                }
            }
        } finally {
            releaseAdmissionSlot(modelId, requestJob)
        }
    }

    /** Enforces [licenseStore] when present and the model carries license metadata. */
    private suspend fun checkLicenseAcceptance(modelId: String): Result<Unit> {
        if (licenseStore == null) return Result.success(Unit)
        return checkLicenseAcceptance(describe(modelId))
    }

    private suspend fun checkLicenseAcceptance(described: ModelDescriptor?): Result<Unit> {
        val store = licenseStore ?: return Result.success(Unit)
        // Missing models continue to modelRuntime.load() so callers get the canonical "not installed" 404 error.
        val descriptor = described ?: return Result.success(Unit)
        val licenseText = descriptor.metadata["license_text"] ?: descriptor.metadata["license"] ?: ""
        val licenseDigest = descriptor.metadata["license_digest"]
            ?: descriptor.metadata["license_text_digest"]
            ?: licenseText.takeIf { it.isNotBlank() }?.let(::sha256Hex)
            ?: ""
        if (licenseDigest.isEmpty()) return Result.success(Unit)
        val version = descriptor.metadata["version"]
            ?: descriptor.metadata["model_version"]
            ?: descriptor.digest
            ?: ""
        val accepted = store.isAccepted(descriptor.id, version, licenseDigest)
        if (!accepted) {
            return Result.failure(
                LicenseNotAcceptedException(
                    descriptor.id,
                    version,
                    licenseText,
                    licenseDigest,
                )
            )
        }
        return Result.success(Unit)
    }

    /** The descriptor for [modelId], preferring the installed record over the catalog's while preserving catalog metadata. */
    private suspend fun describe(modelId: String): ModelDescriptor? {
        val installed = modelRuntime.installed().firstOrNull { it.id == modelId }
        val catalog = modelRuntime.catalog().firstOrNull { it.id == modelId }
        return when {
            installed != null && catalog != null -> installed.copy(
                digest = installed.digest ?: catalog.digest,
                format = installed.format ?: catalog.format,
                quantization = installed.quantization ?: catalog.quantization,
                metadata = catalog.metadata + installed.metadata,
            )
            else -> installed ?: catalog
        }
    }

    /** Force-load a model into memory without performing inference. */
    suspend fun openModel(modelId: String): Result<Unit> {
        val allowed = stateMutex.withLock {
            when {
                shuttingDown -> Result.failure<Unit>(EngineShuttingDownException("Engine is shutting down"))
                closingModels.contains(modelId) || deletingModels.contains(modelId) ->
                    Result.failure<Unit>(EngineModelBusyException("Model $modelId is being closed or deleted"))
                else -> Result.success(Unit)
            }
        }
        if (allowed.isFailure) return allowed

        val licenseCheck = checkLicenseAcceptance(modelId)
        if (licenseCheck.isFailure) return licenseCheck

        return try {
            modelRuntime.load(modelId).map { Unit }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Interrupt active and queued work, then unload the model while keeping it installed. */
    suspend fun closeModel(modelId: String): Result<Unit> {
        var jobs: List<Job> = emptyList()
        var adapter: CancellableModelAdapter? = null
        val admission = stateMutex.withLock {
            if (shuttingDown) return@withLock Result.failure<Unit>(EngineShuttingDownException("Engine is shutting down"))
            if (!closingModels.add(modelId)) {
                return@withLock Result.failure<Unit>(EngineModelBusyException("Model $modelId is already being closed"))
            }
            jobs = requestJobsByModel[modelId]?.toList().orEmpty()
            adapter = activeAdapters[modelId]
            Result.success(Unit)
        }
        if (admission.isFailure) return admission

        return try {
            adapter?.cancelCurrentInference()
            jobs.forEach { it.cancel() }
            val drained = waitUntilModelAdmissionsDrained(modelId, 5_000.milliseconds)
            if (!drained) return Result.failure(
                EngineModelBusyException("Model $modelId did not stop within the close timeout")
            )
            modelRuntime.unload(modelId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            stateMutex.withLock { closingModels.remove(modelId) }
        }
    }

    /** Close (and therefore interrupt) the model before removing its installed artifact. */
    suspend fun deleteModel(modelId: String): Result<Unit> {
        val close = closeModel(modelId)
        if (close.isFailure) return close
        val canDelete = stateMutex.withLock {
            if (shuttingDown) return@withLock Result.failure<Unit>(EngineShuttingDownException("Engine is shutting down"))
            if (!deletingModels.add(modelId)) {
                return@withLock Result.failure<Unit>(EngineModelBusyException("Model $modelId is already being deleted"))
            }
            Result.success(Unit)
        }
        if (canDelete.isFailure) return canDelete
        return try {
            modelRuntime.delete(modelId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            stateMutex.withLock { deletingModels.remove(modelId) }
        }
    }

    /**
     * Whether a request for [modelId] is queued, loading or running, without suspending. This is
     * what the model runtime asks before it evicts or idles out a model (RFC-0022), from inside
     * its own locks, so it must not wait on this manager's lock.
     */
    fun isModelBusy(modelId: String): Boolean = modelId in busyModels

    suspend fun isModelActive(modelId: String): Boolean = stateMutex.withLock {
        (activeByModel[modelId] ?: 0) > 0 || (admittedByModel[modelId] ?: 0) > 0
    }

    suspend fun snapshotMetrics(): InferenceLifecycleMetrics = stateMutex.withLock {
        InferenceLifecycleMetrics(queueDepth, runningRequests, totalRequests, completedRequests, cancelledRequests, failedRequests)
    }

    suspend fun waitUntilModelIdle(modelId: String, timeout: Duration = 5_000.milliseconds): Boolean {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            if (stateMutex.withLock { (activeByModel[modelId] ?: 0) == 0 }) return true
            delay(25.milliseconds)
        }
        return stateMutex.withLock { (activeByModel[modelId] ?: 0) == 0 }
    }

    suspend fun cancelAll() {
        val jobsAndAdapters = stateMutex.withLock {
            shuttingDown = true
            requestJobsByModel.values.flatten() to activeAdapters.values.toList()
        }
        jobsAndAdapters.second.forEach { it.cancelCurrentInference() }
        jobsAndAdapters.first.forEach { it.cancel() }
    }

    suspend fun shutdownAndDrain(timeout: Duration = 5_000.milliseconds): Boolean {
        cancelAll()
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            if (stateMutex.withLock { queueDepth == 0 && runningRequests == 0 }) return true
            delay(25.milliseconds)
        }
        return stateMutex.withLock { queueDepth == 0 && runningRequests == 0 }
    }

    private suspend fun acquireAdmissionSlot(modelId: String, requestJob: Job): Result<Unit> {
        val admitted = stateMutex.withLock {
            if (shuttingDown) return@withLock Result.failure<Unit>(EngineShuttingDownException("Engine is shutting down"))
            if (closingModels.contains(modelId) || deletingModels.contains(modelId)) {
                return@withLock Result.failure<Unit>(EngineModelBusyException("Model $modelId is being closed or deleted"))
            }
            val inSystem = queueDepth + runningRequests
            val queueIsBounded = maxQueuedRequests != Int.MAX_VALUE
            val queueLimit = maxConcurrentRequests.toLong() + maxQueuedRequests.toLong()
            if (queueIsBounded && inSystem.toLong() >= queueLimit) {
                return@withLock Result.failure<Unit>(EngineBusyException("Engine is busy (running=$runningRequests, queued=$queueDepth, max=$maxConcurrentRequests+$maxQueuedRequests)"))
            }
            queueDepth++
            totalRequests++
            admittedByModel[modelId] = (admittedByModel[modelId] ?: 0) + 1
            busyModels = admittedByModel.keys.toSet()
            requestJobsByModel.getOrPut(modelId) { mutableSetOf() }.add(requestJob)
            Result.success(Unit)
        }
        if (admitted.isFailure) return admitted
        // The turn is taken under the same lock that counts running requests, so two waiters can
        // never both see a free slot, and waiters are served in arrival order.
        val turn = CompletableDeferred<Unit>()
        withBookkeepingLock {
            waiters.addLast(turn)
            grantTurns()
        }
        return try {
            updateState(InferenceRequestState.QUEUED)
            if (maxParallelRequests > maxConcurrentRequests) {
                // Memory headroom can appear without any request finishing (a model was unloaded),
                // so a waiter for a parallel slot looks again now and then. Serialized engines
                // never take this branch and are woken only by a release.
                while (withTimeoutOrNull(HEADROOM_RECHECK_MILLIS) { turn.await() } == null) {
                    withBookkeepingLock { grantTurns() }
                }
            } else {
                turn.await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            withBookkeepingLock {
                if (waiters.remove(turn)) {
                    queueDepth = (queueDepth - 1).coerceAtLeast(0)
                } else {
                    // The turn was granted in the same instant the request was cancelled: hand it on.
                    runningRequests = (runningRequests - 1).coerceAtLeast(0)
                    grantTurns()
                }
                decrementAdmitted(modelId)
                cancelledRequests++
                requestJobsByModel[modelId]?.remove(requestJob)
                if (requestJobsByModel[modelId].isNullOrEmpty()) requestJobsByModel.remove(modelId)
            }
            Result.failure(e)
        }
    }

    /** Starts as many waiting requests, oldest first, as the limits allow. Caller holds [stateMutex]. */
    private fun grantTurns() {
        while (waiters.isNotEmpty() &&
            (runningRequests < maxConcurrentRequests || (runningRequests < maxParallelRequests && parallelHeadroom()))
        ) {
            val next = waiters.removeFirst()
            queueDepth = (queueDepth - 1).coerceAtLeast(0)
            runningRequests++
            next.complete(Unit)
        }
    }

    private suspend fun releaseAdmissionSlot(modelId: String, requestJob: Job) {
        withBookkeepingLock {
            runningRequests = (runningRequests - 1).coerceAtLeast(0)
            decrementAdmitted(modelId)
            requestJobsByModel[modelId]?.remove(requestJob)
            if (requestJobsByModel[modelId].isNullOrEmpty()) requestJobsByModel.remove(modelId)
            grantTurns()
        }
    }

    private fun decrementAdmitted(modelId: String) {
        val next = ((admittedByModel[modelId] ?: 0) - 1).coerceAtLeast(0)
        if (next == 0) admittedByModel.remove(modelId) else admittedByModel[modelId] = next
        busyModels = admittedByModel.keys.toSet()
    }

    private suspend fun markActive(modelId: String, delta: Int, adapter: ModelAdapter) {
        withBookkeepingLock {
            val next = ((activeByModel[modelId] ?: 0) + delta).coerceAtLeast(0)
            if (next == 0) {
                activeByModel.remove(modelId)
                activeAdapters.remove(modelId)
            } else {
                activeByModel[modelId] = next
                if (adapter is CancellableModelAdapter) activeAdapters[modelId] = adapter
            }
        }
    }

    private suspend fun waitUntilModelAdmissionsDrained(modelId: String, timeout: Duration): Boolean {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            if (stateMutex.withLock { (admittedByModel[modelId] ?: 0) == 0 }) return true
            delay(25.milliseconds)
        }
        return stateMutex.withLock { (admittedByModel[modelId] ?: 0) == 0 }
    }

    private suspend fun updateState(state: InferenceRequestState) {
        withBookkeepingLock {
            when (state) {
                InferenceRequestState.COMPLETED -> completedRequests++
                InferenceRequestState.CANCELLED -> cancelledRequests++
                InferenceRequestState.FAILED -> failedRequests++
                else -> Unit
            }
        }
    }

    // Bookkeeping runs from catch/finally blocks of requests that cancelAll() has just
    // cancelled. Mutex.lock is cancellable, so under contention a plain withLock would throw
    // there and skip the counter update -- leaving runningRequests > 0 and making
    // shutdownAndDrain time out. NonCancellable guarantees the release always happens.
    private companion object {
        const val HEADROOM_RECHECK_MILLIS = 50L
    }

    private suspend fun <T> withBookkeepingLock(action: () -> T): T =
        withContext(NonCancellable) { stateMutex.withLock(action = action) }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
