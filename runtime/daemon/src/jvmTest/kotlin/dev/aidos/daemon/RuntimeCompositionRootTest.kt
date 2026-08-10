package dev.aidos.daemon

import dev.aidos.executor.RunCreator
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.EventId
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RunId
import dev.aidos.kernel.SessionId
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [RuntimeCompositionRoot]: composes a real `CapabilityManager`/`ToolBroker` (with
 * `FilesystemTool`/`GitTool` registered)/`InferenceRouter`/`PromptAssembler`/`AgentLoopTaskRunner`
 * stack and drives a Run through it. `SqliteRunExecutorTest`'s own composition-root test covers
 * the `send()`-wired path; these tests exercise [RuntimeCompositionRoot.drive] directly.
 */
class RuntimeCompositionRootTest {

    private val nowIso = "2026-08-10T00:00:00Z"

    private fun nextId() = UuidV7Generator().next()

    private fun openDriver() = run {
        val root = Files.createTempDirectory("composition-root-test").toFile()
        AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
            as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
    }

    private fun seedProjectAndSession(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        projectId: String,
        sessionId: String,
        rootPath: String = "/",
    ) {
        driver.execute(null,
            "INSERT INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) " +
                "VALUES (?, 'test', ?, 'generic', ?, ?, ?)", 5
        ) { bindString(0, projectId); bindString(1, rootPath); bindString(2, nowIso); bindString(3, nowIso); bindString(4, nowIso) }
        driver.execute(null,
            "INSERT INTO sessions (id, project_id, name, role, state, created_at, last_active_at, state_updated_at) " +
                "VALUES (?, ?, 'test', 'DRIVER', 'RUNNING', ?, ?, ?)", 5
        ) { bindString(0, sessionId); bindString(1, projectId); bindString(2, nowIso); bindString(3, nowIso); bindString(4, nowIso) }
    }

    private fun createRun(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        pid: String,
        sid: String,
    ): RunId {
        val eid = nextId()
        driver.execute(null,
            "INSERT INTO events (id, project_id, sequence, type, schema_version, category, visibility, " +
                "timestamp, source, payload, causal_depth) VALUES (?, ?, 0, 'UserMessage', 1, 'SIGNAL', 'SESSION', ?, 'user', '{}', 0)", 4
        ) { bindString(0, eid); bindString(1, pid); bindString(2, nowIso) }
        return RunCreator(driver, idGen = { nextId() }, nowIso = { nowIso }).createForUserMessage(
            sessionId = SessionId(sid), projectId = ProjectId(pid), triggerEventId = EventId(eid),
            userMessageSummary = "hello", platformProfile = PlatformProfile.DESKTOP,
            deviceId = "dev-1", networkAvailable = false,
        )
    }

    private fun runState(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        runId: String,
    ): String? = driver.executeQuery(null, "SELECT state FROM runs WHERE id = ?",
        mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 1
    ) { bindString(0, runId) }.value

    @Test
    fun `drives a MODEL_CALL to a clean failure when no model adapter is configured`() = runTest {
        val driver = openDriver()
        val pid = nextId(); val sid = nextId()
        seedProjectAndSession(driver, pid, sid)
        val runId = createRun(driver, pid, sid)

        RuntimeCompositionRoot(anthropicApiKey = { null }).drive(
            projectDriver = driver, runId = runId, projectId = ProjectId(pid), sessionId = sid,
            deviceId = "dev-1", platformProfile = PlatformProfile.DESKTOP, networkAvailable = false,
            idGen = { nextId() }, nowIso = { nowIso },
        )

        assertEquals("FAILED", runState(driver, runId.value))
    }

    @Test
    fun `no-ops when the project's root_path row cannot be found`() = runTest {
        val driver = openDriver()
        val pid = nextId(); val sid = nextId()
        seedProjectAndSession(driver, pid, sid)
        val runId = createRun(driver, pid, sid)

        // A projectId that has no `projects` row at all -- projectRootPath() returns null. This
        // can't happen for a real send()/wake() call (the Run's own projectId always has a row,
        // by the same foreign-key constraint that makes seeding one required above), but D3 says
        // the honest response to an inconsistency like this is "do nothing", not a crash mid-send.
        RuntimeCompositionRoot(anthropicApiKey = { null }).drive(
            projectDriver = driver, runId = runId, projectId = ProjectId("no-such-project"), sessionId = sid,
            deviceId = "dev-1", platformProfile = PlatformProfile.DESKTOP, networkAvailable = false,
            idGen = { nextId() }, nowIso = { nowIso },
        )

        assertEquals("PENDING", runState(driver, runId.value), "nothing lost (D3) -- left exactly as RunCreator made it")
    }
}
