package dev.aidos.ui.paradigms.outliner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens

@Composable
fun CausalJumpPill(
    prerequisiteId: String,
    prerequisiteTitle: String,
    onJump: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(Color(0x33EF4444), shape = RoundedCornerShape(CornerTokens.pill))
            .clickable { onJump(prerequisiteId) }
            .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xxs)
    ) {
        Text(
            text = "⏳ Blocked by #$prerequisiteId ($prerequisiteTitle)",
            style = AidosTypography.baseTypography.labelSmall,
            color = ColorTokens.StatusBlocked,
        )
    }
}
