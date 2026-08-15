package fi.italeino.aidos.engine.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Manages notifications for Engine app approvals (RFC-0103).
 *
 * Posts notifications when a new app requests access, directing users
 * to the ConnectedAppsScreen to approve/deny.
 */
class AppNotificationManager(private val context: Context) {
    
    companion object {
        private const val CHANNEL_ID = "aidos_engine_approvals"
        private const val CHANNEL_NAME = "Aidos Engine Approvals"
        private const val NOTIFICATION_ID_BASE = 9000
        
        // Intent action to route notifications to ConnectedAppsScreen
        private const val ACTION_SHOW_CONNECTED_APPS = "fi.italeino.aidos.engine.SHOW_CONNECTED_APPS"
    }
    
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    init {
        createNotificationChannel()
    }
    
    /**
     * Post a notification that a new app is requesting access.
     * Tapping the notification opens ConnectedAppsScreen.
     */
    fun notifyPendingApproval(packageName: String, displayName: String) {
        val notificationId = (NOTIFICATION_ID_BASE + packageName.hashCode()) % Int.MAX_VALUE
        
        val deepLink = createConnectedAppsDeepLink(context)
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // TODO: use Aidos icon
            .setContentTitle("App Requesting Access")
            .setContentText("$displayName wants to use Aidos Engine")
            .setContentIntent(deepLink)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        
        notificationManager.notify(notificationId, notification)
    }
    
    /**
     * Create a deep-link PendingIntent that opens ConnectedAppsScreen.
     * The Activity receiving this Intent should parse the package name from extras
     * if present (for app-specific routing).
     */
    fun createConnectedAppsDeepLink(context: Context): PendingIntent {
        val intent = Intent(ACTION_SHOW_CONNECTED_APPS).apply {
            `package` = context.packageName
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    /**
     * Create a deep-link PendingIntent to ConnectedAppsScreen, highlighting a specific app.
     */
    fun createConnectedAppsDeepLink(context: Context, packageName: String): PendingIntent {
        val intent = Intent(ACTION_SHOW_CONNECTED_APPS).apply {
            `package` = context.packageName
            putExtra("app_package_name", packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        return PendingIntent.getActivity(
            context,
            packageName.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "Notifications for app access requests to Aidos Engine"
            notificationManager.createNotificationChannel(channel)
        }
    }
}
