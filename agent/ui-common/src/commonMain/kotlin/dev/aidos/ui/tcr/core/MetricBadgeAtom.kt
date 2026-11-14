package dev.aidos.ui.tcr.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens

@Composable
fun MetricBadgeAtom(
    label: String,
    value: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(
                color = accentColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(CornerTokens.pill),
            )
            .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xxs)
    ) {
        Text(
            text = "$label: $value",
            style = AidosTypography.baseTypography.labelSmall,
            color = accentColor,
        )
    }
}
