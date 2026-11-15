package dev.aidos.daemon

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aidos.api.CapabilityResult
import dev.aidos.api.CreateProjectRequest
import dev.aidos.api.CreateSessionRequest
import dev.aidos.api.GrantCapabilityRequest
import dev.aidos.api.ProjectLocation
import dev.aidos.api.ProjectResult
import dev.aidos.api.RealRuntimeClient
import dev.aidos.api.SessionResult
import dev.aidos.bridge.ParkedMessageStatus
import dev.aidos.capability.SqliteCapabilityManager
import dev.aidos.executor.RunCreator
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.EventId
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RunId
import dev.aidos.kernel.SessionId
import dev.aidos.settings.EgressPolicy
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RFC-0113 as the daemon runs it: the composition root registers the bridge for every project it
 * drives, and a person's decision on a parked `message:send` is what issues, or withholds, the
 * single-use grant. (The tools themselves are covered end to end in `:bridge`.)
 */
class BridgeDaemonWiringTest {
    private val nowIso = "2026-11-15T00:00:00Z"
    private fun nextId() = UuidV7Generator().next()

    private val callId = "call-msg-1"
    private val messageArgs = buildJsonObject {
        put("channel", JsonPrimitive("sms"))
        put("recipient", JsonPrimitive("Alice (+358 40 123 4567)"))
        put("payload", JsonPrimitive("I can't make it at 7, but 8:30 works."))
    }

    private class Env(val driver: JdbcSqliteDriver, val projectId: String, val sessionId: String, val runId: RunId, val taskId: String)

    /** A personal project whose Run is parked on a `message:send` TOOL_CALL continuation, as the agent loop leaves it. */
    private fun parkedPersonalProject(): Env {
        val root = Files.createTempDirectory("bridge-daemon").toFile()
        val driver = AidosStorage.openProject(DesktopPaths.stateDb(root.path), "t") { nowIso }.driver as JdbcSqliteDriver
        val pid = nextId(); val sid = nextId()
        driver.execute(
            null,
            "INSERT INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) " +
                "VALUES (?, 'life', ?, 'personal', ?, ?, ?)", 5,
        ) { bindString(0, pid); bindString(1, root.path); bindString(2, nowIso); bindString(3, nowIso); bindString(4, nowIso) }
        driver.execute(
            null,
            "INSERT INTO sessions (id, project_id, name, role, state, created_at, last_active_at, state_updated_at) " +
                "VALUES (?, ?, 's', 'DRIVER', 'RUNNING', ?, ?, ?)", 5,
        ) { bindString(0, sid); bindString(1, pid); bindString(2, nowIso); bindString(3, nowIso); bindString(4, nowIso) }
        val eid = nextId()
        driver.execute(
            null,
            "INSERT INTO events (id, project_id, sequence, type, schema_version, category, visibility, timestamp, source, payload, causal_depth) " +
                "VALUES (?, ?, 0, 'UserMessage', 1, 'SIGNAL', 'SESSION', ?, 'user', '{}', 0)", 3,
        ) { bindString(0, eid); bindString(1, pid); bindString(2, nowIso) }
        val runId = RunCreator(driver, idGen = { nextId() }, nowIso = { nowIso }).createForUserMessage(
            sessionId = SessionId(sid), projectId = ProjectId(pid), triggerEventId = EventId(eid),
            userMessageSummary = "reply to Alice", platformProfile = PlatformProfile.DESKTOP, deviceId = "dev-1", networkAvailable = false,
        )
        val taskId = driver.executeQuery(
            null, "SELECT id FROM tasks WHERE run_id = ?",
            { c -> QueryResult.Value(if (c.next().value) c.getString(0)!! else error("no task")) }, 1,
        ) { bindString(0, runId.value) }.value
        driver.execute(
            null,
            "INSERT INTO tool_calls (call_id, run_id, model_task_id, tool_task_id, tool_name, arguments_json, schema_valid, outcome, step_index) " +
                "VALUES (?, ?, ?, NULL, 'message:send', ?, 1, 'PENDING', 0)", 4,
        ) { bindString(0, callId); bindString(1, runId.value); bindString(2, taskId); bindString(3, messageArgs.toString()) }
        driver.execute(
            null,
            "INSERT INTO continuations (run_id, task_id, suspended_operation, operation_detail_json, correlation_id, created_at) " +
                "VALUES (?, ?, 'TOOL_CALL', ?, NULL, ?)", 4,
        ) {
            bindString(0, runId.value); bindString(1, taskId)
            bindString(2, """{"capabilityId":"cap-1","toolName":"message:send","callId":"$callId"}""")
            bindString(3, nowIso)
        }
        driver.execute(null, "UPDATE tasks SET state = 'AWAITING_APPROVAL' WHERE id = ?", 1) { bindString(0, taskId) }
        driver.execute(null, "UPDATE runs SET state = 'YIELDED' WHERE id = ?", 1) { bindString(0, runId.value) }
        return Env(driver, pid, sid, runId, taskId)
    }

    private fun root() = RuntimeCompositionRoot(anthropicApiKey = { null })

    @Test
    fun `driving a project registers it with the bridge, and a personal project is air-gapped`(): Unit = runBlocking {
        val e = parkedPersonalProject()
        // Leaves the parked state alone: this drives a fresh Run on the same project.
        val root = root()
        assertNull(root.bridgeDrivers.driverFor(e.projectId))
        val eid = nextId()
        e.driver.execute(
            null,
            "INSERT INTO events (id, project_id, sequence, type, schema_version, category, visibility, timestamp, source, payload, causal_depth) " +
                "VALUES (?, ?, 1, 'UserMessage', 1, 'SIGNAL', 'SESSION', ?, 'user', '{}', 0)", 3,
        ) { bindString(0, eid); bindString(1, e.projectId); bindString(2, nowIso) }
        val run = RunCreator(e.driver, idGen = { nextId() }, nowIso = { nowIso }).createForUserMessage(
            sessionId = SessionId(e.sessionId), projectId = ProjectId(e.projectId), triggerEventId = EventId(eid),
            userMessageSummary = "hi", platformProfile = PlatformProfile.DESKTOP, deviceId = "dev-1", networkAvailable = false,
        )
        root.drive(e.driver, run, ProjectId(e.projectId), e.sessionId, "dev-1", PlatformProfile.DESKTOP, false, { nextId() }, { nowIso })

        assertTrue(root.bridgeDrivers.driverFor(e.projectId) != null, "the bridge can now reach this project's database")
        assertTrue(root.bridge.policies.isEgressAirGapped(e.projectId, "personal"))
    }

    @Test
    fun `the parked message is read back from the durable state for the approval card`(): Unit = runBlocking {
        val e = parkedPersonalProject()
        val request = assertNotNull(root().parkedMessageForRun(e.driver, e.runId))
        assertEquals("sms", request.channel)
        assertEquals("Alice (+358 40 123 4567)", request.recipient)
        assertEquals("I can't make it at 7, but 8:30 works.", request.payload)
        assertEquals(e.runId.value, request.runId)
    }

    @Test
    fun `denying the parked message records a refusal and issues no grant`(): Unit = runBlocking {
        val e = parkedPersonalProject()
        val root = root()
        val request = assertNotNull(root.parkedMessageForRun(e.driver, e.runId))

        root.resolveToolCallApproval(e.driver, e.runId, approved = false, denialReason = "not now", idGen = { nextId() }, nowIso = { nowIso })

        assertNull(root.bridge.takeGrant(request))
        assertEquals(ParkedMessageStatus.REJECTED, root.bridge.coordinator.getParkedMessage(request.id)?.status)
    }

    @Test
    fun `approving the parked message issues the single-use grant for exactly that message`(): Unit = runBlocking {
        val e = parkedPersonalProject()
        val root = root()
        val request = assertNotNull(root.parkedMessageForRun(e.driver, e.runId))

        root.resolveToolCallApproval(e.driver, e.runId, approved = true, idGen = { nextId() }, nowIso = { nowIso })

        val grant = assertNotNull(root.bridge.takeGrant(request), "the person's approval is what creates the grant")
        assertEquals(request.recipient, grant.recipient)
        assertEquals(dev.aidos.bridge.EgressGateCoordinator.sha256Hex(request.payload), grant.payloadHash)
        assertNull(root.bridge.takeGrant(request), "single use")
    }

    // ---- API ---------------------------------------------------------------------------------

    private class ApiEnv(val client: RealRuntimeClient, val sessionId: String, val projectId: String, val manager: SqliteCapabilityManager, val driver: app.cash.sqldelight.db.SqlDriver)

    private fun apiEnv(): ApiEnv = runBlocking {
        val home = Files.createTempDirectory("bridge-api-home").toFile()
        val projDir = Files.createTempDirectory("bridge-api-proj").toFile()
        val userDb = AidosStorage.openUser(DesktopPaths.userDb(home.absolutePath), "t") { nowIso }
        val client = RealRuntimeClient().apply {
            userDriver = userDb.driver
            projectDbFactory = { r -> AidosStorage.openProject(DesktopPaths.stateDb(r), "t") { nowIso }.driver }
            runtimeManagedProjectsRoot = File(home, ".aidos/projects").absolutePath
            capabilityManagerFor = { d -> SqliteCapabilityManager(d, UuidV7Generator()) { nowIso } }
        }
        val project = assertIs<ProjectResult.Success>(client.projects.create(CreateProjectRequest("p", "", ProjectLocation.LocalPath(projDir.absolutePath)))).project
        val session = assertIs<SessionResult.Success>(client.sessions.create(CreateSessionRequest(project.id, "s"))).session
        val driver = AidosStorage.openProject(DesktopPaths.stateDb(projDir.absolutePath), "t") { nowIso }.driver
        ApiEnv(client, session.id, project.id, SqliteCapabilityManager(driver, UuidV7Generator()) { nowIso }, driver)
    }

    @Test
    fun `a bridge grant made through the API is a real capability with the scope that was asked for`(): Unit = runBlocking {
        val e = apiEnv()
        val q = assertIs<CapabilityResult.Success>(
            e.client.capabilities.grant(GrantCapabilityRequest(e.sessionId, "BLINDED_QUERY", "bridge:schemas=CheckAvailability,CheckListContains;targets=life-1")),
        ).capabilityId
        val m = assertIs<CapabilityResult.Success>(
            e.client.capabilities.grant(GrantCapabilityRequest(e.sessionId, "MESSAGE_SEND", "bridge:channels=sms,matrix")),
        ).capabilityId

        val stored = e.manager.loadForSubject(e.sessionId)
        val querying = assertIs<CapabilityScope.Bridge>(stored.single { it.id.value == q }.scope)
        assertEquals(setOf("CheckAvailability", "CheckListContains"), querying.allowedQuerySchemas)
        assertEquals(setOf("life-1"), querying.targetProjectIds)
        assertTrue(querying.allowedChannels.isEmpty(), "a list that is left out allows nothing")
        val messaging = assertIs<CapabilityScope.Bridge>(stored.single { it.id.value == m }.scope)
        assertEquals(setOf("sms", "matrix"), messaging.allowedChannels)
        assertTrue(messaging.targetProjectIds.isEmpty())
    }

    @Test
    fun `parked messages are listed from durable state, and only the parked call`(): Unit = runBlocking {
        val e = apiEnv()
        // A parked message:send continuation plus an older, finished message:send call in the same Run.
        val eid = nextId()
        e.driver.execute(
            null,
            "INSERT INTO events (id, project_id, sequence, type, schema_version, category, visibility, timestamp, source, payload, causal_depth) " +
                "VALUES (?, ?, 0, 'UserMessage', 1, 'SIGNAL', 'SESSION', ?, 'user', '{}', 0)", 3,
        ) { bindString(0, eid); bindString(1, e.projectId); bindString(2, nowIso) }
        val run = RunCreator(e.driver, idGen = { nextId() }, nowIso = { nowIso }).createForUserMessage(
            sessionId = SessionId(e.sessionId), projectId = ProjectId(e.projectId), triggerEventId = EventId(eid),
            userMessageSummary = "x", platformProfile = PlatformProfile.DESKTOP, deviceId = "dev-1", networkAvailable = false,
        )
        val taskId = e.driver.executeQuery(
            null, "SELECT id FROM tasks WHERE run_id = ?",
            { c -> QueryResult.Value(if (c.next().value) c.getString(0)!! else error("no task")) }, 1,
        ) { bindString(0, run.value) }.value
        fun toolCall(id: String, payload: String, step: Int) = e.driver.execute(
            null,
            "INSERT INTO tool_calls (call_id, run_id, model_task_id, tool_task_id, tool_name, arguments_json, schema_valid, outcome, step_index) " +
                "VALUES (?, ?, ?, NULL, 'message:send', ?, 1, 'OK', ?)", 5,
        ) {
            bindString(0, id); bindString(1, run.value); bindString(2, taskId)
            bindString(3, """{"channel":"sms","recipient":"Bob","payload":"$payload"}"""); bindLong(4, step.toLong())
        }
        toolCall("old", "earlier message", 0)
        toolCall("parked", "waiting message", 1)
        e.driver.execute(
            null,
            "INSERT INTO continuations (run_id, task_id, suspended_operation, operation_detail_json, correlation_id, created_at) " +
                "VALUES (?, ?, 'TOOL_CALL', ?, NULL, ?)", 4,
        ) { bindString(0, run.value); bindString(1, taskId); bindString(2, """{"capabilityId":"c","toolName":"message:send","callId":"parked"}"""); bindString(3, nowIso) }

        val parked = e.client.capabilities.listParkedEgress().single()
        assertEquals("waiting message", parked.payload)
        assertEquals(run.value, parked.runId)
        assertEquals(e.projectId, parked.projectId)
        assertEquals(dev.aidos.bridge.EgressGateCoordinator.sha256Hex("waiting message"), parked.payloadFingerprint)
    }

    @Test
    fun `an ASK or NEVER egress policy gates like a personal project does`() {
        val r = dev.aidos.bridge.RegisteredProjectPolicyResolver()
        r.register("p-never", EgressPolicy.NEVER); r.register("p-ask", EgressPolicy.ASK); r.register("p-allow", EgressPolicy.ALLOW)
        assertTrue(r.isEgressAirGapped("p-never", "coding"))
        assertTrue(r.isEgressAirGapped("p-ask", "coding"))
        assertTrue(!r.isEgressAirGapped("p-allow", "coding"))
        assertTrue(r.isEgressAirGapped("p-allow", "personal"))
    }
}
