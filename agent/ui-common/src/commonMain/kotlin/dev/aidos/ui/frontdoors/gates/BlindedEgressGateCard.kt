package dev.aidos.ui.frontdoors.gates

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aidos.ui.tcr.core.ActionButtonAtom
import dev.aidos.ui.tcr.core.ActionButtonKind
import dev.aidos.ui.tcr.core.TextSpanAtom
import dev.aidos.ui.tcr.core.TextSpanKind
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens

data class BlindedEgressRequestUiModel(
    val requestId: String,
    val projectId: String,
    val channel: String,
    val recipient: String,
    val payloadPreview: String,
    val cryptographicFingerprint: String,
    val taintLevel: String = "HIGH",
)

/**
 * Explicit cryptographic declassification card for the Blinded Egress Bridge (RFC-0113 §4.3).
 * Requires explicit two-step confirmation before network transmission is permitted.
 */
@Composable
fun BlindedEgressGateCard(
    request: BlindedEgressRequestUiModel,
    onApprove: (String) -> Unit,
    onDeny: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isConfirmingApproval by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1B4B), shape = RoundedCornerShape(CornerTokens.md))
            .border(width = 1.5.dp, color = ColorTokens.ActorHybrid, shape = RoundedCornerShape(CornerTokens.md))
            .padding(SpacingTokens.base)
            .semantics {
                contentDescription = "Blinded Egress Approval Gate for ${request.recipient}"
            }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "🔒 Blinded Egress Gate (RFC-0113)",
                    style = AidosTypography.baseTypography.titleSmall,
                    color = ColorTokens.ActorHybrid,
                )

                Box(
                    modifier = Modifier
                        .background(Color(0x33EF4444), shape = RoundedCornerShape(CornerTokens.pill))
                        .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xxs)
                ) {
                    Text(
                        text = "Taint: ${request.taintLevel}",
                        style = AidosTypography.baseTypography.labelSmall,
                        color = ColorTokens.StatusBlocked,
                    )
                }
            }

            Text(
                text = "Target: ${request.channel} ➔ ${request.recipient}",
                style = AidosTypography.baseTypography.bodyMedium,
                color = Color.White,
            )

            TextSpanAtom(
                text = request.payloadPreview,
                kind = TextSpanKind.MONOSPACE_TOKEN,
                color = Color(0xFFF1F5F9),
            )

            Text(
                text = "SHA-256 Fingerprint: ${request.cryptographicFingerprint}",
                style = AidosTypography.codeSnippet,
                color = Color(0xFF94A3B8),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionButtonAtom(
                    text = "Deny",
                    onClick = { onDeny(request.requestId, "User rejected declassification") },
                    kind = ActionButtonKind.DANGER,
                    modifier = Modifier.padding(end = SpacingTokens.sm),
                )

                if (!isConfirmingApproval) {
                    ActionButtonAtom(
                        text = "Authorize Egress...",
                        onClick = { isConfirmingApproval = true },
                        kind = ActionButtonKind.PRIMARY,
                    )
                } else {
                    ActionButtonAtom(
                        text = "Confirm Declassification (Send)",
                        onClick = {
                            isConfirmingApproval = false
                            onApprove(request.requestId)
                        },
                        kind = ActionButtonKind.PRIMARY,
                    )
                }
            }
        }
    }
}
