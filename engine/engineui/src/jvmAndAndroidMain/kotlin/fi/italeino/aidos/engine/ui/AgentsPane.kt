package fi.italeino.aidos.engine.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.aidos.tcr.agent.AgentFilterTab as TcrAgentFilterTab
import dev.aidos.tcr.agent.AgentLocality
import dev.aidos.tcr.agent.AgentOverviewUiState as TcrAgentOverviewUiState
import dev.aidos.tcr.agent.AgentPlacement
import dev.aidos.tcr.agent.AgentRunUiModel
import dev.aidos.tcr.agent.AgentRuntimeOverviewStats
import dev.aidos.tcr.agent.AgentViewLayout as TcrAgentViewLayout
import dev.aidos.tcr.agent.AgentsPane as TcrAgentsPane
import dev.aidos.tcr.agent.TcrProjectModelConfigUiModel
import dev.aidos.tcr.agent.TcrRunLaunchConfigUiModel
import dev.aidos.tcr.agent.TcrTaintApprovalPolicy

enum class AgentFilterTab(val label: String) {
    ALL("All"),
    ACTIVE("Active"),
    AWAITING_TOOLS("Waiting on Tools"),
    COMPLETED("Completed"),
    FAILED("Failed / Cancelled");

    internal fun toTcr(): TcrAgentFilterTab = when (this) {
        ALL -> TcrAgentFilterTab.ALL
        ACTIVE -> TcrAgentFilterTab.ACTIVE
        AWAITING_TOOLS -> TcrAgentFilterTab.AWAITING_TOOLS
        COMPLETED -> TcrAgentFilterTab.COMPLETED
        FAILED -> TcrAgentFilterTab.FAILED
    }

    internal companion object {
        fun fromTcr(tab: TcrAgentFilterTab): AgentFilterTab = when (tab) {
            TcrAgentFilterTab.ALL -> ALL
            TcrAgentFilterTab.ACTIVE -> ACTIVE
            TcrAgentFilterTab.AWAITING_TOOLS -> AWAITING_TOOLS
            TcrAgentFilterTab.COMPLETED -> COMPLETED
            TcrAgentFilterTab.FAILED -> FAILED
        }
    }
}

enum class AgentViewLayout(val label: String) {
    HIERARCHY("🌳 Subagent Hierarchy"),
    FLAT("📋 Flat Feed");

    internal fun toTcr(): TcrAgentViewLayout = when (this) {
        HIERARCHY -> TcrAgentViewLayout.HIERARCHY
        FLAT -> TcrAgentViewLayout.FLAT
    }

    internal companion object {
        fun fromTcr(layout: TcrAgentViewLayout): AgentViewLayout = when (layout) {
            TcrAgentViewLayout.HIERARCHY -> HIERARCHY
            TcrAgentViewLayout.FLAT -> FLAT
        }
    }
}

enum class PlacementFilterOption(val label: String, val placement: AgentPlacement?) {
    ALL("All Placements", null),
    LOCAL("🟢 Local (Device)", AgentPlacement.LOCAL),
    CLUSTER("🔵 Cluster (Peer)", AgentPlacement.CLUSTER),
    REMOTE("🟣 Remote (Cloud)", AgentPlacement.REMOTE)
}

data class AgentOverviewUiState(
    val stats: AgentRuntimeOverviewStats = AgentRuntimeOverviewStats(),
    val runs: List<AgentRunUiModel> = emptyList(),
    val selectedAppFilter: String? = null,
    val selectedStatusFilter: AgentFilterTab = AgentFilterTab.ALL,
    val selectedPlacementFilter: AgentPlacement? = null,
    val selectedViewLayout: AgentViewLayout = AgentViewLayout.HIERARCHY,
    val searchQuery: String = "",
    val isEngineRunning: Boolean = true,
    val selectedRunId: String? = null,
    val projectConfig: TcrProjectModelConfigUiModel? = null,
    val runLaunchConfig: TcrRunLaunchConfigUiModel = TcrRunLaunchConfigUiModel(),
)

/**
 * Shared Stateless Compose UI for Agent Runtime Control & Telemetry.
 * Delegates rendering to the authoritative `dev.aidos.tcr.agent.AgentsPane`.
 * Consumed by both DesktopApp and Android MainActivity.
 */
@Composable
fun AgentsPane(
    state: AgentOverviewUiState,
    onAppFilterSelected: (String?) -> Unit,
    onStatusFilterSelected: (AgentFilterTab) -> Unit,
    onPlacementFilterSelected: (AgentPlacement?) -> Unit = {},
    onSearchQueryChanged: (String) -> Unit = {},
    onResetFilters: () -> Unit = {},
    onViewLayoutSelected: (AgentViewLayout) -> Unit = {},
    onCancelRun: (String) -> Unit,
    onInspectRun: (String?) -> Unit,
    modifier: Modifier = Modifier,
    onMainModelChange: (String) -> Unit = {},
    onDefaultRunModelChange: (String) -> Unit = {},
    onProjectLocalityChange: (AgentLocality) -> Unit = {},
    onProjectGuardianPolicyChange: (TcrTaintApprovalPolicy) -> Unit = {},
    onDecisionModelChange: (String) -> Unit = {},
    onGuardianLlmChange: (String) -> Unit = {},
    onRunGuardianPolicyOverride: (TcrTaintApprovalPolicy?) -> Unit = {},
    onRunModelOverride: (String) -> Unit = {},
    onRunLocalityOverride: (AgentLocality?) -> Unit = {},
) {
    TcrAgentsPane(
        state = TcrAgentOverviewUiState(
            stats = state.stats,
            runs = state.runs,
            selectedAppFilter = state.selectedAppFilter,
            selectedStatusFilter = state.selectedStatusFilter.toTcr(),
            selectedPlacementFilter = state.selectedPlacementFilter,
            selectedViewLayout = state.selectedViewLayout.toTcr(),
            searchQuery = state.searchQuery,
            isEngineRunning = state.isEngineRunning,
            selectedRunId = state.selectedRunId,
            projectConfig = state.projectConfig,
            runLaunchConfig = state.runLaunchConfig,
        ),
        onAppFilterSelected = onAppFilterSelected,
        onStatusFilterSelected = { onStatusFilterSelected(AgentFilterTab.fromTcr(it)) },
        onPlacementFilterSelected = onPlacementFilterSelected,
        onSearchQueryChanged = onSearchQueryChanged,
        onResetFilters = onResetFilters,
        onViewLayoutSelected = { onViewLayoutSelected(AgentViewLayout.fromTcr(it)) },
        onCancelRun = onCancelRun,
        onInspectRun = onInspectRun,
        modifier = modifier,
        onMainModelChange = onMainModelChange,
        onDefaultRunModelChange = onDefaultRunModelChange,
        onProjectLocalityChange = onProjectLocalityChange,
        onProjectGuardianPolicyChange = onProjectGuardianPolicyChange,
        onDecisionModelChange = onDecisionModelChange,
        onGuardianLlmChange = onGuardianLlmChange,
        onRunGuardianPolicyOverride = onRunGuardianPolicyOverride,
        onRunModelOverride = onRunModelOverride,
        onRunLocalityOverride = onRunLocalityOverride,
    )
}
