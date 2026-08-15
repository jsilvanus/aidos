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
import dev.aidos.modelruntime.GlobalModelRuntime
import dev.aidos.modelruntime.LlamaCppInferenceBackend
import fi.italeino.aidos.engine.approval.AppApprovalManager
import fi.italeino.aidos.engine.approval.EncryptedAppApprovalStore
import fi.italeino.aidos.engine.binder.EngineHandshakeImpl
import fi.italeino.aidos.engine.http.EngineHttpServer
import fi.italeino.aidos.engine.http.TokenManager
import fi.italeino.aidos.engine.notification.AppNotificationManager
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
 * - [onCreate]: Initialize Engine core, HTTP server, Binder handshake, and app approval system
 * - [onStartCommand]: Start foreground notification; return sticky service mode
 * - [onDestroy]: Graceful shutdown of HTTP server and Engine
 * - [onBind]: Expose Binder handshake interface for clients
 *
 * The service binds an HTTP server to 127.0.0.1 on an ephemeral port and posts
 * an ongoing foreground notification (required by Android 12+).
 *
 * Approval System (RFC-0103): User-approval workflow for connected apps. First handshake
 * from an app triggers PENDING_APPROVAL response with deep-link to ConnectedAppsScreen.
 * User decides to APPROVE or DENY. Approved apps receive credentials on handshake; denied
 * apps receive 401 Unauthorized on HTTP requests.
 */
class EngineService : LifecycleService() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "aidos_engine"

        // Singleton access for UI binding (RFC-0103 Phase E)
        private var _instance: EngineService? = null
        val instance: EngineService? get() = _instance
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var tokenManager: TokenManager
    private lateinit var httpServer: EngineHttpServer
    private lateinit var binder: EngineHandshakeImpl
    
    // Public state for UI ViewModels
    lateinit var modelRuntime: GlobalModelRuntime
        private set
        
    private lateinit var approvalStore: EncryptedAppApprovalStore
    private lateinit var approvalManager: AppApprovalManager
    private var _isRunning = false
    val isRunning: Boolean get() = _isRunning

    override fun onCreate() {
        super.onCreate()
        _instance = this
        serviceScope.launch {
            try {
                // Initialize token manager
                tokenManager = TokenManager()

                // Initialize model runtime with llama.cpp backend (RFC-0103, M21)
                // GlobalModelRuntime manages the admission queue and loaded model lifecycle
                modelRuntime = GlobalModelRuntime(LlamaCppInferenceBackend())

                // Initialize HTTP server (on ephemeral port, chosen by OS)
                httpServer = EngineHttpServer(tokenManager, modelRuntime)
                httpServer.start()

                val boundPort = httpServer.getBoundPort()
                if (boundPort == null) {
                    throw IllegalStateException("HTTP server failed to bind")
                }

                // Initialize app approval system (RFC-0103)
                approvalStore = EncryptedAppApprovalStore(this@EngineService)
                val notificationManager = AppNotificationManager(this@EngineService)
                approvalManager = AppApprovalManager(this@EngineService, approvalStore, notificationManager)

                // Initialize Binder handshake interface with approval manager
                binder = EngineHandshakeImpl(this@EngineService, tokenManager, httpServer, approvalManager)

                _isRunning = true
                updateNotification("Engine running on port $boundPort")
            } catch (e: Exception) {
                _isRunning = false
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
                    
                    // Unload all models and shut down the runtime (RFC-0103, RFC-0022)
                    modelRuntime.loaded().forEach { modelId ->
                        modelRuntime.unload(modelId)
                    }
                    
                    tokenManager.clearTokens()
                    _isRunning = false
                }
            } catch (e: Exception) {
                // Log error, but don't throw from shutdown
            } finally {
                _instance = null
                serviceScope.cancel()
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
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

