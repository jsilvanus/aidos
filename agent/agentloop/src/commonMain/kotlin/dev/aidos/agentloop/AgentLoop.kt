package dev.aidos.agentloop

import dev.aidos.kernel.Budget
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ContextItem
import dev.aidos.kernel.EffectBroker
import dev.aidos.kernel.ExecutionWindow
import dev.aidos.kernel.InferenceRouter
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.RoutingContext
import dev.aidos.kernel.RoutingDecision
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TensorOutput
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.ToolCallOutput
import dev.aidos.kernel.ToolCallResult
import dev.aidos.kernel.ToolChoice
import dev.aidos.kernel.ToolDescriptor
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.Turn
import dev.aidos.prompt.AssemblyRequest
import dev.aidos.prompt.AssemblyResult
import dev.aidos.prompt.InstructionSet
import dev.aidos.prompt.PromptAssembler

object UnboundedExecutionWindow : ExecutionWindow {
    override fun remainingMillis(): Long? = null
    override fun permitsLocalInference(): Boolean = true
}

class AgentLoop(
    private val router: InferenceRouter,
    private val assembler: PromptAssembler,
    private val broker: EffectBroker,
    private val checkpoint: suspend (StepEvent) -> Unit = {},
) {
    val maxSteps: Int = 24

    private fun buildRoutingContext(
        request: RunRequest,
        budgetUsed: Long,
        runTaint: TrustLevel,
        minimumContextWindow: Int? = null,
    ): RoutingContext {
        val remainingBudget = request.budgetRemaining?.let { limit ->
            Budget(costUnits = (limit - budgetUsed).coerceAtLeast(0L))
        }
        return RoutingContext(
            profile = request.profile,
            networkAvailable = request.networkAvailable,
            budgetRemaining = remainingBudget,
            runTaint = runTaint,
            minimumContextWindow = minimumContextWindow,
            executionWindow = request.executionWindow,
        )
    }

    private fun responseUsageCost(response: ModelResponse): Long {
        val usage = response.usage
        if (usage != null) {
            val total = usage.totalTokens
            if (total != null && (total > 0 || (usage.inputTokens == null && usage.outputTokens == null))) {
                return total.coerceAtLeast(0).toLong()
            }
            if (usage.inputTokens != null || usage.outputTokens != null) {
                val input = (usage.inputTokens ?: 0).coerceAtLeast(0).toLong()
                val output = (usage.outputTokens ?: 0).coerceAtLeast(0).toLong()
                return input + output
            }
        }
        if (response.outputs.isEmpty()) return 0L
        val charCount = response.outputs.sumOf { output ->
            when (output) {
                is TextOutput -> output.text.length.toLong()
                is ToolCallOutput -> (output.call.toolName.length + output.call.arguments.toString().length).toLong()
                is TensorOutput -> output.tensor.storage.byteSize
                else -> 0L
            }
        }
        return (charCount / 4L).coerceAtLeast(1L)
    }

    suspend fun run(request: RunRequest): RunOutcome {
        val initialCtx = buildRoutingContext(
            request = request,
            budgetUsed = 0L,
            runTaint = TrustLevel.TRUSTED,
        )
        val initialDecision = router.select(request.modelKind, initialCtx)
        var currentAdapter = when (initialDecision) {
            is RoutingDecision.Local -> initialDecision.adapter
            is RoutingDecision.RemoteApproved -> initialDecision.adapter
            is RoutingDecision.RemotePendingApproval -> return RunOutcome.Suspended("Remote approval required: ${initialDecision.reason}")
            is RoutingDecision.UnavailableOffline -> return RunOutcome.Suspended("Model unavailable offline: ${initialDecision.kind}")
            else -> return RunOutcome.Failed("Routing failed: $initialDecision")
        }

        var minimumContextWindow: Int? = null
        val assemblyReq = AssemblyRequest(
            model = currentAdapter,
            userMessage = request.userMessage,
            tools = request.tools,
            conversationHistory = emptyList(),
            knowledgeContext = request.knowledgeContext,
            instructionSet = request.instructionSet,
        )
        val pkg = when (val ar = assembler.assemble(assemblyReq)) {
            is AssemblyResult.Ok -> ar.pkg
            is AssemblyResult.TooBig -> {
                minimumContextWindow = ar.minimumContextWindow
                val larger = router.select(
                    request.modelKind,
                    initialCtx.copy(minimumContextWindow = minimumContextWindow),
                )
                currentAdapter = when (larger) {
                    is RoutingDecision.Local -> larger.adapter
                    is RoutingDecision.RemoteApproved -> larger.adapter
                    is RoutingDecision.RemotePendingApproval -> return RunOutcome.Suspended("Remote approval required: ${larger.reason}")
                    is RoutingDecision.UnavailableOffline -> return RunOutcome.Suspended("Model unavailable offline: ${larger.kind}")
                    else -> return RunOutcome.Failed("No model with context window >= ${ar.minimumContextWindow}")
                }
                when (val ar2 = assembler.assemble(assemblyReq.copy(model = currentAdapter))) {
                    is AssemblyResult.Ok -> ar2.pkg
                    is AssemblyResult.TooBig -> return RunOutcome.Failed("Prompt does not fit even in larger context window")
                }
            }
        }

        val transcript = mutableListOf<Turn>()
        transcript.addAll(pkg.request.messages)
        var runTaint = TrustLevel.TRUSTED
        var taintSourceDescription: String? = null
        var steps = 0
        var budgetUsed = 0L
        val consecutiveCalls = mutableListOf<String>()

        checkpoint(StepEvent.RunStarted(instructionSetHash = pkg.instructionSetHash))

        while (steps < maxSteps) {
            // RFC-0008: Check budget exhaustion before each step (RFC-0028)
            if (request.budgetRemaining != null && budgetUsed >= request.budgetRemaining) {
                checkpoint(StepEvent.RunFailed(step = steps, reason = "Budget exhausted"))
                return RunOutcome.Failed("Budget exhausted after $steps steps")
            }

            if (steps > 0) {
                val turnCtx = buildRoutingContext(
                    request = request,
                    budgetUsed = budgetUsed,
                    runTaint = runTaint,
                    minimumContextWindow = minimumContextWindow,
                )
                currentAdapter = when (val turnDecision = router.select(request.modelKind, turnCtx)) {
                    is RoutingDecision.Local -> turnDecision.adapter
                    is RoutingDecision.RemoteApproved -> turnDecision.adapter
                    is RoutingDecision.RemotePendingApproval -> return RunOutcome.Suspended("Remote approval required: ${turnDecision.reason}")
                    is RoutingDecision.UnavailableOffline -> return RunOutcome.Suspended("Model unavailable offline: ${turnDecision.kind}")
                    else -> return RunOutcome.Failed("Routing failed: $turnDecision")
                }
            }

            steps++
            checkpoint(StepEvent.StepStarted(step = steps))
            val modelReq = ModelRequest(
                messages = transcript.toList(),
                tools = pkg.request.tools,
                toolChoice = ToolChoice.Auto,
                maxOutputTokens = currentAdapter.contextWindow / 8,
            )
            val response = currentAdapter.invoke(modelReq).getOrElse { err ->
                checkpoint(StepEvent.StepFailed(step = steps, reason = err.message ?: "model error"))
                return RunOutcome.Failed("Model invocation failed: ${err.message}")
            }

            // RFC-0028: Account for model response tokens consumed from usage
            budgetUsed += responseUsageCost(response)

            val responseText = response.outputs
                .filterIsInstance<TextOutput>()
                .joinToString("") { it.text }
                .ifEmpty { null }
            val responseToolCalls = response.outputs.filterIsInstance<ToolCallOutput>().map { it.call }
            transcript.add(Turn.Assistant(text = responseText, toolCalls = responseToolCalls))

            if (response.stopReason in setOf(StopReason.END_TURN, StopReason.STOP_SEQUENCE, StopReason.REFUSAL) || responseToolCalls.isEmpty()) {
                checkpoint(StepEvent.RunCompleted(step = steps, taint = runTaint))
                return RunOutcome.Completed(transcript.toList(), steps, runTaint, taintSourceDescription, pkg.instructionSetHash)
            }

            val callKey = responseToolCalls.joinToString("|") { "${it.toolName}:${it.arguments}" }
            if (consecutiveCalls.size >= 3 && consecutiveCalls.takeLast(3).all { it == callKey }) {
                checkpoint(StepEvent.RunFailed(step = steps, reason = "Repeated identical tool call"))
                return RunOutcome.Failed("Loop detected: same tool call repeated 3 times")
            }
            consecutiveCalls.add(callKey)

            for (toolCall in responseToolCalls) {
                val result: ToolCallResult = broker.invoke(subjectId = "run", call = toolCall, runTaint = runTaint)
                val newTaint = runTaint raisedBy result.trustLevel
                if (newTaint != runTaint) {
                    runTaint = newTaint
                    taintSourceDescription = toolCall.toolName
                }
                if (result.outcome is dev.aidos.kernel.ToolOutcome.Denied) {
                    val denial = result.outcome as dev.aidos.kernel.ToolOutcome.Denied
                    val taintDesc = if (runTaint != TrustLevel.TRUSTED) " (Run is tainted by: $taintSourceDescription)" else ""
                    transcript.add(Turn.ToolResult(result.copy(content = listOf(ContentBlock.Text("Denied: ${denial.reason}$taintDesc")))))
                } else {
                    transcript.add(Turn.ToolResult(result))
                }
            }
            checkpoint(StepEvent.StepCompleted(step = steps, taint = runTaint))
        }

        checkpoint(StepEvent.RunFailed(step = steps, reason = "Step limit reached"))
        return RunOutcome.Failed("Step limit of $maxSteps reached")
    }
}

data class RunRequest(
    val userMessage: String,
    val modelKind: ModelKind = ModelKind.LLM,
    val profile: PlatformProfile = PlatformProfile.DESKTOP,
    val networkAvailable: Boolean = false,
    val executionWindow: ExecutionWindow = UnboundedExecutionWindow,
    val tools: List<ToolDescriptor> = emptyList(),
    val knowledgeContext: List<ContextItem> = emptyList(),
    val instructionSet: InstructionSet? = null,
    /** RFC-0028: Budget remaining for this run. If null, no budget limit enforced. */
    val budgetRemaining: Long? = null,
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
