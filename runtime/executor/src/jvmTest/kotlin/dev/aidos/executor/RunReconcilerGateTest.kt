package dev.aidos.executor

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
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
import kotlin.test.assertTrue

/**
 * M13 (RFC-0053): `SqliteExecutor.drive()`'s own half of the before-a-Run-starts gate — that it
 * actually calls a [RunReconciler] and honors its verdict. The reconciler's own real logic
 * (fingerprint comparison, content-node reconciliation, `reconciliations` row) is JGit/SQL-heavy
 * and lives in `daemon`'s `GitRunReconciler`/`GitRunReconcilerTest`, which `executor`'s
 * `commonMain` cannot depend on; this test doubles [RunReconciler] to isolate `drive()`'s own
 * contract with it.
 */
class RunReconcilerGateTest {

    private val nowIso = "2026-08-10T00:00:00Z"
    private fun nextId() = UuidV7Generator().next()

    private fun openDriver() = run {
        val root = Files.createTempDirectory("run-reconciler-gate-test").toFile()
        AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
            as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
    }

    private fun seedProjectAndRun(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        projectId: String, sessionId: String, eventId: String, runId: String,
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
                "timestamp, source, payload, causal_depth) VALUES (?, ?, 0, 'UserMessage', 1, 'SIGNAL', 'SESSION', ?, 'user', '{}', 0)", 3
        ) { bindString(0, eventId); bindString(1, projectId); bindString(2, nowIso) }
        driver.execute(null,
            "INSERT INTO runs (id, session_id, project_id, trigger_event_id, started_at, state, " +
                "retry_policy_json, platform_profile, device_id) VALUES (?, ?, ?, ?, ?, 'PENDING', '{}', 'DESKTOP', 'device-1')", 5
        ) { bindString(0, runId); bindString(1, sessionId); bindString(2, projectId); bindString(3, eventId); bindString(4, nowIso) }
    }

    private fun runState(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, runId: String): String? =
        driver.executeQuery(null, "SELECT state FROM runs WHERE id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 1
        ) { bindString(0, runId) }.value

    private class RecordingReconciler(private val toTerminate: Set<String>) : RunReconciler {
        var callCount = 0
        override suspend fun reconcileBeforeRun(driver: SqlDriver, projectId: ProjectId, runId: RunId): Set<RunId> {
            callCount++
            return toTerminate.map { RunId(it) }.toSet()
        }
    }

    private class AlwaysSucceedRunner : TaskRunner {
        override suspend fun execute(task: dev.aidos.kernel.Task) = TaskResult(success = true)
    }

    @Test
    fun `a reconciler that finds no mismatch does not block the Run`() = runBlocking {
        val driver = openDriver()
        val pid = nextId(); val sid = nextId(); val eid = nextId(); val runId = nextId()
        seedProjectAndRun(driver, pid, sid, eid, runId)

        val reconciler = RecordingReconciler(toTerminate = emptySet())
        val executor = SqliteExecutor(
            driver = driver, audit = AuditLog(driver), events = EventStore(driver),
            idGen = { nextId() }, nowIso = { nowIso }, taskRunner = AlwaysSucceedRunner(),
            reconciler = reconciler,
        )

        executor.drive(RunId(runId))

        assertEquals(1, reconciler.callCount, "the gate must consult the reconciler before starting")
        // No tasks were ever created for this Run, so with no mismatch it just falls through to
        // "no runnable tasks" and completes -- the point under test is that it was NOT blocked.
        assertEquals("COMPLETED", runState(driver, runId))
    }

    @Test
    fun `a reconciler that names this Run terminates it without running any tasks`() = runBlocking {
        val driver = openDriver()
        val pid = nextId(); val sid = nextId(); val eid = nextId(); val runId = nextId()
        seedProjectAndRun(driver, pid, sid, eid, runId)

        val reconciler = RecordingReconciler(toTerminate = setOf(runId))
        val executor = SqliteExecutor(
            driver = driver, audit = AuditLog(driver), events = EventStore(driver),
            idGen = { nextId() }, nowIso = { nowIso }, taskRunner = AlwaysSucceedRunner(),
            reconciler = reconciler,
        )

        executor.drive(RunId(runId))

        assertEquals(1, reconciler.callCount)
        // drive() must not have advanced the Run to RUNNING/COMPLETED itself -- a reconciler that
        // names this Run is expected to have already written its own terminal state (that's
        // GitRunReconciler's job, verified separately); the gate's contract is only "don't proceed".
        assertTrue(runState(driver, runId) != "RUNNING" && runState(driver, runId) != "COMPLETED")
    }

    @Test
    fun `an unset reconciler preserves pre-reconciliation behavior`() = runBlocking {
        val driver = openDriver()
        val pid = nextId(); val sid = nextId(); val eid = nextId(); val runId = nextId()
        seedProjectAndRun(driver, pid, sid, eid, runId)

        val executor = SqliteExecutor(
            driver = driver, audit = AuditLog(driver), events = EventStore(driver),
            idGen = { nextId() }, nowIso = { nowIso }, taskRunner = AlwaysSucceedRunner(),
        )

        executor.drive(RunId(runId))

        assertEquals("COMPLETED", runState(driver, runId))
    }
}
