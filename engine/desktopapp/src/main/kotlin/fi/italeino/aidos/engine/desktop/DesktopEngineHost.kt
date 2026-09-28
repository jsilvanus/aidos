package fi.italeino.aidos.engine.desktop

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aidos.cookbook.CookbookEngine
import dev.aidos.downloads.DownloadManager
import dev.aidos.downloads.LocalDownloadManager
import dev.aidos.huggingface.HuggingFaceClient
import dev.aidos.kernel.BasicResourceHandle
import dev.aidos.kernel.CapabilityId
import dev.aidos.modelruntime.GlobalModelRuntime
import dev.aidos.modelruntime.create
import dev.aidos.models.DatabaseModelCatalogManager
import dev.aidos.models.ModelBrowser
import dev.aidos.models.ModelCatalogManager
import fi.italeino.aidos.engine.EngineState
import fi.italeino.aidos.engine.approval.AppApprovalStore
import fi.italeino.aidos.engine.http.EngineHttpServer
import fi.italeino.aidos.engine.http.KtorEffectBroker
import fi.italeino.aidos.engine.http.TokenManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * In-process Engine host for the Windows debug app: the desktop counterpart of Android's
 * EngineService (RFC-0103), wiring the same Engine Core pieces.
 *
 * Two lifetimes, matching how the Android UI behaves:
 * - Catalog, downloader, Hugging Face client and model browser exist for the whole app lifetime,
 *   so models can be browsed and installed with the Engine off (Android builds local fallbacks
 *   for the same reason).
 * - The model runtime, token manager and loopback HTTP server exist only between [start] and
 *   [stop].
 */
class DesktopEngineHost(
    private val modelsDir: File = DesktopPaths.modelsDir,
    stateDir: File = DesktopPaths.stateDir,
) {
    private val _state = MutableStateFlow(EngineState.STARTING)
    /** STARTING also means "stopped"; see [isRunning]. */
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _boundPort = MutableStateFlow<Int?>(null)
    val boundPort: StateFlow<Int?> = _boundPort.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    /** Why the last start failed, shown in the window; Android only has the notification. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val lifecycle = Mutex()

    private val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }

    val hfClient: HuggingFaceClient =
        HuggingFaceClient(KtorEffectBroker(httpClient), BasicResourceHandle(CapabilityId("huggingface")))

    val downloadManager: DownloadManager = LocalDownloadManager(modelsDir.absolutePath)

    val catalogManager: ModelCatalogManager = DatabaseModelCatalogManager(
        JdbcSqliteDriver(
            url = "jdbc:sqlite:" + File(stateDir, "aidos_engine.db").absolutePath,
            schema = CatalogSchema,
        )
    )

    val modelBrowser: ModelBrowser = ModelBrowser(
        catalogManager = catalogManager,
        hfClient = hfClient,
        cookbookEngine = CookbookEngine(),
        deviceProfile = DesktopDevice.profile(),
    )

    /** Where installers should put artifacts; the runtime only loads from inside it. */
    val artifactDir: File get() = modelsDir

    val approvalStore: AppApprovalStore = FileAppApprovalStore(File(stateDir, "approvals.json"))

    var modelRuntime: GlobalModelRuntime? = null
        private set

    private var tokenManager: TokenManager? = null
    private var httpServer: EngineHttpServer? = null

    suspend fun start() = lifecycle.withLock {
        if (_isRunning.value) return@withLock
        _state.value = EngineState.STARTING
        _lastError.value = null
        try {
            val tokens = TokenManager()
            // JVM llama.cpp backend (RFC-0022, M21). It reads its directory from the system
            // property, so point that at ours before constructing it.
            System.setProperty("aidos.models.dir", modelsDir.absolutePath)
            val runtime = GlobalModelRuntime.create()
            val server = EngineHttpServer(tokens, runtime)
            server.start()
            val port = server.getBoundPort() ?: run {
                server.stop()
                throw IllegalStateException("HTTP server failed to bind")
            }
            tokenManager = tokens
            modelRuntime = runtime
            httpServer = server
            _boundPort.value = port
            _isRunning.value = true
            _state.value = EngineState.READY
        } catch (e: Exception) {
            _lastError.value = e.message ?: e::class.simpleName
            _state.value = EngineState.FAILED
        }
    }

    /** Mirrors EngineService.onDestroy: stop serving, drain inference, unload, drop tokens. */
    suspend fun stop() = lifecycle.withLock {
        val server = httpServer
        val runtime = modelRuntime
        try {
            if (server != null) {
                server.stop()
                server.shutdownInference()
                runtime?.loaded()?.forEach { modelId ->
                    server.waitUntilModelIdle(modelId)
                    runtime.unload(modelId)
                }
            }
            tokenManager?.clearTokens()
        } catch (_: Exception) {
        } finally {
            httpServer = null
            modelRuntime = null
            tokenManager = null
            _boundPort.value = null
            _isRunning.value = false
            _state.value = EngineState.STARTING
        }
    }

    /** Called once when the window closes. */
    suspend fun close() {
        stop()
        httpClient.close()
    }

    /** Same schema bootstrap EngineService uses for its AndroidSqliteDriver. */
    private object CatalogSchema : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 1
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            DatabaseModelCatalogManager.createTables(driver)
            return QueryResult.Value(Unit)
        }

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> = QueryResult.Value(Unit)
    }
}
