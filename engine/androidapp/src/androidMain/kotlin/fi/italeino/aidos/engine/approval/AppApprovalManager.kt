package fi.italeino.aidos.engine.approval

import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import fi.italeino.aidos.engine.notification.AppNotificationManager

/**
 * Android-side helpers around app approval (RFC-0103): display names, the pending-approval
 * notification, and the deep link into ConnectedAppsScreen.
 *
 * The approval *decision* is not here — it is `HandshakeCore` in :enginehost, which is JVM-tested.
 */
class AppApprovalManager(
    private val context: Context,
    val store: AppApprovalStore,
    private val notificationManager: AppNotificationManager
) {

    private val packageManager: PackageManager = context.packageManager

    /** Alert the user that [packageName] is asking for access for the first time. */
    fun notifyFirstRequest(packageName: String, displayName: String) {
        notificationManager.notifyPendingApproval(packageName, displayName)
    }

    /** Intent that opens ConnectedAppsScreen, handed to a caller whose approval is pending. */
    fun deepLinkIntent(): PendingIntent = notificationManager.createConnectedAppsDeepLink(context)

    /**
     * Persist request count for an approved app.
     * Called on shutdown or periodically to save in-memory counters to storage.
     */
    suspend fun persistRequestCount(packageName: String, count: Int) {
        store.updateRequestCount(packageName, count)
    }
    
    fun displayNameOf(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}
