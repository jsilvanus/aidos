package fi.italeino.aidos.engine.agent

import dev.aidos.aidin.NodeId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A tool call on a tainted remote run that is waiting for the user (RFC-0107). Carries no arguments or content. */
data class PendingToolApproval(
    val id: String,
    val peer: NodeId,
    val app: String,
    val tool: String,
    val requestedAtMillis: Long,
)

/**
 * The approval prompt behind [OriginToolHost.approve] (RFC-0107): an effectful tool on a remote run that
 * has received tool results waits here until the user answers, and is refused if nobody does within
 * [timeout]. "No answer" is "no", so an unattended Engine never lets a tainted remote loop write anything.
 *
 * [pending] is what the UI shows; [decide] is what its buttons call. Decisions are per call: nothing
 * is remembered, so approving one write does not approve the next.
 */
class ToolApprovalQueue(
    private val timeout: Duration = 60.seconds,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val _pending = MutableStateFlow<List<PendingToolApproval>>(emptyList())
    val pending: StateFlow<List<PendingToolApproval>> = _pending.asStateFlow()
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** Suspends until the user decides; false on refusal or timeout. */
    suspend fun request(peer: NodeId, app: String, tool: String): Boolean {
        val item = PendingToolApproval(UUID.randomUUID().toString(), peer, app, tool, now())
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
