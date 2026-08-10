package dev.aidos.daemon

import dev.aidos.api.UserMessage
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.PlatformProfile
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * [SqliteRunExecutor]: the real [dev.aidos.api.RunExecutor] `sessions.send()` calls once storage
 * is wired. Publishes the trigger event, then creates the durable Run via `RunCreator` (RFC-0008,
 * RFC-0009) — but does not drive it (see the class's own doc comment for why).
 */
class SqliteRunExecutorTest {

    private val nowIso = "2026-08-10T00:00:00Z"

    private fun nextId() = UuidV7Generator().next()

    private fun openDriver() = run {
        val root = Files.createTempDirectory("run-executor-test").toFile()
        AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
            as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
    }

    private fun seedProjectAndSession(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        projectId: String,
        sessionId: String,
    ) {
        driver.execute(null,
            "INSERT INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) " +
                "VALUES (?, 'test', '/', 'generic', ?, ?, ?)", 4
        ) { bindString(0, projectId); bindString(1, nowIso); bindString(2, nowIso); bindString(3, nowIso) }
        driver.execute(null,
            "INSERT INTO sessions (id, project_id, name, role, state, created_at, last_active_at, state_updated_at) " +
                "VALUES (?, ?, 'test', 'DRIVER', 'CREATED', ?, ?, ?)", 5
        ) { bindString(0, sessionId); bindString(1, projectId); bindString(2, nowIso); bindString(3, nowIso); bindString(4, nowIso) }
    }

    @Test
    fun `send publishes the trigger event and creates a durable PENDING run`() = runTest {
        val driver = openDriver()
        val pid = nextId(); val sid = nextId()
        seedProjectAndSession(driver, pid, sid)

        val executor = SqliteRunExecutor(idGen = { nextId() }, nowIso = { nowIso })
        val result = executor.send(
            projectDriver = driver,
            projectId = pid,
            sessionId = sid,
            message = UserMessage(content = "Hello, world"),
            platformProfile = PlatformProfile.DESKTOP,
            deviceId = "dev-1",
            networkAvailable = false,
        )

        assertIs<dev.aidos.api.RunResult.Accepted>(result)

        val runRow = driver.executeQuery(null,
            "SELECT state, user_message_summary FROM runs WHERE id = ?",
            mapper = { c ->
                app.cash.sqldelight.db.QueryResult.Value(
                    if (c.next().value) c.getString(0) to c.getString(1) else null
                )
            }, 1
        ) { bindString(0, result.runId) }.value
        assertEquals("PENDING" to "Hello, world", runRow)

        val eventRow = driver.executeQuery(null,
            "SELECT type FROM events WHERE project_id = ? AND type = 'UserCommand'",
            mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 1
        ) { bindString(0, pid) }.value
        assertEquals("UserCommand", eventRow)

        val taskRow = driver.executeQuery(null,
            "SELECT kind, ordinal FROM tasks WHERE run_id = ?",
            mapper = { c ->
                app.cash.sqldelight.db.QueryResult.Value(
                    if (c.next().value) c.getString(0) to c.getLong(1) else null
                )
            }, 1
        ) { bindString(0, result.runId) }.value
        assertEquals("MODEL_CALL" to 0L, taskRow)
    }

    @Test
    fun `send wakes another session subscribed to UserCommand, without double-running the sender`() = runTest {
        val driver = openDriver()
        val pid = nextId(); val senderId = nextId(); val watcherId = nextId()
        seedProjectAndSession(driver, pid, senderId)
        driver.execute(null,
            "INSERT INTO sessions (id, project_id, name, role, state, created_at, last_active_at, state_updated_at) " +
                "VALUES (?, ?, 'watcher', 'DRIVER', 'SLEEPING', ?, ?, ?)", 5
        ) { bindString(0, watcherId); bindString(1, pid); bindString(2, nowIso); bindString(3, nowIso); bindString(4, nowIso) }
        dev.aidos.executor.SessionSubscriptionStore(driver).subscribe(
            id = nextId(), sessionId = watcherId, topicPatterns = listOf("*"),
            eventTypes = listOf("UserCommand"), nowIso = nowIso,
        )

        val executor = SqliteRunExecutor(idGen = { nextId() }, nowIso = { nowIso })
        val result = executor.send(
            projectDriver = driver,
            projectId = pid,
            sessionId = senderId,
            message = UserMessage(content = "Hello, world"),
            platformProfile = PlatformProfile.DESKTOP,
            deviceId = "dev-1",
            networkAvailable = false,
        )
        assertIs<dev.aidos.api.RunResult.Accepted>(result)

        val watcherState = driver.executeQuery(null, "SELECT state FROM sessions WHERE id = ?",
            mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 1
        ) { bindString(0, watcherId) }.value
        assertEquals("RUNNING", watcherState)

        val runCount = driver.executeQuery(null, "SELECT COUNT(*) FROM runs",
            mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) }, 0
        ) {}.value
        assertEquals(2L, runCount, "sender's own run plus the watcher's woken run")
    }
}
