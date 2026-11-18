package dev.aidos.api

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `tasks` rows written by the persistent send path are project-database rows, so their ids must
 * not come from the process-local `nextId()` counter (regression: after a restart the counter
 * restarted at `aidos-1`, the same shape as the intent ids fixed in RFC-0110).
 */
class TaskIdPersistenceTest {

    private val home = Files.createTempDirectory("task-id-persistence-test").toFile()
    private val nowIso = { "2026-11-18T00:00:00Z" }

    @AfterTest
    fun cleanup() {
        home.deleteRecursively()
    }

    /** Captures the project driver most recently opened by this client. */
    private class Opened {
        var driver: SqlDriver? = null
    }

    private fun persistentClient(opened: Opened): RealRuntimeClient {
        val userDb = AidosStorage.openUser(DesktopPaths.userDb(home.path), "test", nowIso)
        return RealRuntimeClient().apply {
            userDriver = userDb.driver
            projectDbFactory = { projectRoot ->
                AidosStorage.openProject(DesktopPaths.stateDb(projectRoot), "test", nowIso).driver
                    .also { opened.driver = it }
            }
            projectLocker = JvmProjectLocker()
            runtimeManagedProjectsRoot = "${home.path}/.aidos/projects"
        }
    }

    private fun taskIds(driver: SqlDriver): List<String> =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT id FROM tasks ORDER BY id",
            mapper = { c ->
                val ids = mutableListOf<String>()
                while (c.next().value) ids += c.getString(0)!!
                QueryResult.Value(ids)
            },
            parameters = 0,
        ).value

    @Test
    fun `task rows written after a restart get fresh ids that do not use the process counter`() = runTest {
        val firstOpened = Opened()
        val first = persistentClient(firstOpened)
        val created = first.projects.create(
            CreateProjectRequest(
                name = "task-ids",
                description = "task id persistence",
                location = ProjectLocation.RuntimeManaged("task-ids"),
            ),
        )
        assertIs<ProjectResult.Success>(created)
        val projectId = created.project.id
        val session = first.sessions.create(CreateSessionRequest(projectId, "before-restart"))
        assertIs<SessionResult.Success>(session)
        assertIs<RunResult.Accepted>(first.sessions.send(session.session.id, UserMessage(content = "first")))
        first.projects.close(projectId)

        // A brand new instance over the same user db and project root: simulates a restart.
        val secondOpened = Opened()
        val second = persistentClient(secondOpened)
        assertIs<ProjectResult.Success>(second.projects.open(projectId))
        val afterRestart = second.sessions.create(CreateSessionRequest(projectId, "after-restart"))
        assertIs<SessionResult.Success>(afterRestart)
        assertIs<RunResult.Accepted>(second.sessions.send(afterRestart.session.id, UserMessage(content = "second")))

        val ids = taskIds(assertNotNullDriver(secondOpened.driver))
        assertEquals(2, ids.size)
        assertEquals(2, ids.toSet().size)
        assertTrue(ids.none { it.startsWith("aidos-") }, "task ids must not come from nextId(): $ids")
        assertFalse(ids.any { it.isBlank() })
    }

    private fun assertNotNullDriver(driver: SqlDriver?): SqlDriver =
        checkNotNull(driver) { "project driver was not opened" }
}
