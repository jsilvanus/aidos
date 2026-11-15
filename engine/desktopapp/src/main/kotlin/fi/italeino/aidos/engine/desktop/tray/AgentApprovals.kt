package fi.italeino.aidos.engine.desktop.tray

import dev.aidos.api.RuntimeClient
import fi.italeino.aidos.engine.control.ApprovalsControl
import fi.italeino.aidos.engine.control.PendingApproval

/**
 * The Agent daemon's side of the approvals feed (RFC-0115, D59): capability requests waiting for the user.
 * Ids are `cap:<requestId>`. The toast title names the permission only; the request's free-text reason can contain
 * content, so it appears in the popup detail and never in the title.
 */
class AgentApprovalsControl(private val client: RuntimeClient) : ApprovalsControl {
    override suspend fun pending(): List<PendingApproval> = client.capabilities.listPending().map {
        PendingApproval(
            "cap:${it.requestId}", "capability", "An agent session asks for permission: ${it.permission}",
            it.reason.ifBlank { "No reason was given." }, null,
        )
    }

    override suspend fun decide(id: String, allow: Boolean): Boolean {
        if (!id.startsWith("cap:")) return false
        val request = id.removePrefix("cap:")
        // Answered elsewhere or timed out: the runtime throws, which is "no longer waiting", not a failure.
        return try {
            if (allow) client.capabilities.approve(request) else client.capabilities.deny(request, "Denied from the tray")
            true
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * One approvals feed over several sources (Engine, Agent daemon). A source that fails is skipped for that poll so the
 * other still shows; only when every source fails does [pending] throw. [decide] goes to whichever source takes it.
 */
class CompositeApprovals(private val sources: List<ApprovalsControl>) : ApprovalsControl {
    override suspend fun pending(): List<PendingApproval> {
        var failures = 0
        var last: Exception? = null
        val out = sources.flatMap { s ->
            try { s.pending() } catch (e: Exception) { failures++; last = e; emptyList() }
        }
        if (sources.isNotEmpty() && failures == sources.size) throw last!!
        return out
    }

    override suspend fun decide(id: String, allow: Boolean): Boolean = sources.any { runCatching { it.decide(id, allow) }.getOrDefault(false) }
}
