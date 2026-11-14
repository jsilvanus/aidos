package dev.aidos.ui.paradigms.livingdoc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.aidos.ui.pills.LineReservationLock
import dev.aidos.ui.pills.TravelingVoiceCaret
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.SpacingTokens

@Composable
fun LivingDocView(
    presenter: LivingDocPresenter,
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
            text = state.title,
            style = AidosTypography.baseTypography.headlineMedium,
            color = Color.White,
            modifier = Modifier.padding(bottom = SpacingTokens.md),
        )

        if (state.isRecordingVoice || state.ghostText.isNotEmpty()) {
            TravelingVoiceCaret(
                isRecording = state.isRecordingVoice,
                ghostText = state.ghostText,
                modifier = Modifier.padding(bottom = SpacingTokens.sm),
            )
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
            contentPadding = PaddingValues(vertical = SpacingTokens.sm),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            items(state.lines, key = { it.lineNumber }) { line ->
                LineReservationLock(
                    isLocked = line.isLockedByAgent,
                    lockOwnerRole = line.lockOwner,
                ) {
                    Text(
                        text = line.text,
                        style = AidosTypography.baseTypography.bodyMedium,
                        color = Color(0xFFF1F5F9),
                    )
                }
            }

            items(state.completedDiffs) { (title, diffs) ->
                DiffReservationBlock(
                    reservationTitle = title,
                    agentRoleLabel = "Worker Code (🔵 Blue)",
                    diffLines = diffs,
                )
            }
        }
    }
}
