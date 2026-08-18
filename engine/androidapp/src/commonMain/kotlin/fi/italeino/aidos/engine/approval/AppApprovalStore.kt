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
    val requestCount: Int = 0
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
     * Delete all records (for testing, or if Engine is uninstalled).
     */
    suspend fun clear()
}
