package dev.aidos.ui.paradigms.pipeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.components.QuickIntentCreatorBar
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens

@Composable
fun RelayPipelineScreen(
    presenter: RelayPipelinePresenter,
    onCreateIntent: ((title: String, type: IntentNodeType, actorKind: IntentActorKind) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by presenter.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ColorTokens.SurfaceDark)
            .padding(SpacingTokens.base)
    ) {
        Text(
            text = "Relay River: Phase-Gated Pipeline",
            style = AidosTypography.baseTypography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(bottom = SpacingTokens.md),
        )

        if (onCreateIntent != null) {
            QuickIntentCreatorBar(
                onCreateIntent = onCreateIntent,
                modifier = Modifier.padding(bottom = SpacingTokens.sm),
            )
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.lg),
            contentPadding = PaddingValues(vertical = SpacingTokens.sm),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.stages, key = { it.stageIndex }) { stage ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F172A), shape = RoundedCornerShape(CornerTokens.md))
                        .border(1.dp, Color(0xFF334155), shape = RoundedCornerShape(CornerTokens.md))
                        .padding(SpacingTokens.base),
                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
                ) {
                    Text(
                        text = "Stage ${stage.stageIndex}",
                        style = AidosTypography.baseTypography.titleMedium,
                        color = Color(0xFF38BDF8),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md),
                    ) {
                        // Human track
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "👤 Human Track",
                                style = AidosTypography.baseTypography.labelMedium,
                                color = ColorTokens.ActorHuman,
                            )
                            stage.humanNodes.forEach { node ->
                                Text(
                                    text = "• ${node.title}",
                                    style = AidosTypography.baseTypography.bodySmall,
                                    color = Color.White,
                                )
                            }
                        }

                        // Agent track
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "🤖 Agent Track",
                                style = AidosTypography.baseTypography.labelMedium,
                                color = ColorTokens.ActorAgent,
                            )
                            stage.agentNodes.forEach { node ->
                                Text(
                                    text = "• ${node.title}",
                                    style = AidosTypography.baseTypography.bodySmall,
                                    color = Color.White,
                                )
                            }
                        }
                    }

                    if (stage.hasBatonCross) {
                        BatonVectorLine(isHumanToAgent = true)
                    }
                }
            }
        }
    }
}
