package fi.italeino.aidos.engine.desktop.tray

import fi.italeino.aidos.engine.control.ApprovalsControl
import fi.italeino.aidos.engine.control.PendingApproval

/** What the tray shows about the Engine service (RFC-0115, D59). */
enum class ServiceState { STOPPED, RUNNING, UNREACHABLE }

/** One poll's worth of facts for the tray: the service state and what is waiting. */
data class TraySnapshot(
    val engine: ServiceState,
    val pending: List<PendingApproval>,
    /** Items that were not in the previous snapshot: these get a toast. */
    val raised: List<PendingApproval>,
    /** Ids that were in the previous snapshot and are gone (answered elsewhere, or timed out): close their popups. */
    val resolvedIds: Set<String>,
) {
    /** The tray icon's tooltip. Counts only, never content. */
    fun tooltip(): String = when (engine) {
        ServiceState.STOPPED -> "Aidos: Engine is not running"
        ServiceState.UNREACHABLE -> "Aidos: Engine is not answering"
        ServiceState.RUNNING -> if (pending.isEmpty()) "Aidos: running" else "Aidos: ${pending.size} waiting for your answer"
    }
}

/**
 * Polls the Engine's approvals feed and works out what is new since the last poll. Pure logic with the control
 * injected, so it is tested without a tray. A toast is raised once per approval id: it is never repeated by a later
 * poll, and an item that disappears and reappears under the same id would not toast again (ids are unique per ask).
 */
class ApprovalWatcher(private val control: suspend () -> ApprovalsControl?) {
    private var known: Map<String, PendingApproval> = emptyMap()
    private val toasted = mutableSetOf<String>()

    suspend fun poll(): TraySnapshot {
        val c = control()
            ?: run {
                val gone = known.keys.toSet()
                known = emptyMap()
                return TraySnapshot(ServiceState.STOPPED, emptyList(), emptyList(), gone)
            }
        val items = try {
            c.pending()
        } catch (_: Exception) {
            // Keep what we knew: a blip must not close a popup the user is reading, nor toast it again afterwards.
            return TraySnapshot(ServiceState.UNREACHABLE, known.values.toList(), emptyList(), emptySet())
        }
        val now = items.associateBy { it.id }
        val raised = items.filter { toasted.add(it.id) }
        val resolved = known.keys - now.keys
        known = now
        toasted.retainAll(now.keys + resolved.toSet())
        return TraySnapshot(ServiceState.RUNNING, items, raised, resolved)
    }
}
