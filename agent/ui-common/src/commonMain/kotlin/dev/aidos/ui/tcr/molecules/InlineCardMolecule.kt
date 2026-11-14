package dev.aidos.ui.tcr.molecules

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.aidos.intent.IntentActorKind
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.theme.colorForActor

@Composable
fun InlineCardMolecule(
    actorKind: IntentActorKind,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val accentColor = colorForActor(actorKind)

    Surface(
        shape = RoundedCornerShape(CornerTokens.md),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f)),
        color = ColorTokens.SurfaceCardDark,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(SpacingTokens.base),
            content = content,
        )
    }
}
