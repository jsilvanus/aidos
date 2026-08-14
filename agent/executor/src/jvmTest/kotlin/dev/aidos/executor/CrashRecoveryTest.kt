package dev.aidos.executor

import dev.aidos.broker.AuditLog
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RunId
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M8 / G1: Crash-recovery suite (RFC-0038).
 *
 * Simulates a crash at each executor checkpoint boundary by building the expected DB state
 * that would exist if a crash occurred exactly at that boundary, then calling drive() on a
 * fresh executor instance and asserting it resumes correctly to COMPLETED.
 *
 * Boundaries tested:
 *   B1  Run is PENDING, task is PENDING — not yet started.
 *   B2  Run is RUNNING, task is PENDING — Run started but task not yet dispatched.
 *   B3  Run is RUNNING, task is RUNNING — task dispatched but not finished.
 *   B4  Run is RUNNING, task is COMPLETED, step_index incremented — all tasks done but Run
 *       not yet set to COMPLETED.
 *
 * In each case, a second call to drive() on a fresh executor must arrive at COMPLETED without
 * re-executing completed tasks. drive() is re-entrant and idempotent (RFC-0009).
 */
class CrashRecoveryTest {

    private val nowIso = "2026-08-05T00:00:00Z"

    private fun openDriver() = run {
        val root = Files.createTempDirectory("crash-test").toFile()
        AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
            as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
    }

    private fun buildExecutor(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        runner: TaskRunner = AlwaysSucceedRunner(),
    ): SqliteExecutor {
        return SqliteExecutor(
            driver = driver,
            audit = AuditLog(driver),
            events = EventStore(driver),
            idGen = { UuidV7Generator().next() },
            nowIso = { nowIso },
            taskRunner = runner,
        )
    }

    private fun seedProject(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        projectId: String,
    ) {
        driver.execute(null,
            "INSERT OR IGNORE INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) " +
                "VALUES (?, 'test', '/', 'generic', ?, ?, ?)", 4
        ) { bindString(0, projectId); bindString(1, nowIso); bindString(2, nowIso); bindString(3, nowIso) }
        driver.execute(null,
            "INSERT OR IGNORE INTO project_revocation_epoch (project_id, epoch) VALUES (?, 0)", 1
        ) { bindString(0, projectId) }
    }

    private fun seedAll(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        projectId: String,
        sessionId: String,
        eventId: String,
        runId: String,
        taskId: String,
    ) {
        seedProject(driver, projectId)
        driver.execute(null,
            "INSERT OR IGNORE INTO sessions (id, project_id, name, role, state, created_at, last_active_at, state_updated_at) " +
                "VALUES (?, ?, 'test', 'DRIVER', 'RUNNING', ?, ?, ?)", 5
        ) { bindString(0, sessionId); bindString(1, projectId); bindString(2, nowIso); bindString(3, nowIso); bindString(4, nowIso) }
        driver.execute(null,
            "INSERT OR IGNORE INTO events (id, project_id, sequence, type, schema_version, category, visibility, " +
                "timestamp, source, payload, causal_depth) VALUES (?, ?, 0, 'UserMessage', 1, 'SIGNAL', 'SESSION', ?, 'user', '{}', 0)", 4
        ) { bindString(0, eventId); bindString(1, projectId); bindString(2, nowIso) }
        driver.execute(null,
            "INSERT INTO runs (id, session_id, project_id, trigger_event_id, started_at, state, " +
                "retry_policy_json, platform_profile, device_id) VALUES (?, ?, ?, ?, ?, 'PENDING', '{}', 'DESKTOP', 'dev-1')", 5
        ) { bindString(0, runId); bindString(1, sessionId); bindString(2, projectId); bindString(3, eventId); bindString(4, nowIso) }
        driver.execute(null,
            "INSERT INTO tasks (id, run_id, session_id, project_id, ordinal, kind, description, state, retry_policy_json) " +
                "VALUES (?, ?, ?, ?, 0, 'TOOL_CALL', 'test task', 'PENDING', '{}')", 5
        ) { bindString(0, taskId); bindString(1, runId); bindString(2, sessionId); bindString(3, projectId) }
    }

    private fun runState(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        runId: String,
    ): String? = driver.executeQuery(null, "SELECT state FROM runs WHERE id = ?",
        mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 1
    ) { bindString(0, runId) }.value

    private fun taskState(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        taskId: String,
    ): String? = driver.executeQuery(null, "SELECT state FROM tasks WHERE id = ?",
        mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 1
    ) { bindString(0, taskId) }.value

    // ─── Boundary tests ──────────────────────────────────────────────────────

    /**
     * B1: Crash before any work — Run PENDING, task PENDING.
     * Resume from B1 → drive() runs the task and completes the Run.
     */
    @Test
    fun `B1 crash before start resumes to COMPLETED`() = runBlocking {
        val driver = openDriver()
        val pid = "p-b1"; val sid = "s-b1"; val eid = "e-b1"; val rid = "r-b1"; val tid = "t-b1"
        seedAll(driver, pid, sid, eid, rid, tid)
        // State: Run=PENDING, Task=PENDING — exactly B1

        buildExecutor(driver).drive(RunId(rid))

        assertEquals("COMPLETED", runState(driver, rid), "B1: Run should be COMPLETED after resume")
        assertEquals("COMPLETED", taskState(driver, tid), "B1: Task should be COMPLETED after resume")
    }

    /**
     * B2: Crash after Run→RUNNING but before task dispatch.
     * State: Run=RUNNING, task=PENDING, step_index=0.
     */
    @Test
    fun `B2 crash after run started resumes to COMPLETED`() = runBlocking {
        val driver = openDriver()
        val pid = "p-b2"; val sid = "s-b2"; val eid = "e-b2"; val rid = "r-b2"; val tid = "t-b2"
        seedAll(driver, pid, sid, eid, rid, tid)
        // Simulate crash after PENDING→RUNNING
        driver.execute(null, "UPDATE runs SET state = 'RUNNING' WHERE id = ?", 1) { bindString(0, rid) }

        buildExecutor(driver).drive(RunId(rid))

        assertEquals("COMPLETED", runState(driver, rid), "B2: Run should complete")
        assertEquals("COMPLETED", taskState(driver, tid), "B2: Task should complete")
    }

    /**
     * B3: Crash after task→RUNNING but before task completion.
     * State: Run=RUNNING, task=RUNNING, step_index=1.
     */
    @Test
    fun `B3 crash after task started resumes to COMPLETED`() = runBlocking {
        val driver = openDriver()
        val pid = "p-b3"; val sid = "s-b3"; val eid = "e-b3"; val rid = "r-b3"; val tid = "t-b3"
        seedAll(driver, pid, sid, eid, rid, tid)
        // Simulate crash mid-task
        driver.execute(null, "UPDATE runs SET state = 'RUNNING', step_index = 1 WHERE id = ?", 1) { bindString(0, rid) }
        driver.execute(null, "UPDATE tasks SET state = 'RUNNING' WHERE id = ?", 1) { bindString(0, tid) }

        // On resume: task is RUNNING — executor's drive() sees no PENDING tasks.
        // For B3, we need recovery to reset the task before re-driving.
        // First call recover() to reset the RUNNING task (no UNSAFE attempts), then drive().
        val executor = buildExecutor(driver)
        executor.recover(ProjectId(pid))
        executor.drive(RunId(rid))

        assertEquals("COMPLETED", runState(driver, rid), "B3: Run should complete after recovery + drive")
        assertEquals("COMPLETED", taskState(driver, tid), "B3: Task should complete after recovery + drive")
    }

    /**
     * B4: Crash after all tasks COMPLETED but before Run→COMPLETED.
     * State: Run=RUNNING, task=COMPLETED, step_index=1.
     */
    @Test
    fun `B4 crash after task completed but before run completed`() = runBlocking {
        val driver = openDriver()
        val pid = "p-b4"; val sid = "s-b4"; val eid = "e-b4"; val rid = "r-b4"; val tid = "t-b4"
        seedAll(driver, pid, sid, eid, rid, tid)
        driver.execute(null, "UPDATE runs SET state = 'RUNNING', step_index = 1 WHERE id = ?", 1) { bindString(0, rid) }
        driver.execute(null, "UPDATE tasks SET state = 'COMPLETED' WHERE id = ?", 1) { bindString(0, tid) }

        buildExecutor(driver).drive(RunId(rid))

        assertEquals("COMPLETED", runState(driver, rid), "B4: Run should be COMPLETED")
    }

    /**
     * Idempotency: calling drive() on an already-COMPLETED Run is a no-op and does not
     * re-execute any tasks (RFC-0009, M5/M8).
     */
    @Test
    fun `drive on COMPLETED run is idempotent`() = runBlocking {
        val driver = openDriver()
        val pid = "p-idemp"; val sid = "s-idemp"; val eid = "e-idemp"; val rid = "r-idemp"; val tid = "t-idemp"
        seedAll(driver, pid, sid, eid, rid, tid)

        var executionCount = 0
        val countingRunner = object : TaskRunner {
            override suspend fun execute(task: dev.aidos.kernel.Task): TaskResult {
                executionCount++
                return TaskResult(success = true)
            }
        }
        val executor = buildExecutor(driver, countingRunner)
        executor.drive(RunId(rid))
        assertEquals(1, executionCount, "Task should run exactly once")

        // Drive again — must not re-execute
        executor.drive(RunId(rid))
        assertEquals(1, executionCount, "Task must not run on re-entrant drive()")
        assertEquals("COMPLETED", runState(driver, rid))
    }

    private class AlwaysSucceedRunner : TaskRunner {
        override suspend fun execute(task: dev.aidos.kernel.Task) = TaskResult(success = true)
    }
}
