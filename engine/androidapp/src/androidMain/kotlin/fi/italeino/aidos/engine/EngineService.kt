package fi.italeino.aidos.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.aidos.cookbook.CookbookEngine
import dev.aidos.downloads.LocalDownloadManager
import dev.aidos.downloads.DownloadManager
import dev.aidos.huggingface.HuggingFaceClient
import dev.aidos.kernel.BasicResourceHandle
import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.EffectBroker
import dev.aidos.modelruntime.GlobalModelRuntime
import dev.aidos.modelruntime.LlamaCppInferenceBackend
import dev.aidos.models.DatabaseModelCatalogManager
import dev.aidos.models.ModelBrowser
import dev.aidos.models.ModelCatalogManager
import fi.italeino.aidos.engine.approval.AppApprovalManager
import fi.italeino.aidos.engine.approval.EncryptedAppApprovalStore
import fi.italeino.aidos.engine.binder.EngineHandshakeImpl
import fi.italeino.aidos.engine.http.AndroidEffectBroker
import fi.italeino.aidos.engine.http.EngineHttpServer
import fi.italeino.aidos.engine.http.HttpModelClient
import fi.italeino.aidos.engine.http.TokenManager
import fi.italeino.aidos.engine.notification.AppNotificationManager
import fi.italeino.aidos.engine.ui.DeviceProfileProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android foreground service hosting Aidos Engine Core (RFC-0103).
 *
 * Wires the Engine core (model loading, inference backends) into the Android service
 * lifecycle. Model acquisition uses the shared engine DownloadManager abstraction.
 */
class EngineService : LifecycleService() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "aidos_engine"

        private var _instance: EngineService? = null
        val instance: EngineService? get() = _instance
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var tokenManager: TokenManager
    private lateinit var httpServer: EngineHttpServer
    private lateinit var binder: EngineHandshakeImpl

    var modelRuntime: GlobalModelRuntime? = null
        private set

    var hfClient: HuggingFaceClient? = null
        private set

    var catalogManager: ModelCatalogManager? = null
        private set

    var modelBrowser: ModelBrowser? = null
        private set

    /** Shared engine download abstraction used by Android model acquisition. */
    var downloadManager: DownloadManager? = null
        private set

    private lateinit var httpClient: HttpClient
    private lateinit var effectBroker: EffectBroker

    private lateinit var approvalStore: EncryptedAppApprovalStore
    private lateinit var approvalManager: AppApprovalManager
    private var _isRunning = false
    val isRunning: Boolean get() = _isRunning

    override fun onCreate() {
        super.onCreate()
        _instance = this
        serviceScope.launch {
            try {
                tokenManager = TokenManager()

                httpClient = HttpClient(io.ktor.client.engine.android.Android) {
                    install(ContentNegotiation) { json() }
                }
                val broker = AndroidEffectBroker(httpClient)
                effectBroker = broker
                val hfHandle = BasicResourceHandle(CapabilityId("huggingface"))
                val client = HuggingFaceClient(broker, hfHandle)
                hfClient = client

                // All engine model downloads go through the shared DownloadManager.
                val modelsDir = java.io.File(filesDir, "models")
                downloadManager = LocalDownloadManager(modelsDir.absolutePath)

                val databaseDriver = AndroidSqliteDriver(
                    schema = object : SqlSchema<QueryResult.Value<Unit>> {
                        override val version: Long = 1
                        override fun create(driver: SqlDriver): QueryResult.Value<Unit> = QueryResult.Value(Unit)
                        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> = QueryResult.Value(Unit)
                    },
                    context = this@EngineService,
                    name = "aidos_engine.db",
                )
                val catalog = DatabaseModelCatalogManager(databaseDriver)
                catalogManager = catalog

                val deviceProfile = DeviceProfileProvider(this@EngineService).getProfile()
                modelBrowser = ModelBrowser(
                    catalogManager = catalog,
                    hfClient = client,
                    cookbookEngine = CookbookEngine(),
                    deviceProfile = deviceProfile
                )

                val runtime = GlobalModelRuntime(LlamaCppInferenceBackend())
                modelRuntime = runtime

                httpServer = EngineHttpServer(tokenManager, runtime)
                httpServer.start()
                val boundPort = httpServer.getBoundPort()
                    ?: throw IllegalStateException("HTTP server failed to bind")

                approvalStore = EncryptedAppApprovalStore(this@EngineService)
                val notificationManager = AppNotificationManager(this@EngineService)
                approvalManager = AppApprovalManager(this@EngineService, approvalStore, notificationManager)
                binder = EngineHandshakeImpl(this@EngineService, tokenManager, httpServer, approvalManager, runtime)

                _isRunning = true
                updateNotification("Engine running on port $boundPort")
            } catch (_: Exception) {
                _isRunning = false
                updateNotification("Engine failed: Unable to start HTTP server or model runtime")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        createNotificationChannel()
        val notification = buildNotification("Initializing...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val hasDataSyncPermission = ContextCompat.checkSelfPermission(
                this, "android.permission.FOREGROUND_SERVICE_DATA_SYNC"
            ) == PackageManager.PERMISSION_GRANTED
            if (hasDataSyncPermission) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.launch {
            try {
                if (isRunning) {
                    httpServer.stop()
                    httpClient.close()
                    modelRuntime?.loaded()?.forEach { modelId -> modelRuntime?.unload(modelId) }
                    tokenManager.clearTokens()
                    _isRunning = false
                }
            } catch (_: Exception) {
            } finally {
                _instance = null
                serviceScope.cancel()
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return if (isRunning) binder.asBinder() else null
    }

    /**
     * A client for this service's own `/v1/chat/completions` endpoint, for first-party in-app
     * callers (the Test Chat screen, RFC-0103 Phase E) rather than the Binder-handshake
     * (`EngineHandshakeImpl`) path external client apps use. The engine trusts its own process,
     * so it self-issues a token via [TokenManager] instead of requiring a handshake round trip;
     * an existing still-valid token (e.g. one already issued to a connected app) is reused rather
     * than rotated, since [TokenManager] holds only one token at a time and rotating it here would
     * invalidate that app's session.
     *
     * Null when the engine (and therefore its HTTP server) isn't running.
     */
    suspend fun createHttpModelClient(): HttpModelClient? {
        if (!isRunning) return null
        val port = httpServer.getBoundPort() ?: return null
        val token = tokenManager.currentValidToken() ?: tokenManager.generateNewToken().token
        return HttpModelClient(port = port, token = token)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Aidos Engine",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "Aidos Engine local model inference service"
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
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
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(message))
    }
}
