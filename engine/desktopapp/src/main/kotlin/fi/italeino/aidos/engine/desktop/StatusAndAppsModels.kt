package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.EngineState
import fi.italeino.aidos.engine.approval.AppApprovalRecord
import fi.italeino.aidos.engine.approval.AppApprovalStatus
import fi.italeino.aidos.engine.ui.ResidentModel
import fi.italeino.aidos.engine.ui.StatusUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Desktop counterpart of Android's StatusViewModel: polls the in-process host every 2 s, as the
 * Android Home screen does while visible (RAM and resident models change without UI events).
 */
class DesktopStatusModel(private val host: DesktopEngineHost, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(StatusUiState())
    val state: StateFlow<StatusUiState> = _state.asStateFlow()

    init {
        scope.launch {
            while (true) {
                refresh()
                delay(2_000)
            }
        }
    }

    fun toggleEngine() {
        scope.launch {
            if (host.isRunning.value) host.stop() else host.start()
            refresh()
        }
    }

    suspend fun refresh() {
        val approvals = host.approvalStore.listAllApprovals()
        val runtime = host.modelRuntime
        val resident = if (runtime == null) emptyList() else {
            val installedById = withContext(Dispatchers.IO) {
                host.catalogManager.listInstalled().getOrNull()
            }.orEmpty().associateBy { it.modelId }
            val now = System.currentTimeMillis()
            runtime.loaded().map { id ->
                val installed = installedById[id]
                ResidentModel(
                    id = id,
                    displayName = installed?.userLabel ?: id,
                    quantization = installed?.quantization ?: "unknown",
                    loadedAgoMs = runtime.loadedAtMillis(id)?.let { now - it } ?: 0L,
                )
            }
        }
        val port = host.boundPort.value
        _state.value = StatusUiState(
            isEngineRunning = host.isRunning.value,
            // EngineState has no "stopped" value (Android shows STARTING when off); say OFF here.
            engineStateName = when {
                host.isRunning.value && port != null -> "${host.state.value.name} · 127.0.0.1:$port"
                host.state.value == EngineState.STARTING && !host.isRunning.value -> "OFF"
                else -> host.state.value.name
            },
            residentModels = resident,
            memory = DesktopDevice.memory(),
            freeStorageGb = DesktopDevice.freeStorageGb(),
            approvedApps = approvals.filter { it.status == AppApprovalStatus.APPROVED }.map { it.displayName },
            pendingAppCount = approvals.count { it.status == AppApprovalStatus.PENDING },
        )
    }
}

/**
 * Desktop counterpart of ConnectedAppsViewModel. There is no Binder on Windows, so apps only
 * appear through [simulateHandshake], which records the same first-handshake entry
 * EngineHandshakeImpl would, letting the approve/deny/revoke/undo flow be exercised.
 */
class DesktopAppsModel(private val host: DesktopEngineHost, private val scope: CoroutineScope) {
    val approvals: StateFlow<List<AppApprovalRecord>> =
        host.approvalStore.watchApprovals().stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun simulateHandshake(displayName: String) {
        val name = displayName.trim().ifEmpty { "Debug App" }
        val id = "debug." + name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        scope.launch {
            val store = host.approvalStore
            if (store.getApproval(id) == null) store.recordFirstHandshake(id, name)
            else store.recordHandshakeAttempt(id)
        }
    }

    fun approve(id: String) = act { it.approveApp(id) }
    fun deny(id: String) = act { it.denyApp(id) }
    fun revoke(id: String) = act { it.revokeApproval(id) }
    fun undoDeny(id: String) = act { it.undoDenyApp(id) }

    private fun act(action: suspend (fi.italeino.aidos.engine.approval.AppApprovalStore) -> Unit) {
        scope.launch { action(host.approvalStore) }
    }
}
