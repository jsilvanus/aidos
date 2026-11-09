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
    /**
     * The widest placement the caller accepts for the turn's model calls: `local_only`,
     * `trusted_cluster` or `any` (RFC-0104). Absent means `local_only` (D47). The runtime replaces it
     * with the effective limit (strictest of this, the app's and Engine's) before the run starts.
     */
    val locality: String? = null,
    val role: String? = "DRIVER",
    val parentSessionId: String? = null,
    val parentRunId: String? = null,
    val workerBrief: String? = null,
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

@Serializable
data class EgressRecordDto(
    val destinationHost: String,
    val method: String = "GET",
    val requestBytes: Long = 0L,
    val responseBytes: Long = 0L,
    val payloadHash: String = "",
    val durationMs: Long = 0L,
    val outcome: String = "ALLOWED",
    val timestamp: String = "",
)

@Serializable
data class TaskGraphNodeDto(
    val taskId: String,
    val ordinal: Int,
    val kind: String,
    val description: String,
    val state: String,
    val dependsOnTaskIds: List<String> = emptyList(),
    val toolName: String? = null,
    val awaitingRunId: String? = null,
)

@Serializable
data class WorkerSpawnDto(
    val taskId: String,
    val workerSessionId: String? = null,
    val childRunId: String? = null,
    val brief: String,
    val budgetSplitWays: Int = 1,
    val delegatedCapabilities: List<String> = emptyList(),
)

@Serializable
data class AgentBudgetDto(
    val modelCalls: Int? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val costUnits: Long? = null,
    val steps: Int? = null,
    val maxSteps: Int? = null,
    val splitWays: Int? = null,
)

@Serializable
data class WorkerOutcomeDto(
    val workerSessionId: String,
    val commitRef: String,
    val outcomeSummary: String,
    val committedAt: String? = null,
)

@Serializable
data class AgentRunSummaryDto(
    val runId: String,
    val owner: String,
    val model: String,
    val placement: String = "local",
    val locality: String = "local_only",
    val status: String,
    val steps: Int = 0,
    val maxSteps: Int = 8,
    val promptPreview: String = "",
    val replyText: String = "",
    val error: String? = null,
    val seq: Int = 0,
    val pendingToolCall: PendingToolCall? = null,
    val executedTools: List<ExecutedToolCallDto> = emptyList(),
    val egressRecords: List<EgressRecordDto> = emptyList(),
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
    val role: String = "DRIVER",
    val parentSessionId: String? = null,
    val parentRunId: String? = null,
    val workerBrief: String? = null,
    val tasks: List<TaskGraphNodeDto> = emptyList(),
    val workerSpawns: List<WorkerSpawnDto> = emptyList(),
    val budget: AgentBudgetDto? = null,
    val workerOutcome: WorkerOutcomeDto? = null,
    val isParkedOnChild: Boolean = false,
    val awaitingChildRunId: String? = null,
)

@Serializable
data class AgentOverviewStatsDto(
    val activeRuns: Int,
    val maxConcurrentRuns: Int,
    val waitingToolCalls: Int,
    val completedRuns: Int,
    val failedRuns: Int,
    val totalRetainedRuns: Int,
    val appsCount: Int,
    val runsByApp: Map<String, Int>,
    val totalEgressCalls: Int,
)

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
