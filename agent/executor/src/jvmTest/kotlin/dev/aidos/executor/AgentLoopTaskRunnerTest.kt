package dev.aidos.executor

import dev.aidos.broker.AuditLog
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.AvailabilityTier
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ContentNodeId
import dev.aidos.kernel.DenialReason
import dev.aidos.kernel.EffectBroker
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
import dev.aidos.kernel.ProviderRetention
import dev.aidos.kernel.RecoveryClass
import dev.aidos.kernel.RetentionPolicy
import dev.aidos.kernel.RoutingContext
import dev.aidos.kernel.RoutingDecision
import dev.aidos.kernel.RunId
import dev.aidos.kernel.SessionId
import dev.aidos.kernel.StopReason
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
import dev.aidos.kernel.TrainingUse
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.Turn
import dev.aidos.prompt.InstructionDiscovery
import dev.aidos.prompt.PromptAssembler
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The AgentLoop↔executor bridge end to end: [RunCreator] creates the Run, [SqliteExecutor.drive]
 * with [AgentLoopTaskRunner] steps it through MODEL_CALL/TOOL_CALL tasks, matching RFC-0008's own
 * mapping table. Fakes mirror `agentloop.AgentLoopTest`'s so the two suites stay comparable even
 * though nothing is shared between them (see [AgentLoopTaskRunner]'s class doc for why).
 */
class AgentLoopTaskRunnerTest {

    private val nowIso = "2026-08-09T00:00:00Z"

    private fun nextId() = UuidV7Generator().next()

    private fun openDriver() = run {
        val root = Files.createTempDirectory("bridge-test").toFile()
        AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
            as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
    }

    private fun seedProjectAndSession(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        projectId: String,
        sessionId: String,
        triggerEventId: String,
        rootPath: String = Files.createTempDirectory("bridge-test-root").toFile().path,
    ) {
        driver.execute(null,
            "INSERT INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) " +
                "VALUES (?, 'test', ?, 'generic', ?, ?, ?)", 5
        ) { bindString(0, projectId); bindString(1, rootPath); bindString(2, nowIso); bindString(3, nowIso); bindString(4, nowIso) }
        driver.execute(null,
            "INSERT INTO sessions (id, project_id, name, role, state, created_at, last_active_at, state_updated_at) " +
                "VALUES (?, ?, 'test', 'DRIVER', 'RUNNING', ?, ?, ?)", 5
        ) { bindString(0, sessionId); bindString(1, projectId); bindString(2, nowIso); bindString(3, nowIso); bindString(4, nowIso) }
        driver.execute(null,
            "INSERT INTO events (id, project_id, sequence, type, schema_version, category, visibility, " +
                "timestamp, source, payload, causal_depth) VALUES (?, ?, 0, 'UserMessage', 1, 'SIGNAL', 'SESSION', ?, 'user', '{}', 0)", 4
        ) { bindString(0, triggerEventId); bindString(1, projectId); bindString(2, nowIso) }
    }

    private fun fakeModel(
        responses: List<ModelResponse>,
        requestLog: MutableList<ModelRequest> = mutableListOf(),
        isLocalAdapter: Boolean = true,
        retention: ProviderRetention? = null,
        modelId: String = "test-model",
    ): ModelAdapter {
        val queue = ArrayDeque(responses)
        return object : ModelAdapter {
            override val providerId = "test"
            override val modelId = modelId
            override val modelVersion = "1.0"
            override val contextWindow = 4096
            override val isLocal = isLocalAdapter
            override val providerRetention = retention
            override fun supportsNativeToolCalls() = false
            override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
                requestLog.add(request)
                return if (queue.isEmpty()) Result.failure(NoSuchElementException("No more responses"))
                else Result.success(queue.removeFirst())
            }
        }
    }

    private fun fakeRouter(adapter: ModelAdapter): InferenceRouter = object : InferenceRouter {
        override suspend fun select(kind: ModelKind, context: RoutingContext) = RoutingDecision.Local(adapter)
    }

    private fun brokerReturning(outcome: ToolOutcome, trustLevel: TrustLevel, text: String = "ok"): EffectBroker =
        object : EffectBroker {
            override fun register(tool: Tool) {}
            override fun descriptorsFor(subjectId: String, profile: PlatformProfile, networkAvailable: Boolean) = emptyList<ToolDescriptor>()
            override suspend fun invoke(subjectId: String, call: ToolCall, runTaint: TrustLevel) = ToolCallResult(
                callId = call.callId,
                outcome = outcome,
                content = listOf(ContentBlock.Text(text)),
                trustLevel = trustLevel,
            )
            override suspend fun preview(subjectId: String, call: ToolCall) = Result.success(Preview.Description("preview"))
            override suspend fun cancel(callId: String) {}
        }

    private fun noOpBroker() = brokerReturning(ToolOutcome.Ok, TrustLevel.UNTRUSTED)

    private fun endTurnResponse(text: String = "Done") = ModelResponse(
        outputs = listOf(TextOutput(text)),
        stopReason = StopReason.END_TURN,
        usage = testUsage(),
        model = testModel(),
    )

    private fun toolCallResponse(toolName: String, callId: String = "call-1") = ModelResponse(
        outputs = listOf(
            ToolCallOutput(
                ToolCall(callId = callId, toolName = toolName, arguments = buildJsonObject {}, capabilityId = null),
            ),
        ),
        stopReason = StopReason.TOOL_USE,
        usage = testUsage(),
        model = testModel(),
    )

    private fun askUserResponse(question: String, callId: String = "call-1") = ModelResponse(
        outputs = listOf(
            ToolCallOutput(
                ToolCall(
                    callId = callId, toolName = "ask_user",
                    arguments = buildJsonObject { put("question", question) }, capabilityId = null,
                ),
            ),
        ),
        stopReason = StopReason.TOOL_USE,
        usage = testUsage(),
        model = testModel(),
    )

    private fun testUsage() = Usage(inputTokens = 10, outputTokens = 5, totalTokens = 15)

    private fun testModel() = ModelRef(id = "test-model", version = "1.0")

    private fun createRun(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        maxSteps: Int = 24,
        rootPath: String = Files.createTempDirectory("bridge-test-root").toFile().path,
    ): Triple<String, String, RunId> {
        val pid = nextId(); val sid = nextId(); val eid = nextId()
        seedProjectAndSession(driver, pid, sid, eid, rootPath)
        val runId = RunCreator(driver, idGen = { nextId() }, nowIso = { nowIso }).createForUserMessage(
            sessionId = SessionId(sid),
            projectId = ProjectId(pid),
            triggerEventId = EventId(eid),
            userMessageSummary = "Please help",
            platformProfile = PlatformProfile.DESKTOP,
            deviceId = "dev-1",
            networkAvailable = false,
            maxSteps = maxSteps,
        )
        return Triple(pid, sid, runId)
    }

    private fun buildExecutor(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        router: InferenceRouter,
        broker: EffectBroker,
        redact: (String) -> String = { it },
        resolveRemoteAdapter: (String) -> ModelAdapter? = { null },
    ) = SqliteExecutor(
        driver = driver,
        audit = AuditLog(driver),
        events = EventStore(driver),
        idGen = { nextId() },
        nowIso = { nowIso },
        taskRunner = AgentLoopTaskRunner(
            driver = driver,
            audit = AuditLog(driver),
            idGen = { nextId() },
            nowIso = { nowIso },
            router = router,
            assembler = PromptAssembler(),
            broker = broker,
            redact = redact,
            resolveRemoteAdapter = resolveRemoteAdapter,
        ),
    )

    private fun modelCallAttempt(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        runId: RunId,
    ): Pair<String, String?> =
        driver.executeQuery(
            null,
            "SELECT a.output_snapshot, a.provider_retention_json FROM tasks t " +
                "JOIN attempts a ON a.task_id = t.id AND a.attempt_number = 1 " +
                "WHERE t.run_id = ? AND t.kind = 'MODEL_CALL' ORDER BY t.ordinal DESC LIMIT 1",
            mapper = { c ->
                check(c.next().value) { "no MODEL_CALL attempt for run ${runId.value}" }
                app.cash.sqldelight.db.QueryResult.Value(c.getString(0)!! to c.getString(1))
            }, 1
        ) { bindString(0, runId.value) }.value

    private fun runTaintSourceNodeId(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, runId: RunId): String? =
        driver.executeQuery(null, "SELECT taint_source_node_id FROM runs WHERE id = ?",
            mapper = { c ->
                check(c.next().value) { "run ${runId.value} not found" }
                app.cash.sqldelight.db.QueryResult.Value(c.getString(0))
            }, 1
        ) { bindString(0, runId.value) }.value

    private fun runInstructionSetHash(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, runId: RunId): String? =
        driver.executeQuery(null, "SELECT instruction_set_hash FROM runs WHERE id = ?",
            mapper = { c ->
                check(c.next().value) { "run ${runId.value} not found" }
                app.cash.sqldelight.db.QueryResult.Value(c.getString(0))
            }, 1
        ) { bindString(0, runId.value) }.value

    private fun adoptInstructionSet(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, projectId: String, hash: String) {
        driver.execute(null,
            "INSERT INTO instruction_adoptions (project_id, set_hash, adopted_at, adopted_by, source_manifest) " +
                "VALUES (?, ?, ?, 'user', '[]')", 3
        ) { bindString(0, projectId); bindString(1, hash); bindString(2, nowIso) }
    }

    private fun runRow(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, runId: RunId): Pair<String, String> =
        driver.executeQuery(null, "SELECT state, taint_level FROM runs WHERE id = ?",
            mapper = { c ->
                check(c.next().value) { "run ${runId.value} not found" }
                app.cash.sqldelight.db.QueryResult.Value(c.getString(0)!! to c.getString(1)!!)
            }, 1
        ) { bindString(0, runId.value) }.value

    private fun toolCallOutcome(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, runId: RunId): List<String> {
        val rows = mutableListOf<String>()
        driver.executeQuery(null, "SELECT outcome FROM tool_calls WHERE run_id = ? ORDER BY step_index",
            mapper = { c ->
                while (c.next().value) rows.add(c.getString(0)!!)
                app.cash.sqldelight.db.QueryResult.Value(Unit)
            }, 1
        ) { bindString(0, runId.value) }
        return rows
    }

    @Test
    fun `run with no tool calls completes on the first MODEL_CALL task`() = runBlocking {
        val driver = openDriver()
        val (_, _, runId) = createRun(driver)
        val adapter = fakeModel(listOf(endTurnResponse("Task complete")))

        buildExecutor(driver, fakeRouter(adapter), noOpBroker()).drive(runId)

        val (state, taint) = runRow(driver, runId)
        assertEquals("COMPLETED", state)
        assertEquals("TRUSTED", taint, "no tool call was made — taint stays TRUSTED")
    }

    @Test
    fun `tool call round trip completes and taints the run`() = runBlocking {
        val driver = openDriver()
        val (_, _, runId) = createRun(driver)
        val requests = mutableListOf<ModelRequest>()
        val adapter = fakeModel(listOf(toolCallResponse("read-file"), endTurnResponse()), requests)

        buildExecutor(driver, fakeRouter(adapter), noOpBroker()).drive(runId)

        val (state, taint) = runRow(driver, runId)
        assertEquals("COMPLETED", state)
        assertEquals("UNTRUSTED", taint, "a tool result taints the Run monotonically (RFC-0027)")
        assertEquals(listOf("OK"), toolCallOutcome(driver, runId))

        // The second model call must see the first turn's tool result in its history — proof
        // that reconstructHistory() rebuilt it from durable rows, not from anything held in
        // memory across the two Task executions.
        assertEquals(2, requests.size)
        val secondRequestMessages = requests[1].messages
        assertTrue(secondRequestMessages.any { it is Turn.Assistant && it.toolCalls.any { c -> c.toolName == "read-file" } })
        assertTrue(secondRequestMessages.any { it is Turn.ToolResult })
    }

    @Test
    fun `denied tool call is recorded and does not fail the run`() = runBlocking {
        val driver = openDriver()
        val (_, _, runId) = createRun(driver)
        val adapter = fakeModel(listOf(toolCallResponse("write-remote"), endTurnResponse()))
        val denied = brokerReturning(ToolOutcome.Denied(DenialReason.ATTENUATED_BY_TAINT), TrustLevel.UNTRUSTED, "Denied: tainted")

        buildExecutor(driver, fakeRouter(adapter), denied).drive(runId)

        val (state, _) = runRow(driver, runId)
        assertEquals("COMPLETED", state, "Denied/Failed outcomes are data returned to the model, not a Task failure (RFC-0008)")
        assertEquals(listOf("DENIED"), toolCallOutcome(driver, runId))
    }

    @Test
    fun `same tool call repeated three times fails the run with no-progress`() = runBlocking {
        val driver = openDriver()
        val (_, _, runId) = createRun(driver)
        // Same tool name and arguments each time (what the no-progress guard actually compares)
        // but a distinct call_id per turn — tool_calls.call_id is a real primary key, and a
        // provider mints a fresh one per call even when the model is stuck in a loop.
        val responses = (1..4).map { toolCallResponse("echo", callId = "call-$it") }
        val adapter = fakeModel(responses)

        buildExecutor(driver, fakeRouter(adapter), noOpBroker()).drive(runId)

        val (state, _) = runRow(driver, runId)
        assertEquals("FAILED", state)
    }

    @Test
    fun `run fails when the step ceiling is reached`() = runBlocking {
        val driver = openDriver()
        val (_, _, runId) = createRun(driver, maxSteps = 3)
        // Distinct call each time so the no-progress guard doesn't fire first.
        val responses = (1..10).map { toolCallResponse("tool-$it", callId = "call-$it") }
        val adapter = fakeModel(responses)

        buildExecutor(driver, fakeRouter(adapter), noOpBroker()).drive(runId)

        val (state, _) = runRow(driver, runId)
        assertEquals("FAILED", state)
    }

    @Test
    fun `unavailable offline routing fails the model call task without a model invocation`() = runBlocking {
        val driver = openDriver()
        val (_, _, runId) = createRun(driver)
        val offlineRouter = object : InferenceRouter {
            override suspend fun select(kind: ModelKind, context: RoutingContext) = RoutingDecision.UnavailableOffline(kind)
        }

        buildExecutor(driver, offlineRouter, noOpBroker()).drive(runId)

        val (state, _) = runRow(driver, runId)
        assertEquals("FAILED", state)
    }

    @Test
    fun `output snapshot is passed through the injected redact function before it is persisted`() = runBlocking {
        val driver = openDriver()
        val (_, _, runId) = createRun(driver)
        val adapter = fakeModel(listOf(endTurnResponse("secret-value-should-not-persist")))

        buildExecutor(driver, fakeRouter(adapter), noOpBroker(), redact = { it.replace("secret-value-should-not-persist", "«redacted»") })
            .drive(runId)

        val (snapshot, _) = modelCallAttempt(driver, runId)
        assertTrue(snapshot.contains("«redacted»"))
        assertTrue(!snapshot.contains("secret-value-should-not-persist"))
    }

    @Test
    fun `local model attempts record no provider retention`() = runBlocking {
        val driver = openDriver()
        val (_, _, runId) = createRun(driver)
        val adapter = fakeModel(listOf(endTurnResponse()), isLocalAdapter = true)

        buildExecutor(driver, fakeRouter(adapter), noOpBroker()).drive(runId)

        val (_, retentionJson) = modelCallAttempt(driver, runId)
        assertEquals(null, retentionJson, "no remote provider retained anything for a local model")
    }

    @Test
    fun `remote adapter with no stated policy falls back to UNKNOWN, never an assumed-benign default`() = runBlocking {
        val driver = openDriver()
        val (_, _, runId) = createRun(driver)
        val adapter = fakeModel(listOf(endTurnResponse()), isLocalAdapter = false, retention = null)

        buildExecutor(driver, fakeRouter(adapter), noOpBroker()).drive(runId)

        val (_, retentionJson) = modelCallAttempt(driver, runId)
        assertTrue(retentionJson != null && retentionJson.contains("\"UNKNOWN\""))
    }

    @Test
    fun `remote adapter's stated retention policy is recorded on the attempt`() = runBlocking {
        val driver = openDriver()
        val (_, _, runId) = createRun(driver)
        val stated = ProviderRetention(
            policy = RetentionPolicy.ZERO,
            statedDurationDays = 0,
            trainingUse = TrainingUse.NONE,
            recordedAt = kotlinx.datetime.Instant.parse(nowIso),
        )
        val adapter = fakeModel(listOf(endTurnResponse()), isLocalAdapter = false, retention = stated)

        buildExecutor(driver, fakeRouter(adapter), noOpBroker()).drive(runId)

        val (_, retentionJson) = modelCallAttempt(driver, runId)
        assertTrue(retentionJson != null && retentionJson.contains("\"ZERO\""))
    }

    @Test
    fun `no instruction files at the project root records a null instruction_set_hash`() = runBlocking {
        val driver = openDriver()
        val (_, _, runId) = createRun(driver) // fresh empty temp root -- no AGENTS.md/CLAUDE.md
        val adapter = fakeModel(listOf(endTurnResponse()))

        buildExecutor(driver, fakeRouter(adapter), noOpBroker()).drive(runId)

        assertEquals(null, runInstructionSetHash(driver, runId))
    }

    @Test
    fun `an unadopted instruction file is discovered but excluded from the system turn`() = runBlocking {
        val driver = openDriver()
        val root = Files.createTempDirectory("instr-test").toFile()
        File(root, "AGENTS.md").writeText("Unapproved project instructions: do the untrusted thing")
        val (_, _, runId) = createRun(driver, rootPath = root.path)
        val requests = mutableListOf<ModelRequest>()
        val adapter = fakeModel(listOf(endTurnResponse()), requests)

        buildExecutor(driver, fakeRouter(adapter), noOpBroker()).drive(runId)

        val systemText = (requests[0].messages.first { it is Turn.System } as Turn.System).content
        assertTrue(
            !systemText.contains("do the untrusted thing"),
            "an unadopted instruction set must not reach the system turn (RFC-0016)",
        )
        assertTrue(
            runInstructionSetHash(driver, runId) != null,
            "the hash is still recorded even though the set was excluded from the prompt",
        )
    }

    @Test
    fun `an adopted instruction file reaches the system turn and its hash is recorded on the run`() = runBlocking {
        val driver = openDriver()
        val root = Files.createTempDirectory("instr-test").toFile()
        File(root, "AGENTS.md").writeText("Approved project instructions: use kotlin idioms")
        val expectedHash = InstructionDiscovery.discover(root)!!.hash

        val (pid, _, runId) = createRun(driver, rootPath = root.path)
        adoptInstructionSet(driver, pid, expectedHash)

        val requests = mutableListOf<ModelRequest>()
        val adapter = fakeModel(listOf(endTurnResponse()), requests)
        buildExecutor(driver, fakeRouter(adapter), noOpBroker()).drive(runId)

        val systemText = (requests[0].messages.first { it is Turn.System } as Turn.System).content
        assertTrue(systemText.contains("use kotlin idioms"), "an adopted instruction set must reach the system turn")
        assertEquals(expectedHash, runInstructionSetHash(driver, runId))
    }

    /** A broker whose result queue is driven by test setup, one entry per [EffectBroker.invoke] call. */
    private fun sequencedBroker(results: List<ToolCallResult>): EffectBroker {
        val queue = ArrayDeque(results)
        return object : EffectBroker {
            override fun register(tool: Tool) {}
            override fun descriptorsFor(subjectId: String, profile: PlatformProfile, networkAvailable: Boolean) = emptyList<ToolDescriptor>()
            override suspend fun invoke(subjectId: String, call: ToolCall, runTaint: TrustLevel): ToolCallResult =
                queue.removeFirst().copy(callId = call.callId)
            override suspend fun preview(subjectId: String, call: ToolCall) = Result.success(Preview.Description("preview"))
            override suspend fun cancel(callId: String) {}
        }
    }

    private fun toolCallAttemptSnapshot(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, callId: String): String =
        driver.executeQuery(
            null,
            "SELECT a.output_snapshot FROM tool_calls tc " +
                "JOIN attempts a ON a.task_id = tc.tool_task_id AND a.attempt_number = 1 " +
                "WHERE tc.call_id = ?",
            mapper = { c ->
                check(c.next().value) { "no attempt for call $callId" }
                app.cash.sqldelight.db.QueryResult.Value(c.getString(0)!!)
            }, 1
        ) { bindString(0, callId) }.value

    @Test
    fun `a taint-attenuated denial names the tool call that first raised the taint`() = runBlocking {
        val driver = openDriver()
        val (_, _, runId) = createRun(driver)
        val adapter = fakeModel(listOf(
            toolCallResponse("read-untrusted-file", callId = "call-1"),
            toolCallResponse("push-to-remote", callId = "call-2"),
            endTurnResponse(),
        ))
        val broker = sequencedBroker(listOf(
            ToolCallResult(
                callId = "call-1", outcome = ToolOutcome.Ok,
                content = listOf(ContentBlock.Text("file contents")), trustLevel = TrustLevel.UNTRUSTED,
            ),
            ToolCallResult(
                callId = "call-2", outcome = ToolOutcome.Denied(DenialReason.ATTENUATED_BY_TAINT),
                content = listOf(ContentBlock.Text("denied: ATTENUATED_BY_TAINT")), trustLevel = TrustLevel.TRUSTED,
            ),
        ))

        buildExecutor(driver, fakeRouter(adapter), broker).drive(runId)

        val snapshot = toolCallAttemptSnapshot(driver, "call-2")
        assertTrue(
            snapshot.contains("read-untrusted-file"),
            "the denial must name the tool call that actually tainted the Run, not a bare enum (RFC-0027): $snapshot",
        )
    }

    @Test
    fun `taint_source_node_id is recorded when the tainting result carries a content node reference`() = runBlocking {
        val driver = openDriver()
        val (_, _, runId) = createRun(driver)
        val adapter = fakeModel(listOf(toolCallResponse("search-knowledge"), endTurnResponse()))
        val broker = sequencedBroker(listOf(
            ToolCallResult(
                callId = "call-1", outcome = ToolOutcome.Ok,
                content = listOf(ContentBlock.ResourceRef(ContentNodeId("node-42"), sizeBytes = 128)),
                trustLevel = TrustLevel.UNTRUSTED,
            ),
        ))

        buildExecutor(driver, fakeRouter(adapter), broker).drive(runId)

        assertEquals("node-42", runTaintSourceNodeId(driver, runId))
    }

    @Test
    fun `taint_source_node_id stays null when the tainting result carries no content node`() = runBlocking {
        val driver = openDriver()
        val (_, _, runId) = createRun(driver)
        val adapter = fakeModel(listOf(toolCallResponse("read-file"), endTurnResponse()))

        buildExecutor(driver, fakeRouter(adapter), noOpBroker()).drive(runId)

        assertEquals(null, runTaintSourceNodeId(driver, runId), "Text-only content names no content node to record")
    }

    // ─── RFC-0008 step 8d: CAPABILITY_APPROVAL park/resume ─────────────────────

    private fun modelCallTaskState(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, runId: RunId): String =
        driver.executeQuery(null,
            "SELECT state FROM tasks WHERE run_id = ? AND kind = 'MODEL_CALL' ORDER BY ordinal DESC LIMIT 1",
            mapper = { c ->
                check(c.next().value) { "no MODEL_CALL task for run ${runId.value}" }
                app.cash.sqldelight.db.QueryResult.Value(c.getString(0)!!)
            }, 1
        ) { bindString(0, runId.value) }.value

    private fun continuationCount(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, runId: RunId): Long =
        driver.executeQuery(null, "SELECT COUNT(*) FROM continuations WHERE run_id = ?",
            mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) }, 1
        ) { bindString(0, runId.value) }.value

    private fun pendingApprovalRouter(candidate: ModelAdapter, reason: String = "ASK policy — approval required"): InferenceRouter =
        object : InferenceRouter {
            override suspend fun select(kind: ModelKind, context: RoutingContext) =
                RoutingDecision.RemotePendingApproval(candidate, reason)
        }

    @Test
    fun `RemotePendingApproval parks the run instead of failing it`() = runBlocking {
        val driver = openDriver()
        val (_, _, runId) = createRun(driver)
        val candidate = fakeModel(listOf(endTurnResponse()), isLocalAdapter = false, modelId = "remote-claude")

        buildExecutor(driver, pendingApprovalRouter(candidate), noOpBroker()).drive(runId)

        val (state, _) = runRow(driver, runId)
        assertEquals("YIELDED", state, "a Run awaiting approval yields — it does not fail (RFC-0008 step 8d)")
        assertEquals("AWAITING_APPROVAL", modelCallTaskState(driver, runId))
        assertEquals(1L, continuationCount(driver, runId), "a continuations row records what the Run is parked on")
    }

    @Test
    fun `approving a parked capability approval resumes the run using the named adapter`() = runBlocking {
        val driver = openDriver()
        val (_, _, runId) = createRun(driver)
        val requests = mutableListOf<ModelRequest>()
        val candidate = fakeModel(listOf(endTurnResponse("done")), requests, isLocalAdapter = false, modelId = "remote-claude")
        val executor = buildExecutor(
            driver, pendingApprovalRouter(candidate), noOpBroker(),
            resolveRemoteAdapter = { id -> candidate.takeIf { id == "remote-claude" } },
        )
        executor.drive(runId)
        assertEquals("YIELDED", runRow(driver, runId).first, "sanity: parked before resolving")
        assertEquals(0, requests.size, "the model must not be invoked while parked")

        val resolution = executor.resolveCapabilityApproval(runId, approved = true)

        assertIs<CapabilityApprovalResolution.Resumed>(resolution)
        assertEquals(1, requests.size, "approval resumes the same MODEL_CALL task, which now invokes the named adapter")
        val (state, _) = runRow(driver, runId)
        assertEquals("COMPLETED", state)
        assertEquals(0L, continuationCount(driver, runId), "the continuation is consumed once the resumed attempt runs")
    }

    @Test
    fun `denying a parked capability approval fails the run and task without invoking the model`() = runBlocking {
        val driver = openDriver()
        val (_, _, runId) = createRun(driver)
        val requests = mutableListOf<ModelRequest>()
        val candidate = fakeModel(listOf(endTurnResponse()), requests, isLocalAdapter = false, modelId = "remote-claude")
        val executor = buildExecutor(driver, pendingApprovalRouter(candidate), noOpBroker())
        executor.drive(runId)

        val resolution = executor.resolveCapabilityApproval(runId, approved = false, denialReason = "not right now")

        assertIs<CapabilityApprovalResolution.Denied>(resolution)
        assertEquals(0, requests.size, "a denial never re-drives the task — nothing to resume")
        val (state, _) = runRow(driver, runId)
        assertEquals("FAILED", state)
        assertEquals("FAILED", modelCallTaskState(driver, runId))
        assertEquals(0L, continuationCount(driver, runId), "the continuation is deleted on denial, not left dangling")
    }

    @Test
    fun `resolving a run with no pending continuation reports NotFound`() = runBlocking {
        val driver = openDriver()
        val (_, _, runId) = createRun(driver)
        val adapter = fakeModel(listOf(endTurnResponse()))
        val executor = buildExecutor(driver, fakeRouter(adapter), noOpBroker())
        executor.drive(runId) // completes normally, never parks

        val resolution = executor.resolveCapabilityApproval(runId, approved = true)

        assertTrue(resolution is CapabilityApprovalResolution.NotFound)
    }

    // ─── RFC-0008 step 8d: USER_PROMPT (`ask_user`) park/resume ────────────────

    private fun toolCallTaskState(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, runId: RunId): String =
        driver.executeQuery(null,
            "SELECT state FROM tasks WHERE run_id = ? AND kind = 'TOOL_CALL' ORDER BY ordinal DESC LIMIT 1",
            mapper = { c ->
                check(c.next().value) { "no TOOL_CALL task for run ${runId.value}" }
                app.cash.sqldelight.db.QueryResult.Value(c.getString(0)!!)
            }, 1
        ) { bindString(0, runId.value) }.value

    private fun toolCallOutcomeFor(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, runId: RunId, callId: String): String =
        driver.executeQuery(null, "SELECT outcome FROM tool_calls WHERE run_id = ? AND call_id = ?",
            mapper = { c ->
                check(c.next().value) { "no tool_calls row for call $callId" }
                app.cash.sqldelight.db.QueryResult.Value(c.getString(0)!!)
            }, 2
        ) { bindString(0, runId.value); bindString(1, callId) }.value

    @Test
    fun `the model calling ask_user parks the run instead of executing anything`() = runBlocking {
        val driver = openDriver()
        val (_, _, runId) = createRun(driver)
        val adapter = fakeModel(listOf(askUserResponse("Which database should I use?"), endTurnResponse()))

        buildExecutor(driver, fakeRouter(adapter), noOpBroker()).drive(runId)

        assertEquals("YIELDED", runRow(driver, runId).first, "a question parks the Run -- it is not answered by the model itself")
        assertEquals("AWAITING_INPUT", toolCallTaskState(driver, runId))
        assertEquals(1L, continuationCount(driver, runId))
    }

    @Test
    fun `answering a parked ask_user call resumes with the answer as the tool result`() = runBlocking {
        val driver = openDriver()
        val (_, _, runId) = createRun(driver)
        val requests = mutableListOf<ModelRequest>()
        val adapter = fakeModel(listOf(askUserResponse("Which database?", callId = "ask-1"), endTurnResponse("Using Postgres")), requests)
        val executor = buildExecutor(driver, fakeRouter(adapter), noOpBroker())
        executor.drive(runId)
        assertEquals("YIELDED", runRow(driver, runId).first, "sanity: parked before resolving")

        val resolution = executor.resolveUserPrompt(runId, answer = "Postgres")

        assertIs<CapabilityApprovalResolution.Resumed>(resolution)
        assertEquals("OK", toolCallOutcomeFor(driver, runId, "ask-1"))
        assertEquals("COMPLETED", runRow(driver, runId).first)
        assertEquals(0L, continuationCount(driver, runId), "the continuation is consumed once the resumed attempt runs")
        // The model's *next* turn must actually see the answer, not just a bare "OK" -- the whole
        // point of ask_user is that the reply becomes usable context.
        val secondRequestMessages = requests[1].messages
        assertTrue(secondRequestMessages.any { it is Turn.ToolResult && it.result.content.any { c -> c is ContentBlock.Text && c.text == "Postgres" } })
    }

    @Test
    fun `declining a parked ask_user call fails the run without executing anything`() = runBlocking {
        val driver = openDriver()
        val (_, _, runId) = createRun(driver)
        val adapter = fakeModel(listOf(askUserResponse("Which database?"), endTurnResponse()))
        val executor = buildExecutor(driver, fakeRouter(adapter), noOpBroker())
        executor.drive(runId)

        val resolution = executor.resolveUserPrompt(runId, answer = null, denialReason = "not now")

        assertIs<CapabilityApprovalResolution.Denied>(resolution)
        assertEquals("FAILED", runRow(driver, runId).first)
        assertEquals("FAILED", toolCallTaskState(driver, runId))
        assertEquals(0L, continuationCount(driver, runId))
    }
}
