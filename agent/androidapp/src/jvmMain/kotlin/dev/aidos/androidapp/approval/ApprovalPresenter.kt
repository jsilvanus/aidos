package dev.aidos.androidapp.approval

import dev.aidos.api.RuntimeEvent

/**
 * Approval, preview, and memory review (M30, RFC-0018, RFC-0027, RFC-0030, RFC-0026).
 */

data class ApprovalCard(
    val approvalId: String,
    val toolName: String,
    val previewDescription: String,
    val untrustedSourceDescription: String?,
    val providerRetentionStatement: String?,
    val kind: ApprovalKind,
    val voiceSafe: Boolean = false,
)

enum class ApprovalKind {
    MUTATION,
    EGRESS,
    ESCALATION,
}

sealed interface ApprovalDecision {
    data object Approved : ApprovalDecision
    data object Denied : ApprovalDecision
}

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
            event.previewDescription
        } else null

        val providerRetention = if (isEgress) {
            "Provider retention policy: see run details"
        } else null

        val safeForVoice = kind == ApprovalKind.MUTATION && untrustedSource == null && providerRetention == null

        return ApprovalCard(
            approvalId = event.taskId,
            toolName = event.toolName,
            previewDescription = event.previewDescription,
            untrustedSourceDescription = untrustedSource,
            providerRetentionStatement = providerRetention,
            kind = kind,
            voiceSafe = safeForVoice,
        )
    }
}
