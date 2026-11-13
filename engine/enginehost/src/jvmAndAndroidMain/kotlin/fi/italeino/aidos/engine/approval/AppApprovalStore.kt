package fi.italeino.aidos.engine.approval

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Approval decision for a caller app (RFC-0103, user-approval model).
 *
 * Each app can be in one of three states:
 * - APPROVED: user explicitly approved this app to use Engine
 * - DENIED: user explicitly denied this app (sticky until undone)
 * - PENDING: app has requested access but user hasn't decided yet
 */
@Serializable
enum class AppApprovalStatus {
    APPROVED, DENIED, PENDING
}

/**
 * Metadata about an app requesting access (RFC-0103).
 */
@Serializable
data class AppApprovalRecord(
    /** Package name of the requesting app (from Binder caller context). */
    val packageName: String,
    
    /** Display name derived from PackageManager label (human-readable). */
    val displayName: String,
    
    /** Current approval status. */
    val status: AppApprovalStatus,
    
    /** Timestamp when approval decision was made (or null if still pending). */
    val decidedAt: String?,
    
    /** Timestamp of first handshake request. */
    val firstSeenAt: String,
    
    /** Timestamp of most recent handshake attempt. */
    val lastSeenAt: String,
    
    /** Total number of handshake attempts (for metrics). */
    val attemptCount: Int,
    
    /** Approximate total number of inference requests (session-scoped or summed). */
    val requestCount: Int = 0,

    /** SDK build the app last reported (`X-Aidos-Sdk-Version`); null until it sends a request. Recorded, never enforced. */
    val sdkVersion: String? = null,

    /** API version the app last reported speaking (`X-Aidos-Api-Version`); null until it sends a request. */
    val apiVersion: Int? = null,

    /**
     * D49: the user has allowed this app to run agents (and so to have the Engine act through tools).
     * Separate from [status], which only covers inference; default false, so an inference-only app
     * cannot start agent runs until the user grants it. Cleared when approval is revoked.
     */
    val agentAllowed: Boolean = false,

    /**
     * RFC-0104 per-app locality limit: null means no extra cap, otherwise one of
     * `local_only`, `trusted_cluster`, `any`.
     */
    val localityLimit: String? = null,

    /** Number of times this app hit a locality refusal (HTTP 403 locality_violation). */
    val localityRefusalCount: Int = 0,

    /** Timestamp of the latest locality refusal, or null if none. */
    val lastLocalityRefusalAt: String? = null,

    /** Latest locality refusal code (`placement_local|cluster|remote`), for audit/debug visibility. */
    val lastLocalityRefusalCode: String? = null
)

/**
 * Persistent storage for app approval decisions (RFC-0103).
 *
 * Backed by encrypted shared preferences on Android, not in-memory,
 * so decisions survive Engine restarts and app crashes.
 *
 * Note: "request count" starts in-memory per Engine service lifetime
 * and is published to storage on graceful shutdown; does not require
 * atomic increment on every HTTP request (would be excessive overhead).
 */
interface AppApprovalStore {
    /**
     * Get the approval status and metadata for an app.
     * Returns null if the app has never been seen before (first handshake).
     */
    suspend fun getApproval(packageName: String): AppApprovalRecord?
    
    /**
     * List all known apps with their approval status, ordered by lastSeenAt descending
     * (most recently active first).
     */
    suspend fun listAllApprovals(): List<AppApprovalRecord>
    
    /**
     * Watch for changes to approval list. Emits the full list whenever any record changes.
     */
    fun watchApprovals(): Flow<List<AppApprovalRecord>>
    
    /**
     * Approve an app that is currently pending.
     * Sets status to APPROVED and records the timestamp.
     */
    suspend fun approveApp(packageName: String): AppApprovalRecord?
    
    /**
     * Deny an app (first time or after approval). Sets status to DENIED.
     * Denying is sticky — the app will see DENIED on retry, not PENDING.
     */
    suspend fun denyApp(packageName: String): AppApprovalRecord?
    
    /**
     * Undo a denial, returning the app to PENDING status so it can be approved.
     */
    suspend fun undoDenyApp(packageName: String): AppApprovalRecord?
    
    /**
     * Revoke approval: move from APPROVED back to PENDING.
     * Forces re-approval on next handshake.
     */
    suspend fun revokeApproval(packageName: String): AppApprovalRecord?
    
    /**
     * Record a new app's first handshake attempt.
     * Returns the record with status PENDING.
     */
    suspend fun recordFirstHandshake(
        packageName: String,
        displayName: String
    ): AppApprovalRecord
    
    /**
     * Update lastSeenAt and attemptCount on subsequent handshake attempts.
     */
    suspend fun recordHandshakeAttempt(packageName: String): AppApprovalRecord?
    
    /**
     * Update request count for an approved app (for metrics/analytics).
     * Call on shutdown or periodically to persist the count.
     */
    suspend fun updateRequestCount(packageName: String, increment: Int): AppApprovalRecord?
    
    /**
     * Record the versions an approved app reported on a request. A no-op when they are unchanged,
     * so calling it on every request costs no write. Returns null for an unknown app.
     */
    suspend fun recordClientVersions(packageName: String, sdkVersion: String?, apiVersion: Int?): AppApprovalRecord?

    /**
     * Grant or withdraw the agent permission (D49). Returns null for an unknown app.
     */
    suspend fun setAgentAllowed(packageName: String, allowed: Boolean): AppApprovalRecord?

    /**
     * Set or clear this app's RFC-0104 locality cap. Null means no extra app-level cap.
     */
    suspend fun setLocalityLimit(packageName: String, localityLimit: String?): AppApprovalRecord?

    /**
     * Record a locality refusal seen at the Engine boundary for this app.
     */
    suspend fun recordLocalityRefusal(packageName: String, requiredLocality: String, placement: String): AppApprovalRecord?

    /**
     * Delete all records (for testing, or if Engine is uninstalled).
     */
    suspend fun clear()
}
