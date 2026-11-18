package dev.aidos.api

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.api.socket.Wire
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

// RFC-0124 Gates (UI-03): tool calls parked for a person's approval are listed from durable state.
class ParkedEffectsTest {

    private val home = Files.createTempDirectory("parked-effects-test").toFile()
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

    /** Writes one run whose task is parked on a TOOL_CALL continuation for [toolName]. */
    private fun SqlDriver.park(
        sessionId: String,
        projectId: String,
        runId: String,
        toolName: String,
        callId: String,
        argumentsJson: String,
        taint: String,
    ): String {
        val taskId = "task-$runId"
        execute(
            null,
            "INSERT INTO runs (id, session_id, project_id, trigger_event_id, started_at, state, retry_policy_json, " +
                "taint_level, platform_profile, device_id) VALUES (?, ?, ?, ?, ?, 'YIELDED', '{}', ?, 'DESKTOP', 'dev-1')", 6,
        ) {
            bindString(0, runId); bindString(1, sessionId); bindString(2, projectId)
            bindString(3, "event-$runId"); bindString(4, nowIso()); bindString(5, taint)
        }
        execute(
            null,
            "INSERT INTO tasks (id, run_id, session_id, project_id, ordinal, kind, description, state, retry_policy_json) " +
                "VALUES (?, ?, ?, ?, 0, 'TOOL_CALL', 'call', 'AWAITING_APPROVAL', '{}')", 4,
        ) { bindString(0, taskId); bindString(1, runId); bindString(2, sessionId); bindString(3, projectId) }
        execute(
            null,
            "INSERT INTO tool_calls (call_id, run_id, model_task_id, tool_task_id, tool_name, arguments_json, " +
                "schema_valid, outcome, step_index) VALUES (?, ?, ?, ?, ?, ?, 1, 'OK', 0)", 6,
        ) {
            bindString(0, callId); bindString(1, runId); bindString(2, taskId); bindString(3, taskId)
            bindString(4, toolName); bindString(5, argumentsJson)
        }
        execute(
            null,
            "INSERT INTO continuations (run_id, task_id, suspended_operation, operation_detail_json, correlation_id, created_at) " +
                "VALUES (?, ?, 'TOOL_CALL', ?, NULL, ?)", 4,
        ) {
            bindString(0, runId); bindString(1, taskId)
            bindString(2, """{"capabilityId":"c","toolName":"$toolName","callId":"$callId"}""")
            bindString(3, nowIso())
        }
        return taskId
    }

    @Test
    fun `a parked fs_write is listed with its run, task, tool and taint, and message send is not`() = runTest {
        val client = persistentClient()
        val project = client.projects.create(CreateProjectRequest("proj-parked", "d", ProjectLocation.RuntimeManaged("proj-parked")))
        assertIs<ProjectResult.Success>(project)
        val session = client.sessions.create(CreateSessionRequest(project.project.id, "s"))
        assertIs<SessionResult.Success>(session)

        val raw = AidosStorage.openProject(DesktopPaths.stateDb(project.project.projectPath), "test", nowIso).driver
        val taskId = raw.park(
            session.session.id, project.project.id, "run-fs", "fs_write", "call-fs",
            """{"path":"notes.txt","content":"hello"}""", "UNTRUSTED",
        )
        raw.park(
            session.session.id, project.project.id, "run-msg", "message:send", "call-msg",
            """{"channel":"sms","recipient":"Bob","payload":"hi"}""", "TRUSTED",
        )

        val parked = client.capabilities.listParkedEffects()
        assertEquals(1, parked.size, "message:send must stay in listParkedEgress, not here")
        val effect = parked.single()
        assertEquals("run-fs", effect.runId)
        assertEquals(taskId, effect.taskId)
        assertEquals(project.project.id, effect.projectId)
        assertEquals("fs_write", effect.toolName)
        assertEquals("UNTRUSTED", effect.runTaint)
        assertEquals("""{"path":"notes.txt","content":"hello"}""", effect.argumentsJson)
    }

    @Test
    fun `arguments are truncated to 4000 characters`() = runTest {
        val client = persistentClient()
        val project = client.projects.create(CreateProjectRequest("proj-long", "d", ProjectLocation.RuntimeManaged("proj-long")))
        assertIs<ProjectResult.Success>(project)
        val session = client.sessions.create(CreateSessionRequest(project.project.id, "s"))
        assertIs<SessionResult.Success>(session)

        val raw = AidosStorage.openProject(DesktopPaths.stateDb(project.project.projectPath), "test", nowIso).driver
        raw.park(
            session.session.id, project.project.id, "run-long", "fs_write", "call-long",
            "x".repeat(5_000), "PROJECT",
        )

        assertEquals(4_000, client.capabilities.listParkedEffects().single().argumentsJson.length)
    }

    @Test
    fun `wire round trip keeps the parked effect list`() {
        val list = listOf(
            ParkedEffectRequest("run-1", "task-1", "p1", "fs_write", """{"path":"a"}""", "PROJECT"),
            ParkedEffectRequest("run-2", "task-2", "p1", "shell_run", """{"cmd":"ls"}""", "UNTRUSTED"),
        )
        assertEquals(list, Wire.decodeParkedEffectList(Wire.encodeParkedEffectList(list)))
    }
}
