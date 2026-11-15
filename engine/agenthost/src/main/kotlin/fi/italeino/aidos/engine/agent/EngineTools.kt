package fi.italeino.aidos.engine.agent

import dev.aidos.amp.AmpToolExecutionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A tool the Engine itself runs for an agent loop (RFC-0115, RFC-0031): an operation of an MCP server the user adopted.
 * Unlike a client tool, nothing is handed to the calling app: the Engine executes it. [readOnly] is the server's own
 * claim (an MCP annotation, untrusted), so it only decides whether a call may skip the user's approval; every other
 * tool waits for the user, per call.
 */
class EngineTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val readOnly: Boolean,
    val execute: suspend (JsonObject) -> AmpToolExecutionResult,
)

/** Where a run's Engine-side tools come from. Read when a run starts that asked for them. */
fun interface EngineToolSource {
    suspend fun toolsFor(owner: String): List<EngineTool>
}

/** A call of an Engine-side tool waiting for the user. Carries no arguments or content. */
data class PendingEngineToolApproval(val id: String, val app: String, val tool: String, val requestedAtMillis: Long)

/**
 * The approval prompt behind Engine-side tools that are not read only (RFC-0115): a call waits here until the user
 * answers, and no answer within [timeout] is a refusal, so an unattended Engine never lets an agent loop change
 * anything through MCP. Decisions are per call; nothing is remembered.
 */
class EngineToolApprovals(
    private val timeout: Duration = 60.seconds,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val _pending = MutableStateFlow<List<PendingEngineToolApproval>>(emptyList())
    val pending: StateFlow<List<PendingEngineToolApproval>> = _pending.asStateFlow()
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** Suspends until the user decides; false on refusal or timeout. */
    suspend fun request(app: String, tool: String): Boolean {
        val item = PendingEngineToolApproval(UUID.randomUUID().toString(), app, tool, now())
        val answer = CompletableDeferred<Boolean>()
        waiting[item.id] = answer
        _pending.update { it + item }
        return try {
            withTimeoutOrNull(timeout) { answer.await() } ?: false
        } finally {
            waiting.remove(item.id)
            _pending.update { list -> list.filterNot { it.id == item.id } }
        }
    }

    /** Answers a pending request; false when it was already answered or has timed out. */
    fun decide(id: String, allow: Boolean): Boolean = waiting[id]?.complete(allow) ?: false
}
