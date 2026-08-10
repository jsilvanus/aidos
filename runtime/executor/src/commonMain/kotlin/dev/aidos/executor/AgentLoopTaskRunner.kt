package dev.aidos.executor

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.broker.AuditLog
import dev.aidos.kernel.AidosError
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ContentNodeId
import dev.aidos.kernel.DenialReason
import dev.aidos.kernel.EffectBroker
import dev.aidos.kernel.ErrorClass
import dev.aidos.kernel.ExecutionWindow
import dev.aidos.kernel.InferenceRouter
import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.Permission
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.ProviderRetention
import dev.aidos.kernel.RetentionPolicy
import dev.aidos.kernel.RoutingContext
import dev.aidos.kernel.RoutingDecision
import dev.aidos.kernel.RunId
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.Task
import dev.aidos.kernel.TaskId
import dev.aidos.kernel.TaskKind
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolCallResult
import dev.aidos.kernel.ToolChoice
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrainingUse
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.Turn
import dev.aidos.prompt.AssemblyRequest
import dev.aidos.prompt.AssemblyResult
import dev.aidos.prompt.InstructionDiscovery
import dev.aidos.prompt.InstructionSet
import dev.aidos.prompt.PromptAssembler
import java.io.File
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Effectively unbounded execution window — desktop profile and tests. Deliberately not the
 * `agentloop` module's own `UnboundedExecutionWindow`: `executor` has no reason to depend on
 * `agentloop` for one five-line object, and this bridge does not otherwise touch that module
 * (see this file's class doc for why `AgentLoop.kt` itself stays unbuilt-upon rather than reused).
 */
private object UnboundedWindow : ExecutionWindow {
    override fun remainingMillis(): Long? = null
    override fun permitsLocalInference(): Boolean = true
}

private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class StoredToolCall(val callId: String, val toolName: String, val argumentsJson: String)

@Serializable
private data class StoredModelResponse(
    val text: String?,
    val toolCalls: List<StoredToolCall>,
    val stopReason: String,
)

@Serializable
private data class StoredToolResult(
    val outcome: String, // OK | DENIED | FAILED | CANCELLED
    val detail: String?, // DenialReason name, or an error message — meaning depends on outcome
    val text: String, // concatenated ContentBlock.Text content (MVP: text-only replay, see below)
    val trustLevel: String,
)

/**
 * The AgentLoop↔executor bridge (RFC-0008, RFC-0009, RFC-0019): a [TaskRunner] that drives the
 * model-call loop one Task at a time instead of one suspend call at a time.
 *
 * **Why this exists instead of calling `agentloop.AgentLoop.run()`.** `AgentLoop.run()` holds the
 * entire multi-step transcript in a local `mutableListOf<Turn>()` across its whole `while` loop,
 * in one suspend call. That is exactly what RFC-0009 forbids for durable execution: "session logic
 * may not hold important state in local variables across a step boundary... anything that must
 * survive is a column." A crash midway through `AgentLoop.run()` loses every step taken so far,
 * with no row anywhere recording it happened. `AgentLoop.kt` remains a valid, self-contained,
 * non-durable loop (useful where durability doesn't matter — a quick eval, a future REPL); it is
 * simply the wrong shape to plug into `SqliteExecutor.drive()`, so this is new code built at the
 * step machine's actual grain, not a wrapper around it. The two share no code today beyond the
 * `kernel`/`prompt` types both are built from — flagged as a known, accepted duplication (a few
 * dozen lines: model resolution, the `TooBig` retry, termination conditions) rather than a forced
 * shared abstraction with only one real caller so far.
 *
 * **The mapping (RFC-0008's own table):**
 * - One step's model call → `Task(kind = MODEL_CALL)`, executed by [executeModelCall].
 * - One tool call from the response → `Task(kind = TOOL_CALL)`, executed by [executeToolCall].
 * - The `PRODUCED_CALL` edge between them → a `tool_calls` row (`model_task_id`/`tool_task_id`).
 * - The whole loop → the `Run`; termination is `drive()`'s own existing "no runnable tasks, all
 *   terminal → COMPLETED" path once a `MODEL_CALL` task appends nothing.
 *
 * **What durably replaces the in-memory transcript:** `attempts.output_snapshot`, one row per
 * `MODEL_CALL`/`TOOL_CALL` task (`attempt_number = 1` always — this bridge does not retry a
 * failed attempt in place; a task that fails outright fails its Run, matching `drive()`'s
 * existing behaviour). [reconstructHistory] rebuilds the `List<Turn>` the assembler needs from
 * those rows plus `tool_calls`, queried fresh on every `MODEL_CALL` task — there is no cache to
 * invalidate and nothing held across the call.
 *
 * **Deliberately not built here, flagged rather than silently absent:**
 * - **JSON Schema argument validation (RFC-0008 step 8b)** — every `tool_calls` row is written
 *   with `schema_valid = 1` unconditionally. Real validation needs a schema validator this
 *   codebase doesn't yet depend on; this bridge's job was the structural wiring, not that.
 * - ~~Capability resolution for a model's tool call (RFC-0008 step 8c)~~ **Built, M19**: see
 *   [resolveCapability] and [dev.aidos.daemon.CapabilityResolver]. `ToolCall.capabilityId` is
 *   resolved fresh in [executeToolCall], immediately before the call — the actual authority
 *   decision still lives entirely in `CapabilityManager.validate()`, called next by
 *   `ToolBroker.invoke()`; this bridge only looks up which existing grant to hand it.
 * - **The approval flow (RFC-0008 step 8d, `Task(kind = CAPABILITY_REQUEST)`, `AWAITING_APPROVAL`)**
 *   — a `RoutingDecision.RemotePendingApproval` fails the Run outright instead of parking it.
 *   Building the park/resume machinery (a `continuations` row, an event that un-parks it) is
 *   substantial and was ruled out of this link's scope deliberately, the same way RFC-0009's own
 *   MVP section defers `CHECKABLE` recovery probes.
 * - **Instruction adoption UX (RFC-0016)** — [discoverInstructionSet] reads `instruction_adoptions`
 *   but nothing in this codebase writes to it yet, so a freshly discovered `AGENTS.md`/`CLAUDE.md`
 *   is correctly excluded from the system turn (never adopted) and stays that way until some other
 *   part of the system inserts an adoption row; there is no session/UI flow here that could ever
 *   produce one. What *is* wired: discovery, the adopted/unadopted gate itself, and persisting
 *   `runs.instruction_set_hash` — M15's actual done-when.
 */
class AgentLoopTaskRunner(
    private val driver: SqlDriver,
    private val audit: AuditLog,
    private val idGen: () -> String,
    private val nowIso: () -> String,
    private val router: InferenceRouter,
    private val assembler: PromptAssembler,
    private val broker: EffectBroker,
    private val subjectId: String = "run",
    /**
     * RFC-0035: redacts known secret values out of anything durably persisted. Applied to
     * `attempts.output_snapshot` before it is written — a model's own text can echo back a
     * secret that appeared earlier in its context. Defaults to identity so callers with no
     * [dev.aidos.vault.Redactor] to inject (most tests) keep the pre-M14 behaviour.
     */
    private val redact: (String) -> String = { it },
    /**
     * RFC-0008 step 8c, M19: resolves a model-emitted [ToolCall] to a capability the subject
     * already holds for the tool's required permission — picking the most recently issued,
     * unexpired, unrevoked match; see [dev.aidos.daemon.CapabilityResolver]'s own doc comment
     * for why a resolver this simple is safe (it can only under-grant: `ToolBroker.invoke()`
     * calls `CapabilityManager.validate()` immediately after on whatever id this returns, and
     * that call remains the actual authority decision — scope, expiry, revocation, taint
     * attenuation). Defaults to always-null so callers with no [dev.aidos.kernel.CapabilityManager]
     * to inject (most tests) keep the pre-M19 behaviour of every tool call being denied.
     */
    private val resolveCapability: suspend (subjectId: String, permission: Permission) -> CapabilityId? = { _, _ -> null },
) : TaskRunner {

    override suspend fun execute(task: Task): TaskResult = when (task.kind) {
        TaskKind.MODEL_CALL -> executeModelCall(task)
        TaskKind.TOOL_CALL -> executeToolCall(task)
        else -> TaskResult(success = false, errorMessage = "AgentLoopTaskRunner does not handle ${task.kind}")
    }

    private suspend fun executeModelCall(task: Task): TaskResult {
        val run = loadRunContext(task.runId) ?: return TaskResult(false, "Run ${task.runId.value} not found")

        val routingCtx = RoutingContext(
            profile = run.platformProfile,
            networkAvailable = run.networkAvailable,
            budgetRemaining = null,
            runTaint = run.taintLevel,
            executionWindow = UnboundedWindow,
        )
        val decision = router.select(ModelKind.LLM, routingCtx)
        val adapter = when (decision) {
            is RoutingDecision.Local -> decision.adapter
            is RoutingDecision.RemoteApproved -> decision.adapter
            is RoutingDecision.RemotePendingApproval ->
                return TaskResult(false, "Remote approval required: ${decision.reason}")
            is RoutingDecision.UnavailableOffline ->
                return TaskResult(false, "Model unavailable offline: ${decision.kind}")
            else -> return TaskResult(false, "Routing failed: $decision")
        }

        val tools = broker.descriptorsFor(subjectId, run.platformProfile, run.networkAvailable)
        val history = reconstructHistory(task.runId, task.ordinal)
        val instructionSet = discoverInstructionSet(run.projectId)
        val assemblyReq = AssemblyRequest(
            model = adapter,
            userMessage = run.userMessageSummary,
            tools = tools,
            conversationHistory = history,
            instructionSet = instructionSet,
        )
        val pkg = when (val ar = assembler.assemble(assemblyReq)) {
            is AssemblyResult.Ok -> ar.pkg
            is AssemblyResult.TooBig -> {
                // One re-selection with minimumContextWindow — bounded, not a loop (D22), same
                // as AgentLoop.kt's Phase 2.
                val larger = router.select(
                    ModelKind.LLM,
                    routingCtx.copy(minimumContextWindow = ar.minimumContextWindow),
                )
                val largerAdapter = when (larger) {
                    is RoutingDecision.Local -> larger.adapter
                    is RoutingDecision.RemoteApproved -> larger.adapter
                    else -> return TaskResult(false, "No model with context window >= ${ar.minimumContextWindow}")
                }
                when (val ar2 = assembler.assemble(assemblyReq.copy(model = largerAdapter))) {
                    is AssemblyResult.Ok -> ar2.pkg
                    is AssemblyResult.TooBig -> return TaskResult(false, "Prompt does not fit even in larger context window")
                }
            }
        }
        // RFC-0016: "records which set governed the Run" -- written on every MODEL_CALL, not just
        // the first, so a Run that spans a mid-run instruction-file edit reflects the set that
        // actually steered its most recent turn, not a stale first-turn value.
        updateInstructionSetHash(task.runId, pkg.instructionSetHash)

        val modelReq = ModelRequest(
            messages = pkg.request.messages,
            tools = tools,
            toolChoice = ToolChoice.Auto,
            maxOutputTokens = pkg.model.contextWindow / 8,
        )
        val response = adapter.invoke(modelReq).getOrElse {
            return TaskResult(false, "Model invocation failed: ${it.message}")
        }

        // RFC-0008 checkpoint 6: "the model response is the most costly thing to reproduce."
        // Persisting it here is what makes the *next* MODEL_CALL task's history reconstruction
        // possible without holding the transcript in memory (D3) — there is no other copy.
        writeAttempt(
            task = task,
            projectId = run.projectId,
            recoveryClass = "PURE",
            modelProvider = adapter.providerId,
            modelVersion = adapter.modelVersion,
            tokensInput = response.usage.inputTokens,
            tokensOutput = response.usage.outputTokens,
            outputSnapshot = json.encodeToString(
                StoredModelResponse(
                    text = response.text,
                    toolCalls = response.toolCalls.map {
                        StoredToolCall(it.callId, it.toolName, it.arguments.toString())
                    },
                    stopReason = response.stopReason.name,
                )
            ),
            // RFC-0026: never assumed-benign. A remote adapter that reports no policy at all
            // (the interface's own `null` default) is UNKNOWN, not treated as ZERO/local-shaped.
            providerRetention = if (adapter.isLocal) null else adapter.providerRetention
                ?: ProviderRetention(
                    policy = RetentionPolicy.UNKNOWN,
                    statedDurationDays = null,
                    trainingUse = TrainingUse.UNSPECIFIED,
                    recordedAt = Instant.parse(nowIso()),
                ),
        )

        // Termination (RFC-0008 "Termination" table): no tool calls, or a terminal stop reason.
        // Appending nothing here is the whole mechanism — drive()'s existing "no runnable tasks,
        // all terminal → COMPLETED" path takes it from there. There is no agentloop-specific
        // "mark the Run done" step to add.
        if (response.toolCalls.isEmpty() ||
            response.stopReason in setOf(StopReason.END_TURN, StopReason.STOP_SEQUENCE, StopReason.REFUSAL)
        ) {
            return TaskResult(success = true)
        }

        // No-progress guard (RFC-0008): the same tool call, identically, three times
        // consecutively. Derived from durable rows each time, not an in-memory counter (D3) —
        // AgentLoop.kt's own `consecutiveCalls` list is exactly the pattern this bridge cannot use.
        if (isNoProgress(task.runId, task.ordinal, response.toolCalls)) {
            return TaskResult(false, "Loop detected: same tool call repeated 3 times")
        }

        // Fan-out: one TOOL_CALL Task per ToolCall. drive()'s own loop dispatches them strictly
        // in ordinal order, one at a time, which is RFC-0008's "Ordering and parallelism"
        // requirement (sequential, in emission order) for free — no extra sequencing needed here.
        // The tool_calls row for each spec is written via afterInsert, not eagerly here: its
        // tool_task_id is a foreign key onto a task row that doesn't exist yet (the id is minted
        // now, but the INSERT happens inside appendTasks's transaction, after execute() returns).
        val specs = response.toolCalls.map { call ->
            val toolTaskId = idGen()
            NewTaskSpec(
                id = toolTaskId,
                kind = TaskKind.TOOL_CALL,
                description = call.toolName,
                toolName = call.toolName,
                afterInsert = {
                    insertToolCallRow(
                        callId = call.callId,
                        runId = task.runId,
                        modelTaskId = task.id,
                        toolTaskId = toolTaskId,
                        toolName = call.toolName,
                        argumentsJson = call.arguments.toString(),
                        stepIndex = task.ordinal,
                    )
                },
            )
        }
        return TaskResult(success = true, appendTasks = specs)
    }

    private suspend fun executeToolCall(task: Task): TaskResult {
        val callRow = loadToolCallForTask(task.id)
            ?: return TaskResult(false, "No tool_calls row for task ${task.id.value}")
        val run = loadRunContext(task.runId) ?: return TaskResult(false, "Run ${task.runId.value} not found")

        // RFC-0008 step 8c, M19: resolved fresh here, immediately before the call it gates --
        // not carried from executeModelCall's fan-out -- so a capability revoked in between the
        // model turn and this step is never used (D3: nothing security-relevant held across a
        // step boundary). A tool no longer in the current catalog (descriptor == null) resolves
        // to no capability, same as today's unconditional null -- ToolBroker denies it either way.
        val descriptor = broker.descriptorsFor(subjectId, run.platformProfile, run.networkAvailable)
            .firstOrNull { it.name == callRow.toolName }
        val capabilityId = descriptor?.let { resolveCapability(subjectId, it.requiredPermission) }

        val call = ToolCall(
            callId = callRow.callId,
            toolName = callRow.toolName,
            arguments = parseJsonObject(callRow.argumentsJson),
            capabilityId = capabilityId,
        )
        // Denied/Failed outcomes are data returned to the model (RFC-0008 Security #3), not a
        // reason to fail this Task — only an actual runner-level problem (missing row, no Run)
        // does that. This matches AgentLoop.kt's own handling of the same broker call.
        val result = broker.invoke(subjectId, call, run.taintLevel)

        // Taint is monotonic within a Run (RFC-0027); persisted immediately so the next
        // MODEL_CALL task's routing context reads it correctly even after a restart.
        val newTaint = run.taintLevel raisedBy result.trustLevel
        if (newTaint != run.taintLevel) {
            updateRunTaint(task.runId, newTaint)
            if (run.taintLevel == TrustLevel.TRUSTED) {
                // RFC-0027 Data Model: "first node that raised the taint" -- written once, at the
                // moment the Run first leaves TRUSTED, and only when the tainting result actually
                // names a content node. No production tool returns ContentBlock.ResourceRef today
                // (FilesystemTool/GitTool are Text-only), so this stays null for them -- honest
                // absence, not a silent no-op; see this class's own doc comment for the gap.
                result.content.filterIsInstance<ContentBlock.ResourceRef>().firstOrNull()?.let {
                    updateTaintSourceNode(task.runId, it.nodeId)
                }
            }
        }

        val outcomeStr = when (result.outcome) {
            is ToolOutcome.Ok -> "OK"
            is ToolOutcome.Denied -> "DENIED"
            is ToolOutcome.Failed -> "FAILED"
            is ToolOutcome.Cancelled -> "CANCELLED"
        }
        val detail = when (val outcome = result.outcome) {
            is ToolOutcome.Denied -> outcome.reason.name
            is ToolOutcome.Failed -> outcome.error.message
            else -> null
        }
        val rawText = result.content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
        // RFC-0027 "Escalation": a taint-attenuated denial must name which untrusted content
        // caused it, not just report that the Run is tainted -- "the prompt is unanswerable [to a
        // human approver] and the user will click through it" otherwise. Best available source
        // today is the tool operation that first raised the taint (durable data, always present);
        // a real content node (path, not just an operation name) is used instead once one exists.
        val deniedOutcome = result.outcome as? ToolOutcome.Denied
        val text = if (deniedOutcome != null && deniedOutcome.reason == DenialReason.ATTENUATED_BY_TAINT) {
            rawText + " (Run is tainted by: ${taintSourceDescription(task.runId)})"
        } else {
            rawText
        }

        updateToolCallOutcome(callRow.callId, outcomeStr)
        writeAttempt(
            task = task,
            projectId = run.projectId,
            recoveryClass = "IDEMPOTENT",
            modelProvider = null,
            modelVersion = null,
            tokensInput = null,
            tokensOutput = null,
            outputSnapshot = json.encodeToString(StoredToolResult(outcomeStr, detail, text, newTaint.name)),
        )

        // Fan-in: once every sibling TOOL_CALL task for this model turn is terminal, append the
        // next MODEL_CALL task so the model sees the results (RFC-0008 steps 9-10: append
        // results, increment step, go to 1). Until then this task appends nothing — the next
        // sibling in ordinal order runs next via drive()'s own loop, and whichever one is last
        // is the one that triggers this.
        return if (allSiblingToolCallsTerminal(callRow.modelTaskId, task.id)) {
            TaskResult(success = true, appendTasks = listOf(
                NewTaskSpec(id = idGen(), kind = TaskKind.MODEL_CALL, description = "Model call"),
            ))
        } else {
            TaskResult(success = true)
        }
    }

    // ─── No-progress detection ───────────────────────────────────────────────

    private fun isNoProgress(runId: RunId, beforeOrdinal: Int, currentCalls: List<ToolCall>): Boolean {
        val currentKey = callKey(currentCalls.map { it.toolName to it.arguments.toString() })
        val priorKeys = previousModelCallKeys(runId, beforeOrdinal, limit = 2)
        return priorKeys.size == 2 && priorKeys.all { it == currentKey }
    }

    private fun callKey(calls: List<Pair<String, String>>): String =
        calls.joinToString("|") { (name, args) -> "$name:$args" }

    private fun previousModelCallKeys(runId: RunId, beforeOrdinal: Int, limit: Int): List<String> {
        val keys = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT a.output_snapshot FROM tasks t JOIN attempts a ON a.task_id = t.id AND a.attempt_number = 1 " +
                "WHERE t.run_id = ? AND t.kind = 'MODEL_CALL' AND t.ordinal < ? ORDER BY t.ordinal DESC LIMIT ?",
            mapper = { c ->
                while (c.next().value) {
                    val snapshot = c.getString(0) ?: continue
                    val resp = json.decodeFromString<StoredModelResponse>(snapshot)
                    keys.add(callKey(resp.toolCalls.map { it.toolName to it.argumentsJson }))
                }
                QueryResult.Value(Unit)
            },
            parameters = 3,
        ) {
            bindString(0, runId.value)
            bindLong(1, beforeOrdinal.toLong())
            bindLong(2, limit.toLong())
        }
        return keys
    }

    // ─── Transcript reconstruction ───────────────────────────────────────────

    /**
     * Rebuilds the `Assistant`/`ToolResult` turns for ordinals `< beforeOrdinal` from
     * `attempts.output_snapshot` and `tool_calls`. The user turn and system turn are not part of
     * this: [PromptAssembler] adds them fresh from [AssemblyRequest.userMessage] on every call,
     * so only the *history* the assembler doesn't already know how to build belongs here.
     */
    private fun reconstructHistory(runId: RunId, beforeOrdinal: Int): List<Turn> {
        val turns = mutableListOf<Turn>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT t.kind, a.output_snapshot, tc.call_id FROM tasks t " +
                "LEFT JOIN attempts a ON a.task_id = t.id AND a.attempt_number = 1 " +
                "LEFT JOIN tool_calls tc ON tc.tool_task_id = t.id " +
                "WHERE t.run_id = ? AND t.ordinal < ? AND t.kind IN ('MODEL_CALL','TOOL_CALL') " +
                "ORDER BY t.ordinal ASC",
            mapper = { c ->
                while (c.next().value) {
                    val kind = c.getString(0)!!
                    val snapshot = c.getString(1) ?: continue
                    when (kind) {
                        "MODEL_CALL" -> {
                            val resp = json.decodeFromString<StoredModelResponse>(snapshot)
                            turns.add(
                                Turn.Assistant(
                                    text = resp.text,
                                    toolCalls = resp.toolCalls.map {
                                        ToolCall(
                                            callId = it.callId,
                                            toolName = it.toolName,
                                            arguments = parseJsonObject(it.argumentsJson),
                                            capabilityId = null,
                                        )
                                    },
                                )
                            )
                        }
                        "TOOL_CALL" -> {
                            val tr = json.decodeFromString<StoredToolResult>(snapshot)
                            val callId = c.getString(2) ?: ""
                            turns.add(
                                Turn.ToolResult(
                                    result = ToolCallResult(
                                        callId = callId,
                                        outcome = storedOutcome(tr),
                                        content = listOf(ContentBlock.Text(tr.text)),
                                        trustLevel = TrustLevel.valueOf(tr.trustLevel),
                                    )
                                )
                            )
                        }
                    }
                }
                QueryResult.Value(Unit)
            },
            parameters = 2,
        ) {
            bindString(0, runId.value)
            bindLong(1, beforeOrdinal.toLong())
        }
        return turns
    }

    private fun storedOutcome(tr: StoredToolResult): ToolOutcome = when (tr.outcome) {
        "OK" -> ToolOutcome.Ok
        "DENIED" -> ToolOutcome.Denied(
            runCatching { DenialReason.valueOf(tr.detail ?: "") }.getOrDefault(DenialReason.NO_CAPABILITY)
        )
        "CANCELLED" -> ToolOutcome.Cancelled
        else -> ToolOutcome.Failed(AidosError(code = "tool.error", errorClass = ErrorClass.TRANSIENT, message = tr.detail ?: "unknown"))
    }

    private fun parseJsonObject(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    // ─── Row access ──────────────────────────────────────────────────────────

    private data class RunContext(
        val projectId: ProjectId,
        val platformProfile: PlatformProfile,
        val networkAvailable: Boolean,
        val taintLevel: TrustLevel,
        val userMessageSummary: String,
    )

    private fun loadRunContext(runId: RunId): RunContext? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT project_id, platform_profile, network_available, taint_level, user_message_summary " +
                "FROM runs WHERE id = ?",
            mapper = { c ->
                QueryResult.Value(
                    if (c.next().value) RunContext(
                        projectId = ProjectId(c.getString(0)!!),
                        platformProfile = PlatformProfile.valueOf(c.getString(1)!!),
                        networkAvailable = (c.getLong(2) ?: 0L) != 0L,
                        taintLevel = TrustLevel.valueOf(c.getString(3)!!),
                        userMessageSummary = c.getString(4) ?: "",
                    ) else null
                )
            },
            parameters = 1,
        ) { bindString(0, runId.value) }.value

    private fun updateRunTaint(runId: RunId, taint: TrustLevel) {
        driver.execute(
            identifier = null,
            sql = "UPDATE runs SET taint_level = ? WHERE id = ?",
            parameters = 2,
        ) {
            bindString(0, taint.name)
            bindString(1, runId.value)
        }
    }

    private fun updateTaintSourceNode(runId: RunId, nodeId: ContentNodeId) {
        driver.execute(
            identifier = null,
            sql = "UPDATE runs SET taint_source_node_id = ? WHERE id = ?",
            parameters = 2,
        ) {
            bindString(0, nodeId.value)
            bindString(1, runId.value)
        }
    }

    /**
     * RFC-0027 "Escalation": which tool call first raised this Run's taint, read back from
     * durable rows (no in-memory tracking across the Run — D3). `writeAttempt` stores the *Run's*
     * taint level as of each TOOL_CALL, not that call's own result, so the earliest row whose
     * stored taint differs from `TRUSTED` is exactly the call that caused the transition — every
     * row before it was written while the Run was still `TRUSTED`.
     */
    private fun taintSourceDescription(runId: RunId): String =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT tc.tool_name, a.output_snapshot FROM tool_calls tc " +
                "JOIN attempts a ON a.task_id = tc.tool_task_id AND a.attempt_number = 1 " +
                "WHERE tc.run_id = ? ORDER BY tc.step_index ASC",
            mapper = { c ->
                var found: String? = null
                while (found == null && c.next().value) {
                    val toolName = c.getString(0) ?: continue
                    val snapshot = c.getString(1) ?: continue
                    val stored = runCatching { json.decodeFromString<StoredToolResult>(snapshot) }.getOrNull()
                    if (stored != null && stored.trustLevel != TrustLevel.TRUSTED.name) found = toolName
                }
                QueryResult.Value(found)
            },
            parameters = 1,
        ) { bindString(0, runId.value) }.value ?: "unknown tool call"

    /**
     * RFC-0016: discovers AGENTS.md/CLAUDE.md at the project root and marks the set adopted iff
     * its hash has an `instruction_adoptions` row for this project -- an unadopted set is still
     * returned (so its hash can be recorded), but [PromptAssembler] excludes an unadopted set's
     * text from the system turn, which is the actual security property this gates.
     */
    private fun discoverInstructionSet(projectId: ProjectId): InstructionSet? {
        val rootPath = projectRootPath(projectId.value) ?: return null
        val discovered = InstructionDiscovery.discover(File(rootPath)) ?: return null
        return discovered.copy(adopted = isInstructionSetAdopted(projectId.value, discovered.hash))
    }

    private fun projectRootPath(projectId: String): String? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT root_path FROM projects WHERE id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) },
            parameters = 1,
        ) { bindString(0, projectId) }.value

    private fun isInstructionSetAdopted(projectId: String, hash: String): Boolean =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT 1 FROM instruction_adoptions WHERE project_id = ? AND set_hash = ?",
            mapper = { c -> QueryResult.Value(c.next().value) },
            parameters = 2,
        ) { bindString(0, projectId); bindString(1, hash) }.value

    private fun updateInstructionSetHash(runId: RunId, hash: String?) {
        driver.execute(
            identifier = null,
            sql = "UPDATE runs SET instruction_set_hash = ? WHERE id = ?",
            parameters = 2,
        ) {
            bindString(0, hash)
            bindString(1, runId.value)
        }
    }

    private fun writeAttempt(
        task: Task,
        projectId: ProjectId,
        recoveryClass: String,
        modelProvider: String?,
        modelVersion: String?,
        tokensInput: Int?,
        tokensOutput: Int?,
        outputSnapshot: String,
        providerRetention: ProviderRetention? = null,
    ) {
        val auditId = idGen()
        audit.write(
            id = auditId,
            projectId = projectId.value,
            kind = if (task.kind == TaskKind.MODEL_CALL) "ModelCallCompleted" else "ToolCallCompleted",
            actorKind = "SESSION",
            actorId = task.sessionId.value,
            subjectRef = task.toolName ?: task.kind.name,
            nowIso = nowIso(),
        )
        // RFC-0035: redact before this row is durably written -- output_snapshot is one of
        // RFC-0035's own listed redaction boundaries, and unlike the vault this is text the
        // model produced, so the vault's own register-on-load never sees it.
        val redactedSnapshot = redact(outputSnapshot)
        // RFC-0026: recordedAt is stamped fresh here, at write time -- see ProviderRetention's
        // own doc comment for why the adapter-level value isn't reused as-is.
        val retentionJson = providerRetention
            ?.copy(recordedAt = Instant.parse(nowIso()))
            ?.let { json.encodeToString(it) }
        driver.execute(
            identifier = null,
            sql = "INSERT INTO attempts (id, task_id, attempt_number, started_at, ended_at, state, " +
                "output_snapshot, model_provider, model_version, provider_retention_json, tokens_input, " +
                "tokens_output, recovery_class, audit_ref) VALUES (?, ?, 1, ?, ?, 'COMPLETED', ?, ?, ?, ?, ?, ?, ?, ?)",
            parameters = 12,
        ) {
            bindString(0, idGen())
            bindString(1, task.id.value)
            bindString(2, nowIso())
            bindString(3, nowIso())
            bindString(4, redactedSnapshot)
            bindString(5, modelProvider)
            bindString(6, modelVersion)
            bindString(7, retentionJson)
            tokensInput?.let { bindLong(8, it.toLong()) } ?: bindString(8, null)
            tokensOutput?.let { bindLong(9, it.toLong()) } ?: bindString(9, null)
            bindString(10, recoveryClass)
            bindString(11, auditId)
        }
    }

    private data class ToolCallRow(val callId: String, val modelTaskId: TaskId, val toolName: String, val argumentsJson: String)

    private fun loadToolCallForTask(taskId: TaskId): ToolCallRow? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT call_id, model_task_id, tool_name, arguments_json FROM tool_calls WHERE tool_task_id = ?",
            mapper = { c ->
                QueryResult.Value(
                    if (c.next().value) ToolCallRow(
                        callId = c.getString(0)!!,
                        modelTaskId = TaskId(c.getString(1)!!),
                        toolName = c.getString(2)!!,
                        argumentsJson = c.getString(3)!!,
                    ) else null
                )
            },
            parameters = 1,
        ) { bindString(0, taskId.value) }.value

    private fun insertToolCallRow(
        callId: String,
        runId: RunId,
        modelTaskId: TaskId,
        toolTaskId: String,
        toolName: String,
        argumentsJson: String,
        stepIndex: Int,
    ) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO tool_calls (call_id, run_id, model_task_id, tool_task_id, tool_name, " +
                "arguments_json, schema_valid, outcome, step_index) VALUES (?, ?, ?, ?, ?, ?, 1, 'PENDING', ?)",
            parameters = 7,
        ) {
            bindString(0, callId)
            bindString(1, runId.value)
            bindString(2, modelTaskId.value)
            bindString(3, toolTaskId)
            bindString(4, toolName)
            bindString(5, argumentsJson)
            bindLong(6, stepIndex.toLong())
        }
    }

    private fun updateToolCallOutcome(callId: String, outcome: String) {
        driver.execute(
            identifier = null,
            sql = "UPDATE tool_calls SET outcome = ? WHERE call_id = ?",
            parameters = 2,
        ) {
            bindString(0, outcome)
            bindString(1, callId)
        }
    }

    private fun allSiblingToolCallsTerminal(modelTaskId: TaskId, excludingTaskId: TaskId): Boolean {
        val nonTerminal = driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM tool_calls tc JOIN tasks t ON t.id = tc.tool_task_id " +
                "WHERE tc.model_task_id = ? AND tc.tool_task_id != ? " +
                "AND t.state NOT IN ('COMPLETED','FAILED','CANCELLED','SKIPPED')",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) },
            parameters = 2,
        ) {
            bindString(0, modelTaskId.value)
            bindString(1, excludingTaskId.value)
        }.value
        return nonTerminal == 0L
    }
}
