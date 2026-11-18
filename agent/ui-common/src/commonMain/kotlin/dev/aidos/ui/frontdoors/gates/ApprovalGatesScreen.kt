package dev.aidos.ui.frontdoors.gates

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.aidos.ui.layout.FormFactor
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.workspace.WorkspaceController

/**
 * Gates (RFC-0124, RFC-0113, RFC-0110): every approval the person owes, in one list. Sections with
 * nothing waiting are hidden; when all are empty the screen says so.
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

        if (state.pendingCount == 0) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Nothing waiting.",
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
            if (state.toolApprovals.isNotEmpty()) {
                section("Tool approvals", state.toolApprovals.size)
                items(state.toolApprovals, key = { "tool:${it.runId}:${it.taskId}" }) { request ->
                    ToolApprovalCard(
                        request = request,
                        onApprove = { runId, taskId -> presenter.send(ApprovalGateIntent.ApproveToolEffect(runId, taskId)) },
                        onDeny = { runId, taskId, reason -> presenter.send(ApprovalGateIntent.DenyToolEffect(runId, taskId, reason)) },
                    )
                }
            }

            if (state.pendingEgressRequests.isNotEmpty()) {
                section("Outbound messages", state.pendingEgressRequests.size)
                items(state.pendingEgressRequests, key = { "egress:${it.requestId}" }) { request ->
                    BlindedEgressGateCard(
                        request = request,
                        onApprove = { id -> presenter.send(ApprovalGateIntent.ApproveEgress(id)) },
                        onDeny = { id, reason -> presenter.send(ApprovalGateIntent.DenyEgress(id, reason)) },
                    )
                }
            }

            if (state.capabilityRequests.isNotEmpty()) {
                section("Capability requests", state.capabilityRequests.size)
                items(state.capabilityRequests, key = { "cap:${it.requestId}" }) { request ->
                    CapabilityRequestCard(
                        request = request,
                        onApprove = { id -> presenter.send(ApprovalGateIntent.ApproveCapability(id)) },
                        onDeny = { id, reason -> presenter.send(ApprovalGateIntent.DenyCapability(id, reason)) },
                    )
                }
            }

            if (state.proposals.isNotEmpty()) {
                section("Proposals", state.proposals.size)
                items(state.proposals, key = { "proposal:${it.proposalId}" }) { proposal ->
                    ProposalCard(
                        model = proposal,
                        onAccept = { id -> presenter.send(ApprovalGateIntent.ResolveProposal(id, accept = true)) },
                        onReject = { id -> presenter.send(ApprovalGateIntent.ResolveProposal(id, accept = false)) },
                    )
                }
            }

            if (state.pendingVerifications.isNotEmpty()) {
                section("Verification", state.pendingVerifications.size)
                items(state.pendingVerifications, key = { "verify:${it.nodeId}" }) { verification ->
                    SplitVerificationCard(
                        model = verification,
                        onConfirm = { nodeId -> presenter.send(ApprovalGateIntent.ConfirmVerification(nodeId)) },
                        onViewDiff = { nodeId -> presenter.send(ApprovalGateIntent.InspectDiff(nodeId)) },
                    )
                }
            }

            state.inspectedDiffs?.let { diffs ->
                item(key = "diff-sheet") {
                    DiffInspectionSheet(
                        fileDiffs = diffs,
                        onClose = { presenter.send(ApprovalGateIntent.CloseDiffInspection) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: String, count: Int) {
    item(key = "header:$title") {
        Text(
            text = "$title ($count)",
            style = AidosTypography.baseTypography.titleSmall,
            color = Color.White,
        )
    }
}
