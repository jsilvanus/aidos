package dev.aidos.api

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.api.socket.WireExtended
import dev.aidos.intent.IntentActorKind
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

// RFC-0124 (UI-50): `present_ui` calls persisted by the executor come back newest first, filtered
// by node, carrying the run's taint. Rows are inserted directly -- the executor side has its own test.
class ListUiManifestsTest {

    private val home = Files.createTempDirectory("list-ui-manifests-test").toFile()
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
            CreateProjectRequest(name = "ui-manifests", description = "t", location = ProjectLocation.RuntimeManaged("ui-manifests")),
        )
        assertIs<ProjectResult.Success>(created)
        return Fixture(client, assertNotNull(projectDriver), created.project.id)
    }

    private fun SqlDriver.insertCall(callId: String, runId: String, tool: String, args: String, outcome: String, step: Int) {
        execute(null, "PRAGMA foreign_keys = OFF", 0)
        execute(
            null,
            "INSERT INTO tool_calls (call_id, run_id, model_task_id, tool_name, arguments_json, schema_valid, outcome, step_index) " +
                "VALUES (?, ?, 'm', ?, ?, 1, ?, ?)",
            6,
        ) {
            bindString(0, callId); bindString(1, runId); bindString(2, tool)
            bindString(3, args); bindString(4, outcome); bindLong(5, step.toLong())
        }
    }

    private fun args(node: String, id: String) =
        """{"targetNodeId":"$node","manifest":{"${'$'}schema":"https://aidos.dev/schemas/a2ui-v1.json","manifestId":"$id","root":{"type":"text"}}}"""

    @Test
    fun `persisted present_ui calls come back newest first filtered by node with the run taint`() = runTest {
        val f = fixture()
        for (id in listOf("a", "b")) {
            f.client.intents.createNode(
                CreateIntentNodeRequest(projectId = f.projectId, id = id, title = id, type = IntentNodeType.ATOMIC_TASK, actorKind = IntentActorKind.AGENT),
            )
        }
        val run1 = (f.client.intents.startNodeRun(f.projectId, "a") as StartNodeRunResult.Started).runId
        f.driver.insertCall("c1", run1, "present_ui", args("a", "m1"), "OK", 1)
        f.driver.insertCall("c2", run1, "present_ui", args("b", "m2"), "OK", 2)
        f.driver.insertCall("c3", run1, "present_ui", args("a", "m3"), "OK", 3)
        f.driver.insertCall("c4", run1, "present_ui", args("a", "m4"), "FAILED", 4)
        f.driver.insertCall("c5", run1, "ask_user", """{"question":"q"}""", "OK", 5)

        val all = f.client.intents.listUiManifests(f.projectId)
        assertEquals(listOf("c3", "c2", "c1"), all.map { it.callId })
        assertEquals(setOf(run1), all.map { it.runId }.toSet())
        assertEquals("TRUSTED", all.first().runTaint)

        val forA = f.client.intents.listUiManifests(f.projectId, targetNodeId = "a")
        assertEquals(listOf("c3", "c1"), forA.map { it.callId })
        assertEquals(true, forA.first().manifestJson.contains("\"manifestId\":\"m3\""))

        assertEquals(listOf("c3"), f.client.intents.listUiManifests(f.projectId, limit = 1).map { it.callId })
        assertEquals(emptyList(), f.client.intents.listUiManifests("nope"))
    }

    @Test
    fun `wire round trip`() {
        val list = listOf(
            UiManifestRecord("c1", "r1", "p", "n", """{"manifestId":"m"}""", "UNTRUSTED", "2026-08-09T00:00:00Z"),
            UiManifestRecord("c2", "r1", "p", "n", "{}", "TRUSTED", null),
        )
        assertEquals(list, WireExtended.decodeUiManifestList(WireExtended.encodeUiManifestList(list)))
    }
}
