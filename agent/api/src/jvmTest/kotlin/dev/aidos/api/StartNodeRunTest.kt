package dev.aidos.api

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.api.socket.WireExtended
import dev.aidos.intent.IntentActorKind
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// RFC-0124 (UI-04): an agent run is started on an intent node through the normal sessions.send path.
// No RunExecutor is wired here, so sessions.send persists a PENDING `runs` row (see RealRuntimeClient).
class StartNodeRunTest {

    private val home = Files.createTempDirectory("start-node-run-test").toFile()
    private val nowIso = { "2026-08-09T00:00:00Z" }

    @AfterTest
    fun cleanup() {
        home.deleteRecursively()
    }

    private class Fixture(val client: RealRuntimeClient, val driver: SqlDriver, val projectId: String)

    private suspend fun fixture(): Fixture {
        var projectDriver: SqlDriver? = null
        val userDb = AidosStorage.openUser(DesktopPaths.userDb(home.path), "test", nowIso)
        val client = RealRuntimeClient().apply {
            userDriver = userDb.driver
            projectDbFactory = { projectRoot ->
                AidosStorage.openProject(DesktopPaths.stateDb(projectRoot), "test", nowIso).driver
                    .also { projectDriver = it }
            }
            projectLocker = JvmProjectLocker()
            runtimeManagedProjectsRoot = "${home.path}/.aidos/projects"
        }
        val created = client.projects.create(
            CreateProjectRequest(name = "start-node", description = "t", location = ProjectLocation.RuntimeManaged("start-node")),
        )
        assertIs<ProjectResult.Success>(created)
        return Fixture(client, assertNotNull(projectDriver), created.project.id)
    }

    private suspend fun Fixture.task(id: String, actor: IntentActorKind = IntentActorKind.AGENT) =
        client.intents.createNode(
            CreateIntentNodeRequest(
                projectId = projectId, id = id, title = "Task $id", type = IntentNodeType.ATOMIC_TASK,
                actorKind = actor, description = "do $id",
            ),
        )

    private fun SqlDriver.runSummary(runId: String): String? {
        var summary: String? = null
        executeQuery(
            null, "SELECT user_message_summary FROM runs WHERE id = ?",
            { c -> if (c.next().value) summary = c.getString(0); QueryResult.Value(Unit) }, 1,
        ) { bindString(0, runId) }
        return summary
    }

    @Test
    fun `agent task starts a run that is linked to the node`() = runTest {
        val f = fixture()
        f.task("fix")
        val criterion = f.client.intents.createNode(
            CreateIntentNodeRequest(
                projectId = f.projectId, id = "crit", title = "Tests pass", type = IntentNodeType.ACCEPTANCE_CRITERION,
            ),
        )
        f.client.intents.addEdge(f.projectId, "fix", criterion.id, IntentEdgeKind.ACCEPTS)

        val result = f.client.intents.startNodeRun(f.projectId, "fix")

        assertIs<StartNodeRunResult.Started>(result)
        val summary = assertNotNull(f.driver.runSummary(result.runId))
        assertTrue(summary.contains("Work on intent node fix: Task fix"), summary)
        assertTrue(summary.contains("- Tests pass"), summary)
        val status = assertNotNull(f.client.intents.getNodeStatus(f.projectId, "fix"))
        assertTrue(result.runId in status.activeRunIds)
    }

    @Test
    fun `human task is refused`() = runTest {
        val f = fixture()
        f.task("buy", IntentActorKind.HUMAN)

        val result = f.client.intents.startNodeRun(f.projectId, "buy")

        assertIs<StartNodeRunResult.Refused>(result)
        assertEquals("intent.not_startable", result.code)
    }

    @Test
    fun `task with an unmet dependency is refused`() = runTest {
        val f = fixture()
        f.task("first")
        f.task("second")
        f.client.intents.addEdge(f.projectId, "second", "first", IntentEdgeKind.DEPENDS_ON)

        val result = f.client.intents.startNodeRun(f.projectId, "second")

        assertIs<StartNodeRunResult.Refused>(result)
        assertEquals("intent.not_startable", result.code)
    }

    @Test
    fun `unknown project and the default implementation refuse`() = runTest {
        val f = fixture()
        assertIs<StartNodeRunResult.Refused>(f.client.intents.startNodeRun("nope", "x"))
        val bare = object : IntentCommands by f.client.intents {
            override suspend fun startNodeRun(projectId: String, nodeId: String, sessionId: String?) =
                StartNodeRunResult.Refused("intent.start_unsupported", "x")
        }
        assertEquals("intent.start_unsupported", bare.startNodeRun("p", "n").code)
    }

    @Test
    fun `wire round trip of both result kinds`() {
        val started = StartNodeRunResult.Started("run-1", "sess-1")
        val refused = StartNodeRunResult.Refused("intent.not_startable", "nope")
        assertEquals(started, WireExtended.decodeStartNodeRunResult(WireExtended.encodeStartNodeRunResult(started)))
        assertEquals(refused, WireExtended.decodeStartNodeRunResult(WireExtended.encodeStartNodeRunResult(refused)))
    }
}
