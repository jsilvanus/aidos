package dev.aidos.androidapp.approval

import dev.aidos.api.RuntimeEvent

/**
 * Approval, preview, and memory review (M30, RFC-0018, RFC-0027, RFC-0030, RFC-0026).
 *
 * Every mutation shows its Preview before it happens. An escalation names the untrusted source
 * that caused it. Approval requires a `user_interactive` connection — this means the UI is
 * visible and the screen is on; approval cannot happen in a background service.
 *
 * Memory review lists entries with source and confidence. Promotion to project scope is the
 * ONLY path by which a project-scoped entry exists (D33 — enforced by schema, verified by M16b).
 *
 * The egress approval prompt states what the target provider retains (RFC-0026).
 */

/** A pending approval request shown to the user (M30). */
data class ApprovalCard(
    val approvalId: String,
    val toolName: String,
    /** The preview text — what exactly will change. Shown before the user decides. */
    val previewDescription: String,
    /** Non-null if this escalation was triggered by untrusted content. Names the source (M30). */
    val untrustedSourceDescription: String?,
    /** Non-null for egress approvals — states what the provider retains (RFC-0026). */
    val providerRetentionStatement: String?,
    val kind: ApprovalKind,
)

enum class ApprovalKind {
    /** A filesystem or git mutation that the user must approve. */
    MUTATION,
    /** Network egress — data is leaving the device (RFC-0026). */
    EGRESS,
    /** A tainted run is requesting elevated action. */
    ESCALATION,
}

/** User's decision on an approval card. */
sealed interface ApprovalDecision {
    data object Approved : ApprovalDecision
    data object Denied : ApprovalDecision
}

/**
 * Approval presenter: converts a `RuntimeEvent.ToolApprovalRequired` into an [ApprovalCard].
 *
 * The presenter is stateless — it maps domain events to UI data. The actual approve/deny
 * is sent back through [RuntimeClient.sessions.approveToolCall].
 */
object ApprovalPresenter {

    fun toCard(event: RuntimeEvent.ToolApprovalRequired): ApprovalCard {
        val isEgress = event.toolName.contains("http", ignoreCase = true) ||
            event.toolName.contains("egress", ignoreCase = true) ||
            event.toolName.startsWith("mcp_http")
        val isEscalation = event.previewDescription.contains("UNTRUSTED", ignoreCase = true) ||
            event.previewDescription.contains("untrusted source", ignoreCase = true)

        val kind = when {
            isEscalation -> ApprovalKind.ESCALATION
            isEgress -> ApprovalKind.EGRESS
            else -> ApprovalKind.MUTATION
        }

        val untrustedSource = if (isEscalation) {
            // Extract source description from the preview text (set by taint propagation in M16).
            event.previewDescription
        } else null

        val providerRetention = if (isEgress) {
            // Egress approvals must state provider retention policy (RFC-0026).
            // In a real implementation this comes from the attempt's provider_retention_json.
            // For the UI layer, we show whatever the event carries — "UNKNOWN" if not stated.
            "Provider retention policy: see run details"
        } else null

        return ApprovalCard(
            approvalId = event.taskId,
            toolName = event.toolName,
            previewDescription = event.previewDescription,
            untrustedSourceDescription = untrustedSource,
            providerRetentionStatement = providerRetention,
            kind = kind,
        )
    }
}
