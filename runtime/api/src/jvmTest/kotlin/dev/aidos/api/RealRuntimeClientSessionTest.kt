package dev.aidos.api

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.PlatformProfile
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `sessions.create()`/`sessions.send()` real-storage wiring (RFC-0008, RFC-0009, RFC-0052).
 *
 * `RunExecutor` itself is composed in `daemon` (see `SqliteRunExecutorTest` there) — `api` cannot
 * depend on `executor` without a module cycle, so these tests exercise the dispatch seam with a
 * fake, not the real `RunCreator`/`SqliteExecutor` machinery.
 */
class RealRuntimeClientSessionTest {

    private val home = Files.createTempDirectory("real-runtime-client-session-test").toFile()
    private val nowIso = { kotlinx.datetime.Clock.System.now().toString() }

    @AfterTest
    fun cleanup() {
        home.deleteRecursively()
    }

    private fun persistentClient(): RealRuntimeClient {
        val userDb = AidosStorage.openUser(DesktopPaths.userDb(home.path), "test", nowIso)
        return RealRuntimeClient().apply {
            userDriver = userDb.driver
            projectDbFactory = { projectRoot ->
                AidosStorage.openProject(DesktopPaths.stateDb(projectRoot), "test", nowIso).driver
            }
            projectLocker = JvmProjectLocker()
            runtimeManagedProjectsRoot = "${home.path}/.aidos/projects"
        }
    }

    private fun projectRequest(name: String) =
        CreateProjectRequest(name, "desc for $name", ProjectLocation.RuntimeManaged(name))

    private fun sessionRow(driver: SqlDriver, sessionId: String): Pair<String, String>? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT name, role FROM sessions WHERE id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0)!! to c.getString(1)!! else null) },
            parameters = 1,
        ) { bindString(0, sessionId) }.value

    @Test
    fun `create persists a session row when the project driver is open`() = runTest {
        val client = persistentClient()
        val project = client.projects.create(projectRequest("proj-session-a"))
        assertIs<ProjectResult.Success>(project)

        val session = client.sessions.create(CreateSessionRequest(project.project.id, "driver-session"))
        assertIs<SessionResult.Success>(session)

        // Same backing file the client itself opened -- a fresh driver against it proves the row
        // is durable, not just held in the client's own in-memory cache.
        val raw = AidosStorage.openProject(
            DesktopPaths.stateDb(project.project.projectPath), "test", nowIso,
        ).driver
        assertEquals("driver-session" to "DRIVER", sessionRow(raw, session.session.id))
    }

    @Test
    fun `create without a persisted project stays in-memory only`() = runTest {
        val client = RealRuntimeClient()
        val session = client.sessions.create(CreateSessionRequest("unpersisted-project", "s"))
        assertIs<SessionResult.Success>(session)
        // No driver was ever opened for "unpersisted-project" -- nothing to assert against
        // except that create() didn't throw trying to write to a missing driver.
    }

    private class RecordingRunExecutor : RunExecutor {
        var calls = 0
        var lastSessionId: String? = null
        var lastMessage: UserMessage? = null

        override suspend fun send(
            projectDriver: SqlDriver,
            projectId: String,
            sessionId: String,
            message: UserMessage,
            platformProfile: PlatformProfile,
            deviceId: String,
            networkAvailable: Boolean,
        ): RunResult {
            calls++
            lastSessionId = sessionId
            lastMessage = message
            return RunResult.Accepted("real-run-1")
        }
    }

    @Test
    fun `send uses RunExecutor when both it and the project driver are wired`() = runTest {
        val client = persistentClient()
        val recorder = RecordingRunExecutor()
        client.runExecutor = recorder

        val project = client.projects.create(projectRequest("proj-session-b"))
        assertIs<ProjectResult.Success>(project)
        val session = client.sessions.create(CreateSessionRequest(project.project.id, "s"))
        assertIs<SessionResult.Success>(session)

        val result = client.sessions.send(session.session.id, UserMessage(content = "hello"))

        assertEquals(1, recorder.calls)
        assertEquals(session.session.id, recorder.lastSessionId)
        assertEquals("hello", recorder.lastMessage?.content)
        assertIs<RunResult.Accepted>(result)
        assertEquals("real-run-1", result.runId)
    }

    @Test
    fun `send falls back to the in-memory stub when RunExecutor is unset`() = runTest {
        val client = persistentClient()
        val project = client.projects.create(projectRequest("proj-session-c"))
        assertIs<ProjectResult.Success>(project)
        val session = client.sessions.create(CreateSessionRequest(project.project.id, "s"))
        assertIs<SessionResult.Success>(session)

        val result = client.sessions.send(session.session.id, UserMessage(content = "hello"))
        assertIs<RunResult.Accepted>(result)
    }

    @Test
    fun `send on an unknown session is an error, RunExecutor or not`() = runTest {
        val client = persistentClient()
        client.runExecutor = RecordingRunExecutor()
        val result = client.sessions.send("no-such-session", UserMessage(content = "hello"))
        assertIs<RunResult.Error>(result)
        assertEquals("session.not_found", result.code)
    }
}
