package dev.aidos.lock

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

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
    private val lockKey: String,
    private val raf: RandomAccessFile,
    private val fileLock: FileLock,
    val instanceId: String,
    val acquiredAt: String,
) {
    @Volatile
    private var released = false

    companion object {
        private data class HolderEntry(
            val lock: ProjectLock,
            val instanceId: String,
            val acquiredAt: String,
            val heartbeatAt: String,
        )

        private val inProcessHolders = ConcurrentHashMap<String, HolderEntry>()
        private val lockMonitors = ConcurrentHashMap<String, Any>()

        private fun monitorFor(key: String): Any = lockMonitors.computeIfAbsent(key) { Any() }

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
            val key = lockKey(lockFile)
            synchronized(monitorFor(key)) {
                inProcessHolders[key]?.let { holder ->
                    return AcquireResult.AlreadyHeld(
                        instanceId = holder.instanceId,
                        acquiredAt = holder.acquiredAt,
                        heartbeatAt = holder.heartbeatAt,
                    )
                }

                lockFile.parentFile?.mkdirs()

                val raf = RandomAccessFile(lockFile, "rw")
                val channel = raf.channel
                val fileLock = try {
                    channel.tryLock()
                } catch (_: java.nio.channels.OverlappingFileLockException) {
                    // Same JVM process holds the lock via another path alias. Treat as AlreadyHeld.
                    val meta = readMeta(raf)
                    val holder = inProcessHolders[key]
                    raf.close()
                    return AcquireResult.AlreadyHeld(
                        instanceId = holder?.instanceId ?: meta["instance_id"] ?: "unknown",
                        acquiredAt = holder?.acquiredAt ?: meta["acquired_at"] ?: "unknown",
                        heartbeatAt = holder?.heartbeatAt ?: meta["heartbeat_at"] ?: "unknown",
                    )
                }

                if (fileLock == null) {
                    // OS lock held by another process.
                    val meta = readMeta(raf)
                    raf.close()
                    return AcquireResult.AlreadyHeld(
                        instanceId = meta["instance_id"] ?: "unknown",
                        acquiredAt = meta["acquired_at"] ?: "unknown",
                        heartbeatAt = meta["heartbeat_at"] ?: "unknown",
                    )
                }

                try {
                    val now = nowIso()
                    // We hold the OS lock. Check if the file has stale content from a prior owner
                    // that lost the OS lock without cleanup (e.g. network FS).
                    val meta = readMeta(raf)
                    val heartbeatAt = meta["heartbeat_at"]
                    if (heartbeatAt != null && isStale(heartbeatAt, now)) {
                        // Stale — we have the OS lock, so we can just overwrite.
                        // Return StaleBreakable so the caller gets a log record.
                        val prevId = meta["instance_id"] ?: "unknown"
                        writeMeta(raf, instanceId, now)
                        val lock = ProjectLock(key, raf, fileLock, instanceId, now)
                        inProcessHolders[key] = HolderEntry(lock, instanceId, now, now)
                        return AcquireResult.StaleBreakable(
                            lock = lock,
                            previousInstanceId = prevId,
                            previousHeartbeatAt = heartbeatAt,
                        )
                    }

                    writeMeta(raf, instanceId, now)
                    val lock = ProjectLock(key, raf, fileLock, instanceId, now)
                    inProcessHolders[key] = HolderEntry(lock, instanceId, now, now)
                    return AcquireResult.Acquired(lock)
                } catch (t: Throwable) {
                    inProcessHolders.computeIfPresent(key) { _, existing ->
                        if (existing.lock.raf === raf) null else existing
                    }
                    try {
                        fileLock.release()
                    } catch (_: Exception) { }
                    try {
                        raf.close()
                    } catch (_: Exception) { }
                    throw t
                }
            }
        }

        /** Reads the key=value metadata from the lock file without acquiring the lock. */
        fun readMeta(lockFile: File): Map<String, String> {
            val key = lockKey(lockFile)
            synchronized(monitorFor(key)) {
                if (!lockFile.exists()) return emptyMap()
                return try {
                    RandomAccessFile(lockFile, "r").use { raf -> readMeta(raf) }
                } catch (_: Exception) {
                    emptyMap()
                }
            }
        }

        private fun readMeta(raf: RandomAccessFile): Map<String, String> {
            return try {
                raf.readLines()
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

        private fun lockKey(lockFile: File): String =
            runCatching { lockFile.canonicalPath }
                .getOrElse { lockFile.absoluteFile.normalize().path }
    }

    /**
     * Updates the heartbeat timestamp. Must be called at least once per [HEARTBEAT_INTERVAL_SECONDS].
     * Caller is responsible for scheduling (e.g. via a ScheduledExecutorService or coroutine timer).
     */
    fun heartbeat(nowIso: String) {
        synchronized(monitorFor(lockKey)) {
            if (released) return
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
                inProcessHolders.computeIfPresent(lockKey) { _, existing ->
                    if (existing.lock === this@ProjectLock) existing.copy(heartbeatAt = nowIso) else existing
                }
            } catch (_: Exception) {
                // Heartbeat failure is non-fatal — the lock is still held via OS advisory lock.
            }
        }
    }

    /** Releases the OS advisory lock and closes the file. */
    fun release() {
        synchronized(monitorFor(lockKey)) {
            if (released) return
            released = true
            try {
                try {
                    fileLock.release()
                } finally {
                    inProcessHolders.computeIfPresent(lockKey) { _, existing ->
                        if (existing.lock === this@ProjectLock) null else existing
                    }
                }
            } finally {
                try {
                    raf.close()
                } catch (_: Exception) { }
            }
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
