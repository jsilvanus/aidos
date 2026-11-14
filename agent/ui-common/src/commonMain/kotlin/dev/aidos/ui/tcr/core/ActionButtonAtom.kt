package dev.aidos.ui.tcr.core

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens

enum class ActionButtonKind {
    PRIMARY,
    SECONDARY,
    DANGER,
    TACTILE_MICRO,
}

@Composable
fun ActionButtonAtom(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ActionButtonKind = ActionButtonKind.PRIMARY,
    enabled: Boolean = true,
) {
    val minSizeModifier = modifier.defaultMinSize(
        minWidth = SpacingTokens.minTouchTarget,
        minHeight = if (kind == ActionButtonKind.TACTILE_MICRO) SpacingTokens.lg else SpacingTokens.minTouchTarget,
    )

    when (kind) {
        ActionButtonKind.PRIMARY -> {
            Button(
                onClick = onClick,
                enabled = enabled,
                shape = RoundedCornerShape(CornerTokens.sm),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ColorTokens.ActorAgent,
                    contentColor = Color.Black,
                ),
                modifier = minSizeModifier,
            ) {
                Text(text)
            }
        }
        ActionButtonKind.SECONDARY -> {
            OutlinedButton(
                onClick = onClick,
                enabled = enabled,
                shape = RoundedCornerShape(CornerTokens.sm),
                modifier = minSizeModifier,
            ) {
                Text(text)
            }
        }
        ActionButtonKind.DANGER -> {
            Button(
                onClick = onClick,
                enabled = enabled,
                shape = RoundedCornerShape(CornerTokens.sm),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ColorTokens.StatusBlocked,
                    contentColor = Color.White,
                ),
                modifier = minSizeModifier,
            ) {
                Text(text)
            }
        }
        ActionButtonKind.TACTILE_MICRO -> {
            Button(
                onClick = onClick,
                enabled = enabled,
                shape = RoundedCornerShape(CornerTokens.xs),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0x3306B6D4),
                    contentColor = ColorTokens.ActorAgent,
                ),
                modifier = minSizeModifier,
            ) {
                Text(text, style = dev.aidos.ui.theme.AidosTypography.baseTypography.labelSmall)
            }
        }
    }
}
