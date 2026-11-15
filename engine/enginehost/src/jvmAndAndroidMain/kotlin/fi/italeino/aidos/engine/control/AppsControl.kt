package fi.italeino.aidos.engine.control

import fi.italeino.aidos.engine.approval.AppApprovalRecord
import kotlinx.serialization.Serializable

/**
 * The connected apps group of `EngineControl` (RFC-0115, D61; RFC-0103 approvals, D49 agent use, RFC-0104 locality):
 * which apps may use this Engine, and the user's decision on each. The decision methods return false when the app
 * is not known (nothing changed), so a stale screen shows "gone" rather than an error.
 */
interface AppsControl {
    suspend fun list(): List<AppApprovalRecord>
    suspend fun approve(id: String): Boolean
    suspend fun deny(id: String): Boolean
    suspend fun revoke(id: String): Boolean
    suspend fun undoDeny(id: String): Boolean
    suspend fun setAgentAllowed(id: String, allowed: Boolean): Boolean

    /** [limit] is null (no extra cap), `local_only`, `trusted_cluster` or `any`. */
    suspend fun setLocalityLimit(id: String, limit: String?): Boolean
}

@Serializable data class AppAgentAllowedRequest(val allowed: Boolean)
@Serializable data class AppLocalityLimitRequest(val limit: String? = null)
