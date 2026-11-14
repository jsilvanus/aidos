package dev.aidos.lock

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
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
            first.lock.release()
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
        var released = false
        try {
            val newTime = Instant.now().plusSeconds(60).toString()
            lock.heartbeat(newTime)
            lock.release()
            released = true
            val meta = ProjectLock.readMeta(File(root, ProjectLock.LOCK_FILE_REL))
            assertEquals(newTime, meta["heartbeat_at"],
                "heartbeat_at should be updated to $newTime but got ${meta["heartbeat_at"]}")
        } finally {
            if (!released) lock.release()
        }
    }

    @Test
    fun `concurrent acquire heartbeat and release on same project is thread-safe`() {
        val root = tempRoot()
        val threads = 12
        val iterations = 40
        val executor = Executors.newFixedThreadPool(threads)
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threads)
        val activeHolders = AtomicInteger(0)
        val acquiredCount = AtomicInteger(0)
        val alreadyHeldCount = AtomicInteger(0)
        val errors = CopyOnWriteArrayList<Throwable>()

        try {
            repeat(threads) { threadIdx ->
                executor.submit {
                    try {
                        assertTrue(startGate.await(10, TimeUnit.SECONDS))
                        val instanceId = "inst-$threadIdx"
                        repeat(iterations) { iter ->
                            when (val res = ProjectLock.tryAcquire(root, instanceId = instanceId)) {
                                is AcquireResult.Acquired -> {
                                    try {
                                        assertEquals(1, activeHolders.incrementAndGet())
                                        acquiredCount.incrementAndGet()
                                        res.lock.heartbeat(Instant.now().plusSeconds(iter.toLong()).toString())
                                        Thread.yield()
                                    } finally {
                                        activeHolders.decrementAndGet()
                                        res.lock.release()
                                    }
                                }
                                is AcquireResult.StaleBreakable -> {
                                    try {
                                        assertEquals(1, activeHolders.incrementAndGet())
                                        acquiredCount.incrementAndGet()
                                    } finally {
                                        activeHolders.decrementAndGet()
                                        res.lock.release()
                                    }
                                }
                                is AcquireResult.AlreadyHeld -> {
                                    alreadyHeldCount.incrementAndGet()
                                    assertNotEquals("unknown", res.instanceId)
                                    assertNotEquals("unknown", res.acquiredAt)
                                    assertNotEquals("unknown", res.heartbeatAt)
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        errors.add(t)
                    } finally {
                        doneGate.countDown()
                    }
                }
            }
            startGate.countDown()
            assertTrue(doneGate.await(30, TimeUnit.SECONDS), "Concurrency test timed out")
        } finally {
            executor.shutdownNow()
        }

        assertTrue(errors.isEmpty(), "Unexpected concurrency errors: $errors")
        assertTrue(acquiredCount.get() > 0)
        assertEquals(threads * iterations, acquiredCount.get() + alreadyHeldCount.get())

        val finalAcquire = ProjectLock.tryAcquire(root, instanceId = "final-check")
        try {
            assertIs<AcquireResult.Acquired>(finalAcquire, "Lock should be free after all threads release")
        } finally {
            (finalAcquire as? AcquireResult.Acquired)?.lock?.release()
        }
    }

    @Test
    fun `concurrent acquire and release across distinct projects does not corrupt holder map`() {
        val projectCount = 8
        val roots = List(projectCount) { tempRoot() }
        val threads = 16
        val iterations = 50
        val executor = Executors.newFixedThreadPool(threads)
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threads)
        val activePerProject = AtomicIntegerArray(projectCount)
        val errors = CopyOnWriteArrayList<Throwable>()

        try {
            repeat(threads) { threadIdx ->
                executor.submit {
                    try {
                        assertTrue(startGate.await(10, TimeUnit.SECONDS))
                        repeat(iterations) { iter ->
                            val idx = (threadIdx + iter) % projectCount
                            when (val res = ProjectLock.tryAcquire(roots[idx], instanceId = "worker-$threadIdx")) {
                                is AcquireResult.Acquired -> {
                                    try {
                                        assertEquals(1, activePerProject.incrementAndGet(idx))
                                        Thread.yield()
                                    } finally {
                                        activePerProject.decrementAndGet(idx)
                                        res.lock.release()
                                    }
                                }
                                is AcquireResult.StaleBreakable -> {
                                    try {
                                        assertEquals(1, activePerProject.incrementAndGet(idx))
                                    } finally {
                                        activePerProject.decrementAndGet(idx)
                                        res.lock.release()
                                    }
                                }
                                is AcquireResult.AlreadyHeld -> {
                                    assertTrue(res.instanceId.startsWith("worker-"))
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        errors.add(t)
                    } finally {
                        doneGate.countDown()
                    }
                }
            }
            startGate.countDown()
            assertTrue(doneGate.await(30, TimeUnit.SECONDS), "Multi-project concurrency test timed out")
        } finally {
            executor.shutdownNow()
        }

        assertTrue(errors.isEmpty(), "Unexpected multi-project concurrency errors: $errors")
        roots.forEach { root ->
            val check = ProjectLock.tryAcquire(root, instanceId = "verify-clean")
            try {
                assertIs<AcquireResult.Acquired>(check)
            } finally {
                (check as? AcquireResult.Acquired)?.lock?.release()
            }
        }
    }
}
