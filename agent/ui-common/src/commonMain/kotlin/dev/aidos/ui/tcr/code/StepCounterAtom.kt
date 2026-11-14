package dev.aidos.ui.tcr.code

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

/**
 * Bounded run step meter (e.g., "Step 3 / 5").
 */
@Composable
fun StepCounterAtom(
    currentStep: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier,
) {
    val progressColor = if (currentStep >= totalSteps) Color(0xFF10B981) else Color(0xFF06B6D4)

    Box(
        modifier = modifier
            .background(
                color = progressColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(CornerTokens.pill),
            )
            .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xxs)
    ) {
        Text(
            text = "Step $currentStep / $totalSteps",
            style = AidosTypography.baseTypography.labelSmall,
            color = progressColor,
        )
    }
}
