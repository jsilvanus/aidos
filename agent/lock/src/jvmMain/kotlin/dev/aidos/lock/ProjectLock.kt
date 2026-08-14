package dev.aidos.lock

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.time.Instant

/**
 * Advisory file lock for a project (RFC-0055).
 *
 * Acquisition:
 * 1. Take an OS advisory lock on `.aidos/instance.lock` via `FileChannel.tryLock`.
 * 2. Write metadata (instance_id, pid, acquired_at, heartbeat_at) into the file.
 * 3. Heartbeat loop updates `heartbeat_at` every 30 s so others can detect a crash.
 * 4. Release on clean shutdown.
 *
 * A lock whose heartbeat is older than [STALE_THRESHOLD_SECONDS] may be broken:
 * breaking is always visible — the caller receives a [LockBreakRecord] they must log.
 *
 * OS advisory locks are released by the OS when the process dies, so a crashed runtime
 * leaves no permanent lock. The heartbeat exists for unreliable OS lock surfaces (network
 * filesystems, some Android volumes).
 */
class ProjectLock private constructor(
    private val lockFile: File,
    private val raf: RandomAccessFile,
    private val fileLock: FileLock,
    val instanceId: String,
    val acquiredAt: String,
) {
    companion object {
        /** File path within a project root (RFC-0055). */
        const val LOCK_FILE_REL = ".aidos/instance.lock"

        /** Heartbeat interval (RFC-0055: every 30 s). */
        const val HEARTBEAT_INTERVAL_SECONDS = 30L

        /** Heartbeat age beyond which a lock is treated as stale (RFC-0055: 3 min). */
        const val STALE_THRESHOLD_SECONDS = 180L

        /**
         * Attempts to acquire the project lock.
         *
         * Returns [AcquireResult.Acquired] on success.
         * Returns [AcquireResult.AlreadyHeld] if the lock is held by another live process.
         * Returns [AcquireResult.StaleBreakable] if the lock file exists but its heartbeat
         * is stale — the caller may choose to call [breakStaleAndAcquire].
         */
        fun tryAcquire(
            projectRoot: File,
            instanceId: String,
            nowIso: () -> String = { Instant.now().toString() },
        ): AcquireResult {
            val lockFile = File(projectRoot, LOCK_FILE_REL)
            lockFile.parentFile.mkdirs()

            val raf = RandomAccessFile(lockFile, "rw")
            val channel = raf.channel
            val fileLock = try {
                channel.tryLock()
            } catch (_: java.nio.channels.OverlappingFileLockException) {
                // Same JVM process holds the lock (e.g. in tests). Treat as AlreadyHeld.
                raf.close()
                val meta = readMeta(lockFile)
                return AcquireResult.AlreadyHeld(
                    instanceId = meta["instance_id"] ?: "unknown",
                    acquiredAt = meta["acquired_at"] ?: "unknown",
                    heartbeatAt = meta["heartbeat_at"] ?: "unknown",
                )
            }

            if (fileLock == null) {
                // OS lock held by another process.
                raf.close()
                val meta = readMeta(lockFile)
                return AcquireResult.AlreadyHeld(
                    instanceId = meta["instance_id"] ?: "unknown",
                    acquiredAt = meta["acquired_at"] ?: "unknown",
                    heartbeatAt = meta["heartbeat_at"] ?: "unknown",
                )
            }

            // We hold the OS lock. Check if the file has stale content from a prior owner
            // that lost the OS lock without cleanup (e.g. network FS).
            val meta = readMeta(lockFile)
            val heartbeatAt = meta["heartbeat_at"]
            if (heartbeatAt != null && isStale(heartbeatAt, nowIso())) {
                // Stale — we have the OS lock, so we can just overwrite.
                // Return StaleBreakable so the caller gets a log record.
                val prevId = meta["instance_id"] ?: "unknown"
                writeMeta(raf, instanceId, nowIso())
                return AcquireResult.StaleBreakable(
                    lock = ProjectLock(lockFile, raf, fileLock, instanceId, nowIso()),
                    previousInstanceId = prevId,
                    previousHeartbeatAt = heartbeatAt,
                )
            }

            writeMeta(raf, instanceId, nowIso())
            return AcquireResult.Acquired(
                ProjectLock(lockFile, raf, fileLock, instanceId, nowIso())
            )
        }

        /** Reads the key=value metadata from the lock file without acquiring the lock. */
        fun readMeta(lockFile: File): Map<String, String> {
            if (!lockFile.exists()) return emptyMap()
            return try {
                lockFile.readLines()
                    .filter { it.contains('=') }
                    .associate { line ->
                        val idx = line.indexOf('=')
                        line.substring(0, idx).trim() to line.substring(idx + 1).trim().removeSurrounding("\"")
                    }
            } catch (_: Exception) {
                emptyMap()
            }
        }

        private fun writeMeta(raf: RandomAccessFile, instanceId: String, now: String) {
            raf.seek(0)
            raf.setLength(0)
            val content = buildString {
                appendLine("instance_id  = \"$instanceId\"")
                appendLine("pid          = ${ProcessHandle.current().pid()}")
                appendLine("acquired_at  = \"$now\"")
                appendLine("heartbeat_at = \"$now\"")
            }
            raf.write(content.toByteArray())
            raf.channel.force(false)
        }

        private fun isStale(heartbeatAt: String, now: String): Boolean {
            return try {
                val heartbeat = Instant.parse(heartbeatAt)
                val current = Instant.parse(now)
                current.epochSecond - heartbeat.epochSecond > STALE_THRESHOLD_SECONDS
            } catch (_: Exception) {
                true // Unparseable heartbeat → treat as stale
            }
        }
    }

    /**
     * Updates the heartbeat timestamp. Must be called at least once per [HEARTBEAT_INTERVAL_SECONDS].
     * Caller is responsible for scheduling (e.g. via a ScheduledExecutorService or coroutine timer).
     */
    fun heartbeat(nowIso: String) {
        try {
            raf.seek(0)
            val content = raf.readLines()
            val updated = content.map { line ->
                if (line.trimStart().startsWith("heartbeat_at")) "heartbeat_at = \"$nowIso\""
                else line
            }.joinToString("\n") + "\n"
            raf.seek(0)
            raf.setLength(0)
            raf.write(updated.toByteArray())
            raf.channel.force(false)
        } catch (_: Exception) {
            // Heartbeat failure is non-fatal — the lock is still held via OS advisory lock.
        }
    }

    /** Releases the OS advisory lock and closes the file. */
    fun release() {
        try {
            fileLock.release()
        } finally {
            try {
                raf.close()
            } catch (_: Exception) { }
        }
    }
}

private fun RandomAccessFile.readLines(): List<String> {
    seek(0)
    val sb = StringBuilder()
    var b: Int
    while (read().also { b = it } != -1) sb.append(b.toChar())
    return sb.toString().lines()
}

/** Result of a lock acquisition attempt (RFC-0055). */
sealed interface AcquireResult {
    data class Acquired(val lock: ProjectLock) : AcquireResult
    data class AlreadyHeld(
        val instanceId: String,
        val acquiredAt: String,
        val heartbeatAt: String,
    ) : AcquireResult
    /** Lock was stale (OS lock acquired successfully); contains the newly-acquired lock. */
    data class StaleBreakable(
        val lock: ProjectLock,
        val previousInstanceId: String,
        val previousHeartbeatAt: String,
    ) : AcquireResult
}

/** What was recorded when a stale lock was broken (RFC-0055). For the caller to persist. */
data class LockBreakRecord(
    val previousInstanceId: String,
    val previousHeartbeatAt: String,
    val brokenByInstanceId: String,
    val brokenAt: String,
)
