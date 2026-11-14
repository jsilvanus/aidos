package dev.aidos.ui.paradigms.pipeline

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
import androidx.compose.ui.unit.dp
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens

@Composable
fun HandoffChannelView(
    checkpointTitle: String,
    criteriaDescription: String,
    isCriteriaMet: Boolean,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (isCriteriaMet) ColorTokens.StatusDone else ColorTokens.ActorHybrid

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1B4B), shape = RoundedCornerShape(CornerTokens.md))
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(CornerTokens.md))
            .padding(SpacingTokens.base)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "🤝 Handoff Dock: $checkpointTitle",
                    style = AidosTypography.baseTypography.titleSmall,
                    color = Color.White,
                )

                Box(
                    modifier = Modifier
                        .background(
                            color = if (isCriteriaMet) Color(0x3310B981) else Color(0x338B5CF6),
                            shape = RoundedCornerShape(CornerTokens.pill)
                        )
                        .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xxs)
                ) {
                    Text(
                        text = if (isCriteriaMet) "✔ ACCEPTS Met" else "⏳ Criteria Pending",
                        style = AidosTypography.baseTypography.labelSmall,
                        color = if (isCriteriaMet) ColorTokens.StatusDone else ColorTokens.ActorHybrid,
                    )
                }
            }

            Text(
                text = criteriaDescription,
                style = AidosTypography.baseTypography.bodySmall,
                color = Color(0xFFCBD5E1),
            )
        }
    }
}
