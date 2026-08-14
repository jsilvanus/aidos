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
import dev.aidos.api.RealRuntimeClient
import fi.italeino.aidos.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Android foreground service hosting the runtime (RFC-0050's `AidosService : LifecycleService`,
 * M27, D24).
 *
 * Wires the platform-neutral [RuntimeServiceHost] into the Android service lifecycle:
 * [onStartCommand] starts hosting a run and calls `startForeground` with the ongoing
 * notification (RFC-0050 "Notifications" item 1 — "what is running, and Cancel"); [onDestroy]
 * shuts the host down. Notification *content* decisions (what text, whether a given
 * notification should fire at all) stay in the platform-neutral
 * `dev.aidos.androidapp.notification.NotificationManager` — this class only performs the actual
 * Android `NotificationManager`/`NotificationChannel` calls RFC-0050 says belong to the service
 * wrapper.
 *
 * Owns its own [RealRuntimeClient] for now, separate from [MainActivity]'s — binding the two so
 * they share one client (and its state survives activity recreation) is the next step, tracked
 * in PIPELINE.md's Group 2 checklist.
 *
 * The Cancel action and the wake lock RFC-0050's D24(a) calls for are not wired yet — this link
 * covers the service lifecycle and the ongoing notification itself; both are flagged as
 * follow-up in PIPELINE.md rather than guessed at here.
 */
class AidosService : LifecycleService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runtimeClient = RealRuntimeClient()
    private val runtimeServiceHost = RuntimeServiceHost(client = runtimeClient, scope = serviceScope)
    private val notificationDecisions = AidosNotificationManager()

    override fun onCreate() {
        super.onCreate()
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

        // A Run reaching a model call needs the foreground window regardless of what started
        // this call (D24(a)) — startForeground on every onStartCommand keeps that window open.
        startForeground(NOTIFICATION_ID, buildNotification())

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // RuntimeServiceHost.shutdown() cancels the active run's job and joins it so the
        // recovery checkpoint (RFC-0009) is the one actually written, not whatever was in
        // flight — serviceScope is cancelled separately below rather than racing this suspend
        // call against Service teardown.
        runBlocking { runtimeServiceHost.shutdown() }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updateNotification() {
        val manager = getSystemService(AndroidNotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
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
            // TODO: real app icon (no drawable resources exist in this module yet — tracked
            // separately from the service wiring, not blocking it).
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
        getSystemService(AndroidNotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val EXTRA_RUN_ID = "dev.aidos.EXTRA_RUN_ID"
        const val EXTRA_RUN_DESCRIPTION = "dev.aidos.EXTRA_RUN_DESCRIPTION"
        private const val CHANNEL_ID = "aidos_runtime"
        private const val NOTIFICATION_ID = 1
    }
}
