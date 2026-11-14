package dev.aidos.ui.tcr.code

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens

@Composable
fun ScopePathChipAtom(
    path: String,
    isLocked: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .then(clickModifier)
            .background(
                color = if (isLocked) Color(0x33EF4444) else Color(0x2238BDF8),
                shape = RoundedCornerShape(CornerTokens.xs)
            )
            .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xxs)
    ) {
        val lockIndicator = if (isLocked) "🔒 " else "📄 "
        Text(
            text = "$lockIndicator$path",
            style = AidosTypography.baseTypography.labelSmall,
            color = if (isLocked) Color(0xFFFCA5A5) else Color(0xFF7DD3FC),
        )
    }
}
