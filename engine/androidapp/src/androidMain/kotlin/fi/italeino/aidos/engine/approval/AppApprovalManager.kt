package fi.italeino.aidos.engine.approval

import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import fi.italeino.aidos.engine.notification.AppNotificationManager

/**
 * Orchestrates app approval decisions and handshake responses (RFC-0103).
 *
 * Responsibilities:
 * 1. Check if an app is approved for this Engine handshake.
 * 2. Return appropriate handshake response (APPROVED or PENDING_APPROVAL with deep-link).
 * 3. Post notification when a new app first requests access.
 * 4. Track attempts and metadata.
 */
class AppApprovalManager(
    context: Context,
    private val store: AppApprovalStore,
    private val notificationManager: AppNotificationManager
) {
    
    private val packageManager: PackageManager = context.packageManager
    private val context = context
    
    /**
     * Check and record a handshake attempt from [callerPackageName].
     *
     * Returns [ApprovalDecision.Approved] if the app is pre-approved,
     * or [ApprovalDecision.PendingApproval] if approval is needed.
     *
     * Denied apps return [ApprovalDecision.Denied].
     *
     * RFC-0103: First handshake creates a PENDING record and posts a notification.
     */
    suspend fun checkApproval(callerPackageName: String): ApprovalDecision {
        val existing = store.getApproval(callerPackageName)
        
        return if (existing == null) {
            // First time this app is requesting access.
            // Record it as PENDING and notify the user.
            val displayName = getDisplayName(callerPackageName)
            val record = store.recordFirstHandshake(callerPackageName, displayName)
            
            // Post notification to alert user.
            notificationManager.notifyPendingApproval(callerPackageName, displayName)
            
            ApprovalDecision.PendingApproval(
                deepLinkIntent = notificationManager.createConnectedAppsDeepLink(context)
            )
        } else {
            // Update last-seen timestamp.
            store.recordHandshakeAttempt(callerPackageName)
            
            when (existing.status) {
                AppApprovalStatus.APPROVED -> ApprovalDecision.Approved
                AppApprovalStatus.DENIED -> ApprovalDecision.Denied
                AppApprovalStatus.PENDING -> ApprovalDecision.PendingApproval(
                    deepLinkIntent = notificationManager.createConnectedAppsDeepLink(context)
                )
            }
        }
    }
    
    /**
     * Persist request count for an approved app.
     * Called on shutdown or periodically to save in-memory counters to storage.
     */
    suspend fun persistRequestCount(packageName: String, count: Int) {
        store.updateRequestCount(packageName, count)
    }
    
    private fun getDisplayName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}

/**
 * Result of an approval check (RFC-0103).
 */
sealed class ApprovalDecision {
    /**
     * App is approved; handshake returns full credentials {status: "APPROVED", port, token, ...}.
     */
    object Approved : ApprovalDecision()
    
    /**
     * App is denied; handshake returns 401 Unauthorized or error.
     */
    object Denied : ApprovalDecision()
    
    /**
     * App is pending approval; handshake returns {status: "PENDING_APPROVAL", deepLinkIntent}.
     * deepLinkIntent brings user to ConnectedAppsScreen for this app.
     */
    data class PendingApproval(val deepLinkIntent: PendingIntent) : ApprovalDecision()
}
