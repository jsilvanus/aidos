package dev.aidos.lock

import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M7 done-when (RFC-0055):
 *
 * 1. Second acquire on held project → AlreadyHeld with holder metadata.
 * 2. Stale lock (heartbeat expired) → StaleBreakable, existing process can proceed.
 * 3. Clean release → file lock freed.
 */
class ProjectLockTest {

    private fun tempRoot(): File = Files.createTempDirectory("lock-test").toFile()

    @Test
    fun `first acquire succeeds`() {
        val root = tempRoot()
        val result = ProjectLock.tryAcquire(root, instanceId = "inst-1")
        try {
            assertIs<AcquireResult.Acquired>(result)
        } finally {
            (result as? AcquireResult.Acquired)?.lock?.release()
        }
    }

    @Test
    fun `second acquire on live lock returns AlreadyHeld`() {
        val root = tempRoot()
        val first = ProjectLock.tryAcquire(root, instanceId = "inst-1")
        assertIs<AcquireResult.Acquired>(first, "First acquire must succeed")
        try {
            val second = ProjectLock.tryAcquire(root, instanceId = "inst-2")
            assertIs<AcquireResult.AlreadyHeld>(second,
                "Second acquire must see AlreadyHeld, not $second")
            assertEquals("inst-1", second.instanceId)
        } finally {
            (first as AcquireResult.Acquired).lock.release()
        }
    }

    @Test
    fun `stale lock is breakable when heartbeat exceeds threshold`() {
        val root = tempRoot()
        // Simulate a stale lock: write a lock file with an old heartbeat, but no OS lock held.
        val lockFile = File(root, ProjectLock.LOCK_FILE_REL)
        lockFile.parentFile.mkdirs()
        val staleTime = Instant.now()
            .minusSeconds(ProjectLock.STALE_THRESHOLD_SECONDS + 60)
            .toString()
        lockFile.writeText("""
            instance_id  = "old-inst"
            pid          = 99999
            acquired_at  = "$staleTime"
            heartbeat_at = "$staleTime"
        """.trimIndent())

        val result = ProjectLock.tryAcquire(root, instanceId = "inst-new")
        try {
            assertIs<AcquireResult.StaleBreakable>(result,
                "Stale lock should return StaleBreakable, got $result")
            assertEquals("old-inst", result.previousInstanceId)
        } finally {
            (result as? AcquireResult.StaleBreakable)?.lock?.release()
            (result as? AcquireResult.Acquired)?.lock?.release()
        }
    }

    @Test
    fun `released lock can be re-acquired`() {
        val root = tempRoot()
        val first = ProjectLock.tryAcquire(root, instanceId = "inst-1")
        assertIs<AcquireResult.Acquired>(first)
        first.lock.release()

        val second = ProjectLock.tryAcquire(root, instanceId = "inst-2")
        try {
            assertIs<AcquireResult.Acquired>(second, "Re-acquire after release should succeed")
        } finally {
            (second as? AcquireResult.Acquired)?.lock?.release()
        }
    }

    @Test
    fun `heartbeat updates timestamp in file`() {
        val root = tempRoot()
        val result = ProjectLock.tryAcquire(root, instanceId = "inst-1")
        assertIs<AcquireResult.Acquired>(result)
        val lock = result.lock
        try {
            val newTime = Instant.now().plusSeconds(60).toString()
            lock.heartbeat(newTime)
            val meta = ProjectLock.readMeta(File(root, ProjectLock.LOCK_FILE_REL))
            assertEquals(newTime, meta["heartbeat_at"],
                "heartbeat_at should be updated to $newTime but got ${meta["heartbeat_at"]}")
        } finally {
            lock.release()
        }
    }
}
