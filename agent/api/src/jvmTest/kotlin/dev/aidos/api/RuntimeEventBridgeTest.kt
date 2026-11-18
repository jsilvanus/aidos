package dev.aidos.api

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.api.socket.Wire
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

// RFC-0004 / RFC-0052 (UI-70): executor rows reach RuntimeClient.events subscribers.
class RuntimeEventBridgeTest {

    private val home = Files.createTempDirectory("event-bridge-test").toFile()
    private val nowIso = { Clock.System.now().toString() }

    @AfterTest
    fun cleanup() {
        home.deleteRecursively()
    }

    private fun client() = RealRuntimeClient().apply {
        liveEventBridgeEnabled = true
        userDriver = AidosStorage.openUser(DesktopPaths.userDb(home.path), "test", nowIso).driver
        projectDbFactory = { root -> AidosStorage.openProject(DesktopPaths.stateDb(root), "test", nowIso).driver }
        projectLocker = JvmProjectLocker()
        runtimeManagedProjectsRoot = "${home.path}/.aidos/projects"
    }

    /** A second connection to the project database: what another process running the executor looks like. */
    private fun otherProcess(projectRoot: String): SqlDriver =
        AidosStorage.openProject(DesktopPaths.stateDb(projectRoot), "test", nowIso).driver

    private fun SqlDriver.run(runId: String, sessionId: String, projectId: String, state: String) {
        execute(
            null,
            "INSERT INTO runs (id, session_id, project_id, trigger_event_id, started_at, state, retry_policy_json, " +
                "platform_profile, device_id) VALUES (?, ?, ?, 'trigger', ?, ?, '{}', 'DESKTOP', 'dev-1')", 5,
        ) { bindString(0, runId); bindString(1, sessionId); bindString(2, projectId); bindString(3, nowIso()); bindString(4, state) }
    }

    private fun SqlDriver.task(id: String, runId: String, sessionId: String, projectId: String, kind: String, tool: String?, state: String, ordinal: Int = id.hashCode().and(0xffff)) {
        execute(
            null,
            "INSERT INTO tasks (id, run_id, session_id, project_id, ordinal, kind, description, tool_name, state, retry_policy_json) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'd', ?, ?, '{}')", 8,
        ) {
            bindString(0, id); bindString(1, runId); bindString(2, sessionId); bindString(3, projectId)
            bindLong(4, ordinal.toLong()); bindString(5, kind); bindString(6, tool); bindString(7, state)
        }
    }

    /**
     * Writes a FACT row the way the executor's EventStore does (`:api` cannot depend on `:executor`:
     * the Engine build includes `:api` without it). The executor-side test in
     * CapabilityResolutionEndToEndTest pins the payload shape this relies on.
     */
    private class EventStore(private val driver: SqlDriver) {
        private var counter = 0
        fun fact(projectId: String, type: String, vararg fields: Pair<String, String>) {
            val payload = fields.joinToString(",", "{", "}") { "\"${it.first}\":\"${it.second}\"" }
            driver.execute(
                null,
                "INSERT INTO events (id, project_id, sequence, type, schema_version, category, visibility, timestamp, " +
                    "source, payload, causal_depth) VALUES (?, ?, (SELECT COALESCE(MAX(sequence), 0) + 1 FROM events " +
                    "WHERE project_id = ?), ?, 1, 'FACT', 'PUBLIC', ?, 'executor', ?, 0)", 6,
            ) {
                bindString(0, "ev-${type}-${counter++}-${payload.hashCode()}"); bindString(1, projectId)
                bindString(2, projectId); bindString(3, type)
                bindString(4, Clock.System.now().toString()); bindString(5, payload)
            }
        }
    }

    private suspend fun setup(): Triple<RealRuntimeClient, String, Pair<String, String>> {
        val client = client()
        val project = client.projects.create(CreateProjectRequest("proj-b", "d", ProjectLocation.RuntimeManaged("proj-b")))
        assertIs<ProjectResult.Success>(project)
        val session = client.sessions.create(CreateSessionRequest(project.project.id, "s"))
        assertIs<SessionResult.Success>(session)
        return Triple(client, project.project.projectPath, project.project.id to session.session.id)
    }

    @Test
    fun `executor rows arrive in order, once, and unknown rows are skipped`() = runTest {
        val (client, root, ids) = setup()
        val (projectId, sessionId) = ids
        val raw = otherProcess(root)
        val events = EventStore(raw)
        val received = client.events.subscribe(EventFilter(projectIds = listOf(projectId)))

        raw.run("r1", sessionId, projectId, "RUNNING")
        raw.task("t-model", "r1", sessionId, projectId, "MODEL_CALL", null, "COMPLETED")
        raw.execute(
            null,
            "INSERT INTO audit_log (id, project_id, sequence, occurred_at, kind, actor_kind, actor_id, device_id) " +
                "VALUES ('au', ?, 1, ?, 'ToolInvoked', 'RUNTIME', 'x', 'dev-1')", 2,
        ) { bindString(0, projectId); bindString(1, nowIso()) }
        raw.execute(
            null,
            "INSERT INTO attempts (id, task_id, attempt_number, started_at, state, output_snapshot, audit_ref) " +
                "VALUES ('a1', 't-model', 1, ?, 'COMPLETED', '{\"text\":\"Reading the notes\",\"toolCalls\":[]}', 'au')", 1,
        ) { bindString(0, nowIso()) }
        raw.task("t-tool", "r1", sessionId, projectId, "TOOL_CALL", "present_ui", "COMPLETED")
        raw.execute(
            null,
            "INSERT INTO tool_calls (call_id, run_id, model_task_id, tool_task_id, tool_name, arguments_json, schema_valid, outcome, step_index) " +
                "VALUES ('call-1', 'r1', 't-model', 't-tool', 'present_ui', '{\"secret\":1}', 1, 'OK', 1)", 0,
        ) {}
        raw.task("t-park", "r1", sessionId, projectId, "TOOL_CALL", "fs_write", "AWAITING_APPROVAL")

        events.fact(projectId, "RunStepCompleted", "run_id" to "r1", "task_id" to "t-model", "state" to "COMPLETED")
        events.fact(projectId, "SessionWoken") // unknown to the bridge: skipped
        events.fact(projectId, "ToolCompleted", "run_id" to "r1", "task_id" to "t-tool", "operation" to "present_ui")
        events.fact(projectId, "RunStepCompleted", "run_id" to "r1", "task_id" to "t-park", "state" to "AWAITING_APPROVAL")

        client.pollEventBridgeOnce(projectId)
        client.pollEventBridgeOnce(projectId) // a second poll must not repeat anything

        raw.execute(null, "UPDATE runs SET state = 'COMPLETED' WHERE id = 'r1'", 0) {}
        client.pollEventBridgeOnce(projectId)

        val got = withTimeout(5_000) { received.filter { it !is RuntimeEvent.SessionCreated }.take(7).toList() }
        assertEquals(
            listOf(
                "RunStarted", "RunStepCompleted", "AiResponseDelta", "ToolCallCompleted",
                "RunStepCompleted", "ToolApprovalRequired", "RunCompleted",
            ),
            got.map { it::class.simpleName },
        )
        assertEquals("Reading the notes", assertIs<RuntimeEvent.AiResponseDelta>(got[2]).delta)
        val tool = assertIs<RuntimeEvent.ToolCallCompleted>(got[3])
        assertEquals("present_ui", tool.toolName)
        assertEquals("call-1", tool.callId)
        assertEquals("OK", tool.outcome)
        val approval = assertIs<RuntimeEvent.ToolApprovalRequired>(got[5])
        assertEquals("fs_write", approval.toolName)
        assertEquals("t-park", approval.taskId)
        assertEquals(false, approval.previewDescription.contains("secret"), "arguments are never read")
    }

    @Test
    fun `rows written before the client opened the project are not replayed`() = runTest {
        val (client, root, ids) = setup()
        val (projectId, sessionId) = ids
        val raw = otherProcess(root)
        raw.run("old", sessionId, projectId, "COMPLETED")
        raw.task("t-old", "old", sessionId, projectId, "MODEL_CALL", null, "COMPLETED")
        EventStore(raw).fact(projectId, "RunStepCompleted", "run_id" to "old", "task_id" to "t-old", "state" to "COMPLETED")

        // A second client opening the same database starts at the current end of the table.
        val later = client()
        later.projects.list()
        later.projects.get(projectId)
        later.pollEventBridgeOnce(projectId)
        val seen = later.events.subscribe(EventFilter(projectIds = listOf(projectId), types = listOf(RuntimeEventType.RUN_STEP_COMPLETED)))

        raw.run("new", sessionId, projectId, "RUNNING")
        raw.task("t-new", "new", sessionId, projectId, "MODEL_CALL", null, "COMPLETED")
        EventStore(raw).fact(projectId, "RunStepCompleted", "run_id" to "new", "task_id" to "t-new", "state" to "COMPLETED")
        later.pollEventBridgeOnce(projectId)

        val step = assertIs<RuntimeEvent.RunStepCompleted>(withTimeout(5_000) { seen.take(1).toList() }.single())
        assertEquals("new", step.runId)
        client.shutdown()
        later.shutdown()
    }

    @Test
    fun `a failed run is reported with its error code`() = runTest {
        val (client, root, ids) = setup()
        val (projectId, sessionId) = ids
        val raw = otherProcess(root)
        val failed = client.events.subscribe(EventFilter(projectIds = listOf(projectId), types = listOf(RuntimeEventType.RUN_FAILED)))
        raw.run("rf", sessionId, projectId, "RUNNING")
        raw.task("t-f", "rf", sessionId, projectId, "MODEL_CALL", null, "FAILED")
        EventStore(raw).fact(projectId, "RunStepCompleted", "run_id" to "rf", "task_id" to "t-f", "state" to "FAILED")
        client.pollEventBridgeOnce(projectId)
        raw.execute(null, "UPDATE runs SET state = 'FAILED', error_code = 'model.unavailable' WHERE id = 'rf'", 0) {}
        client.pollEventBridgeOnce(projectId)

        val event = assertIs<RuntimeEvent.RunFailed>(withTimeout(5_000) { failed.take(1).toList() }.single())
        assertEquals("model.unavailable", event.errorCode)
        client.shutdown()
    }

    @Test
    fun `wire round trip keeps ToolCallCompleted`() {
        val original = RuntimeEvent.ToolCallCompleted(
            eventId = "e1", timestamp = Clock.System.now(), projectId = "p1", sessionId = "s1",
            runId = "r1", callId = "c1", toolName = "present_ui", outcome = "OK",
        )
        assertEquals(original, Wire.decodeRuntimeEvent(Wire.encodeRuntimeEvent(original)))
    }
}
