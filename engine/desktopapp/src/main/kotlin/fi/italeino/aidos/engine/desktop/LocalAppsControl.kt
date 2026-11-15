package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.approval.AppApprovalRecord
import fi.italeino.aidos.engine.approval.AppApprovalStore
import fi.italeino.aidos.engine.control.AppsControl

/** [AppsControl] over this process's approval store: the in-process path, and what the admin API serves from. */
class LocalAppsControl(private val host: DesktopEngineHost) : AppsControl {
    private val store: AppApprovalStore get() = host.approvalStore

    override suspend fun list(): List<AppApprovalRecord> = store.listAllApprovals()
    override suspend fun approve(id: String) = store.approveApp(id) != null
    override suspend fun deny(id: String) = store.denyApp(id) != null
    override suspend fun revoke(id: String) = store.revokeApproval(id) != null
    override suspend fun undoDeny(id: String) = store.undoDenyApp(id) != null
    override suspend fun setAgentAllowed(id: String, allowed: Boolean) = store.setAgentAllowed(id, allowed) != null
    override suspend fun setLocalityLimit(id: String, limit: String?): Boolean {
        require(limit == null || limit in setOf("local_only", "trusted_cluster", "any")) { "limit must be local_only, trusted_cluster or any" }
        return store.setLocalityLimit(id, limit) != null
    }
}
