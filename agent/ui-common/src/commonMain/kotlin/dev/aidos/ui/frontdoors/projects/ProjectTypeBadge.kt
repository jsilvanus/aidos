package dev.aidos.ui.frontdoors.projects

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

enum class WorkspaceDomain {
    LIFE_AIR_GAPPED,
    CODE_GIT_BACKED,
}

@Composable
fun ProjectTypeBadge(
    domain: WorkspaceDomain,
    modifier: Modifier = Modifier,
) {
    val (label, bg, fg) = when (domain) {
        WorkspaceDomain.LIFE_AIR_GAPPED -> Triple(
            "🛡 ZERO-EGRESS (LIFE)",
            Color(0x33F59E0B),
            Color(0xFFF59E0B),
        )
        WorkspaceDomain.CODE_GIT_BACKED -> Triple(
            "💻 GIT-BACKED (CODE)",
            Color(0x3306B6D4),
            Color(0xFF06B6D4),
        )
    }

    Box(
        modifier = modifier
            .background(color = bg, shape = RoundedCornerShape(CornerTokens.pill))
            .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xxs)
    ) {
        Text(
            text = label,
            style = AidosTypography.baseTypography.labelSmall,
            color = fg,
        )
    }
}
