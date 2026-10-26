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
    /** Return at once with a `running` task handle instead of waiting for the next stop point. */
    val async: Boolean = false,
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
data class ToolResultsRequest(val results: List<ClientToolResult>, val async: Boolean = false)

@Serializable
data class ExecutedToolCallDto(val toolName: String, val argumentsJson: String, val resultText: String)

/**
 * Snapshot of a run (a task). [status]: `running`; `tool_calls` (input required: execute [toolCalls]
 * and send the results to `/v1/agent/runs/{runId}/tool-results`); or terminal `completed`, `failed`,
 * `cancelled`. [seq] increases on every state change, so a client can tell a new snapshot from one
 * it has seen and ignore duplicate or out-of-order notifications.
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
    val seq: Int = 0,
) {
    val isTerminal: Boolean get() = status == COMPLETED || status == FAILED || status == CANCELLED

    companion object {
        const val RUNNING = "running"
        const val COMPLETED = "completed"
        const val TOOL_CALLS = "tool_calls"
        const val FAILED = "failed"
        const val CANCELLED = "cancelled"
    }
}

/** A state-change notification for a run of the calling app. Advisory: reconcile with GET on the run. */
@Serializable
data class AgentEvent(val type: String, val runId: String, val status: String, val seq: Int)

@Serializable
data class AgentInfo(
    val apiVersion: Int = 1,
    val maxConcurrentRuns: Int,
    val maxSteps: Int,
    val toolWaitSeconds: Long,
    val capabilities: List<String> = listOf("tasks", "notifications"),
)
