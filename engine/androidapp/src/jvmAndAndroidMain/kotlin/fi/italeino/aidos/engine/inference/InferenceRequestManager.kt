package fi.italeino.aidos.engine.inference

import dev.aidos.kernel.CancellableModelAdapter
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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

/** Coordinates bounded inference admission with safe model close/delete lifecycle operations. */
class InferenceRequestManager(
    private val modelRuntime: ModelRuntime,
    private val maxConcurrentRequests: Int = 1,
    private val maxQueuedRequests: Int = 8,
) {
    private val capacity = Semaphore(maxConcurrentRequests)
    private val stateMutex = Mutex()
    private val modelLocks = mutableMapOf<String, Mutex>()
    private val activeByModel = mutableMapOf<String, Int>()
    private val admittedByModel = mutableMapOf<String, Int>()
    private val closingModels = mutableSetOf<String>()
    private val deletingModels = mutableSetOf<String>()
    private val activeAdapters = mutableMapOf<String, CancellableModelAdapter>()
    private val requestJobsByModel = mutableMapOf<String, MutableSet<Job>>()

    private var queueDepth = 0
    private var runningRequests = 0
    private var totalRequests = 0L
    private var completedRequests = 0L
    private var cancelledRequests = 0L
    private var failedRequests = 0L
    private var shuttingDown = false

    suspend fun <T> execute(modelId: String, block: suspend (ModelAdapter) -> T): Result<T> {
        val requestJob = currentCoroutineContext()[Job]
            ?: return Result.failure(IllegalStateException("Inference request requires a coroutine Job"))
        val admitted = acquireAdmissionSlot(modelId, requestJob)
        if (admitted.isFailure) return Result.failure(admitted.exceptionOrNull()!!)

        try {
            updateState(InferenceRequestState.LOADING)
            val adapter = modelRuntime.load(modelId).getOrElse { return Result.failure(it) }
            val modelMutex = stateMutex.withLock { modelLocks.getOrPut(modelId) { Mutex() } }
            return modelMutex.withLock {
                markActive(modelId, +1, adapter)
                try {
                    updateState(InferenceRequestState.RUNNING)
                    Result.success(block(adapter)).also { updateState(InferenceRequestState.COMPLETED) }
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

    /** Interrupt active and queued work, then unload the model while keeping it installed. */
    suspend fun closeModel(modelId: String): Result<Unit> {
        val jobsAndAdapter = stateMutex.withLock {
            if (shuttingDown) return@withLock Result.failure<Unit>(EngineShuttingDownException("Engine is shutting down"))
            if (!closingModels.add(modelId)) {
                return@withLock Result.failure<Unit>(EngineModelBusyException("Model $modelId is already being closed"))
            }
            requestJobsByModel[modelId]?.toList().orEmpty() to activeAdapters[modelId]
        }

        return try {
            jobsAndAdapter.second?.cancelCurrentInference()
            jobsAndAdapter.first.forEach { it.cancel() }
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
            if (inSystem >= maxConcurrentRequests + maxQueuedRequests) {
                return@withLock Result.failure<Unit>(EngineBusyException("Engine is busy (running=$runningRequests, queued=$queueDepth, max=$maxConcurrentRequests+$maxQueuedRequests)"))
            }
            queueDepth++
            totalRequests++
            admittedByModel[modelId] = (admittedByModel[modelId] ?: 0) + 1
            requestJobsByModel.getOrPut(modelId) { mutableSetOf() }.add(requestJob)
            Result.success(Unit)
        }
        if (admitted.isFailure) return admitted
        return try {
            updateState(InferenceRequestState.QUEUED)
            capacity.acquire()
            stateMutex.withLock { queueDepth--; runningRequests++ }
            Result.success(Unit)
        } catch (e: CancellationException) {
            stateMutex.withLock {
                queueDepth = (queueDepth - 1).coerceAtLeast(0)
                decrementAdmitted(modelId)
                cancelledRequests++
                requestJobsByModel[modelId]?.remove(requestJob)
                if (requestJobsByModel[modelId].isNullOrEmpty()) requestJobsByModel.remove(modelId)
            }
            Result.failure(e)
        }
    }

    private suspend fun releaseAdmissionSlot(modelId: String, requestJob: Job) {
        stateMutex.withLock {
            runningRequests = (runningRequests - 1).coerceAtLeast(0)
            decrementAdmitted(modelId)
            requestJobsByModel[modelId]?.remove(requestJob)
            if (requestJobsByModel[modelId].isNullOrEmpty()) requestJobsByModel.remove(modelId)
        }
        capacity.release()
    }

    private fun decrementAdmitted(modelId: String) {
        val next = ((admittedByModel[modelId] ?: 0) - 1).coerceAtLeast(0)
        if (next == 0) admittedByModel.remove(modelId) else admittedByModel[modelId] = next
    }

    private suspend fun markActive(modelId: String, delta: Int, adapter: ModelAdapter) {
        stateMutex.withLock {
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
        stateMutex.withLock {
            when (state) {
                InferenceRequestState.COMPLETED -> completedRequests++
                InferenceRequestState.CANCELLED -> cancelledRequests++
                InferenceRequestState.FAILED -> failedRequests++
                else -> Unit
            }
        }
    }
}
