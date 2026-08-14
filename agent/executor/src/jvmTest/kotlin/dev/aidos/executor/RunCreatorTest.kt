package dev.aidos.executor

import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.EventId
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.SessionId
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * RunCreator: "how a Run comes to exist" — nothing in `executor` created `runs` rows before this
 * (`drive()` only steps an *existing* Run). Covers the AgentLoop↔executor bridge's first half.
 */
class RunCreatorTest {

    private val nowIso = "2026-08-09T00:00:00Z"

    private fun nextId() = UuidV7Generator().next()

    private fun openDriver() = run {
        val root = Files.createTempDirectory("run-creator-test").toFile()
        AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
            as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
    }

    private fun seedProjectAndSession(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        projectId: String,
        sessionId: String,
        triggerEventId: String,
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
        ) { bindString(0, triggerEventId); bindString(1, projectId); bindString(2, nowIso) }
    }

    private fun taskCount(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        runId: String,
    ): Long = driver.executeQuery(null, "SELECT COUNT(*) FROM tasks WHERE run_id = ?",
        mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) }, 1
    ) { bindString(0, runId) }.value

    @Test
    fun `creates a PENDING run with one MODEL_CALL task at ordinal 0`() {
        val driver = openDriver()
        val pid = nextId(); val sid = nextId(); val eid = nextId()
        seedProjectAndSession(driver, pid, sid, eid)

        val creator = RunCreator(driver, idGen = { nextId() }, nowIso = { nowIso })
        val runId = creator.createForUserMessage(
            sessionId = SessionId(sid),
            projectId = ProjectId(pid),
            triggerEventId = EventId(eid),
            userMessageSummary = "Hello, world",
            platformProfile = PlatformProfile.DESKTOP,
            deviceId = "dev-1",
            networkAvailable = false,
        )

        val state = driver.executeQuery(null, "SELECT state, user_message_summary FROM runs WHERE id = ?",
            mapper = { c ->
                app.cash.sqldelight.db.QueryResult.Value(
                    if (c.next().value) c.getString(0) to c.getString(1) else null
                )
            }, 1
        ) { bindString(0, runId.value) }.value
        assertEquals("PENDING" to "Hello, world", state)

        assertEquals(1L, taskCount(driver, runId.value), "Exactly one task at creation")

        val taskKind = driver.executeQuery(null, "SELECT kind, ordinal FROM tasks WHERE run_id = ?",
            mapper = { c ->
                app.cash.sqldelight.db.QueryResult.Value(
                    if (c.next().value) c.getString(0) to c.getLong(1) else null
                )
            }, 1
        ) { bindString(0, runId.value) }.value
        assertEquals("MODEL_CALL" to 0L, taskKind)
    }

    @Test
    fun `created run drives to completion with a task runner`() {
        val driver = openDriver()
        val pid = nextId(); val sid = nextId(); val eid = nextId()
        seedProjectAndSession(driver, pid, sid, eid)

        val creator = RunCreator(driver, idGen = { nextId() }, nowIso = { nowIso })
        val runId = creator.createForUserMessage(
            sessionId = SessionId(sid),
            projectId = ProjectId(pid),
            triggerEventId = EventId(eid),
            userMessageSummary = "Hello",
            platformProfile = PlatformProfile.DESKTOP,
            deviceId = "dev-1",
            networkAvailable = false,
        )
        assertNotNull(runId)

        val executor = SqliteExecutor(
            driver = driver,
            audit = dev.aidos.broker.AuditLog(driver),
            events = EventStore(driver),
            idGen = { nextId() },
            nowIso = { nowIso },
            taskRunner = object : TaskRunner {
                override suspend fun execute(task: dev.aidos.kernel.Task) = TaskResult(success = true)
            },
        )
        kotlinx.coroutines.runBlocking { executor.drive(runId) }

        val state = driver.executeQuery(null, "SELECT state FROM runs WHERE id = ?",
            mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 1
        ) { bindString(0, runId.value) }.value
        assertEquals("COMPLETED", state)
    }
}
