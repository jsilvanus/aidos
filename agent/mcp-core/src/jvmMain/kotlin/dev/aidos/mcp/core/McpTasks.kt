package dev.aidos.mcp.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlin.math.min

/**
 * MCP Tasks and notifications, client side. Three things are kept apart on purpose: the result of a
 * tool call (immediate, or a task handle), progress (describes work in flight, says nothing about
 * a task's lifecycle) and the task lifecycle itself. Notifications are hints: correctness comes
 * from [McpTaskClient.getTask], never from having received one.
 */
enum class McpTaskStatus(val terminal: Boolean) {
    WORKING(false),
    INPUT_REQUIRED(false),
    COMPLETED(true),
    FAILED(true),
    CANCELLED(true),
}

data class McpTaskSnapshot(
    val taskId: String,
    val status: McpTaskStatus,
    val statusMessage: String? = null,
    /** The server's suggested poll interval, if it gave one. */
    val pollIntervalMillis: Long? = null,
    val ttlMillis: Long? = null,
)

/** A tool call either finished at once or started a task; a server without Tasks always yields [Immediate]. */
sealed interface McpToolExecution {
    data class Immediate(val result: McpCallResult) : McpToolExecution
    data class Started(val task: McpTaskSnapshot) : McpToolExecution
}

/**
 * What a cancel request observed. Cancellation is cooperative: [confirmed] is true only when the
 * server reports the task as cancelled, so "cancel requested" and "cancelled" are not conflated.
 */
data class McpCancelOutcome(val snapshot: McpTaskSnapshot) {
    val confirmed: Boolean get() = snapshot.status == McpTaskStatus.CANCELLED
}

sealed interface McpNotification {
    data class Progress(val token: String, val progress: Double, val total: Double?, val message: String?) : McpNotification
    data class TaskStatus(val snapshot: McpTaskSnapshot) : McpNotification
    /** Tools changed on the server: refresh discovery; the descriptor-hash/adoption rules still apply. */
    data object ToolsListChanged : McpNotification
    /** Server log line. Route to diagnostics only; never show or persist it as tool output. */
    data class Log(val level: String, val logger: String?, val data: JsonElement?) : McpNotification
}

interface McpTaskClient {
    /** Server notifications since the connection was made. Lossy (no replay); reconcile with [getTask]. */
    val notifications: Flow<McpNotification>

    /** True only when the server advertised task-augmented `tools/call`. Never assume it. */
    suspend fun supportsTasks(): Boolean

    /**
     * Calls a tool as a task when the server supports that, else as an ordinary call. [ttlMillis] is
     * the retention the caller asks for; the server may grant less.
     */
    suspend fun callToolAsTask(name: String, arguments: kotlinx.serialization.json.JsonObject, ttlMillis: Long? = null): McpToolExecution

    suspend fun getTask(taskId: String): McpTaskSnapshot

    /** The tool result of a completed task. A failed or cancelled task throws [McpTaskException]. */
    suspend fun getTaskResult(taskId: String): McpCallResult

    suspend fun cancelTask(taskId: String): McpCancelOutcome
}

class McpTaskException(message: String) : Exception(message)

/**
 * Observes [taskId] until it is terminal, or [timeoutMillis] passes, and returns the last snapshot
 * (so a timeout is visible as a non-terminal status, not an exception). Polls [McpTaskClient.getTask]
 * with backoff from the server's own interval (default 1s, capped at [maxPollMillis]); a
 * [McpNotification.TaskStatus] for the same task, or `input_required`, just wakes the poll early.
 * [onUpdate] sees each distinct snapshot once.
 */
suspend fun McpTaskClient.awaitTask(
    taskId: String,
    timeoutMillis: Long,
    maxPollMillis: Long = 10_000,
    onUpdate: (McpTaskSnapshot) -> Unit = {},
): McpTaskSnapshot {
    var last = getTask(taskId)
    onUpdate(last)
    var wait = last.pollIntervalMillis ?: 1_000
    val finished = withTimeoutOrNull(timeoutMillis) {
        while (!last.status.terminal) {
            // A notifications flow that ends (a client without push) must still be waited out.
            withTimeoutOrNull(wait) {
                val hint = notifications.firstOrNull { it is McpNotification.TaskStatus && it.snapshot.taskId == taskId }
                if (hint == null) delay(wait)
            }
            val next = getTask(taskId)
            if (next != last) {
                last = next
                onUpdate(next)
                wait = next.pollIntervalMillis ?: 1_000
            } else {
                wait = min(wait * 2, maxPollMillis)
            }
        }
        last
    }
    return finished ?: last
}
