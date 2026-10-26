package fi.italeino.aidos.engine.agent

import dev.aidos.amp.AmpConversationTurn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A tool the calling app offers to the agent; the app executes it, the runtime only asks. */
@Serializable
data class ClientToolSpec(
    val name: String,
    val description: String = "",
    val inputSchema: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class AgentTurnRequest(
    /** Engine model id (as in `/v1/models`). Required: the runtime does not guess a model. */
    val model: String,
    val message: String,
    val systemPrompt: String? = null,
    val history: List<AmpConversationTurn> = emptyList(),
    val tools: List<ClientToolSpec> = emptyList(),
    val maxSteps: Int? = null,
    val contextWindow: Int? = null,
)

@Serializable
data class PendingToolCall(
    val callId: String,
    val name: String,
    val arguments: JsonObject,
)

@Serializable
data class ClientToolResult(
    val callId: String,
    val output: String,
    val isError: Boolean = false,
)

@Serializable
data class ToolResultsRequest(val results: List<ClientToolResult>)

@Serializable
data class ExecutedToolCallDto(val toolName: String, val argumentsJson: String, val resultText: String)

/**
 * One step of a run: either it needs the app to execute tools ([status] `tool_calls`, send the
 * results to `/v1/agent/runs/{runId}/tool-results`), or it is over (`completed`, `failed`).
 */
@Serializable
data class AgentTurnResponse(
    val status: String,
    val runId: String? = null,
    val reply: String? = null,
    val toolCalls: List<PendingToolCall> = emptyList(),
    val executedToolCalls: List<ExecutedToolCallDto> = emptyList(),
    val steps: Int = 0,
    val error: String? = null,
) {
    companion object {
        const val COMPLETED = "completed"
        const val TOOL_CALLS = "tool_calls"
        const val FAILED = "failed"
    }
}

@Serializable
data class AgentInfo(
    val apiVersion: Int = 1,
    val maxConcurrentRuns: Int,
    val maxSteps: Int,
    val toolWaitSeconds: Long,
)
