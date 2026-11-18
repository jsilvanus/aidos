package dev.aidos.ui.frontdoors.gates

import dev.aidos.ui.layout.FormFactor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.workspace.WorkspaceController

/**
 * Unified Approval Gates Surface (RFC-0112 §3.3, RFC-0113).
 * Renders parked Blinded Egress Gate Cards and Mobile->Desktop Split Verification Cards.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun ApprovalGatesScreen(
    presenter: ApprovalGatePresenter,
    controller: WorkspaceController,
    formFactor: FormFactor,
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
            text = "Approval & Egress Gates",
            style = AidosTypography.baseTypography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(bottom = SpacingTokens.md),
        )

        state.message?.let { msg ->
            Text(
                text = msg,
                style = AidosTypography.baseTypography.labelMedium,
                color = ColorTokens.StatusDone,
                modifier = Modifier.padding(bottom = SpacingTokens.sm),
            )
        }

        if (state.pendingEgressRequests.isEmpty() && state.pendingVerifications.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No parked egress requests or split verifications.",
                    style = AidosTypography.baseTypography.bodyMedium,
                    color = ColorTokens.StatusStale,
                )
            }
            return@Column
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
            contentPadding = PaddingValues(vertical = SpacingTokens.xs),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.pendingEgressRequests, key = { it.requestId }) { request ->
                BlindedEgressGateCard(
                    request = request,
                    onApprove = { id -> presenter.send(ApprovalGateIntent.ApproveEgress(id)) },
                    onDeny = { id, reason -> presenter.send(ApprovalGateIntent.DenyEgress(id, reason)) },
                )
            }

            items(state.pendingVerifications, key = { it.nodeId }) { verification ->
                SplitVerificationCard(
                    model = verification,
                    onRunVerification = { nodeId -> presenter.send(ApprovalGateIntent.RunVerification(nodeId)) },
                    onViewDiff = { nodeId -> presenter.send(ApprovalGateIntent.InspectDiff(nodeId)) },
                )
            }
        }
    }
}
