package dev.aidos.api

import dev.aidos.lock.AcquireResult
import dev.aidos.lock.ProjectLock
import java.io.File

/**
 * JVM [ProjectLocker], wrapping `dev.aidos.lock.ProjectLock`'s `FileChannel.tryLock` advisory
 * locking (RFC-0055). Not thread-safe across concurrent calls for the *same* project id — callers
 * (`RealRuntimeClient`) are already single-threaded per project via their own coroutine
 * dispatch, so this doesn't add its own synchronization.
 */
class JvmProjectLocker : ProjectLocker {

    private val heldLocks = mutableMapOf<String, ProjectLock>()

    override fun tryAcquire(projectId: String, projectRootPath: String, instanceId: String): ProjectLockOutcome {
        return when (val result = ProjectLock.tryAcquire(File(projectRootPath), instanceId)) {
            is AcquireResult.Acquired -> {
                heldLocks[projectId] = result.lock
                ProjectLockOutcome.Acquired
            }
            is AcquireResult.StaleBreakable -> {
                heldLocks[projectId] = result.lock
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

    override fun release(projectId: String) {
        heldLocks.remove(projectId)?.release()
    }
}
