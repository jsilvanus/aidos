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

data class SplitVerificationUiModel(
    val nodeId: String,
    val taskTitle: String,
    val checkKind: String,
    val checkSpec: String,
    val scopePaths: List<String> = emptyList(),
)

/**
 * Split Verification Card for Mobile -> Desktop handoffs (RFC-0110, RFC-0111). The node is NEEDS_REVIEW;
 * Confirm records the person's review (RFC-0124 Gates). Running checks is not part of Gates yet.
 */
@Composable
fun SplitVerificationCard(
    model: SplitVerificationUiModel,
    onConfirm: (String) -> Unit,
    onViewDiff: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0F172A), shape = RoundedCornerShape(CornerTokens.md))
            .border(width = 1.dp, color = ColorTokens.StatusReview, shape = RoundedCornerShape(CornerTokens.md))
            .padding(SpacingTokens.base)
            .semantics {
                contentDescription = "Split Verification Review for ${model.taskTitle}"
            }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "⚙ Verification Required",
                    style = AidosTypography.baseTypography.titleSmall,
                    color = ColorTokens.StatusReview,
                )

                Box(
                    modifier = Modifier
                        .background(Color(0x33F97316), shape = RoundedCornerShape(CornerTokens.pill))
                        .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xxs)
                ) {
                    Text(
                        text = model.checkKind,
                        style = AidosTypography.baseTypography.labelSmall,
                        color = ColorTokens.StatusReview,
                    )
                }
            }

            Text(
                text = model.taskTitle,
                style = AidosTypography.baseTypography.bodyMedium,
                color = Color.White,
            )

            TextSpanAtom(
                text = "$ ${model.checkSpec}",
                kind = TextSpanKind.MONOSPACE_TOKEN,
                color = Color(0xFF38BDF8),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionButtonAtom(
                    text = "Inspect Diffs",
                    onClick = { onViewDiff(model.nodeId) },
                    kind = ActionButtonKind.SECONDARY,
                    modifier = Modifier.padding(end = SpacingTokens.sm),
                )

                ActionButtonAtom(
                    text = "Confirm",
                    onClick = { onConfirm(model.nodeId) },
                    kind = ActionButtonKind.PRIMARY,
                )
            }
        }
    }
}
