package dev.aidos.amp

import dev.aidos.kernel.AidosError
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ErrorClass
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.ToolCallOutput
import dev.aidos.kernel.ToolCallResult
import dev.aidos.kernel.ToolChoice
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.Turn
import kotlinx.serialization.json.JsonObject

/**
 * Systemic Aidos Agent Runner for Agentic Messenger Platform (AMP).
 *
 * Implements the core multi-turn agentic execution loop:
 * - Translates incoming AMP conversation turns and external user prompts.
 * - Monotonically applies [TrustLevel.UNTRUSTED] to peer chat inputs (RFC-0027 taint model).
 * - Executes tool calls requested by the model against consumer-provided [AmpToolDefinition]s.
 * - Feeds tool results back to the model until it produces a final response or hits [maxSteps].
 */
class AmpAgentRunner(
    private val adapter: ModelAdapter,
    val maxSteps: Int = 5,
) {

    /**
     * Executes an agentic turn for an incoming user or peer message.
     */
    suspend fun executeTurn(
        systemPrompt: String,
        conversationHistory: List<AmpConversationTurn> = emptyList(),
        incomingMessage: String,
        tools: List<AmpToolDefinition> = emptyList(),
    ): Result<AmpAgentTurnResult> = runCatching {
        val transcript = mutableListOf<Turn>()

        // 1. System Prompt (strictly TRUSTED, runtime/AMP authored)
        transcript.add(Turn.System(systemPrompt))

        // 2. Prior Conversation History
        for (turn in conversationHistory) {
            when (turn.role.lowercase()) {
                "system" -> transcript.add(Turn.System(turn.text))
                "assistant" -> transcript.add(Turn.Assistant(text = turn.text, toolCalls = emptyList()))
                else -> transcript.add(
                    Turn.User(
                        content = listOf(ContentBlock.Text(turn.text)),
                        trustLevel = TrustLevel.UNTRUSTED
                    )
                )
            }
        }

        // 3. New Incoming Message (Untrusted input)
        transcript.add(
            Turn.User(
                content = listOf(ContentBlock.Text(incomingMessage)),
                trustLevel = TrustLevel.UNTRUSTED
            )
        )

        val toolDescriptors = tools.map { it.toToolDescriptor() }
        val toolMap = tools.associateBy { it.name }
        val executedToolCalls = mutableListOf<ExecutedToolCall>()

        var steps = 0
        var finalAssistantText = ""

        while (steps < maxSteps) {
            steps++

            val modelRequest = ModelRequest(
                messages = transcript.toList(),
                tools = toolDescriptors,
                toolChoice = if (toolDescriptors.isNotEmpty()) ToolChoice.Auto else ToolChoice.None,
                maxOutputTokens = adapter.contextWindow / 4,
            )

            val response = adapter.invoke(modelRequest).getOrThrow()

            val textOutput = response.outputs.filterIsInstance<TextOutput>()
                .joinToString("\n") { it.text }
                .trim()
            val toolCalls = response.outputs.filterIsInstance<ToolCallOutput>()
                .map { it.call }

            if (textOutput.isNotEmpty()) {
                finalAssistantText = textOutput
            }

            // If no tools were called or model signaled END_TURN without tools, we are done
            if (toolCalls.isEmpty() || response.stopReason in setOf(StopReason.END_TURN, StopReason.STOP_SEQUENCE)) {
                transcript.add(Turn.Assistant(text = finalAssistantText.ifEmpty { null }, toolCalls = emptyList()))
                return@runCatching AmpAgentTurnResult(
                    replyText = finalAssistantText,
                    executedToolCalls = executedToolCalls,
                    totalSteps = steps,
                )
            }

            // Model requested tool calls
            transcript.add(Turn.Assistant(text = textOutput.ifEmpty { null }, toolCalls = toolCalls))

            for (call in toolCalls) {
                val toolDef = toolMap[call.toolName]
                val result = if (toolDef != null) {
                    try {
                        toolDef.handler(call.arguments)
                    } catch (e: Exception) {
                        AmpToolExecutionResult(
                            outputText = "Tool '${call.toolName}' threw error: ${e.message}",
                            isError = true
                        )
                    }
                } else {
                    AmpToolExecutionResult(
                        outputText = "Tool '${call.toolName}' not found in registry",
                        isError = true
                    )
                }

                executedToolCalls.add(
                    ExecutedToolCall(
                        toolName = call.toolName,
                        argumentsJson = call.arguments.toString(),
                        resultText = result.outputText
                    )
                )

                val outcome = if (result.isError) {
                    ToolOutcome.Failed(
                        AidosError(
                            code = "amp.tool.error",
                            errorClass = ErrorClass.TRANSIENT,
                            message = result.outputText,
                        )
                    )
                } else {
                    ToolOutcome.Ok
                }

                transcript.add(
                    Turn.ToolResult(
                        ToolCallResult(
                            callId = call.callId,
                            outcome = outcome,
                            content = listOf(ContentBlock.Text(result.outputText)),
                            trustLevel = TrustLevel.UNTRUSTED
                        )
                    )
                )
            }
        }

        // Hit maxSteps limit
        AmpAgentTurnResult(
            replyText = finalAssistantText.ifEmpty { "Reached maximum reasoning steps ($maxSteps)." },
            executedToolCalls = executedToolCalls,
            totalSteps = steps,
        )
    }
}
