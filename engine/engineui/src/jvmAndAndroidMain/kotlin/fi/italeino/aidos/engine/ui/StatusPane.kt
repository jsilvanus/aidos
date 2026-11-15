package fi.italeino.aidos.engine.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.aidos.tcr.engine.MemoryBudget as TcrMemoryBudget
import dev.aidos.tcr.engine.ResidentModel as TcrResidentModel
import dev.aidos.tcr.engine.StatusPane as TcrStatusPane
import dev.aidos.tcr.engine.StatusUiState as TcrStatusUiState

/** Everything the Status pane shows; each host fills it from its own runtime and device. */
data class StatusUiState(
    val isEngineRunning: Boolean = false,
    /** Plain lifecycle state name shown under the ON/OFF label, e.g. "READY". */
    val engineStateName: String = "OFF",
    val residentModels: List<ResidentModel> = emptyList(),
    val memory: MemoryBudget = MemoryBudget(0, 0),
    /** Free space on the volume holding the models, or null when unknown. */
    val freeStorageGb: Int? = null,
    /** Display names of apps the user has approved to use the Engine. */
    val approvedApps: List<String> = emptyList(),
    val pendingAppCount: Int = 0,
    val linkedPeerCount: Int = 0,
)

/**
 * Status pane (RFC-0103, Phase D): binds the host's [StatusUiState] to the TCR `StatusPane`, which
 * owns all rendering (D46). [engineControl] is host-specific (Android: hold-to-toggle card; desktop:
 * click), usually a `dev.aidos.tcr.engine.EngineControlCard` with the host's gesture attached.
 */
@Composable
fun StatusPane(
    state: StatusUiState,
    engineControl: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    TcrStatusPane(state = state.toTcr(), engineControl = engineControl, modifier = modifier)
}

private fun StatusUiState.toTcr() = TcrStatusUiState(
    isEngineRunning = isEngineRunning,
    engineStateName = engineStateName,
    residentModels = residentModels.map { TcrResidentModel(it.id, it.displayName, it.quantization, it.loadedAgoMs) },
    memory = TcrMemoryBudget(memory.usedMB, memory.totalMB),
    freeStorageGb = freeStorageGb,
    approvedApps = approvedApps,
    pendingAppCount = pendingAppCount,
    linkedPeerCount = linkedPeerCount,
)
