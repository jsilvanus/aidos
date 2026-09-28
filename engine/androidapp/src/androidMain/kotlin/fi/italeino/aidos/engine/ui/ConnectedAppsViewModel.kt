package fi.italeino.aidos.engine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.italeino.aidos.engine.EngineService
import fi.italeino.aidos.engine.approval.AppApprovalRecord
import fi.italeino.aidos.engine.approval.AppApprovalStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for ConnectedAppsScreen (RFC-0103).
 *
 * Shows the persisted approval decisions (AppApprovalStore) and applies the user's
 * Approve/Deny/Revoke/Undo actions to them. The store lives in EngineService, so the list is
 * empty -- with [isEngineRunning] false -- until the Engine has started.
 */
class ConnectedAppsViewModel : ViewModel() {

    private val _approvals = MutableStateFlow<List<AppApprovalRecord>>(emptyList())
    /** All known apps, most recently active first. */
    val approvals: StateFlow<List<AppApprovalRecord>> = _approvals.asStateFlow()

    private val _isEngineRunning = MutableStateFlow(false)
    val isEngineRunning: StateFlow<Boolean> = _isEngineRunning.asStateFlow()

    private var watchedStore: AppApprovalStore? = null
    private var watchJob: Job? = null

    init {
        viewModelScope.launch {
            EngineService.state.collect { refresh() }
        }
    }

    fun refresh() {
        val store = EngineService.instance?.approvalStore
        _isEngineRunning.value = store != null
        if (store == null) {
            _approvals.value = emptyList()
            return
        }
        viewModelScope.launch { _approvals.value = store.listAllApprovals() }
        if (store !== watchedStore) {
            watchedStore = store
            watchJob?.cancel()
            // A handshake from another app updates the store while this screen is open.
            watchJob = viewModelScope.launch { store.watchApprovals().collect { _approvals.value = it } }
        }
    }

    fun approveApp(packageName: String) {
        act { store -> store.approveApp(packageName) }
    }

    fun denyApp(packageName: String) {
        act { store -> store.denyApp(packageName) }
    }

    fun revokeApproval(packageName: String) {
        act { store -> store.revokeApproval(packageName) }
    }

    fun undoDenyApp(packageName: String) {
        act { store -> store.undoDenyApp(packageName) }
    }

    private fun act(action: suspend (AppApprovalStore) -> Unit) {
        val store = EngineService.instance?.approvalStore ?: return
        viewModelScope.launch {
            action(store)
            _approvals.value = store.listAllApprovals()
        }
    }
}
