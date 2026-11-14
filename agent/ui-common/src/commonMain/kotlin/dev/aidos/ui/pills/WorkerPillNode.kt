package dev.aidos.ui.pills

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens

@Composable
fun WorkerPillNode(
    role: PillRole,
    label: String,
    activeTaskTitle: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier

    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .then(clickModifier)
                .background(
                    color = Color(0xEE0F172A),
                    shape = RoundedCornerShape(CornerTokens.pill)
                )
                .border(
                    width = 1.dp,
                    color = role.color,
                    shape = RoundedCornerShape(CornerTokens.pill)
                )
                .padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.xs)
        ) {
            Text(
                text = "${role.iconEmoji} ${role.displayName}",
                style = AidosTypography.baseTypography.labelSmall,
                color = role.color,
            )

            if (!activeTaskTitle.isNullOrBlank()) {
                Text(
                    text = " • $activeTaskTitle",
                    style = AidosTypography.baseTypography.labelSmall,
                    color = Color(0xFFCBD5E1),
                )
            }
        }
    }
}
