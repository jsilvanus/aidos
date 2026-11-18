package dev.aidos.ui.frontdoors.gates

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aidos.api.ParkedEffectRequest
import dev.aidos.api.PendingCapabilityRequest
import dev.aidos.ui.tcr.core.ActionButtonAtom
import dev.aidos.ui.tcr.core.ActionButtonKind
import dev.aidos.ui.tcr.core.TextSpanAtom
import dev.aidos.ui.tcr.core.TextSpanKind
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Tool arguments pretty-printed, or the raw text when it is not JSON. */
internal fun prettyArguments(argumentsJson: String): String {
    val parsed: JsonElement = runCatching { Json.parseToJsonElement(argumentsJson) }.getOrNull()
        ?: return argumentsJson
    return PrettyJson.encodeToString(JsonElement.serializer(), parsed)
}

private val PrettyJson = Json { prettyPrint = true }

/** Lines of [text] beyond which a card collapses the text behind "Show all". */
internal const val COLLAPSED_LINES = 6

private val AmberTaint = Color(0xFFF59E0B)

@Composable
private fun GateCardFrame(
    title: String,
    accent: Color,
    contentDescription: String,
    badge: String?,
    badgeColor: Color,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F172A), shape = RoundedCornerShape(CornerTokens.md))
            .border(width = 1.dp, color = accent, shape = RoundedCornerShape(CornerTokens.md))
            .padding(SpacingTokens.base)
            .semantics { this.contentDescription = contentDescription },
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, style = AidosTypography.baseTypography.titleSmall, color = accent)
            if (badge != null) {
                Text(
                    text = badge,
                    style = AidosTypography.baseTypography.labelSmall,
                    color = badgeColor,
                    modifier = Modifier
                        .background(badgeColor.copy(alpha = 0.2f), shape = RoundedCornerShape(CornerTokens.pill))
                        .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xxs),
                )
            }
        }
        content()
    }
}

/** Approve, or Deny after the person writes a reason (the runtime records the reason). */
@Composable
private fun ApproveOrDeny(
    approveText: String,
    denyText: String,
    onApprove: () -> Unit,
    onDeny: (String) -> Unit,
) {
    var denying by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
        if (denying) {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text("Reason for denying") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (denying) {
                ActionButtonAtom(
                    text = "Cancel",
                    onClick = { denying = false },
                    kind = ActionButtonKind.SECONDARY,
                    modifier = Modifier.padding(end = SpacingTokens.sm),
                )
                ActionButtonAtom(
                    text = denyText,
                    onClick = {
                        denying = false
                        onDeny(reason.trim().ifEmpty { "Denied by the person" })
                    },
                    kind = ActionButtonKind.DANGER,
                )
            } else {
                ActionButtonAtom(
                    text = "Deny",
                    onClick = { denying = true },
                    kind = ActionButtonKind.DANGER,
                    modifier = Modifier.padding(end = SpacingTokens.sm),
                )
                ActionButtonAtom(
                    text = approveText,
                    onClick = onApprove,
                    kind = ActionButtonKind.PRIMARY,
                )
            }
        }
    }
}

/** RFC-0018 tool call parked until a person decides. Arguments collapse beyond [COLLAPSED_LINES] lines. */
@Composable
fun ToolApprovalCard(
    request: ParkedEffectRequest,
    onApprove: (runId: String, taskId: String) -> Unit,
    onDeny: (runId: String, taskId: String, reason: String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val arguments = remember(request.argumentsJson) { prettyArguments(request.argumentsJson) }
    val lines = arguments.lines()
    val untrusted = request.runTaint.equals("UNTRUSTED", ignoreCase = true)
    GateCardFrame(
        title = "🛠 Tool approval: ${request.toolName}",
        accent = ColorTokens.StatusReview,
        contentDescription = "Tool approval for ${request.toolName}",
        badge = "Taint: ${request.runTaint}",
        badgeColor = if (untrusted) AmberTaint else ColorTokens.StatusStale,
    ) {
        Text(
            text = "Project ${request.projectId}",
            style = AidosTypography.baseTypography.labelMedium,
            color = ColorTokens.StatusStale,
        )
        TextSpanAtom(
            text = if (expanded || lines.size <= COLLAPSED_LINES) arguments
            else lines.take(COLLAPSED_LINES).joinToString("\n") + "\n…",
            kind = TextSpanKind.MONOSPACE_TOKEN,
            color = Color(0xFFF1F5F9),
        )
        if (lines.size > COLLAPSED_LINES) {
            ActionButtonAtom(
                text = if (expanded) "Show less" else "Show all",
                onClick = { expanded = !expanded },
                kind = ActionButtonKind.SECONDARY,
            )
        }
        ApproveOrDeny(
            approveText = "Approve",
            denyText = "Confirm deny",
            onApprove = { onApprove(request.runId, request.taskId) },
            onDeny = { reason -> onDeny(request.runId, request.taskId, reason) },
        )
    }
}

/** RFC-0112 capability request: a permission an agent asked for and a person has not yet granted. */
@Composable
fun CapabilityRequestCard(
    request: PendingCapabilityRequest,
    onApprove: (String) -> Unit,
    onDeny: (String, String) -> Unit,
) {
    GateCardFrame(
        title = "🔑 Capability request: ${request.permission}",
        accent = ColorTokens.ActorHybrid,
        contentDescription = "Capability request for ${request.permission}",
        badge = null,
        badgeColor = ColorTokens.StatusStale,
    ) {
        Text(
            text = request.reason.ifBlank { "No reason given." },
            style = AidosTypography.baseTypography.bodyMedium,
            color = Color.White,
        )
        ApproveOrDeny(
            approveText = "Grant",
            denyText = "Confirm deny",
            onApprove = { onApprove(request.requestId) },
            onDeny = { reason -> onDeny(request.requestId, reason) },
        )
    }
}

/** RFC-0110 intent proposal, shown as plain-language operations. */
@Composable
fun ProposalCard(
    model: ProposalUiModel,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    val untrusted = model.runTaint.equals("UNTRUSTED", ignoreCase = true)
    GateCardFrame(
        title = "📋 Proposed changes",
        accent = ColorTokens.StatusReview,
        contentDescription = "Proposed changes",
        badge = "Taint: ${model.runTaint}",
        badgeColor = if (untrusted) AmberTaint else ColorTokens.StatusStale,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs)) {
            model.summary.forEach { line ->
                Text(text = "• $line", style = AidosTypography.baseTypography.bodyMedium, color = Color.White)
            }
        }
        if (model.rationale.isNotBlank()) {
            Text(text = model.rationale, style = AidosTypography.baseTypography.labelMedium, color = ColorTokens.StatusStale)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionButtonAtom(
                text = "Reject",
                onClick = { onReject(model.proposalId) },
                kind = ActionButtonKind.DANGER,
                modifier = Modifier.padding(end = SpacingTokens.sm),
            )
            ActionButtonAtom(
                text = "Accept",
                onClick = { onAccept(model.proposalId) },
                kind = ActionButtonKind.PRIMARY,
            )
        }
    }
}
