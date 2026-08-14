package dev.aidos.executor

import dev.aidos.broker.AuditLog
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RunId
import dev.aidos.kernel.Task
import dev.aidos.kernel.TaskKind
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `TaskRunner.execute()` may now append follow-on tasks (RFC-0009's own `execute(task) // may
 * append new Tasks`) — the primitive the AgentLoop↔executor bridge needs for `MODEL_CALL` →
 * `TOOL_CALL` → `MODEL_CALL` fan-out. This is the generic mechanism, independent of
 * [AgentLoopTaskRunner]: a task completing and the follow-on tasks it produces must land in one
 * transaction, or a crash between them would leave a Run whose task set looks all-terminal (and
 * therefore COMPLETED) when it was really one step short.
 */
class TaskAppendingTest {

    private val nowIso = "2026-08-09T00:00:00Z"

    private fun nextId() = UuidV7Generator().next()

    private fun openDriver() = run {
        val root = Files.createTempDirectory("task-append-test").toFile()
        AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
            as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
    }

    private fun seedRun(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        projectId: String,
        sessionId: String,
        eventId: String,
        runId: String,
        firstTaskId: String,
        runState: String = "PENDING",
        firstTaskState: String = "PENDING",
    ) {
        driver.execute(null,
            "INSERT INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) " +
                "VALUES (?, 'test', '/', 'generic', ?, ?, ?)", 4
        ) { bindString(0, projectId); bindString(1, nowIso); bindString(2, nowIso); bindString(3, nowIso) }
        driver.execute(null,
            "INSERT INTO sessions (id, project_id, name, role, state, created_at, last_active_at, state_updated_at) " +
                "VALUES (?, ?, 'test', 'DRIVER', 'RUNNING', ?, ?, ?)", 5
        ) { bindString(0, sessionId); bindString(1, projectId); bindString(2, nowIso); bindString(3, nowIso); bindString(4, nowIso) }
        driver.execute(null,
            "INSERT INTO events (id, project_id, sequence, type, schema_version, category, visibility, " +
                "timestamp, source, payload, causal_depth) VALUES (?, ?, 0, 'UserMessage', 1, 'SIGNAL', 'SESSION', ?, 'user', '{}', 0)", 4
        ) { bindString(0, eventId); bindString(1, projectId); bindString(2, nowIso) }
        driver.execute(null,
            "INSERT INTO runs (id, session_id, project_id, trigger_event_id, started_at, state, " +
                "retry_policy_json, platform_profile, device_id) VALUES (?, ?, ?, ?, ?, ?, '{}', 'DESKTOP', 'dev-1')", 6
        ) { bindString(0, runId); bindString(1, sessionId); bindString(2, projectId); bindString(3, eventId); bindString(4, nowIso); bindString(5, runState) }
        driver.execute(null,
            "INSERT INTO tasks (id, run_id, session_id, project_id, ordinal, kind, description, state, retry_policy_json) " +
                "VALUES (?, ?, ?, ?, 0, 'MODEL_CALL', 'first', ?, '{}')", 5
        ) { bindString(0, firstTaskId); bindString(1, runId); bindString(2, sessionId); bindString(3, projectId); bindString(4, firstTaskState) }
    }

    private fun buildExecutor(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        runner: TaskRunner,
    ) = SqliteExecutor(
        driver = driver,
        audit = AuditLog(driver),
        events = EventStore(driver),
        idGen = { nextId() },
        nowIso = { nowIso },
        taskRunner = runner,
    )

    private fun runState(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, runId: String): String? =
        driver.executeQuery(null, "SELECT state FROM runs WHERE id = ?",
            mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 1
        ) { bindString(0, runId) }.value

    private fun taskRows(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, runId: String): List<Pair<Int, String>> {
        val rows = mutableListOf<Pair<Int, String>>()
        driver.executeQuery(null, "SELECT ordinal, state FROM tasks WHERE run_id = ? ORDER BY ordinal",
            mapper = { c ->
                while (c.next().value) rows.add((c.getLong(0)?.toInt() ?: 0) to c.getString(1)!!)
                app.cash.sqldelight.db.QueryResult.Value(Unit)
            }, 1
        ) { bindString(0, runId) }
        return rows
    }

    /** Appends a follow-on task exactly once (on the first task it sees), then stops. */
    private class OneShotAppendRunner : TaskRunner {
        private var appended = false
        override suspend fun execute(task: Task): TaskResult {
            if (!appended) {
                appended = true
                return TaskResult(success = true, appendTasks = listOf(
                    NewTaskSpec(id = "generated-follow-on", kind = TaskKind.MODEL_CALL, description = "next")
                ))
            }
            return TaskResult(success = true)
        }
    }

    @Test
    fun `a task that appends a follow-on task drives the chain to completion`() = runBlocking {
        val driver = openDriver()
        val pid = "p1"; val sid = "s1"; val eid = "e1"; val rid = "r1"; val t0 = "t0"
        seedRun(driver, pid, sid, eid, rid, t0)

        buildExecutor(driver, OneShotAppendRunner()).drive(RunId(rid))

        assertEquals("COMPLETED", runState(driver, rid))
        val rows = taskRows(driver, rid)
        assertEquals(listOf(0 to "COMPLETED", 1 to "COMPLETED"), rows, "the appended task ran at the next ordinal")
    }

    @Test
    fun `appended task ordinal is unique and sequential across repeated appends`() = runBlocking {
        val driver = openDriver()
        val pid = "p2"; val sid = "s2"; val eid = "e2"; val rid = "r2"; val t0 = "t0"
        seedRun(driver, pid, sid, eid, rid, t0)

        var count = 0
        val runner = object : TaskRunner {
            override suspend fun execute(task: Task): TaskResult {
                count++
                return if (count < 4) {
                    TaskResult(success = true, appendTasks = listOf(
                        NewTaskSpec(id = "gen-$count", kind = TaskKind.MODEL_CALL, description = "step $count")
                    ))
                } else TaskResult(success = true)
            }
        }
        buildExecutor(driver, runner).drive(RunId(rid))

        assertEquals("COMPLETED", runState(driver, rid))
        val rows = taskRows(driver, rid)
        assertEquals((0..3).map { it to "COMPLETED" }, rows)
    }

    /**
     * Crash-recovery boundary for the append primitive: the first task is RUNNING with no
     * follow-on task row (as if the process died before the append transaction ever started —
     * the same B3-shaped boundary `CrashRecoveryTest` already covers, extended to confirm the
     * *appending* task, not just a plain one, resumes correctly). `recover()` resets the orphan
     * RUNNING task to PENDING; a fresh `drive()` must re-execute it and still reach the appended
     * task, not get stuck believing the Run is already done.
     */
    @Test
    fun `crash while first task is RUNNING still reaches the appended task after recovery`() = runBlocking {
        val driver = openDriver()
        val pid = "p3"; val sid = "s3"; val eid = "e3"; val rid = "r3"; val t0 = "t0"
        seedRun(driver, pid, sid, eid, rid, t0, runState = "RUNNING", firstTaskState = "RUNNING")

        val executor = buildExecutor(driver, OneShotAppendRunner())
        executor.recover(ProjectId(pid))
        executor.drive(RunId(rid))

        assertEquals("COMPLETED", runState(driver, rid))
        val rows = taskRows(driver, rid)
        assertEquals(listOf(0 to "COMPLETED", 1 to "COMPLETED"), rows)
    }

    @Test
    fun `re-entrant drive after append does not re-run completed tasks`() = runBlocking {
        val driver = openDriver()
        val pid = "p4"; val sid = "s4"; val eid = "e4"; val rid = "r4"; val t0 = "t0"
        seedRun(driver, pid, sid, eid, rid, t0)

        var executions = 0
        val runner = object : TaskRunner {
            override suspend fun execute(task: Task): TaskResult {
                executions++
                return if (task.ordinal == 0) {
                    TaskResult(success = true, appendTasks = listOf(
                        NewTaskSpec(id = "gen-once", kind = TaskKind.MODEL_CALL, description = "next")
                    ))
                } else TaskResult(success = true)
            }
        }
        val executor = buildExecutor(driver, runner)
        executor.drive(RunId(rid))
        assertEquals(2, executions)

        executor.drive(RunId(rid))
        assertEquals(2, executions, "drive() on a COMPLETED run must not re-execute anything")
        assertTrue(runState(driver, rid) == "COMPLETED")
    }
}
