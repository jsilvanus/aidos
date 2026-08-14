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
import dev.aidos.kernel.TokenUsage
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * M19: the real authority chain end to end — a capability granted through
 * [SqliteCapabilityManager.grant] (RFC-0018's ordinary flow), resolved by
 * [AgentLoopTaskRunner]'s own `resolveCapability` seam, and validated + exercised by the real
 * [ToolBroker] — not a mock at any layer except the model provider itself (no live network/API
 * key in this environment; the model is a fake emitting a fixed tool call, the same fake shape
 * [AgentLoopTaskRunnerTest] already uses elsewhere).
 *
 * This is the piece the audit's M19 finding named as the actual root cause behind the mock-only
 * `G2` test: "tool calls are still denied end-to-end (no capability resolver wired yet)" was true
 * regardless of transport — a real socket to a real daemon still hit a broker that unconditionally
 * denied every call. That specific claim is what this test disproves, at the layer where it's
 * actually testable without a live model provider.
 */
class CapabilityResolutionEndToEndTest {

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

    /** Stands in for FilesystemTool (requires FS_READ) without a new module dependency on `:filesystem`. */
    private class GrantGatedTool : Tool {
        override val id = "test-fs"
        override val version = "1"
        override fun operations() = listOf(
            ToolDescriptor(
                name = "read-file",
                title = "read-file",
                description = "reads a file",
                inputSchema = buildJsonObject {},
                effect = EffectKind.Read,
                requiredPermission = Permission.FS_READ,
                recoveryClass = RecoveryClass.IDEMPOTENT,
                availability = ToolAvailability(
                    profiles = setOf(PlatformProfile.DESKTOP),
                    tier = AvailabilityTier.BUNDLED,
                    requiresNetwork = false,
                ),
            )
        )
        override suspend fun execute(handle: ResourceHandle, operation: String, arguments: JsonObject) =
            ToolCallResult("", ToolOutcome.Ok, listOf(ContentBlock.Text("real read, real grant")), TrustLevel.TRUSTED)
        override suspend fun preview(handle: ResourceHandle, operation: String, arguments: JsonObject) =
            Result.success(Preview.Description("preview"))
        override suspend fun cancel(operationId: String) {}
    }

    private fun toolCallResponse() = ModelResponse(
        text = null,
        toolCalls = listOf(ToolCall(callId = "call-1", toolName = "read-file", arguments = buildJsonObject {}, capabilityId = null)),
        stopReason = StopReason.TOOL_USE, usage = TokenUsage(10, 5), modelId = "test-model", modelVersion = "1.0",
    )

    private fun endTurnResponse() = ModelResponse(
        text = "done", toolCalls = emptyList(), stopReason = StopReason.END_TURN,
        usage = TokenUsage(10, 5), modelId = "test-model", modelVersion = "1.0",
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

    private fun toolCallOutcome(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, runId: dev.aidos.kernel.RunId): String =
        driver.executeQuery(
            null, "SELECT outcome FROM tool_calls WHERE run_id = ?",
            mapper = { c ->
                check(c.next().value) { "no tool_calls row for run ${runId.value}" }
                app.cash.sqldelight.db.QueryResult.Value(c.getString(0)!!)
            }, 1
        ) { bindString(0, runId.value) }.value

    @Test
    fun `a granted capability lets a real tool call resolve, validate, and execute -- not be denied`() = runBlocking {
        val driver = openDriver()
        val (pid, sid, runId) = buildRun(driver)

        val audit = AuditLog(driver)
        val capabilityManager = SqliteCapabilityManager(driver, UuidV7Generator()) { nowIso }
        // The real grant flow (RFC-0018) -- what a human approving the session's request looks
        // like, issued before drive() is ever called, exactly as CapabilityResolver's own doc
        // comment assumes.
        capabilityManager.grant(
            subjectId = sid, subjectKind = SubjectKind.SESSION, permission = Permission.FS_READ,
            scope = CapabilityScope.Filesystem(ProjectId(pid), "/"), constraints = CapabilityConstraints(),
            expiresAt = null, grantedBy = UserId("user-1"),
        ).getOrThrow()

        val broker = ToolBroker(
            capabilityManager = capabilityManager, audit = audit, idGen = { nextId() }, nowIso = { nowIso },
            projectIdResolver = { capabilityManager.projectIdForCapability(it) ?: "" },
        )
        broker.register(GrantGatedTool())

        val router = fakeRouter(fakeAdapter(listOf(toolCallResponse(), endTurnResponse())))

        val taskRunner = AgentLoopTaskRunner(
            driver = driver, audit = audit, idGen = { nextId() }, nowIso = { nowIso },
            router = router, assembler = PromptAssembler(), broker = broker, subjectId = sid,
            resolveCapability = resolver(capabilityManager),
        )
        val executor = SqliteExecutor(
            driver = driver, audit = audit, events = EventStore(driver), idGen = { nextId() }, nowIso = { nowIso },
            taskRunner = taskRunner,
        )
        executor.drive(runId)

        assertEquals("OK", toolCallOutcome(driver, runId), "a real grant must let the real broker actually execute the call, not deny it")
    }

    @Test
    fun `no capability granted still denies -- the resolver does not fabricate authority`() = runBlocking {
        val driver = openDriver()
        val (_, sid, runId) = buildRun(driver)

        val audit = AuditLog(driver)
        val capabilityManager = SqliteCapabilityManager(driver, UuidV7Generator()) { nowIso }
        // No grant() call -- the subject holds nothing for FS_READ.

        val broker = ToolBroker(
            capabilityManager = capabilityManager, audit = audit, idGen = { nextId() }, nowIso = { nowIso },
            projectIdResolver = { capabilityManager.projectIdForCapability(it) ?: "" },
        )
        broker.register(GrantGatedTool())

        val router = fakeRouter(fakeAdapter(listOf(toolCallResponse(), endTurnResponse())))

        val taskRunner = AgentLoopTaskRunner(
            driver = driver, audit = audit, idGen = { nextId() }, nowIso = { nowIso },
            router = router, assembler = PromptAssembler(), broker = broker, subjectId = sid,
            resolveCapability = resolver(capabilityManager),
        )
        val executor = SqliteExecutor(
            driver = driver, audit = audit, events = EventStore(driver), idGen = { nextId() }, nowIso = { nowIso },
            taskRunner = taskRunner,
        )
        executor.drive(runId)

        // "FAILED", not "DENIED": ToolBroker.invoke()'s own step 2 -- no capability named at all
        // -- returns ToolOutcome.Failed("capability.missing"), a different branch than
        // CapabilityCheckResult.Denied (which the next test exercises). The resolver returning
        // null must reach that same unnamed-capability path, not fabricate one.
        assertEquals("FAILED", toolCallOutcome(driver, runId))
    }

    @Test
    fun `validate() is an independent gate -- a resolved id whose capability is revoked is still denied`() = runBlocking {
        // resolver()'s own filtering already excludes a revoked grant (proven by the "no
        // capability granted" test reaching the same FAILED outcome for a null resolution). This
        // test proves the *other* half of CapabilityResolver's own safety argument: even if a
        // resolver handed over a capability id whose grant turns out to be revoked -- bypassing
        // resolver()'s filter on purpose here, standing in for a resolver bug or a race between
        // resolution and revocation -- ToolBroker.invoke() still calls the real
        // CapabilityManager.validate(), which is the actual authority check and denies it on its
        // own. The resolver can under-grant; it cannot over-grant, because this second gate exists.
        val driver = openDriver()
        val (pid, sid, runId) = buildRun(driver)

        val audit = AuditLog(driver)
        val capabilityManager = SqliteCapabilityManager(driver, UuidV7Generator()) { nowIso }
        val cap = capabilityManager.grant(
            subjectId = sid, subjectKind = SubjectKind.SESSION, permission = Permission.FS_READ,
            scope = CapabilityScope.Filesystem(ProjectId(pid), "/"), constraints = CapabilityConstraints(),
            expiresAt = null, grantedBy = UserId("user-1"),
        ).getOrThrow()
        capabilityManager.revoke(cap.id, "user-1")

        val broker = ToolBroker(
            capabilityManager = capabilityManager, audit = audit, idGen = { nextId() }, nowIso = { nowIso },
            projectIdResolver = { capabilityManager.projectIdForCapability(it) ?: "" },
        )
        broker.register(GrantGatedTool())

        val router = fakeRouter(fakeAdapter(listOf(toolCallResponse(), endTurnResponse())))

        val taskRunner = AgentLoopTaskRunner(
            driver = driver, audit = audit, idGen = { nextId() }, nowIso = { nowIso },
            router = router, assembler = PromptAssembler(), broker = broker, subjectId = sid,
            resolveCapability = { _, _ -> cap.id },
        )
        val executor = SqliteExecutor(
            driver = driver, audit = audit, events = EventStore(driver), idGen = { nextId() }, nowIso = { nowIso },
            taskRunner = taskRunner,
        )
        executor.drive(runId)

        assertEquals("DENIED", toolCallOutcome(driver, runId))
    }

    // ─── RFC-0008 step 8d: TOOL_CALL park/resume (requiresApprovalPerUse) ──────

    private fun runState(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, runId: dev.aidos.kernel.RunId): String =
        driver.executeQuery(null, "SELECT state FROM runs WHERE id = ?",
            mapper = { c -> check(c.next().value); app.cash.sqldelight.db.QueryResult.Value(c.getString(0)!!) }, 1
        ) { bindString(0, runId.value) }.value

    private fun toolCallTaskState(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, runId: dev.aidos.kernel.RunId): String =
        driver.executeQuery(null, "SELECT state FROM tasks WHERE run_id = ? AND kind = 'TOOL_CALL' ORDER BY ordinal DESC LIMIT 1",
            mapper = { c -> check(c.next().value); app.cash.sqldelight.db.QueryResult.Value(c.getString(0)!!) }, 1
        ) { bindString(0, runId.value) }.value

    private fun continuationDetailJson(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, runId: dev.aidos.kernel.RunId): Pair<String, String>? =
        driver.executeQuery(null, "SELECT suspended_operation, operation_detail_json FROM continuations WHERE run_id = ?",
            mapper = { c ->
                app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0)!! to c.getString(1)!! else null)
            }, 1
        ) { bindString(0, runId.value) }.value

    @Test
    fun `a requiresApprovalPerUse grant parks the run instead of denying the model outright`() = runBlocking {
        val driver = openDriver()
        val (pid, sid, runId) = buildRun(driver)

        val audit = AuditLog(driver)
        val capabilityManager = SqliteCapabilityManager(driver, UuidV7Generator()) { nowIso }
        capabilityManager.grant(
            subjectId = sid, subjectKind = SubjectKind.SESSION, permission = Permission.FS_READ,
            scope = CapabilityScope.Filesystem(ProjectId(pid), "/"),
            constraints = CapabilityConstraints(requiresApprovalPerUse = true),
            expiresAt = null, grantedBy = UserId("user-1"),
        ).getOrThrow()

        val broker = ToolBroker(
            capabilityManager = capabilityManager, audit = audit, idGen = { nextId() }, nowIso = { nowIso },
            projectIdResolver = { capabilityManager.projectIdForCapability(it) ?: "" },
        )
        broker.register(GrantGatedTool())
        val router = fakeRouter(fakeAdapter(listOf(toolCallResponse(), endTurnResponse())))
        val taskRunner = AgentLoopTaskRunner(
            driver = driver, audit = audit, idGen = { nextId() }, nowIso = { nowIso },
            router = router, assembler = PromptAssembler(), broker = broker, subjectId = sid,
            resolveCapability = resolver(capabilityManager),
        )
        val executor = SqliteExecutor(
            driver = driver, audit = audit, events = EventStore(driver), idGen = { nextId() }, nowIso = { nowIso },
            taskRunner = taskRunner,
        )

        executor.drive(runId)

        assertEquals("YIELDED", runState(driver, runId), "REQUIRES_APPROVAL must park the Run, not fail it or feed it back to the model as data")
        assertEquals("AWAITING_APPROVAL", toolCallTaskState(driver, runId))
        val (kind, _) = continuationDetailJson(driver, runId) ?: error("no continuation written")
        assertEquals("TOOL_CALL", kind)
    }

    @Test
    fun `approving a parked tool call grants a fresh capability and the resumed call really succeeds`() = runBlocking {
        val driver = openDriver()
        val (pid, sid, runId) = buildRun(driver)

        val audit = AuditLog(driver)
        val capabilityManager = SqliteCapabilityManager(driver, UuidV7Generator()) { nowIso }
        val original = capabilityManager.grant(
            subjectId = sid, subjectKind = SubjectKind.SESSION, permission = Permission.FS_READ,
            scope = CapabilityScope.Filesystem(ProjectId(pid), "/"),
            constraints = CapabilityConstraints(requiresApprovalPerUse = true),
            expiresAt = null, grantedBy = UserId("user-1"),
        ).getOrThrow()

        val broker = ToolBroker(
            capabilityManager = capabilityManager, audit = audit, idGen = { nextId() }, nowIso = { nowIso },
            projectIdResolver = { capabilityManager.projectIdForCapability(it) ?: "" },
        )
        broker.register(GrantGatedTool())
        val router = fakeRouter(fakeAdapter(listOf(toolCallResponse(), endTurnResponse())))
        val taskRunner = AgentLoopTaskRunner(
            driver = driver, audit = audit, idGen = { nextId() }, nowIso = { nowIso },
            router = router, assembler = PromptAssembler(), broker = broker, subjectId = sid,
            resolveCapability = resolver(capabilityManager),
        )
        val executor = SqliteExecutor(
            driver = driver, audit = audit, events = EventStore(driver), idGen = { nextId() }, nowIso = { nowIso },
            taskRunner = taskRunner,
        )
        executor.drive(runId)
        assertEquals("YIELDED", runState(driver, runId), "sanity: parked before resolving")

        // The onApprove callback is RuntimeCompositionRoot.resolveToolCallApproval's real job
        // (daemon module, not importable here — executor cannot depend on daemon); this inlines
        // the exact same logic against the same real SqliteCapabilityManager to prove the
        // mechanism the daemon method wraps, without a mock standing in for it.
        val resolution = executor.resolveToolCallApproval(runId, approved = true) { detailJson ->
            val detail = kotlinx.serialization.json.Json.parseToJsonElement(detailJson).jsonObject
            val originalCapId = detail["capabilityId"]!!.jsonPrimitive.content
            val loaded = capabilityManager.loadForSubject(sid).find { it.id.value == originalCapId }
                ?: error("original capability not found")
            assertEquals(original.id.value, loaded.id.value, "the continuation must name the exact capability that was denied")
            capabilityManager.grant(
                subjectId = loaded.subjectId, subjectKind = loaded.subjectKind, permission = loaded.permission,
                scope = loaded.scope, constraints = loaded.constraints.copy(requiresApprovalPerUse = false),
                expiresAt = null, grantedBy = UserId("user-1"),
            ).getOrThrow()
        }

        assertIs<CapabilityApprovalResolution.Resumed>(resolution)
        assertEquals("OK", toolCallOutcome(driver, runId), "the resumed attempt must use the fresh grant and really execute the tool")
        assertEquals("COMPLETED", runState(driver, runId))
    }

    @Test
    fun `denying a parked tool call fails the run without granting anything`() = runBlocking {
        val driver = openDriver()
        val (pid, sid, runId) = buildRun(driver)

        val audit = AuditLog(driver)
        val capabilityManager = SqliteCapabilityManager(driver, UuidV7Generator()) { nowIso }
        capabilityManager.grant(
            subjectId = sid, subjectKind = SubjectKind.SESSION, permission = Permission.FS_READ,
            scope = CapabilityScope.Filesystem(ProjectId(pid), "/"),
            constraints = CapabilityConstraints(requiresApprovalPerUse = true),
            expiresAt = null, grantedBy = UserId("user-1"),
        ).getOrThrow()

        val broker = ToolBroker(
            capabilityManager = capabilityManager, audit = audit, idGen = { nextId() }, nowIso = { nowIso },
            projectIdResolver = { capabilityManager.projectIdForCapability(it) ?: "" },
        )
        broker.register(GrantGatedTool())
        val router = fakeRouter(fakeAdapter(listOf(toolCallResponse(), endTurnResponse())))
        val taskRunner = AgentLoopTaskRunner(
            driver = driver, audit = audit, idGen = { nextId() }, nowIso = { nowIso },
            router = router, assembler = PromptAssembler(), broker = broker, subjectId = sid,
            resolveCapability = resolver(capabilityManager),
        )
        val executor = SqliteExecutor(
            driver = driver, audit = audit, events = EventStore(driver), idGen = { nextId() }, nowIso = { nowIso },
            taskRunner = taskRunner,
        )
        executor.drive(runId)

        var onApproveCalled = false
        val resolution = executor.resolveToolCallApproval(runId, approved = false, denialReason = "not now") {
            onApproveCalled = true
        }

        assertIs<CapabilityApprovalResolution.Denied>(resolution)
        assertEquals(false, onApproveCalled, "deny must never invoke the grant callback")
        assertEquals("FAILED", runState(driver, runId))
        assertEquals(1, capabilityManager.loadForSubject(sid).size, "no fresh capability should exist after a denial")
    }
}
