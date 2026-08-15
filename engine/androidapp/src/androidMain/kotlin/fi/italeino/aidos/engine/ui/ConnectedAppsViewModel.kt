package fi.italeino.aidos.engine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.italeino.aidos.engine.approval.AppApprovalRecord
import fi.italeino.aidos.engine.approval.AppApprovalStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * ViewModel for ConnectedAppsScreen (RFC-0103).
 *
 * Manages approval list display and user actions (Approve/Deny/Revoke/Undo).
 * Integrates with AppApprovalStore to persist decisions.
 */
class ConnectedAppsViewModel(
    private val approvalStore: AppApprovalStore
) : ViewModel() {
    
    /**
     * Flow of all known apps with their approval status, updated in real-time.
     * Ordered by most recently active first.
     */
    val approvals: Flow<List<AppApprovalRecord>> = approvalStore.watchApprovals()
    
    /**
     * User tapped Approve button on a pending app.
     */
    fun approveApp(packageName: String) {
        viewModelScope.launch {
            approvalStore.approveApp(packageName)
        }
    }
    
    /**
     * User tapped Deny button on a pending app.
     */
    fun denyApp(packageName: String) {
        viewModelScope.launch {
            approvalStore.denyApp(packageName)
        }
    }
    
    /**
     * User tapped Revoke Access button on an approved app.
     */
    fun revokeApproval(packageName: String) {
        viewModelScope.launch {
            approvalStore.revokeApproval(packageName)
        }
    }
    
    /**
     * User tapped Undo Denial button on a denied app.
     */
    fun undoDenyApp(packageName: String) {
        viewModelScope.launch {
            approvalStore.undoDenyApp(packageName)
        }
    }
}
