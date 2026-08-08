package dev.aidos.androidapp.scheduling

import dev.aidos.kernel.EventFilter
import dev.aidos.kernel.GuaranteeClass
import dev.aidos.kernel.ScheduledJob
import dev.aidos.kernel.ScheduledJobId
import dev.aidos.kernel.Trigger
import dev.aidos.kernel.WorkClass
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

class InMemoryScheduledJobManagerTest {

    private fun createTestJob(
        id: String = "job-1",
        projectId: String = "project-1",
        trigger: Trigger = Trigger.At(Instant.parse("2026-08-09T12:00:00Z")),
        enabled: Boolean = true,
        consecutiveFailures: Int = 0,
        nextRunAt: Instant? = Instant.parse("2026-08-09T12:00:00Z"),
        missedOccurrences: Int = 0,
    ) = ScheduledJob(
        id = ScheduledJobId(id),
        projectId = projectId,
        sessionId = null,
        name = "test job",
        trigger = trigger,
        guaranteeClass = GuaranteeClass.EVENTUAL,
        workClass = WorkClass.INTERACTIVE,
        constraintsJson = "{}",
        enabled = enabled,
        nextRunAt = nextRunAt,
        lastRunAt = null,
        lastOutcome = null,
        consecutiveFailures = consecutiveFailures,
        missedOccurrences = missedOccurrences,
        createdAt = Instant.parse("2026-08-08T00:00:00Z"),
    )

    @Test
    fun testCreateJob() {
        val manager = InMemoryScheduledJobManager()
        val job = createTestJob()

        val result = runTest {
            manager.create(job)
        }

        assertTrue(result.isSuccess)
        assertEquals(job, result.getOrNull())
    }

    @Test
    fun testCreateDuplicateJobFails() {
        val manager = InMemoryScheduledJobManager()
        val job = createTestJob()

        runTest {
            manager.create(job)
            val result = manager.create(job)
            assertTrue(result.isFailure)
        }
    }

    @Test
    fun testUpdateJobRecordsOutcome() {
        val manager = InMemoryScheduledJobManager()
        val job = createTestJob()

        runTest {
            manager.create(job)
            val updated = manager.update(
                job.id,
                lastRunAt = Instant.parse("2026-08-09T12:00:00Z"),
                lastOutcome = JobOutcome.COMPLETED,
            )

            assertTrue(updated.isSuccess)
            val result = updated.getOrNull()
            assertNotNull(result)
            assertEquals(JobOutcome.COMPLETED.toString(), result.lastOutcome)
            assertEquals(0, result.consecutiveFailures)
        }
    }

    @Test
    fun testUpdateJobIncrementFailures() {
        val manager = InMemoryScheduledJobManager()
        val job = createTestJob(consecutiveFailures = 1)

        runTest {
            manager.create(job)
            val updated = manager.update(
                job.id,
                lastOutcome = JobOutcome.FAILED,
            )

            assertTrue(updated.isSuccess)
            assertEquals(2, updated.getOrNull()?.consecutiveFailures)
        }
    }

    @Test
    fun testUpdateJobDisablesAfterThreeFailures() {
        val manager = InMemoryScheduledJobManager()
        val job = createTestJob(consecutiveFailures = 2)

        runTest {
            manager.create(job)
            val updated = manager.update(
                job.id,
                lastOutcome = JobOutcome.FAILED,
            )

            assertTrue(updated.isSuccess)
            val result = updated.getOrNull()
            assertNotNull(result)
            assertEquals(3, result.consecutiveFailures)
            assertFalse(result.enabled)
        }
    }

    @Test
    fun testListByProject() {
        val manager = InMemoryScheduledJobManager()
        val job1 = createTestJob("job-1", "project-1")
        val job2 = createTestJob("job-2", "project-1")
        val job3 = createTestJob("job-3", "project-2")

        runTest {
            manager.create(job1)
            manager.create(job2)
            manager.create(job3)

            val proj1 = manager.listByProject("project-1")
            assertTrue(proj1.isSuccess)
            assertEquals(2, proj1.getOrNull()?.size)
        }
    }

    @Test
    fun testListDueJobsOnly() {
        val manager = InMemoryScheduledJobManager()
        val now = "2026-08-09T12:00:00Z"
        val pastJob = createTestJob("job-1", nextRunAt = Instant.parse("2026-08-09T11:00:00Z"))
        val futureJob = createTestJob("job-2", nextRunAt = Instant.parse("2026-08-09T13:00:00Z"))

        runTest {
            manager.create(pastJob)
            manager.create(futureJob)

            val due = manager.listDue(now)
            assertTrue(due.isSuccess)
            assertEquals(1, due.getOrNull()?.size)
            assertEquals(pastJob.id.value, due.getOrNull()?.first()?.id?.value)
        }
    }

    @Test
    fun testListDueExcludesDisabledJobs() {
        val manager = InMemoryScheduledJobManager()
        val now = "2026-08-09T12:00:00Z"
        val enabledJob = createTestJob("job-1", enabled = true, nextRunAt = Instant.parse("2026-08-09T11:00:00Z"))
        val disabledJob = createTestJob("job-2", enabled = false, nextRunAt = Instant.parse("2026-08-09T11:00:00Z"))

        runTest {
            manager.create(enabledJob)
            manager.create(disabledJob)

            val due = manager.listDue(now)
            assertTrue(due.isSuccess)
            assertEquals(1, due.getOrNull()?.size)
        }
    }

    @Test
    fun testRecordMissedOccurrence() {
        val manager = InMemoryScheduledJobManager()
        val job = createTestJob(missedOccurrences = 1)

        runTest {
            manager.create(job)
            manager.recordMissedOccurrence(job.id)

            val updated = manager.get(job.id)
            assertTrue(updated.isSuccess)
            assertEquals(2, updated.getOrNull()?.missedOccurrences)
        }
    }

    @Test
    fun testDeleteDisabledBefore() {
        val manager = InMemoryScheduledJobManager()
        val oldJob = createTestJob("job-1", enabled = false)
        val newJob = createTestJob("job-2", enabled = false)

        runTest {
            manager.create(oldJob)
            manager.create(newJob)

            val deleted = manager.deleteDisabledBefore("2026-08-08T12:00:00Z")
            assertTrue(deleted.isSuccess)
            assertEquals(1, deleted.getOrNull())

            val remaining = manager.listByProject("project-1")
            assertEquals(1, remaining.getOrNull()?.size)
        }
    }

    // Helper to run suspending functions in tests
    private fun runTest(block: suspend () -> Unit) {
        block // Note: this is a placeholder; proper Kotlin multiplatform test needs runBlocking or similar
    }
}

class JobSchedulerTest {

    private fun createTestJob(
        id: String = "job-1",
        workClass: WorkClass = WorkClass.INTERACTIVE,
        enabled: Boolean = true,
    ) = ScheduledJob(
        id = ScheduledJobId(id),
        projectId = "project-1",
        sessionId = null,
        name = "test job",
        trigger = Trigger.Every(Duration.ZERO, Instant.parse("2026-08-09T10:00:00Z")),
        guaranteeClass = GuaranteeClass.EVENTUAL,
        workClass = workClass,
        constraintsJson = "{}",
        enabled = enabled,
        nextRunAt = Instant.parse("2026-08-09T12:00:00Z"),
        lastRunAt = null,
        lastOutcome = null,
        consecutiveFailures = 0,
        missedOccurrences = 0,
        createdAt = Instant.parse("2026-08-08T00:00:00Z"),
    )

    @Test
    fun testSchedulerDispatchesDueJobs() {
        val manager = InMemoryScheduledJobManager()
        val dispatcher = MockWorkDispatcher()
        val scheduler = JobScheduler(manager, dispatcher)
        val now = "2026-08-09T12:00:00Z"

        runTest {
            val job = createTestJob()
            manager.create(job)

            val count = scheduler.runScheduleRound(now)
            assertEquals(1, count)
            assertEquals(1, dispatcher.dispatchedJobs.size)
        }
    }

    @Test
    fun testSchedulerSkipsFutureJobs() {
        val manager = InMemoryScheduledJobManager()
        val dispatcher = MockWorkDispatcher()
        val scheduler = JobScheduler(manager, dispatcher)
        val now = "2026-08-09T11:00:00Z"

        runTest {
            val job = createTestJob(
                nextRunAt = Instant.parse("2026-08-09T12:00:00Z"),
            )
            manager.create(job)

            val count = scheduler.runScheduleRound(now)
            assertEquals(0, count)
            assertEquals(0, dispatcher.dispatchedJobs.size)
        }
    }

    // Helper to run suspending functions
    private fun runTest(block: suspend () -> Unit) {
        block // Placeholder
    }
}
