package dev.aidos.ui.frontdoors.inbox

import androidx.compose.foundation.background
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

enum class BatonTransferDirection {
    AGENT_TO_HUMAN,
    HUMAN_TO_AGENT,
    PEER_COLLABORATION,
}

@Composable
fun BatonHandoffBadge(
    direction: BatonTransferDirection,
    modifier: Modifier = Modifier,
) {
    val (label, bg, fg) = when (direction) {
        BatonTransferDirection.AGENT_TO_HUMAN -> Triple(
            "⚡ Agent ➔ Human (Baton)",
            Color(0x33F59E0B),
            ColorTokens.ActorHuman,
        )
        BatonTransferDirection.HUMAN_TO_AGENT -> Triple(
            "➔ Human ➔ Agent",
            Color(0x3306B6D4),
            ColorTokens.ActorAgent,
        )
        BatonTransferDirection.PEER_COLLABORATION -> Triple(
            "⇄ Hybrid Relay",
            Color(0x338B5CF6),
            ColorTokens.ActorHybrid,
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
