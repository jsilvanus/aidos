package fi.italeino.aidos.engine.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.aidos.tcr.engine.AppApprovalRecordUi
import dev.aidos.tcr.engine.AppApprovalStatus as TcrAppApprovalStatus
import dev.aidos.tcr.engine.ConnectedAppsPane as TcrConnectedAppsPane
import fi.italeino.aidos.engine.approval.AppApprovalRecord
import fi.italeino.aidos.engine.approval.AppApprovalStatus

/**
 * Connected Apps pane (RFC-0103, Phase D): binds the Engine's approval records to the TCR
 * `ConnectedAppsPane`, which owns all rendering (D46). Callbacks receive the app's package name
 * (on desktop, the debug handshake's identifier).
 */
@Composable
fun ConnectedAppsPane(
    approvals: List<AppApprovalRecord>,
    isEngineRunning: Boolean,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onRevoke: (String) -> Unit,
    onUndoDeny: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** D49: when the host runs agents, lets the user grant or withdraw an approved app's agent access; null hides it. */
    onSetAgentAllowed: ((String, Boolean) -> Unit)? = null,
    /** RFC-0104: set/clear a per-app locality cap (`local_only`, `trusted_cluster`, `any`, or null for no extra cap). */
    onSetLocalityLimit: ((String, String?) -> Unit)? = null,
    notRunningText: String = "The Engine is not running. Start it from Home to manage app access.",
) {
    TcrConnectedAppsPane(
        approvals = approvals.map { it.toUi() },
        isEngineRunning = isEngineRunning,
        onApprove = onApprove,
        onDeny = onDeny,
        onRevoke = onRevoke,
        onUndoDeny = onUndoDeny,
        modifier = modifier,
        notRunningText = notRunningText,
        onSetAgentAllowed = onSetAgentAllowed,
        onSetLocalityLimit = onSetLocalityLimit,
    )
}

private fun AppApprovalRecord.toUi() = AppApprovalRecordUi(
    packageName = packageName,
    displayName = displayName,
    status = when (status) {
        AppApprovalStatus.APPROVED -> TcrAppApprovalStatus.APPROVED
        AppApprovalStatus.DENIED -> TcrAppApprovalStatus.DENIED
        AppApprovalStatus.PENDING -> TcrAppApprovalStatus.PENDING
    },
    decidedAt = decidedAt,
    firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt,
    attemptCount = attemptCount,
    requestCount = requestCount,
    sdkVersion = sdkVersion,
    apiVersion = apiVersion,
    agentAllowed = agentAllowed,
    localityLimit = localityLimit,
    localityRefusalCount = localityRefusalCount,
    lastLocalityRefusalAt = lastLocalityRefusalAt,
)
