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
import dev.aidos.models.DatabaseModelCatalogManager
import dev.aidos.models.ModelBrowser
import dev.aidos.models.ModelCatalogManager
import fi.italeino.aidos.engine.approval.AppApprovalManager
import fi.italeino.aidos.engine.approval.AppApprovalStatus
import fi.italeino.aidos.engine.approval.AppApprovalStore
import fi.italeino.aidos.engine.approval.EncryptedAppApprovalStore
import fi.italeino.aidos.engine.binder.EngineHandshakeImpl
import fi.italeino.aidos.engine.http.KtorEffectBroker
import fi.italeino.aidos.engine.http.EngineHttpServer
import fi.italeino.aidos.engine.http.TokenManager
import fi.italeino.aidos.engine.inference.AndroidLlamaCppInferenceBackend
import fi.italeino.aidos.engine.notification.AppNotificationManager
import fi.italeino.aidos.engine.ui.DeviceProfileProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import dev.aidos.kernel.ModelRuntime
import fi.italeino.aidos.engine.handshake.InProcessHandshakeSource
import fi.italeino.aidos.engine.handshake.handshakeCapabilities
import fi.italeino.aidos.sdk.client.AidosEngineClient
import fi.italeino.aidos.sdk.client.AidosEngineClientFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
        private const val READINESS_TIMEOUT_MS = 4_000L

        private var _instance: EngineService? = null
        val instance: EngineService? get() = _instance

        private val _state = MutableStateFlow(EngineState.STARTING)
        val state: StateFlow<EngineState> = _state.asStateFlow()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var tokenManager: TokenManager
    private lateinit var httpServer: EngineHttpServer
    private lateinit var binder: EngineHandshakeImpl
    private val boundPortReady = CompletableDeferred<Int>()
    private val runtimeReady = CompletableDeferred<ModelRuntime>()

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

    /** Persisted per-app approval decisions; the Home and Connected Apps screens read this. */
    var approvalStore: AppApprovalStore? = null
        private set
    private lateinit var approvalManager: AppApprovalManager
    private var _isRunning = false
    val isRunning: Boolean get() = _isRunning

    override fun onCreate() {
        super.onCreate()
        _instance = this
        _state.value = EngineState.STARTING

        // The handshake surface comes up first and independently of model-runtime start-up: a
        // caller binding to a cold Engine must reach the approval check (record PENDING, notify
        // the user) instead of getting a null binder, which the SDK would report as "not
        // installed". Only an APPROVED reply needs the HTTP port and model catalog, and waits
        // (bounded) for them below.
        tokenManager = TokenManager()
        val approvals = EncryptedAppApprovalStore(this)
        approvalStore = approvals
        approvalManager = AppApprovalManager(this, approvals, AppNotificationManager(this))
        binder = EngineHandshakeImpl(
            context = this,
            tokenManager = tokenManager,
            boundPort = { withTimeoutOrNull(READINESS_TIMEOUT_MS) { boundPortReady.await() } },
            approvalManager = approvalManager,
            modelRuntime = { withTimeoutOrNull(READINESS_TIMEOUT_MS) { runtimeReady.await() } }
        )

        serviceScope.launch {
            try {

                httpClient = HttpClient(io.ktor.client.engine.android.Android) {
                    install(ContentNegotiation) { json() }
                }
                val broker = KtorEffectBroker(httpClient)
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
                        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
                            DatabaseModelCatalogManager.createTables(driver)
                            return QueryResult.Value(Unit)
                        }
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

                // Android uses the native llama.cpp binding directly. The JVM-only backend in
                // :modelruntime remains the desktop implementation; both share GlobalModelRuntime.
                val runtime = GlobalModelRuntime(AndroidLlamaCppInferenceBackend(this@EngineService))
                modelRuntime = runtime

                // A token proves which app it was issued to; whether that app is still approved
                // is asked of the store on every request, so revoking in Connected Apps cuts a
                // live session immediately (RFC-0103, "Trust model").
                httpServer = EngineHttpServer(
                    tokenManager,
                    runtime,
                    isSubjectApproved = { pkg ->
                        approvalStore?.getApproval(pkg)?.status == AppApprovalStatus.APPROVED
                    }
                )
                httpServer.start()
                val boundPort = httpServer.getBoundPort()
                    ?: throw IllegalStateException("HTTP server failed to bind")

                boundPortReady.complete(boundPort)
                runtimeReady.complete(runtime)

                _isRunning = true
                _state.value = EngineState.READY
                updateNotification("Engine running on port $boundPort")
            } catch (_: Exception) {
                _isRunning = false
                _state.value = EngineState.FAILED
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
                    httpServer.shutdownInference()
                    modelRuntime?.loaded()?.forEach { modelId ->
                        httpServer.waitUntilModelIdle(modelId)
                        modelRuntime?.unload(modelId)
                    }
                    tokenManager.clearTokens()
                    _isRunning = false
                }
            } catch (_: Exception) {
            } finally {
                _state.value = EngineState.STARTING
                _instance = null
                serviceScope.cancel()
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        // Always hand out the handshake binder, even while start-up is still running (see onCreate).
        return binder.asBinder()
    }

    /**
     * An Aidos SDK client for this service's own `/v1/` endpoint, for first-party in-app callers
     * (the Test Chat and model-detail screens, RFC-0103 Phase E). It is the same client external
     * apps use; only the handshake differs — Engine trusts its own process, so it takes a token
     * from [TokenManager] directly (per-subject, so it never invalidates a connected app's
     * session) instead of a Binder round trip.
     *
     * Null when the engine (and therefore its HTTP server) isn't running. The caller closes it.
     */
    suspend fun createEngineClient(): AidosEngineClient? {
        val runtime = modelRuntime
        if (!isRunning || runtime == null) return null
        val client = AidosEngineClientFactory.create(
            InProcessHandshakeSource(
                tokenManager = tokenManager,
                boundPort = { httpServer.getBoundPort() },
                capabilities = { runtime.handshakeCapabilities() }
            )
        )
        if (client.initialize()) return client
        client.close()
        return null
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
