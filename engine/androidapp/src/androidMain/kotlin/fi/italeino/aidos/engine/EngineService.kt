package fi.italeino.aidos.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import fi.italeino.aidos.engine.binder.EngineHandshakeImpl
import fi.italeino.aidos.engine.http.EngineHttpServer
import fi.italeino.aidos.engine.http.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android foreground service hosting Aidos Engine Core (RFC-0103).
 *
 * Wires the Engine core (model loading, inference backends) into the Android service
 * lifecycle:
 * - [onCreate]: Initialize Engine core, HTTP server, and Binder handshake
 * - [onStartCommand]: Start foreground notification; return sticky service mode
 * - [onDestroy]: Graceful shutdown of HTTP server and Engine
 * - [onBind]: Expose Binder handshake interface for clients
 *
 * The service binds an HTTP server to 127.0.0.1 on an ephemeral port and posts
 * an ongoing foreground notification (required by Android 12+).
 */
class EngineService : LifecycleService() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "aidos_engine"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var tokenManager: TokenManager
    private lateinit var httpServer: EngineHttpServer
    private lateinit var binder: EngineHandshakeImpl
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            try {
                // Initialize token manager
                tokenManager = TokenManager()

                // Initialize HTTP server (on ephemeral port, chosen by OS)
                httpServer = EngineHttpServer(tokenManager)
                httpServer.start()

                val boundPort = httpServer.getBoundPort()
                if (boundPort == null) {
                    throw IllegalStateException("HTTP server failed to bind")
                }

                // Initialize Binder handshake interface
                binder = EngineHandshakeImpl(tokenManager, httpServer)

                isRunning = true
                updateNotification("Engine running on port $boundPort")
            } catch (e: Exception) {
                isRunning = false
                updateNotification("Engine failed: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // Post foreground notification (required by Android 12+)
        createNotificationChannel()
        val notification = buildNotification("Initializing...")
        startForeground(NOTIFICATION_ID, notification)

        // RFC-0103: Sticky mode keeps the service running as long as Engine is needed
        // Clients hold onto the Binder connection; service restarts on crash with data loss
        // recovery per RFC-0009.
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.launch {
            try {
                if (isRunning) {
                    httpServer.stop()
                    tokenManager.clearTokens()
                    isRunning = false
                }
            } catch (e: Exception) {
                // Log error, but don't throw from shutdown
            } finally {
                serviceScope.cancel()
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        super.onBind(intent)

        // Return the handshake Binder interface
        // RFC-0103: The only Binder surface Engine exposes.
        // All subsequent communication is via HTTP.
        return if (isRunning) {
            binder.asBinder()
        } else {
            null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Aidos Engine",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Aidos Engine local model inference service"
            channel.setShowBadge(false)

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Aidos Engine")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)  // Ongoing notification (can't be swiped away by user)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(message: String) {
        val notification = buildNotification(message)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }
}

