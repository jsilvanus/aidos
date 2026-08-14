package dev.aidos.agentloop

import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.InferenceRouter
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.RoutingContext
import dev.aidos.kernel.RoutingDecision
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.EffectBroker
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolCallResult
import dev.aidos.kernel.ToolChoice
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.Turn
import dev.aidos.prompt.AssemblyRequest
import dev.aidos.prompt.AssemblyResult
import dev.aidos.prompt.InstructionSet
import dev.aidos.prompt.PromptAssembler
import dev.aidos.kernel.ContextItem
import dev.aidos.kernel.ToolDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ExecutionWindow

/** An unbounded execution window for tests and desktop use. */
object UnboundedExecutionWindow : ExecutionWindow {
    override fun remainingMillis(): Long? = null
    override fun permitsLocalInference(): Boolean = true
}

/**
 * Agent loop for a single Run (RFC-0008, M16).
 *
 * One iteration of the loop is a **step**. Each step boundary is a checkpoint (RFC-0009).
 * The loop terminates when the model returns END_TURN, STOP_SEQUENCE, or REFUSAL; when
 * [maxSteps] is reached; or when the same tool call is emitted three consecutive times.
 *
 * Taint is monotonic within a Run (RFC-0027): once a tool result arrives it is UNTRUSTED and
 * the Run's taint level never decreases. A tainted Run that requests egress is denied;
 * the error names the specific untrusted content (D6, D7).
 *
 * The model never confirms its own success — the execution layer observes outcomes directly.
 * Every step appends to the [transcript]; the caller reads outcomes from the transcript.
 */
class AgentLoop(
    private val router: InferenceRouter,
    private val assembler: PromptAssembler,
    private val broker: EffectBroker,
    /** Checkpointing callback — called at the start and end of each step. */
    private val checkpoint: suspend (StepEvent) -> Unit = {},
) {
    /** Maximum steps per Run (RFC-0008). Hard ceiling, not advisory. */
    val maxSteps: Int = 24

    /** Run a single step sequence, returning the completed [RunOutcome]. */
    suspend fun run(request: RunRequest): RunOutcome {
        // ── Phase 1: select model (RFC-0020, RFC-0025) ────────────────────────
        val routingCtx = RoutingContext(
            profile = request.profile,
            networkAvailable = request.networkAvailable,
            budgetRemaining = null,
            runTaint = TrustLevel.TRUSTED,
            executionWindow = request.executionWindow,
        )
        val initialDecision = router.select(request.modelKind, routingCtx)
        val adapter = when (initialDecision) {
            is RoutingDecision.Local -> initialDecision.adapter
            is RoutingDecision.RemoteApproved -> initialDecision.adapter
            is RoutingDecision.RemotePendingApproval ->
                return RunOutcome.Suspended("Remote approval required: ${initialDecision.reason}")
            is RoutingDecision.UnavailableOffline ->
                return RunOutcome.Suspended("Model unavailable offline: ${initialDecision.kind}")
            else -> return RunOutcome.Failed("Routing failed: $initialDecision")
        }

        // ── Phase 2: assemble prompt (RFC-0025) ──────────────────────────────
        val assemblyReq = AssemblyRequest(
            model = adapter,
            userMessage = request.userMessage,
            tools = request.tools,
            conversationHistory = emptyList(),
            knowledgeContext = request.knowledgeContext,
            instructionSet = request.instructionSet,
        )
        val pkg = when (val ar = assembler.assemble(assemblyReq)) {
            is AssemblyResult.Ok -> ar.pkg
            is AssemblyResult.TooBig -> {
                // One re-selection with minimumContextWindow — bounded, not a loop (D22).
                val larger = router.select(
                    request.modelKind,
                    routingCtx.copy(minimumContextWindow = ar.minimumContextWindow),
                )
                val largerAdapter = when (larger) {
                    is RoutingDecision.Local -> larger.adapter
                    is RoutingDecision.RemoteApproved -> larger.adapter
                    else -> return RunOutcome.Failed("No model with context window >= ${ar.minimumContextWindow}")
                }
                val ar2 = assembler.assemble(assemblyReq.copy(model = largerAdapter))
                when (ar2) {
                    is AssemblyResult.Ok -> ar2.pkg
                    is AssemblyResult.TooBig -> return RunOutcome.Failed("Prompt does not fit even in larger context window")
                }
            }
        }

        // ── Step loop ─────────────────────────────────────────────────────────
        val transcript = mutableListOf<Turn>()
        transcript.addAll(pkg.request.messages)  // system + history + user

        var runTaint = TrustLevel.TRUSTED
        var taintSourceDescription: String? = null
        var steps = 0
        val consecutiveCalls = mutableListOf<String>()  // dedup key: name+args hash

        checkpoint(StepEvent.RunStarted(instructionSetHash = pkg.instructionSetHash))

        while (steps < maxSteps) {
            steps++
            checkpoint(StepEvent.StepStarted(step = steps))

            // Invoke model.
            val modelReq = ModelRequest(
                messages = transcript.toList(),
                tools = request.tools,
                toolChoice = ToolChoice.Auto,
                maxOutputTokens = adapter.contextWindow / 8,
            )
            val response: ModelResponse = adapter.invoke(modelReq).getOrElse { err ->
                checkpoint(StepEvent.StepFailed(step = steps, reason = err.message ?: "model error"))
                return RunOutcome.Failed("Model invocation failed: ${err.message}")
            }

            // Append assistant turn to transcript.
            val assistantTurn = Turn.Assistant(text = response.text, toolCalls = response.toolCalls)
            transcript.add(assistantTurn)

            // Check termination: stop reason.
            if (response.stopReason in setOf(StopReason.END_TURN, StopReason.STOP_SEQUENCE, StopReason.REFUSAL)) {
                checkpoint(StepEvent.RunCompleted(step = steps, taint = runTaint))
                return RunOutcome.Completed(
                    transcript = transcript.toList(),
                    steps = steps,
                    runTaint = runTaint,
                    taintSourceDescription = taintSourceDescription,
                    instructionSetHash = pkg.instructionSetHash,
                )
            }

            // Check termination: no tool calls and model returned.
            if (response.toolCalls.isEmpty()) {
                checkpoint(StepEvent.RunCompleted(step = steps, taint = runTaint))
                return RunOutcome.Completed(
                    transcript = transcript.toList(),
                    steps = steps,
                    runTaint = runTaint,
                    taintSourceDescription = taintSourceDescription,
                    instructionSetHash = pkg.instructionSetHash,
                )
            }

            // Check repetition: same tool call three consecutive times.
            val callKey = response.toolCalls.joinToString("|") { "${it.toolName}:${it.arguments}" }
            if (consecutiveCalls.size >= 3 && consecutiveCalls.takeLast(3).all { it == callKey }) {
                checkpoint(StepEvent.RunFailed(step = steps, reason = "Repeated identical tool call"))
                return RunOutcome.Failed("Loop detected: same tool call repeated 3 times")
            }
            consecutiveCalls.add(callKey)

            // Execute tool calls — taint propagates monotonically.
            for (toolCall in response.toolCalls) {
                val result: ToolCallResult = broker.invoke(
                    subjectId = "run",
                    call = toolCall,
                    runTaint = runTaint,
                )

                // Taint is monotonic: UNTRUSTED once, always UNTRUSTED (RFC-0027).
                val newTaint = runTaint raisedBy result.trustLevel
                if (newTaint != runTaint) {
                    runTaint = newTaint
                    taintSourceDescription = toolCall.toolName
                }

                // Tainted Run requesting egress is denied at tool execution time (the broker
                // enforces this). Here we surface the denial clearly.
                if (result.outcome is dev.aidos.kernel.ToolOutcome.Denied) {
                    val denial = result.outcome as dev.aidos.kernel.ToolOutcome.Denied
                    val reason = denial.reason
                    val taintDesc = if (runTaint != TrustLevel.TRUSTED) {
                        " (Run is tainted by: $taintSourceDescription)"
                    } else ""
                    transcript.add(Turn.ToolResult(result = result.copy(
                        content = listOf(ContentBlock.Text(
                            "Denied: ${reason}$taintDesc"
                        ))
                    )))
                } else {
                    transcript.add(Turn.ToolResult(result = result))
                }
            }

            checkpoint(StepEvent.StepCompleted(step = steps, taint = runTaint))
        }

        // maxSteps reached.
        checkpoint(StepEvent.RunFailed(step = steps, reason = "Step limit reached"))
        return RunOutcome.Failed("Step limit of $maxSteps reached")
    }
}

/**
 * Input for a single Run (RFC-0008, M16).
 */
data class RunRequest(
    val userMessage: String,
    val modelKind: ModelKind = ModelKind.LLM,
    val profile: PlatformProfile = PlatformProfile.DESKTOP,
    val networkAvailable: Boolean = false,
    val executionWindow: ExecutionWindow = UnboundedExecutionWindow,
    val tools: List<ToolDescriptor> = emptyList(),
    val knowledgeContext: List<ContextItem> = emptyList(),
    val instructionSet: InstructionSet? = null,
)

sealed interface RunOutcome {
    data class Completed(
        val transcript: List<Turn>,
        val steps: Int,
        val runTaint: TrustLevel,
        val taintSourceDescription: String?,
        val instructionSetHash: String?,
    ) : RunOutcome

    data class Failed(val reason: String) : RunOutcome
    data class Suspended(val reason: String) : RunOutcome
}

sealed interface StepEvent {
    data class RunStarted(val instructionSetHash: String?) : StepEvent
    data class StepStarted(val step: Int) : StepEvent
    data class StepCompleted(val step: Int, val taint: TrustLevel) : StepEvent
    data class StepFailed(val step: Int, val reason: String) : StepEvent
    data class RunCompleted(val step: Int, val taint: TrustLevel) : StepEvent
    data class RunFailed(val step: Int, val reason: String) : StepEvent
}
