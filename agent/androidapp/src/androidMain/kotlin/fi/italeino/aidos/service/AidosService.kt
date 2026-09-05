package fi.italeino.aidos.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager as AndroidNotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import dev.aidos.androidapp.notification.NotificationManager as AidosNotificationManager
import dev.aidos.androidapp.service.RuntimeServiceHost
import fi.italeino.aidos.AndroidRuntimeClientFactory
import fi.italeino.aidos.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Android foreground service hosting the runtime (RFC-0050's AidosService, M27, D24). */
class AidosService : LifecycleService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var runtimeServiceHost: RuntimeServiceHost
    private val notificationDecisions = AidosNotificationManager()

    override fun onCreate() {
        super.onCreate()
        runtimeServiceHost = RuntimeServiceHost(
            client = AndroidRuntimeClientFactory.get(this),
            scope = serviceScope,
        )
        createNotificationChannel()

        serviceScope.launch {
            runtimeServiceHost.state.collectLatest {
                updateNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val runId = intent?.getStringExtra(EXTRA_RUN_ID)
        val runDescription = intent?.getStringExtra(EXTRA_RUN_DESCRIPTION)
        if (runId != null && runDescription != null) {
            runtimeServiceHost.startRun(runId, runDescription)
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runBlocking { runtimeServiceHost.shutdown() }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updateNotification() {
        getSystemService(AndroidNotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val content = notificationDecisions.foregroundContent(runtimeServiceHost.currentNotificationText)
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Aidos runtime",
            AndroidNotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows what Aidos is doing while a run is in progress"
        }
        getSystemService(AndroidNotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    companion object {
        const val EXTRA_RUN_ID = "dev.aidos.EXTRA_RUN_ID"
        const val EXTRA_RUN_DESCRIPTION = "dev.aidos.EXTRA_RUN_DESCRIPTION"
        private const val CHANNEL_ID = "aidos_runtime"
        private const val NOTIFICATION_ID = 1
    }
}
