package fi.italeino.aidos.engine.desktop

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aidos.cookbook.CookbookEngine
import dev.aidos.cookbook.DeviceProfile
import dev.aidos.downloads.DownloadManager
import dev.aidos.downloads.LocalDownloadManager
import dev.aidos.huggingface.HuggingFaceClient
import dev.aidos.kernel.BasicResourceHandle
import dev.aidos.kernel.CapabilityId
import dev.aidos.aidin.AidinNode
import dev.aidos.aidin.DesktopAidin
import dev.aidos.decisions.OnnxDecisionBackend
import dev.aidos.vision.OnnxVisionBackend
import dev.aidos.providers.ProviderBackend
import dev.aidos.providers.ProviderConfigs
import dev.aidos.stt.OnnxSttBackend
import dev.aidos.modelruntime.GlobalModelRuntime
import dev.aidos.modelruntime.RunSettingsSource
import dev.aidos.modelruntime.LlamaCppInferenceBackend
import dev.aidos.modelruntime.create
import dev.aidos.models.DatabaseModelCatalogManager
import dev.aidos.models.ModelBrowser
import dev.aidos.models.ModelCatalogManager
import fi.italeino.aidos.engine.EngineState
import fi.italeino.aidos.engine.agent.AgentRuntime
import fi.italeino.aidos.engine.agent.ManagedModelAdapter
import fi.italeino.aidos.engine.agent.agentRoutes
import fi.italeino.aidos.engine.approval.AppApprovalStatus
import fi.italeino.aidos.engine.handshake.HandshakeWire
import fi.italeino.aidos.engine.handshake.InProcessHandshakeSource
import fi.italeino.aidos.engine.handshake.handshakeCapabilities
import fi.italeino.aidos.sdk.client.AidosEngineClient
import fi.italeino.aidos.sdk.client.AidosEngineClientFactory
import fi.italeino.aidos.engine.approval.AppApprovalStore
import fi.italeino.aidos.engine.http.EngineHttpServer
import fi.italeino.aidos.engine.http.KtorEffectBroker
import fi.italeino.aidos.engine.http.TokenManager
import fi.italeino.aidos.engine.inference.InferenceRequestManager
import fi.italeino.aidos.engine.inference.Locality
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * In-process Engine host for the Windows desktop app / daemon: the desktop counterpart of Android's
 * EngineService (RFC-0103), wiring the same Engine Core pieces.
 *
 * Two lifetimes, matching how the Android UI behaves:
 * - Catalog, downloader, Hugging Face client and model browser exist for the whole app lifetime,
 *   so models can be browsed and installed with the Engine off (Android builds local fallbacks
 *   for the same reason).
 * - The model runtime, token manager and loopback HTTP server exist only between [start] and
 *   [stop].
 *
 * When started, writes connection details (port, token, baseUrl) to [discoveryFile] (~/.aidos/engine.json)
 * and unlinks it when stopped.
 */
class DesktopEngineHost(
    private val modelsDir: File = DesktopPaths.modelsDir,
    private val stateDir: File = DesktopPaths.stateDir,
    /** Per-model run settings the runtime reads at load time (RFC-0103); defaults when none are configured. */
    private val runSettings: RunSettingsSource = RunSettingsSource.Defaults,
    /** The user's RAM baseline in bytes (a Settings value), read each time a device profile is taken. */
    private val ramBaselineUsedBytes: () -> Long? = { null },
    /** Port to bind on localhost; defaults to 18080. If 0, OS allocates ephemeral port. */
    private val serverPort: () -> Int = { 18080 },
    /** Path to write active connection info (port, token, etc.). Null skips discovery write. */
    private val discoveryFile: File? = DesktopPaths.discoveryFile,
    /** Whether inference groups (RFC-0105) run with the Engine; read at each [start]. */
    private val groupSharing: () -> Boolean = { false },
    /** Builds the group node; tests replace it to avoid real mDNS and fixed ports. */
    private val aidinFactory: (CoroutineScope) -> AidinNode = { scope ->
        DesktopAidin.create(File(stateDir, "aidin"), scope, engineVersion = "desktop")
    },
    private val aidinPort: Int = DesktopAidin.DEFAULT_PORT,
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

    private val _aidin = MutableStateFlow<AidinNode?>(null)
    /** The running group node, or null when group sharing is off or the Engine is stopped. */
    val aidin: StateFlow<AidinNode?> = _aidin.asStateFlow()

    private val _aidinError = MutableStateFlow<String?>(null)
    /** Why group sharing did not start; the Engine itself still runs. */
    val aidinError: StateFlow<String?> = _aidinError.asStateFlow()

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
        deviceProfile = DesktopDevice.profile(ramBaselineUsedBytes()),
    )

    /** A fresh device profile (current free RAM) carrying the user's RAM baseline. */
    fun deviceProfile(): DeviceProfile = DesktopDevice.profile(ramBaselineUsedBytes())

    /** Where installers should put artifacts; the runtime only loads from inside it. */
    val artifactDir: File get() = modelsDir

    val approvalStore: AppApprovalStore = FileAppApprovalStore(File(stateDir, "approvals.json"))

    var modelRuntime: GlobalModelRuntime? = null
        private set

    private var tokenManager: TokenManager? = null
    private var httpServer: EngineHttpServer? = null
    private var agentScope: CoroutineScope? = null

    suspend fun start() = lifecycle.withLock {
        if (_isRunning.value) return@withLock
        _state.value = EngineState.STARTING
        _lastError.value = null
        try {
            val tokens = TokenManager()
            // Ensure desktop localhost caller has an approved token for external clients (RFC-0103)
            val localTokenInfo = tokens.generateNewToken(
                validityDurationSeconds = 86400 * 365,
                subject = LOCALHOST_SUBJECT
            )
            if (approvalStore.getApproval(LOCALHOST_SUBJECT) == null) {
                approvalStore.recordFirstHandshake(LOCALHOST_SUBJECT, "Localhost Desktop Client")
            }
            approvalStore.approveApp(LOCALHOST_SUBJECT)

            // JVM llama.cpp backend (RFC-0022, M21). It reads its directory from the system
            // property, so point that at ours before constructing it.
            System.setProperty("aidos.models.dir", modelsDir.absolutePath)
            // llama.cpp for GGUF, plus ONNX decision models (Laya packages, RFC-0022 D45), Whisper speech-to-text
            // packages (RFC-0103) and SmolVLM vision packages from the same directory; each decorator claims only its own shape.
            val runtime = GlobalModelRuntime(
                // HTTP providers (e.g. a local Ollama) from providers.json; read per call, so editing it needs no restart.
                ProviderBackend(
                    OnnxVisionBackend(OnnxSttBackend(OnnxDecisionBackend(LlamaCppInferenceBackend(), modelsDir), modelsDir), modelsDir),
                    { ProviderConfigs.load(File(stateDir, "providers.json")) },
                ),
                runSettings = runSettings,
            )
            val desiredPort = serverPort()
            // The agent runtime (/v1/agent) is served from this same process and calls the models
            // through the same InferenceRequestManager as the HTTP API, so admission, model loading
            // and the locality limits (RFC-0104) stay on one path.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val manager = InferenceRequestManager(runtime)
            lateinit var server: EngineHttpServer
            val agent = AgentRuntime(scope, { req ->
                ManagedModelAdapter(
                    manager,
                    req.model,
                    Locality.parse(req.locality.orEmpty()) ?: Locality.LOCAL_ONLY,
                    req.contextWindow ?: 8192,
                )
            })
            server = EngineHttpServer(
                tokens,
                runtime,
                inferenceManager = manager,
                port = desiredPort,
                isSubjectApproved = { id -> approvalStore.getApproval(id)?.status == AppApprovalStatus.APPROVED },
                extraRoutes = { agentRoutes(agent) { subject, requested -> server.effectiveLocality(subject, requested) } },
                onClientVersions = { id, sdk, api -> approvalStore.recordClientVersions(id, sdk, api) }
            )
            server.start()
            val port = server.getBoundPort() ?: run {
                server.stop()
                scope.cancel()
                throw IllegalStateException("HTTP server failed to bind")
            }
            agentScope = scope
            tokenManager = tokens
            modelRuntime = runtime
            httpServer = server
            _boundPort.value = port
            _isRunning.value = true
            _state.value = EngineState.READY

            writeDiscoveryFile(port, localTokenInfo.token)
            if (groupSharing()) startAidin(scope)
        } catch (e: Exception) {
            _lastError.value = e.message ?: e::class.simpleName
            _state.value = EngineState.FAILED
            removeDiscoveryFile()
        }
    }

    /** Phase 1 only links and tracks peers; nothing is served to them yet (RFC-0105 phases 2-3). */
    private fun startAidin(scope: CoroutineScope) {
        _aidinError.value = null
        val node = try {
            aidinFactory(scope)
        } catch (e: Exception) {
            _aidinError.value = e.message ?: e::class.simpleName
            return
        }
        try {
            // A fixed port lets paired peers find us after a restart; fall back if it is taken.
            try {
                node.start(port = aidinPort)
            } catch (e: java.io.IOException) {
                node.start(port = 0)
            }
            _aidin.value = node
        } catch (e: Exception) {
            runCatching { node.stop() }
            _aidinError.value = e.message ?: e::class.simpleName
        }
    }

    /** Turn group sharing on or off while the Engine runs; when stopped, [start] reads [groupSharing]. */
    suspend fun setGroupSharing(enabled: Boolean) = lifecycle.withLock {
        val scope = agentScope ?: return@withLock
        if (enabled && _aidin.value == null) startAidin(scope)
        if (!enabled) stopAidin()
    }

    private fun stopAidin() {
        _aidin.value?.let { runCatching { it.stop() } }
        _aidin.value = null
    }

    private fun writeDiscoveryFile(port: Int, token: String) {
        val file = discoveryFile ?: return
        try {
            file.parentFile?.mkdirs()
            val pid = ProcessHandle.current().pid()
            val json = """
                {
                  "status": "READY",
                  "port": $port,
                  "baseUrl": "http://127.0.0.1:$port",
                  "token": "$token",
                  "apiVersion": ${HandshakeWire.API_VERSION},
                  "minApiVersion": ${HandshakeWire.MIN_API_VERSION},
                  "agentApi": "/v1/agent",
                  "pid": $pid
                }
            """.trimIndent()
            file.writeText(json)
        } catch (_: Exception) {
        }
    }

    private fun removeDiscoveryFile() {
        val file = discoveryFile ?: return
        try {
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * An Aidos SDK client for this host's own `/v1/` endpoint (the same client external apps use;
     * see EngineService.createEngineClient). Null when the Engine is not running. Caller closes it.
     */
    suspend fun createEngineClient(): AidosEngineClient? {
        val tokens = tokenManager
        val server = httpServer
        val runtime = modelRuntime
        if (!_isRunning.value || tokens == null || server == null || runtime == null) return null
        val client = AidosEngineClientFactory.create(
            InProcessHandshakeSource(tokens, { server.getBoundPort() }, { runtime.handshakeCapabilities() })
        )
        if (client.initialize()) return client
        client.close()
        return null
    }

    /** Mirrors EngineService.onDestroy: stop serving, drain inference, unload, drop tokens. */
    suspend fun stop() = lifecycle.withLock {
        val server = httpServer
        val runtime = modelRuntime
        removeDiscoveryFile()
        stopAidin()
        agentScope?.cancel()
        agentScope = null
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

    companion object {
        const val LOCALHOST_SUBJECT = "desktop.localhost"
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
