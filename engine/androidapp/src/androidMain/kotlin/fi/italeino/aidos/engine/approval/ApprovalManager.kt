package fi.italeino.aidos.engine.approval

import kotlin.collections.HashMap

/**
 * Manages app approval state for Aidos Engine (RFC-0103).
 *
 * Tracks which apps have requested access to Aidos Engine and their approval status.
 * Approval grants are session-scoped (cleared on Engine restart) in v1.
 *
 * Approval workflow:
 * 1. App requests handshake → `getApprovalStatus(packageName)` returns "pending" if unseen
 * 2. User approves/denies in Connected Apps screen
 * 3. On next handshake, status is "approved" or "denied"
 */
class ApprovalManager {
    private val approvals = HashMap<String, ApprovalGrant>()
    private val requestCounts = HashMap<String, Int>()

    /**
     * Get the approval status for an app package.
     * Returns one of: "pending" (first request), "approved", or "denied".
     */
    fun getApprovalStatus(packageName: String): ApprovalStatus {
        return approvals[packageName]?.let {
            when (it.status) {
                GrantStatus.PENDING -> ApprovalStatus.PENDING
                GrantStatus.APPROVED -> ApprovalStatus.APPROVED
                GrantStatus.DENIED -> ApprovalStatus.DENIED
            }
        } ?: run {
            // First request from this app
            approvals[packageName] = ApprovalGrant(
                packageName = packageName,
                status = GrantStatus.PENDING,
                requestedAt = System.currentTimeMillis()
            )
            ApprovalStatus.PENDING
        }
    }

    /**
     * Approve an app to access Aidos Engine.
     */
    fun approveApp(packageName: String) {
        val grant = approvals[packageName] ?: ApprovalGrant(
            packageName = packageName,
            status = GrantStatus.PENDING,
            requestedAt = System.currentTimeMillis()
        )
        approvals[packageName] = grant.copy(
            status = GrantStatus.APPROVED,
            approvedAt = System.currentTimeMillis()
        )
    }

    /**
     * Deny an app access to Aidos Engine.
     */
    fun denyApp(packageName: String) {
        val grant = approvals[packageName] ?: ApprovalGrant(
            packageName = packageName,
            status = GrantStatus.PENDING,
            requestedAt = System.currentTimeMillis()
        )
        approvals[packageName] = grant.copy(
            status = GrantStatus.DENIED,
            deniedAt = System.currentTimeMillis()
        )
    }

    /**
     * Revoke a previously-approved app.
     */
    fun revokeApp(packageName: String) {
        approvals.remove(packageName)
        requestCounts.remove(packageName)
    }

    /**
     * Get all pending approval requests.
     */
    fun getPendingRequests(): List<ApprovalGrant> {
        return approvals.values.filter { it.status == GrantStatus.PENDING }
    }

    /**
     * Get all approved apps.
     */
    fun getApprovedApps(): List<ApprovalGrant> {
        return approvals.values.filter { it.status == GrantStatus.APPROVED }
    }

    /**
     * Get all connected apps (both pending and approved).
     */
    fun getAllApps(): List<ApprovalGrant> {
        return approvals.values.toList()
    }

    /**
     * Increment the request counter for an approved app.
     * Used to track session-scoped usage counts.
     */
    fun recordRequest(packageName: String) {
        val count = requestCounts.getOrDefault(packageName, 0)
        requestCounts[packageName] = count + 1
    }

    /**
     * Get the request count for an app (session-scoped).
     */
    fun getRequestCount(packageName: String): Int {
        return requestCounts.getOrDefault(packageName, 0)
    }
}

/**
 * Represents a single app's approval grant.
 */
data class ApprovalGrant(
    val packageName: String,
    val status: GrantStatus,
    val requestedAt: Long,
    val approvedAt: Long? = null,
    val deniedAt: Long? = null
)

/**
 * Approval status enum: the three possible states.
 */
enum class GrantStatus {
    PENDING,
    APPROVED,
    DENIED
}

/**
 * Approval status as returned to clients in the handshake response.
 */
enum class ApprovalStatus {
    PENDING,
    APPROVED,
    DENIED
}
