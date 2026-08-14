package dev.aidos.api

import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RealRuntimeClient's storage/locking wiring (RFC-0010, RFC-0040, RFC-0055).
 *
 * Uses a real temp-directory SQLite backend throughout -- this is core commonMain logic
 * exercised via the JVM target, fully verifiable locally (unlike androidMain code, which is
 * CI-only in this sandbox).
 */
class RealRuntimeClientPersistenceTest {

    private val home = Files.createTempDirectory("real-runtime-client-test").toFile()
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

    @Test
    fun `create persists a project that a second client instance can see`() = runTest {
        val first = persistentClient()
        val created = first.projects.create(projectRequest("proj-a"))
        assertIs<ProjectResult.Success>(created)
        first.projects.close(created.project.id)

        // A brand new instance, simulating a runtime restart -- same backing storage.
        val second = persistentClient()
        val listed = second.projects.list()
        assertEquals(1, listed.size)
        assertEquals(created.project.id, listed.first().id)
        assertEquals("proj-a", listed.first().name)
    }

    @Test
    fun `open hydrates a project created by a previous instance`() = runTest {
        val first = persistentClient()
        val created = first.projects.create(projectRequest("proj-b"))
        assertIs<ProjectResult.Success>(created)
        first.projects.close(created.project.id)

        val second = persistentClient()
        val opened = second.projects.open(created.project.id)
        assertIs<ProjectResult.Success>(opened)
        assertEquals("proj-b", opened.project.name)
        assertEquals("desc for proj-b", opened.project.description)
    }

    @Test
    fun `open refuses a project locked by another instance`() = runTest {
        val first = persistentClient()
        val created = first.projects.create(projectRequest("proj-c"))
        assertIs<ProjectResult.Success>(created)
        // `first` still holds the lock (create() acquires it, close() wasn't called).

        val second = persistentClient()
        val result = second.projects.open(created.project.id)
        assertIs<ProjectResult.Error>(result)
        assertEquals("runtime.locked_by_other_instance", result.code)
    }

    @Test
    fun `close releases the lock so another instance can open`() = runTest {
        val first = persistentClient()
        val created = first.projects.create(projectRequest("proj-d"))
        assertIs<ProjectResult.Success>(created)
        first.projects.close(created.project.id)

        val second = persistentClient()
        val result = second.projects.open(created.project.id)
        assertIs<ProjectResult.Success>(result)
    }

    @Test
    fun `delete unregisters a project so it no longer appears in list`() = runTest {
        val client = persistentClient()
        val created = client.projects.create(projectRequest("proj-e"))
        assertIs<ProjectResult.Success>(created)

        client.projects.delete(created.project.id, confirm = true)

        assertTrue(client.projects.list().none { it.id == created.project.id })
        assertNull(client.projects.get(created.project.id))

        // The project's own directory/state.db is untouched (RFC-0010: archive, not destroy).
        val stateDb = java.io.File(DesktopPaths.stateDb("${home.path}/.aidos/projects/proj-e"))
        assertTrue(stateDb.exists())
    }

    @Test
    fun `delete without confirm is a no-op`() = runTest {
        val client = persistentClient()
        val created = client.projects.create(projectRequest("proj-f"))
        assertIs<ProjectResult.Success>(created)

        client.projects.delete(created.project.id, confirm = false)

        assertNotNull(client.projects.get(created.project.id))
    }

    @Test
    fun `without persistence wired, behavior is pure in-memory as before`() = runTest {
        val client = RealRuntimeClient()
        val created = client.projects.create(projectRequest("proj-g"))
        assertIs<ProjectResult.Success>(created)

        assertEquals(1, client.projects.list().size)

        // A second, unrelated instance has no way to see it -- there's no shared storage.
        val other = RealRuntimeClient()
        assertTrue(other.projects.list().isEmpty())
    }

    @Test
    fun `project ids are unique across instances sharing storage`() = runTest {
        val first = persistentClient()
        val a = first.projects.create(projectRequest("proj-h"))
        assertIs<ProjectResult.Success>(a)
        first.projects.close(a.project.id)

        val second = persistentClient()
        val b = second.projects.create(projectRequest("proj-i"))
        assertIs<ProjectResult.Success>(b)

        assertTrue(a.project.id != b.project.id)
    }
}
