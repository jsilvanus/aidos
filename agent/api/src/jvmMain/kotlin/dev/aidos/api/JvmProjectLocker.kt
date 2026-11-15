package dev.aidos.api

import dev.aidos.lock.AcquireResult
import dev.aidos.lock.ProjectLock
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * JVM [ProjectLocker], wrapping `dev.aidos.lock.ProjectLock`'s `FileChannel.tryLock` advisory
 * locking (RFC-0055), with periodic heartbeat updates and clean release on shutdown.
 */
class JvmProjectLocker(
    private val heartbeatIntervalMillis: Long = TimeUnit.SECONDS.toMillis(ProjectLock.HEARTBEAT_INTERVAL_SECONDS),
    private val nowIso: () -> String = { Instant.now().toString() },
) : ProjectLocker {

    private val heldLocks = ConcurrentHashMap<String, ProjectLock>()
    private val heartbeatTasks = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "aidos-lock-heartbeat").apply { isDaemon = true }
    }

    override fun tryAcquire(projectId: String, projectRootPath: String, instanceId: String): ProjectLockOutcome {
        return when (val result = ProjectLock.tryAcquire(File(projectRootPath), instanceId, nowIso)) {
            is AcquireResult.Acquired -> {
                registerHeldLock(projectId, result.lock)
                ProjectLockOutcome.Acquired
            }
            is AcquireResult.StaleBreakable -> {
                registerHeldLock(projectId, result.lock)
                ProjectLockOutcome.AcquiredAfterBreakingStale(
                    previousInstanceId = result.previousInstanceId,
                    previousHeartbeatAt = result.previousHeartbeatAt,
                )
            }
            is AcquireResult.AlreadyHeld -> ProjectLockOutcome.HeldByOther(
                instanceId = result.instanceId,
                acquiredAt = result.acquiredAt,
                heartbeatAt = result.heartbeatAt,
            )
        }
    }

    private fun registerHeldLock(projectId: String, lock: ProjectLock) {
        heartbeatTasks.remove(projectId)?.cancel(false)
        heldLocks[projectId] = lock
        if (heartbeatIntervalMillis > 0L && !scheduler.isShutdown) {
            val task = scheduler.scheduleAtFixedRate(
                { runCatching { lock.heartbeat(nowIso()) } },
                heartbeatIntervalMillis,
                heartbeatIntervalMillis,
                TimeUnit.MILLISECONDS,
            )
            heartbeatTasks[projectId] = task
        }
    }

    override fun release(projectId: String) {
        heartbeatTasks.remove(projectId)?.cancel(false)
        heldLocks.remove(projectId)?.release()
    }

    override fun releaseAll() {
        heartbeatTasks.values.forEach { it.cancel(false) }
        heartbeatTasks.clear()
        val locks = heldLocks.values.toList()
        heldLocks.clear()
        locks.forEach { runCatching { it.release() } }
        scheduler.shutdownNow()
    }
}
