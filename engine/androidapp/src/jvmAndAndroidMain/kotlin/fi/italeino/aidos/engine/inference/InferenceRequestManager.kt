package fi.italeino.aidos.engine.inference

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

enum class InferenceRequestState {
    QUEUED,
    LOADING,
    RUNNING,
    COMPLETED,
    CANCELLED,
    FAILED,
}

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
 * Serializes model inference and keeps request lifecycle state explicit.
 *
 * - Bounded admission (`maxConcurrentRequests` + `maxQueuedRequests`)
 * - Per-model generation serialization
 * - Metrics for queue/running/fail/cancel counts
 * - Explicit cancellation of all admitted work during service shutdown
 * - Model deletion is rejected while any request for that model is admitted
 */
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
    private val deletingModels = mutableSetOf<String>()
    private val requestJobs = mutableSetOf<Job>()

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
                markActive(modelId, +1)
                try {
                    updateState(InferenceRequestState.RUNNING)
                    val result = block(adapter)
                    updateState(InferenceRequestState.COMPLETED)
                    Result.success(result)
                } catch (e: CancellationException) {
                    updateState(InferenceRequestState.CANCELLED)
                    Result.failure(e)
                } catch (e: Exception) {
                    updateState(InferenceRequestState.FAILED)
                    Result.failure(e)
                } finally {
                    markActive(modelId, -1)
                }
            }
        } finally {
            releaseAdmissionSlot(modelId, requestJob)
        }
    }

    /**
     * Deletes a model only when no request for that model is admitted.
     *
     * The model is marked as deleting under the same mutex used for admission, so there is no
     * check-then-delete race: a new inference cannot be admitted after the deletion check passes.
     * Existing queued or running requests cause deletion to fail rather than being interrupted.
     */
    suspend fun deleteModel(modelId: String): Result<Unit> {
        val canDelete = stateMutex.withLock {
            if (shuttingDown) {
                return@withLock Result.failure(
                    EngineShuttingDownException("Engine is shutting down")
                )
            }
            val admitted = admittedByModel[modelId] ?: 0
            if (admitted > 0) {
                return@withLock Result.failure(
                    EngineModelBusyException(
                        "Model $modelId is busy ($admitted inference request(s) admitted)"
                    )
                )
            }
            if (!deletingModels.add(modelId)) {
                return@withLock Result.failure(
                    EngineModelBusyException("Model $modelId is already being deleted")
                )
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
        InferenceLifecycleMetrics(
            queueDepth = queueDepth,
            runningRequests = runningRequests,
            totalRequests = totalRequests,
            completedRequests = completedRequests,
            cancelledRequests = cancelledRequests,
            failedRequests = failedRequests,
        )
    }

    suspend fun waitUntilModelIdle(modelId: String, timeout: Duration = 5_000.milliseconds): Boolean {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            val active = stateMutex.withLock { activeByModel[modelId] ?: 0 }
            if (active == 0) return true
            delay(25.milliseconds)
        }
        return stateMutex.withLock { (activeByModel[modelId] ?: 0) == 0 }
    }

    /**
     * Stops admitting new work and cancels every currently admitted request.
     * Cancellation is propagated through the request coroutine to the adapter. The native
     * adapter is responsible for observing cancellation at its deepest supported boundary.
     */
    suspend fun cancelAll() {
        val jobs = stateMutex.withLock {
            shuttingDown = true
            requestJobs.toList()
        }
        jobs.forEach { it.cancel() }
    }

    /**
     * Prevents new work, cancels admitted requests, and waits for their cleanup to finish.
     * This is the shutdown path used by the Android service before native model disposal.
     */
    suspend fun shutdownAndDrain(timeout: Duration = 5_000.milliseconds): Boolean {
        cancelAll()
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            val drained = stateMutex.withLock { queueDepth == 0 && runningRequests == 0 }
            if (drained) return true
            delay(25.milliseconds)
        }
        return stateMutex.withLock { queueDepth == 0 && runningRequests == 0 }
    }

    private suspend fun acquireAdmissionSlot(modelId: String, requestJob: Job): Result<Unit> {
        val admitted = stateMutex.withLock {
            if (shuttingDown) {
                return@withLock Result.failure(
                    EngineShuttingDownException("Engine is shutting down")
                )
            }
            if (deletingModels.contains(modelId)) {
                return@withLock Result.failure(
                    EngineModelBusyException("Model $modelId is being deleted")
                )
            }
            val inSystem = queueDepth + runningRequests
            if (inSystem >= maxConcurrentRequests + maxQueuedRequests) {
                return@withLock Result.failure(
                    EngineBusyException(
                        "Engine is busy (running=$runningRequests, queued=$queueDepth, max=$maxConcurrentRequests+$maxQueuedRequests)"
                    )
                )
            }
            queueDepth++
            totalRequests++
            admittedByModel[modelId] = (admittedByModel[modelId] ?: 0) + 1
            requestJobs += requestJob
            Result.success(Unit)
        }
        if (admitted.isFailure) return admitted

        return try {
            updateState(InferenceRequestState.QUEUED)
            capacity.acquire()
            stateMutex.withLock {
                queueDepth--
                runningRequests++
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            stateMutex.withLock {
                queueDepth = (queueDepth - 1).coerceAtLeast(0)
                decrementAdmitted(modelId)
                cancelledRequests++
                requestJobs.remove(requestJob)
            }
            Result.failure(e)
        }
    }

    private suspend fun releaseAdmissionSlot(modelId: String, requestJob: Job) {
        stateMutex.withLock {
            runningRequests = (runningRequests - 1).coerceAtLeast(0)
            decrementAdmitted(modelId)
            requestJobs.remove(requestJob)
        }
        capacity.release()
    }

    private fun decrementAdmitted(modelId: String) {
        val next = ((admittedByModel[modelId] ?: 0) - 1).coerceAtLeast(0)
        if (next == 0) admittedByModel.remove(modelId) else admittedByModel[modelId] = next
    }

    private suspend fun markActive(modelId: String, delta: Int) {
        stateMutex.withLock {
            val current = activeByModel[modelId] ?: 0
            val next = (current + delta).coerceAtLeast(0)
            if (next == 0) activeByModel.remove(modelId) else activeByModel[modelId] = next
        }
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
