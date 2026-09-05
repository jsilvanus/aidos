package fi.italeino.aidos.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager as AndroidNotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import dev.aidos.cookbook.CookbookEngine
import dev.aidos.downloads.DownloadManager
import dev.aidos.downloads.LocalDownloadManager
import dev.aidos.kernel.ModelRuntime
import dev.aidos.models.DatabaseModelCatalogManager
import dev.aidos.models.ModelBrowser
import dev.aidos.models.ModelCatalogManager
import de.kherud.llama.LlamaModel
import fi.italeino.aidos.engine.approval.AppApprovalManager
import fi.italeino.aidos.engine.approval.EncryptedAppApprovalStore
import fi.italeino.aidos.engine.inference.AndroidLlamaCppInferenceBackend
import fi.italeino.aidos.engine.inference.GlobalModelRuntime
import fi.italeino.aidos.engine.http.EngineHttpServer
import fi.italeino.aidos.engine.security.TokenManager
import fi.italeino.aidos.engine.ui.DeviceProfileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.File

/** Foreground service owning the Engine runtime and loopback API. */
class EngineService : LifecycleService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var tokenManager: TokenManager
    private lateinit var httpServer: EngineHttpServer
    private lateinit var binder: EngineHandshakeImpl
    private lateinit var approvalStore: EncryptedAppApprovalStore
    private lateinit var approvalManager: AppApprovalManager
    private var _isRunning = false
    val isRunning: Boolean get() = _isRunning

    var modelRuntime: GlobalModelRuntime? = null
        private set
    var hfClient: HuggingFaceClient? = null
        private set
    var catalogManager: ModelCatalogManager? = null
        private set
    var modelBrowser: ModelBrowser? = null
        private set
    var downloadManager: DownloadManager? = null
        private set

    private lateinit var httpClient: HttpClient
    private lateinit var effectBroker: EffectBroker

    override fun onCreate() {
        super.onCreate()
        _instance = this
        serviceScope.launch {
            try {
                tokenManager = TokenManager()

                httpClient = HttpClient(Android) {
                    install(ContentNegotiation) { json() }
                }
                val broker = AndroidEffectBroker(httpClient)
                effectBroker = broker
                val hfHandle = BasicResourceHandle(CapabilityId("huggingface"))
                val client = HuggingFaceClient(broker, hfHandle)
                hfClient = client

                val modelsDir = File(filesDir, "models")
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
                    deviceProfile = deviceProfile,
                )

                // Android uses the native llama.cpp binding directly. The JVM-only backend in
                // :modelruntime remains the desktop implementation; both share GlobalModelRuntime.
                // The persistent catalog is authoritative for model identity, kind and expected
                // digest; filesDir/models is only the artifact store.
                val runtime = GlobalModelRuntime(
                    AndroidLlamaCppInferenceBackend(
                        context = this@EngineService,
                        catalogManager = catalog,
                    )
                )
                modelRuntime = runtime

                // Reconcile stale installed rows before exposing the Engine to clients. The
                // backend deliberately treats orphan files as non-executable and removes catalog
                // rows that no longer have a trustworthy artifact.
                runtime.catalog()

                httpServer = EngineHttpServer(tokenManager, runtime)
                httpServer.start()
                val boundPort = httpServer.getBoundPort()
                    ?: throw IllegalStateException("HTTP server failed to bind")

                approvalStore = EncryptedAppApprovalStore(this@EngineService)
                val notificationManager = AppNotificationManager(this@EngineService)
                approvalManager = AppApprovalManager(this@EngineService, approvalStore, notificationManager)
                binder = EngineHandshakeImpl(this@EngineService, tokenManager, httpServer, approvalManager, runtime)

                _isRunning = true
                android.util.Log.i(TAG, "Engine started on loopback port $boundPort")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Engine startup failed", e)
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        serviceScope.cancel()
        _isRunning = false
        if (::httpServer.isInitialized) {
            serviceScope.launch { httpServer.stop() }
        }
        if (::httpClient.isInitialized) httpClient.close()
        super.onDestroy()
    }

    private fun buildNotification(message: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aidos Engine")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Aidos Engine",
            AndroidNotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(AndroidNotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "AidosEngine"
        private const val CHANNEL_ID = "aidos_engine"
        private var _instance: EngineService? = null

        val instance: EngineService? get() = _instance
    }
}
