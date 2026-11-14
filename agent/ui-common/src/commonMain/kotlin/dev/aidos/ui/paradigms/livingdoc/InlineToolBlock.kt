package dev.aidos.ui.paradigms.livingdoc

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

/**
 * TCR molecular tool injected at the point of thought (RFC-0106).
 * Tools are NEVER rendered in floating side panels.
 */
@Composable
fun InlineToolBlock(
    toolName: String,
    statusText: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0F172A), shape = RoundedCornerShape(CornerTokens.sm))
            .border(width = 1.dp, color = Color(0xFF334155), shape = RoundedCornerShape(CornerTokens.sm))
            .padding(SpacingTokens.md)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "🛠 Inline Tool: $toolName",
                    style = AidosTypography.baseTypography.labelMedium,
                    color = ColorTokens.ActorAgent,
                )

                Text(
                    text = statusText,
                    style = AidosTypography.baseTypography.labelSmall,
                    color = Color(0xFF94A3B8),
                )
            }

            content()
        }
    }
}
