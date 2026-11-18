package dev.aidos.api

import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentNodeType
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Intent node ids are written to the project database, so they must not restart from a
 * process-local counter (regression: `nextId()` restarted at `aidos-1` after every restart).
 */
class IntentIdPersistenceTest {

    private val home = Files.createTempDirectory("intent-id-persistence-test").toFile()
    private val nowIso = { "2026-11-18T00:00:00Z" }

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

    @Test
    fun `nodes created after a restart get fresh ids and can link to stored nodes`() = runTest {
        val first = persistentClient()
        val created = first.projects.create(
            CreateProjectRequest(
                name = "ids",
                description = "intent id persistence",
                location = ProjectLocation.RuntimeManaged("ids"),
            ),
        )
        assertIs<ProjectResult.Success>(created)
        val projectId = created.project.id

        val a = first.intents.createNode(
            CreateIntentNodeRequest(projectId = projectId, title = "Node A", type = IntentNodeType.ATOMIC_TASK),
        )
        val b = first.intents.createNode(
            CreateIntentNodeRequest(projectId = projectId, title = "Node B", type = IntentNodeType.ATOMIC_TASK),
        )
        first.intents.addEdge(projectId, b.id, a.id, IntentEdgeKind.DEPENDS_ON)
        first.projects.close(projectId)

        // A brand new instance over the same user db and project root: simulates a restart.
        val second = persistentClient()
        assertIs<ProjectResult.Success>(second.projects.open(projectId))

        val c = second.intents.createNode(
            CreateIntentNodeRequest(projectId = projectId, title = "Node C", type = IntentNodeType.ATOMIC_TASK),
        )
        second.intents.addEdge(projectId, c.id, a.id, IntentEdgeKind.DEPENDS_ON)

        assertEquals(3, setOf(a.id, b.id, c.id).size)
        assertEquals(3, second.intents.listNodes(projectId).size)
    }
}
