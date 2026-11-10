package dev.aidos.executor

import dev.aidos.broker.AuditLog
import dev.aidos.broker.ToolBroker
import dev.aidos.capability.SqliteCapabilityManager
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.AvailabilityTier
import dev.aidos.kernel.CapabilityConstraints
import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.EffectKind
import dev.aidos.kernel.EventId
import dev.aidos.kernel.InferenceRouter
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.Permission
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.Preview
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RecoveryClass
import dev.aidos.kernel.ResourceHandle
import dev.aidos.kernel.RoutingContext
import dev.aidos.kernel.RoutingDecision
import dev.aidos.kernel.SessionId
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.SubjectKind
import dev.aidos.kernel.ModelRef
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.ToolCallOutput
import dev.aidos.kernel.Usage
import dev.aidos.kernel.Tool
import dev.aidos.kernel.ToolAvailability
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolCallResult
import dev.aidos.kernel.ToolDescriptor
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.UserId
import dev.aidos.prompt.PromptAssembler
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

import dev.aidos.kernel.GitOperation
import kotlin.test.assertTrue

/**
 * RFC-0027 end to end, on the real broker, capability manager, executor and approval flow — only
 * the model and the two tools are scripted (no live provider in tests).
 *
 * The RFC-0038 taint scenario: a Run reads a file under `untrusted_paths`, then attempts an
 * unsafe effect. It must park naming the file, an approval must be single-use and must not clear
 * the taint; and an ordinary project read must never attenuate anything.
 */
class TaintEscalationEndToEndTest {

    private val nowIso = "2026-08-10T00:00:00Z"
    private fun nextId() = UuidV7Generator().next()

    private fun openDriver() = run {
        val root = Files.createTempDirectory("e2e-cap-test").toFile()
        AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
            as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
    }

    private fun seedEpoch(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, projectId: String) {
        driver.execute(
            null, "INSERT OR IGNORE INTO project_revocation_epoch (project_id, epoch) VALUES (?, 0)", 1
        ) { bindString(0, projectId) }
    }

    private fun seedProjectAndSession(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        projectId: String, sessionId: String, triggerEventId: String,
    ) {
        driver.execute(
            null,
            "INSERT INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) " +
                "VALUES (?, 'test', '/', 'generic', ?, ?, ?)", 4
        ) { bindString(0, projectId); bindString(1, nowIso); bindString(2, nowIso); bindString(3, nowIso) }
        driver.execute(
            null,
            "INSERT INTO sessions (id, project_id, name, role, state, created_at, last_active_at, state_updated_at) " +
                "VALUES (?, ?, 'test', 'DRIVER', 'RUNNING', ?, ?, ?)", 5
        ) { bindString(0, sessionId); bindString(1, projectId); bindString(2, nowIso); bindString(3, nowIso); bindString(4, nowIso) }
        driver.execute(
            null,
            "INSERT INTO events (id, project_id, sequence, type, schema_version, category, visibility, " +
                "timestamp, source, payload, causal_depth) VALUES (?, ?, 0, 'UserMessage', 1, 'SIGNAL', 'SESSION', ?, 'user', '{}', 0)", 4
        ) { bindString(0, triggerEventId); bindString(1, projectId); bindString(2, nowIso) }
    }

    /** `read-file` labels by path like FilesystemTool+PathTrustPolicy; `push` stands in for `git push` (UNSAFE). */
    private class Tools : Tool {
        var pushes = 0
        override val id = "taint-test"
        override val version = "1"
        private val avail = ToolAvailability(setOf(PlatformProfile.DESKTOP), AvailabilityTier.BUNDLED, requiresNetwork = false)
        override fun operations() = listOf(
            ToolDescriptor(name = "read-file", title = "read", description = "reads", inputSchema = buildJsonObject {},
                effect = EffectKind.Read, requiredPermission = Permission.FS_READ,
                recoveryClass = RecoveryClass.IDEMPOTENT, availability = avail),
            ToolDescriptor(name = "push", title = "push", description = "pushes", inputSchema = buildJsonObject {},
                effect = EffectKind.Mutate(dev.aidos.kernel.MutationScope.IN_PROJECT, reversible = false),
                requiredPermission = Permission.GIT_WRITE, recoveryClass = RecoveryClass.UNSAFE, availability = avail),
        )
        override suspend fun execute(handle: ResourceHandle, operation: String, arguments: JsonObject): ToolCallResult =
            if (operation == "push") {
                pushes++
                ToolCallResult("", ToolOutcome.Ok, listOf(ContentBlock.Text("pushed")), TrustLevel.TRUSTED)
            } else {
                val path = arguments["path"]?.jsonPrimitive?.content ?: ""
                val trust = if (path.startsWith("node_modules/")) TrustLevel.UNTRUSTED else TrustLevel.PROJECT
                ToolCallResult("", ToolOutcome.Ok, listOf(ContentBlock.Text("contents of $path")), trust)
            }
        override suspend fun preview(handle: ResourceHandle, operation: String, arguments: JsonObject) =
            Result.success(Preview.Description("preview"))
        override suspend fun cancel(operationId: String) {}
    }

    private fun call(id: String, tool: String, path: String? = null) = ModelResponse(
        outputs = listOf(ToolCallOutput(ToolCall(id, tool,
            buildJsonObject { if (path != null) put("path", path) }, null))),
        stopReason = StopReason.TOOL_USE,
        usage = Usage(inputTokens = 10, outputTokens = 5, totalTokens = 15),
        model = ModelRef(id = "test-model", version = "1.0"),
    )

    private fun endTurnResponse() = ModelResponse(
        outputs = listOf(TextOutput("done")),
        stopReason = StopReason.END_TURN,
        usage = Usage(inputTokens = 10, outputTokens = 5, totalTokens = 15),
        model = ModelRef(id = "test-model", version = "1.0"),
    )

    private fun fakeAdapter(responses: List<ModelResponse>): ModelAdapter {
        val queue = ArrayDeque(responses)
        return object : ModelAdapter {
            override val providerId = "test"
            override val modelId = "test-model"
            override val modelVersion = "1.0"
            override val contextWindow = 4096
            override val isLocal = true
            override fun supportsNativeToolCalls() = false
            override suspend fun invoke(request: ModelRequest) =
                if (queue.isEmpty()) Result.failure(NoSuchElementException()) else Result.success(queue.removeFirst())
        }
    }

    /** One adapter instance, reused across every `select()` call -- its response queue must drain across the Run's several MODEL_CALL steps, not reset on each routing decision. */
    private fun fakeRouter(adapter: ModelAdapter): InferenceRouter = object : InferenceRouter {
        override suspend fun select(kind: ModelKind, context: RoutingContext) = RoutingDecision.Local(adapter)
    }

    /** Same permission-match/most-recent-first shape as `daemon.CapabilityResolver` (not importable here: executor cannot depend on daemon), including its id tie-break for same-instant grants. */
    private fun resolver(capabilityManager: SqliteCapabilityManager): suspend (String, Permission) -> dev.aidos.kernel.CapabilityId? =
        { subjectId, permission ->
            capabilityManager.loadForSubject(subjectId)
                .filter { it.permission == permission && it.revokedAt == null }
                .maxWithOrNull(compareBy({ it.issuedAt }, { it.id.value }))
                ?.id
        }

    private fun buildRun(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
    ): Triple<String, String, dev.aidos.kernel.RunId> {
        val pid = nextId(); val sid = nextId(); val eid = nextId()
        seedProjectAndSession(driver, pid, sid, eid)
        seedEpoch(driver, pid)
        val runId = RunCreator(driver, idGen = { nextId() }, nowIso = { nowIso }).createForUserMessage(
            sessionId = SessionId(sid), projectId = ProjectId(pid), triggerEventId = EventId(eid),
            userMessageSummary = "read a file please", platformProfile = PlatformProfile.DESKTOP,
            deviceId = "dev-1", networkAvailable = false, maxSteps = 24,
        )
        return Triple(pid, sid, runId)
    }

    private class Rig(
        val driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        val runId: dev.aidos.kernel.RunId,
        val executor: SqliteExecutor,
        val tools: Tools,
    )

    private suspend fun rig(vararg responses: ModelResponse): Rig {
        val driver = openDriver()
        val (pid, sid, runId) = buildRun(driver)
        val audit = AuditLog(driver)
        val caps = SqliteCapabilityManager(driver, UuidV7Generator()) { nowIso }
        caps.grant(sid, SubjectKind.SESSION, Permission.FS_READ, CapabilityScope.Filesystem(ProjectId(pid), "/"),
            CapabilityConstraints(), null, UserId("user-1")).getOrThrow()
        caps.grant(sid, SubjectKind.SESSION, Permission.GIT_WRITE, CapabilityScope.Git(ProjectId(pid), emptySet<GitOperation>()),
            CapabilityConstraints(), null, UserId("user-1")).getOrThrow()
        val broker = ToolBroker(
            capabilityManager = caps, audit = audit, idGen = { nextId() }, nowIso = { nowIso },
            projectIdResolver = { caps.projectIdForCapability(it) ?: "" },
        )
        val tools = Tools()
        broker.register(tools)
        val taskRunner = AgentLoopTaskRunner(
            driver = driver, audit = audit, idGen = { nextId() }, nowIso = { nowIso },
            router = fakeRouter(fakeAdapter(responses.toList())), assembler = PromptAssembler(),
            broker = broker, subjectId = sid, resolveCapability = resolver(caps),
        )
        val executor = SqliteExecutor(
            driver = driver, audit = audit, events = EventStore(driver), idGen = { nextId() }, nowIso = { nowIso },
            taskRunner = taskRunner,
        )
        return Rig(driver, runId, executor, tools)
    }

    private fun runState(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, runId: dev.aidos.kernel.RunId): String =
        driver.executeQuery(null, "SELECT state FROM runs WHERE id = ?",
            mapper = { c -> check(c.next().value); app.cash.sqldelight.db.QueryResult.Value(c.getString(0)!!) }, 1
        ) { bindString(0, runId.value) }.value

    private fun continuationDetailJson(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, runId: dev.aidos.kernel.RunId): Pair<String, String>? =
        driver.executeQuery(null, "SELECT suspended_operation, operation_detail_json FROM continuations WHERE run_id = ?",
            mapper = { c ->
                app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0)!! to c.getString(1)!! else null)
            }, 1
        ) { bindString(0, runId.value) }.value

    private fun taintLevel(r: Rig): String =
        r.driver.executeQuery(null, "SELECT taint_level FROM runs WHERE id = ?",
            mapper = { c -> check(c.next().value); app.cash.sqldelight.db.QueryResult.Value(c.getString(0)!!) }, 1
        ) { bindString(0, r.runId.value) }.value

    private fun requestedEvents(r: Rig): List<String> =
        r.driver.executeQuery(null, "SELECT payload FROM events WHERE type = 'CapabilityRequested'",
            mapper = { c ->
                val l = mutableListOf<String>(); while (c.next().value) l.add(c.getString(0)!!)
                app.cash.sqldelight.db.QueryResult.Value(l)
            }, 0).value

    private fun neverGrant(@Suppress("UNUSED_PARAMETER") detail: String): Unit = error("a taint escalation must not issue a capability")

    @Test
    fun `reading ordinary project files never attenuates -- the unsafe call just runs`() = runBlocking {
        val r = rig(call("c1", "read-file", "src/Main.kt"), call("c2", "push"), endTurnResponse())
        r.executor.drive(r.runId)
        assertEquals("COMPLETED", runState(r.driver, r.runId))
        assertEquals(1, r.tools.pushes)
        assertEquals("PROJECT", taintLevel(r), "recorded, but PROJECT never attenuates")
    }

    @Test
    fun `reading a vendored file then attempting an unsafe effect parks and names the file`() = runBlocking {
        val r = rig(call("c1", "read-file", "node_modules/left-pad/README.md"), call("c2", "push"), endTurnResponse())
        r.executor.drive(r.runId)

        assertEquals("YIELDED", runState(r.driver, r.runId))
        assertEquals(0, r.tools.pushes, "nothing unsafe ran before the human answered")
        assertEquals("UNTRUSTED", taintLevel(r))
        val (kind, detail) = continuationDetailJson(r.driver, r.runId) ?: error("no continuation")
        assertEquals("TOOL_CALL", kind)
        assertTrue(detail.contains("node_modules/left-pad/README.md"), "escalation must name the tainting content: $detail")
        assertTrue(detail.contains("push"), detail)
        val events = requestedEvents(r)
        assertEquals(1, events.size, "a CapabilityRequested event carries the taint reason")
        assertTrue(events.single().contains("node_modules/left-pad/README.md"))
    }

    @Test
    fun `approval is single-use, grants nothing, and leaves the run tainted`() = runBlocking {
        val r = rig(
            call("c1", "read-file", "node_modules/left-pad/README.md"),
            call("c2", "push"), call("c3", "push"), endTurnResponse(),
        )
        r.executor.drive(r.runId)
        assertEquals("YIELDED", runState(r.driver, r.runId))

        val resolution = r.executor.resolveToolCallApproval(r.runId, approved = true, onApprove = ::neverGrant)
        assertIs<CapabilityApprovalResolution.Resumed>(resolution)

        assertEquals(1, r.tools.pushes, "the approved call ran exactly once")
        assertEquals("UNTRUSTED", taintLevel(r), "approval does not clear taint")
        // The second push (c3) is a new exercise: it must ask again, not ride on the first approval.
        assertEquals("YIELDED", runState(r.driver, r.runId))
        val detail = continuationDetailJson(r.driver, r.runId)?.second ?: error("second park missing")
        assertTrue(detail.contains("\"call_id\"") || detail.contains("c3") || detail.contains("taintMessage"), detail)
        assertEquals(1, r.tools.pushes)
    }

    @Test
    fun `denying the escalation fails the run and nothing unsafe runs`() = runBlocking {
        val r = rig(call("c1", "read-file", "node_modules/x/index.js"), call("c2", "push"), endTurnResponse())
        r.executor.drive(r.runId)
        val resolution = r.executor.resolveToolCallApproval(r.runId, approved = false, denialReason = "no", onApprove = ::neverGrant)
        assertIs<CapabilityApprovalResolution.Denied>(resolution)
        assertEquals("FAILED", runState(r.driver, r.runId))
        assertEquals(0, r.tools.pushes)
    }
}
