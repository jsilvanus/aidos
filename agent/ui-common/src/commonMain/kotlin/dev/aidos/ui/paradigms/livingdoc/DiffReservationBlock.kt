package dev.aidos.ui.paradigms.livingdoc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.aidos.ui.tcr.code.DiffChunkAtom
import dev.aidos.ui.tcr.code.DiffLine
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.SpacingTokens

/**
 * Unfurls completed agent reservations into inline diff blocks (RFC-0106).
 */
@Composable
fun DiffReservationBlock(
    reservationTitle: String,
    agentRoleLabel: String,
    diffLines: List<DiffLine>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = SpacingTokens.sm),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
    ) {
        Text(
            text = "⚡ Completed Agent Edit by $agentRoleLabel: $reservationTitle",
            style = AidosTypography.baseTypography.labelMedium,
            color = ColorTokens.ActorAgent,
        )

        DiffChunkAtom(
            header = reservationTitle,
            lines = diffLines,
        )
    }
}
