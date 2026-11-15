package fi.italeino.aidos.engine.control

import kotlinx.serialization.Serializable

/**
 * The approvals group of `EngineControl` (RFC-0115, D59): what is waiting for the user right now, and the answer.
 * This is the feed the tray icon watches. Items carry who is asking and what kind of thing, never content
 * (no tool arguments, no file text), so showing one in a toast leaks nothing.
 */
interface ApprovalsControl {
    suspend fun pending(): List<PendingApproval>

    /** Answers one item. False when it was already answered, has timed out, or never existed. */
    suspend fun decide(id: String, allow: Boolean): Boolean
}

/** [kind] is `app` (an app asking to use the Engine), `tool` (an effectful tool call on a tainted remote run). */
@Serializable
data class PendingApproval(
    val id: String,
    val kind: String,
    val title: String,
    val detail: String,
    /** Epoch millis it was raised at, or null when the source does not track it. */
    val requestedAtMillis: Long? = null,
)

@Serializable
data class ApprovalDecision(val allow: Boolean)
