package dev.aidos.androidapp.scheduling

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aidos.kernel.GuaranteeClass
import dev.aidos.kernel.ScheduledJob
import dev.aidos.kernel.ScheduledJobId
import dev.aidos.kernel.Trigger
import dev.aidos.kernel.WorkClass
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class SqliteScheduledJobManagerTest {

    private fun createDriver(): SqlDriver {
        return JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    }

    private fun createTestJob(
        id: String = "job-1",
        projectId: String = "project-1",
        trigger: Trigger = Trigger.At(Instant.parse("2026-08-09T12:00:00Z")),
        enabled: Boolean = true,
        consecutiveFailures: Int = 0,
        nextRunAt: Instant? = Instant.parse("2026-08-09T12:00:00Z"),
        missedOccurrences: Int = 0,
        createdAt: Instant = Instant.parse("2026-08-08T00:00:00Z"),
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
        createdAt = createdAt,
    )

    @Test
    fun testCreateJob() = runTest {
        val driver = createDriver()
        val manager = SqliteScheduledJobManager(driver)
        val job = createTestJob()

        val result = manager.create(job)

        assertTrue(result.isSuccess)
        assertEquals(job, result.getOrNull())
    }

    @Test
    fun testCreateDuplicateJobFails() = runTest {
        val driver = createDriver()
        val manager = SqliteScheduledJobManager(driver)
        val job = createTestJob()

        manager.create(job)
        val result = manager.create(job)
        assertTrue(result.isFailure)
    }

    @Test
    fun testUpdateJobRecordsOutcome() = runTest {
        val driver = createDriver()
        val manager = SqliteScheduledJobManager(driver)
        val job = createTestJob()

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

    @Test
    fun testUpdateJobIncrementFailures() = runTest {
        val driver = createDriver()
        val manager = SqliteScheduledJobManager(driver)
        val job = createTestJob(consecutiveFailures = 1)

        manager.create(job)
        val updated = manager.update(
            job.id,
            lastOutcome = JobOutcome.FAILED,
        )

        assertTrue(updated.isSuccess)
        assertEquals(2, updated.getOrNull()?.consecutiveFailures)
    }

    @Test
    fun testUpdateJobDisablesAfterThreeFailures() = runTest {
        val driver = createDriver()
        val manager = SqliteScheduledJobManager(driver)
        val job = createTestJob(consecutiveFailures = 2)

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

    @Test
    fun testListByProject() = runTest {
        val driver = createDriver()
        val manager = SqliteScheduledJobManager(driver)
        val job1 = createTestJob("job-1", "project-1")
        val job2 = createTestJob("job-2", "project-1")
        val job3 = createTestJob("job-3", "project-2")

        manager.create(job1)
        manager.create(job2)
        manager.create(job3)

        val proj1 = manager.listByProject("project-1")
        assertTrue(proj1.isSuccess)
        assertEquals(2, proj1.getOrNull()?.size)
    }

    @Test
    fun testListDueJobsOnly() = runTest {
        val driver = createDriver()
        val manager = SqliteScheduledJobManager(driver)
        val now = "2026-08-09T12:00:00Z"
        val pastJob = createTestJob("job-1", nextRunAt = Instant.parse("2026-08-09T11:00:00Z"))
        val futureJob = createTestJob("job-2", nextRunAt = Instant.parse("2026-08-09T13:00:00Z"))

        manager.create(pastJob)
        manager.create(futureJob)

        val due = manager.listDue(now)
        assertTrue(due.isSuccess)
        assertEquals(1, due.getOrNull()?.size)
        assertEquals(pastJob.id.value, due.getOrNull()?.first()?.id?.value)
    }

    @Test
    fun testListDueExcludesDisabledJobs() = runTest {
        val driver = createDriver()
        val manager = SqliteScheduledJobManager(driver)
        val now = "2026-08-09T12:00:00Z"
        val enabledJob = createTestJob("job-1", enabled = true, nextRunAt = Instant.parse("2026-08-09T11:00:00Z"))
        val disabledJob = createTestJob("job-2", enabled = false, nextRunAt = Instant.parse("2026-08-09T11:00:00Z"))

        manager.create(enabledJob)
        manager.create(disabledJob)

        val due = manager.listDue(now)
        assertTrue(due.isSuccess)
        assertEquals(1, due.getOrNull()?.size)
    }

    @Test
    fun testRecordMissedOccurrence() = runTest {
        val driver = createDriver()
        val manager = SqliteScheduledJobManager(driver)
        val job = createTestJob(missedOccurrences = 1)

        manager.create(job)
        manager.recordMissedOccurrence(job.id)

        val updated = manager.get(job.id)
        assertTrue(updated.isSuccess)
        assertEquals(2, updated.getOrNull()?.missedOccurrences)
    }

    @Test
    fun testDeleteDisabledBefore() = runTest {
        val driver = createDriver()
        val manager = SqliteScheduledJobManager(driver)
        val oldJob = createTestJob("job-1", enabled = false, createdAt = Instant.parse("2026-08-08T00:00:00Z"))
        val newJob = createTestJob("job-2", enabled = false, createdAt = Instant.parse("2026-08-09T00:00:00Z"))

        manager.create(oldJob)
        manager.create(newJob)

        val deleted = manager.deleteDisabledBefore("2026-08-08T12:00:00Z")
        assertTrue(deleted.isSuccess)
        assertEquals(1, deleted.getOrNull())

        val remaining = manager.listByProject("project-1")
        assertEquals(1, remaining.getOrNull()?.size)
    }

    @Test
    fun testCancelJob() = runTest {
        val driver = createDriver()
        val manager = SqliteScheduledJobManager(driver)
        val job = createTestJob(enabled = true)

        manager.create(job)
        val cancel = manager.cancel(job.id)

        assertTrue(cancel.isSuccess)
        val cancelled = manager.get(job.id).getOrNull()
        assertNotNull(cancelled)
        assertFalse(cancelled.enabled)
    }

    @Test
    fun testGetJob() = runTest {
        val driver = createDriver()
        val manager = SqliteScheduledJobManager(driver)
        val job = createTestJob()

        manager.create(job)
        val retrieved = manager.get(job.id)

        assertTrue(retrieved.isSuccess)
        assertEquals(job, retrieved.getOrNull())
    }

    @Test
    fun testGetNonExistentJob() = runTest {
        val driver = createDriver()
        val manager = SqliteScheduledJobManager(driver)

        val retrieved = manager.get(ScheduledJobId("nonexistent"))
        assertTrue(retrieved.isSuccess)
        assertNull(retrieved.getOrNull())
    }
}
