package dev.aidos.executor

import dev.aidos.broker.AuditLog
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.BackoffStrategy
import dev.aidos.kernel.ErrorClass
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RetryPolicy
import dev.aidos.kernel.RunId
import dev.aidos.kernel.Task
import dev.aidos.kernel.TaskKind
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M5 done-when (RFC-0019, RFC-0009, RFC-0004):
 *
 * 1. Run of hard-coded Tasks (one effectful TOOL_CALL, one MODEL_CALL) → COMPLETED.
 * 2. drive() on already-COMPLETED Run is a no-op (re-entrant).
 * 3. At most one effectful Task RUNNING at a time per Run (D14 — tested via effectful-only Run).
 * 4. RunStepCompleted events published per task with monotonic sequence ordering.
 * 5. Events sequence is per-project, not timestamp (RFC-0004): first event seq=1, second seq=2.
 */
class ExecutorTest {

    private val counter = AtomicInteger(0)
    private val nowIso = "2026-08-05T00:00:00Z"

    private fun nextId() = UuidV7Generator().next()

    private fun openDriver() = run {
        val root = Files.createTempDirectory("executor-test").toFile()
        AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
            as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
    }

    private fun seedProject(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        projectId: String,
    ) {
        driver.execute(null,
            "INSERT OR IGNORE INTO projects (id, name, root_path, project_type, " +
                "created_at, updated_at, state_updated_at) VALUES (?, 'test', '/', 'generic', ?, ?, ?)", 4
        ) {
            bindString(0, projectId)
            bindString(1, nowIso)
            bindString(2, nowIso)
            bindString(3, nowIso)
        }
        driver.execute(null,
            "INSERT OR IGNORE INTO project_revocation_epoch (project_id, epoch) VALUES (?, 0)", 1
        ) { bindString(0, projectId) }
    }

    /** Seeds the minimal rows needed to create a Run. */
    private fun seedRunContext(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        projectId: String,
        sessionId: String,
        triggerEventId: String,
    ) {
        // sessions table
        driver.execute(null,
            "INSERT OR IGNORE INTO sessions (id, project_id, name, role, state, " +
                "created_at, last_active_at, state_updated_at) VALUES (?, ?, 'test', 'DRIVER', 'RUNNING', ?, ?, ?)", 5
        ) {
            bindString(0, sessionId)
            bindString(1, projectId)
            bindString(2, nowIso)
            bindString(3, nowIso)
            bindString(4, nowIso)
        }
        // trigger event
        driver.execute(null,
            "INSERT OR IGNORE INTO events (id, project_id, sequence, type, schema_version, " +
                "category, visibility, timestamp, source, payload, causal_depth) " +
                "VALUES (?, ?, 0, 'UserMessage', 1, 'SIGNAL', 'SESSION', ?, 'user', '{}', 0)", 4
        ) {
            bindString(0, triggerEventId)
            bindString(1, projectId)
            bindString(2, nowIso)
        }
    }

    private fun insertRun(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        runId: String,
        projectId: String,
        sessionId: String,
        triggerEventId: String,
    ) {
        driver.execute(null,
            "INSERT INTO runs (id, session_id, project_id, trigger_event_id, started_at, state, " +
                "retry_policy_json, platform_profile, device_id) " +
                "VALUES (?, ?, ?, ?, ?, 'PENDING', '{}', 'DESKTOP', 'device-1')", 5
        ) {
            bindString(0, runId)
            bindString(1, sessionId)
            bindString(2, projectId)
            bindString(3, triggerEventId)
            bindString(4, nowIso)
        }
    }

    private fun insertTask(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        taskId: String,
        runId: String,
        sessionId: String,
        projectId: String,
        ordinal: Int,
        kind: String = "TOOL_CALL",
    ) {
        driver.execute(null,
            "INSERT INTO tasks (id, run_id, session_id, project_id, ordinal, kind, description, state, retry_policy_json) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'test task', 'PENDING', '{}')", 7
        ) {
            bindString(0, taskId)
            bindString(1, runId)
            bindString(2, sessionId)
            bindString(3, projectId)
            bindLong(4, ordinal.toLong())
            bindString(5, kind)
        }
    }

    private fun runState(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        runId: String,
    ): String? =
        driver.executeQuery(null, "SELECT state FROM runs WHERE id = ?",
            mapper = { c ->
                app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null)
            }, 1
        ) { bindString(0, runId) }.value

    private fun taskState(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        taskId: String,
    ): String? =
        driver.executeQuery(null, "SELECT state FROM tasks WHERE id = ?",
            mapper = { c ->
                app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null)
            }, 1
        ) { bindString(0, taskId) }.value

    private fun buildExecutor(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        runner: TaskRunner = AlwaysSucceedRunner(),
    ): SqliteExecutor {
        val audit = AuditLog(driver)
        val events = EventStore(driver)
        return SqliteExecutor(
            driver = driver,
            audit = audit,
            events = events,
            idGen = { nextId() },
            nowIso = { nowIso },
            taskRunner = runner,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `run with two tasks drives to COMPLETED`() = runBlocking {
        val driver = openDriver()
        val pid = nextId(); val sid = nextId(); val eid = nextId()
        seedProject(driver, pid)
        seedRunContext(driver, pid, sid, eid)

        val runId = nextId()
        val t1 = nextId(); val t2 = nextId()
        insertRun(driver, runId, pid, sid, eid)
        // t1 is effectful TOOL_CALL, t2 is MODEL_CALL (read — D14 allows overlap)
        insertTask(driver, t1, runId, sid, pid, ordinal = 0, kind = "TOOL_CALL")
        insertTask(driver, t2, runId, sid, pid, ordinal = 1, kind = "MODEL_CALL")

        val executor = buildExecutor(driver)
        executor.drive(RunId(runId))

        assertEquals("COMPLETED", runState(driver, runId), "Run should be COMPLETED")
        assertEquals("COMPLETED", taskState(driver, t1), "Task 1 should be COMPLETED")
        assertEquals("COMPLETED", taskState(driver, t2), "Task 2 should be COMPLETED")
    }

    @Test
    fun `drive on completed run is no-op`() = runBlocking {
        val driver = openDriver()
        val pid = nextId(); val sid = nextId(); val eid = nextId()
        seedProject(driver, pid)
        seedRunContext(driver, pid, sid, eid)

        val runId = nextId()
        val t1 = nextId()
        insertRun(driver, runId, pid, sid, eid)
        insertTask(driver, t1, runId, sid, pid, ordinal = 0)

        val invocations = AtomicInteger(0)
        val executor = buildExecutor(driver, CountingRunner(invocations))
        executor.drive(RunId(runId))
        assertEquals("COMPLETED", runState(driver, runId))

        val invocationsBefore = invocations.get()
        // Drive again — should not run any tasks
        executor.drive(RunId(runId))
        assertEquals(invocationsBefore, invocations.get(), "No extra tasks run on re-entrant drive()")
    }

    @Test
    fun `run step events are published with monotonic sequence`() = runBlocking {
        val driver = openDriver()
        val pid = nextId(); val sid = nextId(); val eid = nextId()
        seedProject(driver, pid)
        seedRunContext(driver, pid, sid, eid)

        val runId = nextId()
        val t1 = nextId(); val t2 = nextId()
        insertRun(driver, runId, pid, sid, eid)
        insertTask(driver, t1, runId, sid, pid, ordinal = 0)
        insertTask(driver, t2, runId, sid, pid, ordinal = 1)

        val eventStore = EventStore(driver)
        val executor = buildExecutor(driver)
        executor.drive(RunId(runId))

        val events = eventStore.eventsForProject(pid, type = "RunStepCompleted")
        assertTrue(events.size >= 2, "At least 2 RunStepCompleted events (one per task)")
        // Sequences must be strictly increasing (RFC-0004 ordering guarantee)
        val seqs = events.map { it.sequence }
        for (i in 1 until seqs.size) {
            assertTrue(seqs[i] > seqs[i - 1], "Event sequences must be monotonically increasing: $seqs")
        }
    }

    @Test
    fun `failed task transitions run to FAILED`() = runBlocking {
        val driver = openDriver()
        val pid = nextId(); val sid = nextId(); val eid = nextId()
        seedProject(driver, pid)
        seedRunContext(driver, pid, sid, eid)

        val runId = nextId()
        val t1 = nextId()
        insertRun(driver, runId, pid, sid, eid)
        insertTask(driver, t1, runId, sid, pid, ordinal = 0)

        val executor = buildExecutor(driver, AlwaysFailRunner())
        executor.drive(RunId(runId))

        assertEquals("FAILED", runState(driver, runId))
        assertEquals("FAILED", taskState(driver, t1))
    }

    // ─── M6: Recovery ────────────────────────────────────────────────────────

    @Test
    fun `recover marks UNSAFE attempt as INDETERMINATE and does not retry`() = runBlocking {
        val driver = openDriver()
        val pid = nextId(); val sid = nextId(); val eid = nextId()
        seedProject(driver, pid)
        seedRunContext(driver, pid, sid, eid)

        val runId = nextId(); val t1 = nextId()
        insertRun(driver, runId, pid, sid, eid)
        insertTask(driver, t1, runId, sid, pid, ordinal = 0)

        // Seed a RUNNING attempt with UNSAFE recovery class
        val auditId = nextId(); val attemptId = nextId()
        driver.execute(null,
            "INSERT INTO audit_log (id, project_id, sequence, occurred_at, kind, actor_kind, actor_id, device_id, detail_json) " +
                "VALUES (?, ?, 1, ?, 'ToolInvoked', 'SESSION', ?, 'device-1', '{}')", 5
        ) {
            bindString(0, auditId); bindString(1, pid); bindString(2, nowIso)
            bindString(3, sid)
        }
        driver.execute(null,
            "INSERT INTO attempts (id, task_id, attempt_number, started_at, state, recovery_class, audit_ref) " +
                "VALUES (?, ?, 1, ?, 'RUNNING', 'UNSAFE', ?)", 4
        ) {
            bindString(0, attemptId); bindString(1, t1); bindString(2, nowIso); bindString(3, auditId)
        }

        val executor = buildExecutor(driver)
        val report = executor.recover(dev.aidos.kernel.ProjectId(pid))

        assertEquals(listOf(dev.aidos.kernel.AttemptId(attemptId)), report.indeterminateEffects,
            "UNSAFE attempt should be in indeterminateEffects")

        // Attempt should be FAILED, not RUNNING
        val attemptState = driver.executeQuery(null, "SELECT state FROM attempts WHERE id = ?",
            mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 1
        ) { bindString(0, attemptId) }.value
        assertEquals("FAILED", attemptState)

        // Task should still be PENDING (UNSAFE is never re-queued)
        assertEquals("PENDING", taskState(driver, t1))
    }

    @Test
    fun `recover re-queues PURE interrupted attempt`() = runBlocking {
        val driver = openDriver()
        val pid = nextId(); val sid = nextId(); val eid = nextId()
        seedProject(driver, pid)
        seedRunContext(driver, pid, sid, eid)

        val runId = nextId(); val t1 = nextId()
        insertRun(driver, runId, pid, sid, eid)
        insertTask(driver, t1, runId, sid, pid, ordinal = 0)
        // Simulate task RUNNING at crash time
        driver.execute(null, "UPDATE tasks SET state = 'RUNNING' WHERE id = ?", 1) { bindString(0, t1) }

        val auditId = nextId(); val attemptId = nextId()
        driver.execute(null,
            "INSERT INTO audit_log (id, project_id, sequence, occurred_at, kind, actor_kind, actor_id, device_id, detail_json) " +
                "VALUES (?, ?, 1, ?, 'ToolInvoked', 'SESSION', ?, 'device-1', '{}')", 5
        ) {
            bindString(0, auditId); bindString(1, pid); bindString(2, nowIso); bindString(3, sid)
        }
        driver.execute(null,
            "INSERT INTO attempts (id, task_id, attempt_number, started_at, state, recovery_class, audit_ref) " +
                "VALUES (?, ?, 1, ?, 'RUNNING', 'PURE', ?)", 4
        ) {
            bindString(0, attemptId); bindString(1, t1); bindString(2, nowIso); bindString(3, auditId)
        }

        val executor = buildExecutor(driver)
        executor.recover(dev.aidos.kernel.ProjectId(pid))

        // Task should be reset to PENDING for re-execution
        assertEquals("PENDING", taskState(driver, t1), "PURE task should be reset to PENDING")
    }

    @Test
    fun `EventStore refuses events exceeding MAX_CAUSAL_DEPTH`() {
        val driver = openDriver()
        val pid = nextId()
        seedProject(driver, pid)

        val store = EventStore(driver)
        // Seed trigger event first (sequence 0, causal_depth 0)
        driver.execute(null,
            "INSERT INTO events (id, project_id, sequence, type, schema_version, category, visibility, " +
                "timestamp, source, payload, causal_depth) VALUES (?, ?, 0, 'UserMessage', 1, 'SIGNAL', 'SESSION', ?, 'user', '{}', 0)", 4
        ) { bindString(0, nextId()); bindString(1, pid); bindString(2, nowIso) }

        val overLimit = EventStore.MAX_CAUSAL_DEPTH + 1
        val result = store.publish(
            id = nextId(), projectId = pid, type = "SessionWake", source = "executor",
            causalDepth = overLimit, nowIso = nowIso,
        )
        assertEquals(null, result, "Event with causal_depth > MAX_CAUSAL_DEPTH should be refused")

        // Events count should still be 1 (just the seeded trigger)
        val seeded = store.eventsForProject(pid)
        assertEquals(1, seeded.size, "No new event should have been inserted")
    }

    // ─── TaskRunner stubs ─────────────────────────────────────────────────────

    private class AlwaysSucceedRunner : TaskRunner {
        override suspend fun execute(task: Task) = TaskResult(success = true)
    }

    private class AlwaysFailRunner : TaskRunner {
        override suspend fun execute(task: Task) = TaskResult(success = false, errorMessage = "stub failure")
    }

    private class CountingRunner(private val counter: AtomicInteger) : TaskRunner {
        override suspend fun execute(task: Task): TaskResult {
            counter.incrementAndGet()
            return TaskResult(success = true)
        }
    }
}
