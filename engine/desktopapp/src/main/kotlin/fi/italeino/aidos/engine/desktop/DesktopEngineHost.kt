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
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.aidin.AIDIN_PROTOCOL_VERSION
import dev.aidos.aidin.AidinNode
import dev.aidos.aidin.ContributionPolicy
import dev.aidos.aidin.DesktopAidin
import dev.aidos.aidin.GroupId
import dev.aidos.aidin.Heartbeat
import dev.aidos.aidin.RemoteRouting
import dev.aidos.aidin.RemoteRoutingAudit
import dev.aidos.aidin.SplitHosting
import dev.aidos.aidin.SplitPlanOutcome
import dev.aidos.modelruntime.GgufLoader
import dev.aidos.modelruntime.InferenceBackend
import dev.aidos.modelruntime.ModelRunSettings
import dev.aidos.decisions.OnnxDecisionBackend
import dev.aidos.vision.OnnxVisionBackend
import dev.aidos.providers.ProviderBackend
import dev.aidos.providers.ProviderConfigs
import dev.aidos.stt.OnnxSttBackend
import dev.aidos.modelruntime.GlobalModelRuntime
import dev.aidos.modelruntime.RunSettingsSource
import dev.aidos.modelruntime.LlamaCppInferenceBackend
import dev.aidos.modelruntime.create
import dev.aidos.models.CatalogEntry
import dev.aidos.models.DatabaseModelCatalogManager
import dev.aidos.models.InstalledModel
import dev.aidos.models.ModelBrowser
import dev.aidos.models.ModelCatalogManager
import fi.italeino.aidos.engine.EngineState
import fi.italeino.aidos.engine.agent.AgentRuntime
import fi.italeino.aidos.engine.agent.PeerRunReaper
import fi.italeino.aidos.engine.agent.agentRoutes
import fi.italeino.aidos.engine.agent.AgentRoutingConfigDto
import fi.italeino.aidos.engine.agent.AgentRunLaunchConfigDto
import fi.italeino.aidos.engine.agent.createEngineAgentRuntime
import fi.italeino.aidos.engine.approval.AppApprovalStatus
import fi.italeino.aidos.engine.handshake.HandshakeWire
import fi.italeino.aidos.engine.handshake.InProcessHandshakeSource
import fi.italeino.aidos.engine.handshake.handshakeCapabilities
import fi.italeino.aidos.engine.license.LicenseAcceptanceRecord
import fi.italeino.aidos.engine.license.LicenseAcceptanceStore
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant

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
    private val aidinFactory: ((CoroutineScope) -> AidinNode)? = null,
    private val aidinPort: Int = DesktopAidin.DEFAULT_PORT,
    /** Set by the headless launcher so the tray can stop it through the admin API; null when the Engine lives in a window. */
    private val onAdminShutdown: (() -> Unit)? = null,
    /** Opens the user vault that holds SSH tunnel keys; only called when the user first uses a tunnel. */
    private val tunnelVault: (() -> dev.aidos.vault.SecretsVault)? = null,
    /** RFC-0115, D56: a machine-wide service starts with a locked passphrase vault; a per-user Engine uses the OS keystore. */
    val vaultGate: VaultGate = VaultGate.fromEnvironment(),
    /** Injectable so tests need no SSH server. */
    private val tunnelTransport: fi.italeino.sshtunnel.SshTransport = fi.italeino.sshtunnel.SshjTransport(),
    /**
     * Where the pinned `llama-server` and `ggml-rpc-server` live (the `llama-rpc-native` CI
     * artifact, RFC-0105 phase 3). Without them this machine neither anchors nor helps a split.
     */
    private val llamaRpcDir: File = System.getenv("AIDOS_LLAMA_RPC_DIR")?.let(::File) ?: File(stateDir, "llama-rpc"),
    /** RAM this machine lends to a split, as anchor or helper; a quarter of its memory by default. */
    private val splitRamBudgetBytes: () -> Long = { DesktopDevice.totalMemoryBytes() / 4 },
    /**
     * Engine-wide maximum placement allowed across all requests (RFC-0104). When null, reads
     * `preferences.json` inside [stateDir] dynamically so settings updates take effect immediately.
     */
    private val engineLocality: (() -> Locality)? = null,
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

    private val _split = MutableStateFlow<SplitHosting?>(null)
    /** Layer split on the running group node; null when sharing is off. */
    val split: StateFlow<SplitHosting?> = _split.asStateFlow()

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
            url = "jdbc:sqlite:" + File(stateDir.apply { mkdirs() }, "aidos_engine.db").absolutePath,
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

    /** RFC-0051: opt-in LAN sharing (off by default); devices are approved through [approvalStore]. */
    val lanSharing: DesktopLanSharing = DesktopLanSharing(stateDir, approvalStore)

    val licenseAcceptanceStore: LicenseAcceptanceStore = FileLicenseAcceptanceStore(File(stateDir, "licenses.json"))

    @Volatile
    private var cachedPrefs: Pair<String, Locality>? = null
    private val prefsJson = Json { ignoreUnknownKeys = true }

    private fun resolveEngineLocality(): Locality {
        engineLocality?.let { return it() }
        val prefsFile = File(stateDir, "preferences.json")
        if (!prefsFile.isFile) return Locality.ANY
        val raw = runCatching { prefsFile.readText() }.getOrNull() ?: return Locality.ANY
        cachedPrefs?.let { (cachedRaw, cachedLocality) ->
            if (raw == cachedRaw) return cachedLocality
        }
        val parsed = runCatching {
            prefsJson.decodeFromString<DesktopPreferencesData>(raw).engineLocalityLimit?.let(Locality::parse)
        }.getOrNull() ?: Locality.ANY
        cachedPrefs = raw to parsed
        return parsed
    }

    var modelRuntime: GlobalModelRuntime? = null
        private set

    private var tokenManager: TokenManager? = null
    private var httpServer: EngineHttpServer? = null
    private var agentScope: CoroutineScope? = null
    private var _agentRuntime: AgentRuntime? = null

    /** RFC-0107 switches (hosting for a peer, preferred peers, per-app tool allowlists); null until the host has started. */
    private val _remoteAgent = MutableStateFlow<DesktopRemoteAgent?>(null)
    val remoteAgentFlow: StateFlow<DesktopRemoteAgent?> = _remoteAgent.asStateFlow()
    var remoteAgent: DesktopRemoteAgent?
        get() = _remoteAgent.value
        private set(value) { _remoteAgent.value = value }

    /** RFC-0108 SSH reverse tunnels; null while group sharing (AIDIN) is off. */
    @Volatile
    var tunnels: DesktopTunnels? = null
        private set

    /** RFC-0108 hints and per-peer remote-access switch; null while group sharing (AIDIN) is off. */
    @Volatile
    var remoteRouting: RemoteRouting? = null
        private set
    val agentRuntime: AgentRuntime? get() = _agentRuntime

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
                CatalogAwareInferenceBackend(
                    ProviderBackend(
                        OnnxVisionBackend(OnnxSttBackend(OnnxDecisionBackend(LlamaCppInferenceBackend(), modelsDir), modelsDir), modelsDir),
                        { ProviderConfigs.load(File(stateDir, "providers.json")) },
                    ),
                    modelsDir = modelsDir,
                    catalogManager = catalogManager,
                ),
                runSettings = runSettings,
                // RFC-0022: memory-based admission -- keep resident models within ~80% of physical RAM.
                residentBudgetBytes = (DesktopDevice.totalMemoryBytes() * 0.8).toLong(),
                residentEstimate = { d, s ->
                    if (d == null) 0L else dev.aidos.cookbook.CookbookEngine().residentEstimate(d, s.contextLength ?: d.contextWindow)
                },
            )
            val desiredPort = serverPort()
            // The agent runtime (/v1/agent) is served from this same process and calls the models
            // through the same InferenceRequestManager as the HTTP API, so admission, model loading
            // and the locality limits (RFC-0104) stay on one path.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val manager = InferenceRequestManager(
                runtime,
                licenseStore = licenseAcceptanceStore,
                // RFC-0103: parallel across models while at least 30% of RAM is free; serialized otherwise.
                maxParallelRequests = 2,
                parallelHeadroom = { DesktopDevice.profile().let { it.availableRamBytes > it.totalRamBytes * 0.3 } },
            )
            lateinit var server: EngineHttpServer
            val initialPrefs = runCatching {
                val prefsFile = File(stateDir, "preferences.json")
                if (prefsFile.isFile) prefsJson.decodeFromString<DesktopPreferencesData>(prefsFile.readText()) else DesktopPreferencesData()
            }.getOrDefault(DesktopPreferencesData())
            val initialRouting = AgentRoutingConfigDto(
                mainModelId = initialPrefs.projectMainModelId.ifBlank { initialPrefs.globalMainModelId },
                defaultRunModelId = initialPrefs.projectDefaultRunModelId.ifBlank { initialPrefs.globalDefaultRunModelId },
                allowMainModelRunOverride = initialPrefs.projectAllowMainModelRunOverride,
                enforcedLocality = initialPrefs.projectEnforcedLocality ?: initialPrefs.globalLocality,
                guardianPolicy = initialPrefs.projectGuardianPolicy ?: initialPrefs.globalGuardianPolicy,
                decisionModelId = initialPrefs.projectDecisionModelId.ifBlank { initialPrefs.globalDecisionModelId },
                guardianLlmModelId = initialPrefs.projectGuardianLlmModelId.ifBlank { initialPrefs.globalGuardianLlmModelId },
            )
            val agent = createEngineAgentRuntime(
                scope,
                manager,
                router = { server.virtualModelRouter },
                aidin = { _aidin.value },
                initialRoutingConfig = initialRouting,
            ).apply {
                setRunLaunchConfig(
                    AgentRunLaunchConfigDto(
                        guardianPolicyOverride = initialPrefs.runGuardianPolicyOverride,
                        runModelOverride = initialPrefs.runModelOverride,
                        localityOverride = initialPrefs.runLocalityOverride,
                    )
                )
            }
            _agentRuntime = agent
            // RFC-0107: the peers this Engine prefers for agent turns (local is always the last resort), and the
            // handler that lets a peer with the explicit `agent` scope run a loop here.
            val remoteAgent = DesktopRemoteAgent(stateDir, aidin = { _aidin.value }, scope = scope, onAuditLine = ::addAuditLine)
            this.remoteAgent = remoteAgent
            server = EngineHttpServer(
                tokens,
                runtime,
                inferenceManager = manager,
                port = desiredPort,
                isSubjectApproved = { id -> approvalStore.getApproval(id)?.status == AppApprovalStatus.APPROVED },
                subjectLocality = { subject ->
                    approvalStore.getApproval(subject)?.localityLimit?.let(Locality::parse)
                },
                engineLocality = { resolveEngineLocality() },
                onLocalityViolation = { subject, required, placement, _ ->
                    val pkg = subject
                    if (pkg != null && pkg != TokenManager.ENGINE_SUBJECT) {
                        approvalStore.recordLocalityRefusal(pkg, required.wire, placement.wire)
                    }
                },
                extraRoutes = {
                    agentRoutes(
                        remoteAgent.routed(agent),
                        localityFor = { subject, requested -> server.effectiveLocality(subject, requested) },
                        // D49: running an agent is a separate grant from inference. The Engine's own token and the
                        // desktop's own localhost client are the user's; every other app needs the per-app grant.
                        mayRunAgent = { subject ->
                            subject == TokenManager.ENGINE_SUBJECT || subject == LOCALHOST_SUBJECT ||
                                approvalStore.getApproval(subject)?.agentAllowed == true
                        },
                    )
                },
                onClientVersions = { id, sdk, api -> approvalStore.recordClientVersions(id, sdk, api) },
                aidinNode = { _aidin.value },
                externalDevices = lanSharing.devices,
                lanControl = LocalLanControl(lanSharing),
                tunnelsControl = LocalTunnelsControl(this),
                peersControl = LocalPeersControl(this),
                appsControl = LocalAppsControl(this),
                statusControl = LocalStatusControl(this),
                vaultControl = vaultGate,
                approvalsControl = LocalApprovalsControl(this),
                onAdminShutdown = onAdminShutdown,
                adminSubjects = setOf(LOCALHOST_SUBJECT, TokenManager.ENGINE_SUBJECT),
                pairingCodes = lanSharing.pairing,
                peerAgentEndpoints = remoteAgent.peerEndpoints(agent),
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
            runCatching { lanSharing.start(server) }.onFailure { _lastError.value = "LAN sharing: ${it.message}" }
            if (groupSharing()) startAidin(scope)
        } catch (e: Exception) {
            _lastError.value = e.message ?: e::class.simpleName
            _state.value = EngineState.FAILED
            removeDiscoveryFile()
        }
    }

    /** Starts the AIDIN peer node and wires its delegation handler into the Engine HTTP server (RFC-0105 phases 1-3). */
    private fun startAidin(scope: CoroutineScope) {
        _aidinError.value = null
        val node = try {
            aidinFactory?.invoke(scope) ?: DesktopAidin.create(
                File(stateDir, "aidin"),
                scope,
                engineVersion = "desktop",
                heartbeatContent = { splitHeartbeat() },
            )
        } catch (e: Exception) {
            _aidinError.value = e.message ?: e::class.simpleName
            return
        }
        try {
            httpServer?.attachAidinNode(node)
            // A fixed port lets paired peers find us after a restart; fall back if it is taken.
            try {
                node.start(port = aidinPort)
            } catch (e: java.io.IOException) {
                node.start(port = 0)
            }
            _aidin.value = node
            // RFC-0108: remote routes (off per peer until the user allows them) and their redial loop.
            val routing = RemoteRouting(node, scope, File(stateDir, "remote-routing.json"), audit = { a -> appendRoutingAudit(a) })
            routing.start()
            remoteRouting = routing
            // RFC-0108 phase 3: the user's SSH reverse tunnels publish the remote-ingress listener; saved, confirmed ones start now.
            val tunnels = DesktopTunnels(stateDir, node = { _aidin.value }, scope = scope, vaultProvider = tunnelVault ?: vaultGate::vault, transport = tunnelTransport, onAuditLine = ::addAuditLine)
            this.tunnels = tunnels
            runCatching { tunnels.startSaved() }.onFailure { addAuditLine("tunnels: ${it.message}") }
            // RFC-0108 phase 5: a hosted run outlives a dropped origin for a bounded window, then is cancelled.
            _agentRuntime?.let { rt ->
                val reaper = PeerRunReaper(rt, window = PEER_RUN_GRACE)
                scope.launch {
                    while (isActive) {
                        val connected = node.peers.value.filter { it.connected }.map { it.nodeId.value }.toSet()
                        reaper.check(connected, System.currentTimeMillis())
                        delay(10_000)
                    }
                }
            }
            _split.value = SplitHosting(node, scope, llamaRpcDir, File(stateDir, "aidin"), ::splitPolicy)
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

    private val _remoteAuditLines = MutableStateFlow<List<String>>(emptyList())

    /** The latest remote-access and remote-agent decisions, newest last, for the Peers tab. */
    val remoteAuditLines: StateFlow<List<String>> = _remoteAuditLines.asStateFlow()

    private fun addAuditLine(line: String) {
        _remoteAuditLines.value = (_remoteAuditLines.value + "${java.time.LocalTime.now().withNano(0)} $line").takeLast(50)
    }

    private fun appendRoutingAudit(a: RemoteRoutingAudit) {
        runCatching {
            val what = when (a) {
                is RemoteRoutingAudit.SwitchChanged -> "switch peer=${a.event.peerId} allowed=${a.event.allowed}"
                is RemoteRoutingAudit.LinkDecision -> "link peer=${a.peer.value} route=${a.route} admitted=${a.admitted}"
                is RemoteRoutingAudit.Dialed -> "dialed peer=${a.peer.value} route=${a.route::class.simpleName} rttMs=${a.rttMillis}"
            }
            addAuditLine(what)
            stateDir.mkdirs()
            File(stateDir, "remote-routing-audit.log").appendText("${java.time.Instant.now()} $what\n")
        }
    }

    private fun stopAidin() {
        tunnels = null
        remoteRouting = null
        _split.value?.let { runCatching { it.close() } }
        _split.value = null
        _aidin.value?.let { runCatching { it.stop() } }
        _aidin.value = null
    }

    // A desktop is on mains power, so only the RAM budget limits what it lends.
    private fun splitPolicy() = ContributionPolicy(ramBudgetBytes = splitRamBudgetBytes())

    private fun splitHeartbeat(): Heartbeat {
        val base = Heartbeat(AIDIN_PROTOCOL_VERSION, "desktop")
        return _split.value?.heartbeat(base) ?: base
    }

    /**
     * Installed GGUF models this machine could anchor as a split, by model id.
     *
     * Combines authoritative installed-model records (RFC-0105) with any manual `.gguf` files
     * placed in [modelsDir], deduplicating by canonical file path so catalog metadata wins when both
     * refer to the same artifact.
     */
    fun splittableModels(): List<String> {
        val installed = runCatching { kotlinx.coroutines.runBlocking { catalogManager.listInstalled().getOrThrow() } }.getOrDefault(emptyList())
        val result = mutableSetOf<String>()
        val catalogFiles = mutableSetOf<File>()
        for (entry in installed) {
            if (!entry.path.endsWith(".gguf", ignoreCase = true)) continue
            val file = File(entry.path)
            if (!file.isFile) continue
            result.add(entry.modelId)
            catalogFiles.add(canonicalOrAbsolute(file))
        }
        modelsDir.listFiles { f -> f.isFile && f.extension.equals("gguf", ignoreCase = true) }
            ?.forEach { file ->
                if (canonicalOrAbsolute(file) !in catalogFiles) {
                    result.add(file.nameWithoutExtension)
                }
            }
        return result.sorted()
    }

    /**
     * Serves installed model [modelId] across [group] with this machine as anchor (RFC-0105 phase 3).
     * Returns the result in words; blocks while llama-server loads, so call it off the main thread.
     */
    fun startSplitModel(group: GroupId, modelId: String): String {
        val split = _split.value ?: return "Turn on sharing first."
        val (file, modelDigest) = resolveSplitModel(modelId) ?: return "$modelId is not installed as a GGUF model."
        val meta = GgufLoader.loadMetadata(file) ?: return "Could not read $modelId."
        if (meta.blockCount < 2) return "$modelId has too few layers to split."
        val outcome = split.start(
            group = group,
            modelId = modelId,
            modelDigest = modelDigest,
            modelFile = file,
            blockCount = meta.blockCount,
            contextLength = meta.contextWindow.takeIf { it > 0 }?.coerceAtMost(4096) ?: 4096,
        )
        return split.status.value ?: (outcome as? SplitPlanOutcome.Refused)?.reason.orEmpty()
    }

    internal fun resolveSplitModel(modelId: String): Pair<File, String>? {
        val installed = runCatching { kotlinx.coroutines.runBlocking { catalogManager.listInstalled().getOrThrow() } }.getOrDefault(emptyList())
        val installedEntry = installed.firstOrNull { it.modelId == modelId }
        val file = run {
            val fromCatalog = installedEntry?.path?.let(::File)
            if (fromCatalog != null && fromCatalog.isFile && fromCatalog.extension.equals("gguf", ignoreCase = true)) {
                fromCatalog
            } else {
                val exactLegacy = File(modelsDir, "$modelId.gguf")
                if (exactLegacy.isFile) exactLegacy else legacyQuantizedMatch(modelId, installed)
            }
        } ?: return null

        val matchingInstalled = installedEntry ?: installed.firstOrNull {
            val f = File(it.path)
            f.isFile && canonicalOrAbsolute(f) == canonicalOrAbsolute(file)
        }
        val digestFromInstalled = matchingInstalled?.digest?.trim()?.takeIf { it.isNotEmpty() }
        val digestFromCatalog = if (digestFromInstalled == null) {
            val catalogEntry = runCatching { kotlinx.coroutines.runBlocking { catalogManager.getCatalog(matchingInstalled?.modelId ?: modelId).getOrNull() } }.getOrNull()
            catalogEntry?.propertiesJson?.let(::parseJsonStringMap)?.get("sha256")?.trim()?.takeIf { it.isNotEmpty() }
        } else null

        val digest = digestFromInstalled ?: digestFromCatalog ?: sha256File(file)
        return file to digest
    }

    private fun legacyQuantizedMatch(modelId: String, installed: List<InstalledModel>): File? {
        if (installed.any { it.modelId == modelId }) return null
        val safeId = modelId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return modelsDir.listFiles()
            ?.firstOrNull {
                it.isFile &&
                    it.extension.equals("gguf", ignoreCase = true) &&
                    it.nameWithoutExtension.startsWith("${safeId}_")
            }
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
            // RFC-0115 / D57: the file holds the Engine's local token, so it is owner-only before it is written,
            // and a failure to protect it removes it rather than leaving a readable token behind.
            dev.aidos.api.socket.OwnerOnlyFiles.writeSecret(file.toPath(), json)
        } catch (e: Exception) {
            runCatching { file.delete() }
            _lastError.value = "Could not write the Engine discovery file safely: ${e.message}"
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
        _agentRuntime = null
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
        /** RFC-0108 phase 5: how long a hosted peer run outlives its dropped origin before it is cancelled. */
        val PEER_RUN_GRACE = kotlin.time.Duration.parse("5m")

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

/**
 * Decorates a desktop [InferenceBackend] with catalog & installed records from [ModelCatalogManager]
 * so `InferenceRequestManager` and `/v1/models` see downloaded license metadata, catalog ids, and
 * real artifact paths/digests alongside manually added files and HTTP providers.
 */
private class CatalogAwareInferenceBackend(
    private val delegate: InferenceBackend,
    private val modelsDir: File,
    private val catalogManager: ModelCatalogManager,
) : InferenceBackend {

    override suspend fun catalog(): List<ModelDescriptor> {
        val base = delegate.catalog()
        val entries = catalogManager.listCatalog().getOrNull().orEmpty()
        if (entries.isEmpty()) return base
        val entriesById = entries.associateBy { it.id }
        val emittedIds = mutableSetOf<String>()
        val merged = base.map { existing ->
            emittedIds.add(existing.id)
            val entry = entriesById[existing.id] ?: return@map existing
            val props = metadataFromCatalogEntry(entry)
            existing.copy(
                digest = existing.digest ?: props["sha256"]?.takeIf { it.isNotBlank() },
                quantization = existing.quantization ?: props["quantization"],
                metadata = existing.metadata + props,
            )
        }.toMutableList()
        for (entry in entries) {
            if (emittedIds.add(entry.id)) {
                merged.add(descriptorFromCatalog(entry))
            }
        }
        return merged
    }

    override suspend fun installed(): List<ModelDescriptor> {
        val base = delegate.installed()
        val catalogById = catalogManager.listCatalog().getOrNull().orEmpty().associateBy { it.id }
        val activeInstalled = catalogManager.listInstalled().getOrNull().orEmpty()
            .filter { File(it.path).exists() }
        if (activeInstalled.isEmpty() && catalogById.isEmpty()) return base

        val installedById = activeInstalled.associateBy { it.modelId }
        val aliasToInstalled = mutableMapOf<File, InstalledModel>()
        for (inst in activeInstalled) {
            aliasToInstalled[canonicalOrAbsolute(File(inst.path))] = inst
        }

        val emittedIds = mutableSetOf<String>()
        val result = mutableListOf<ModelDescriptor>()
        val matchedBaseByModelId = mutableMapOf<String, ModelDescriptor>()

        for (item in base) {
            val ownedByDifferentId = findAliasOwner(item, aliasToInstalled)?.takeIf { it.modelId != item.id }
            if (ownedByDifferentId != null) {
                matchedBaseByModelId[ownedByDifferentId.modelId] = item
                continue
            }
            val inst = installedById[item.id]
            val catalogProps = catalogById[item.id]?.let(::metadataFromCatalogEntry).orEmpty()
            val instProps = inst?.propertiesJson?.let(::parseJsonStringMap).orEmpty()
            val mergedMeta = item.metadata + catalogProps + instProps
            emittedIds.add(item.id)
            result.add(
                item.copy(
                    digest = item.digest ?: inst?.digest?.takeIf { it.isNotBlank() },
                    sizeBytes = item.sizeBytes ?: inst?.sizeBytes?.takeIf { it > 0L },
                    quantization = item.quantization ?: inst?.quantization,
                    metadata = mergedMeta,
                )
            )
        }

        for (inst in activeInstalled) {
            if (!emittedIds.add(inst.modelId)) continue
            val catalogEntry = catalogById[inst.modelId]
            val matchedBase = matchedBaseByModelId[inst.modelId]
            val catalogProps = catalogEntry?.let(::metadataFromCatalogEntry).orEmpty()
            val instProps = parseJsonStringMap(inst.propertiesJson)
            val mergedMeta = matchedBase?.metadata.orEmpty() + catalogProps + instProps
            val placement = mergedMeta["placement"]
            result.add(
                ModelDescriptor(
                    id = inst.modelId,
                    name = catalogEntry?.name ?: matchedBase?.name ?: inst.modelId,
                    kind = catalogEntry?.kind ?: matchedBase?.kind ?: dev.aidos.kernel.ModelKind.LLM,
                    providerId = catalogEntry?.provider ?: matchedBase?.providerId ?: "llama.cpp",
                    isLocal = placement?.let { it != "remote" } ?: matchedBase?.isLocal ?: true,
                    contextWindow = mergedMeta["context_window"]?.toIntOrNull() ?: matchedBase?.contextWindow ?: 4096,
                    sizeBytes = inst.sizeBytes.takeIf { it > 0L } ?: matchedBase?.sizeBytes,
                    digest = inst.digest.takeIf { it.isNotBlank() } ?: matchedBase?.digest,
                    format = mergedMeta["format"] ?: matchedBase?.format,
                    quantization = inst.quantization ?: mergedMeta["quantization"] ?: matchedBase?.quantization,
                    metadata = mergedMeta,
                )
            )
        }
        return result
    }

    override suspend fun computeDigest(modelId: String): String {
        val inst = catalogManager.listInstalled().getOrNull()?.firstOrNull { it.modelId == modelId }
        if (inst != null) {
            val file = File(inst.path)
            if (file.isFile) return sha256File(file)
            if (file.isDirectory && inst.digest.isNotBlank()) return inst.digest
        }
        return delegate.computeDigest(modelId)
    }

    override suspend fun computeDigest(modelId: String, artifactPath: String?): String {
        if (artifactPath != null) {
            val file = File(artifactPath)
            if (file.isFile) return sha256File(file)
        } else {
            return computeDigest(modelId)
        }
        return delegate.computeDigest(modelId, artifactPath)
    }

    override suspend fun delete(modelId: String) {
        val inst = catalogManager.listInstalled().getOrNull()?.firstOrNull { it.modelId == modelId }
        delegate.delete(modelId)
        if (inst != null) {
            val f = File(inst.path)
            if (f.isDirectory) f.deleteRecursively() else if (f.isFile) f.delete()
            catalogManager.uninstall(modelId)
        }
    }

    override suspend fun load(modelId: String): Result<ModelAdapter> {
        val inst = catalogManager.listInstalled().getOrNull()?.firstOrNull { it.modelId == modelId }
        if (inst != null && File(inst.path).exists()) {
            return delegate.load(modelId, inst.path)
        }
        return delegate.load(modelId)
    }

    override suspend fun load(modelId: String, artifactPath: String?): Result<ModelAdapter> {
        val resolved = artifactPath ?: catalogManager.listInstalled().getOrNull()
            ?.firstOrNull { it.modelId == modelId }
            ?.path
            ?.takeIf { File(it).exists() }
        return if (resolved != null) delegate.load(modelId, resolved) else delegate.load(modelId)
    }

    override suspend fun load(
        modelId: String,
        artifactPath: String?,
        settings: ModelRunSettings,
    ): Result<ModelAdapter> {
        val resolved = artifactPath ?: catalogManager.listInstalled().getOrNull()
            ?.firstOrNull { it.modelId == modelId }
            ?.path
            ?.takeIf { File(it).exists() }
        return delegate.load(modelId, resolved, settings)
    }

    override suspend fun unload(modelId: String) {
        delegate.unload(modelId)
    }

    private fun findAliasOwner(item: ModelDescriptor, aliasToInstalled: Map<File, InstalledModel>): InstalledModel? {
        val ggufCandidate = canonicalOrAbsolute(File(modelsDir, "${item.id}.gguf"))
        aliasToInstalled[ggufCandidate]?.let { return it }
        val dirCandidate = canonicalOrAbsolute(File(modelsDir, item.id))
        return aliasToInstalled[dirCandidate]
    }

    private fun metadataFromCatalogEntry(entry: CatalogEntry): Map<String, String> = buildMap {
        putAll(parseJsonStringMap(entry.propertiesJson))
        entry.remoteUrl?.takeIf { it.isNotBlank() }?.let { putIfAbsent("download_url", it) }
    }

    private fun descriptorFromCatalog(entry: CatalogEntry): ModelDescriptor {
        val meta = metadataFromCatalogEntry(entry)
        val isLocal = meta["placement"]?.let { it != "remote" } ?: (entry.provider != "remote")
        return ModelDescriptor(
            id = entry.id,
            name = entry.name,
            kind = entry.kind,
            providerId = entry.provider,
            isLocal = isLocal,
            contextWindow = meta["context_window"]?.toIntOrNull() ?: 4096,
            sizeBytes = meta["size_bytes"]?.toLongOrNull(),
            digest = meta["sha256"]?.takeIf { it.isNotBlank() },
            format = meta["format"],
            quantization = meta["quantization"],
            metadata = meta,
        )
    }
}

/**
 * File-backed [LicenseAcceptanceStore] for Desktop, following [FileAppApprovalStore] conventions:
 * JSON persistence with atomic temp-file replacement, [Mutex] synchronization, and a [MutableStateFlow]
 * seeded with the saved records on construction.
 */
internal class FileLicenseAcceptanceStore(private val file: File) : LicenseAcceptanceStore {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()
    private val records = mutableMapOf<Pair<String, String>, LicenseAcceptanceRecord>()
    private val acceptances: MutableStateFlow<List<LicenseAcceptanceRecord>>

    init {
        for (record in readFromDisk()) {
            records[record.modelId to record.modelVersion] = record
        }
        acceptances = MutableStateFlow(sortedSnapshotLocked())
    }

    override suspend fun isAccepted(
        modelId: String,
        modelVersion: String,
        licenseTextDigest: String,
    ): Boolean = mutex.withLock {
        records[modelId to modelVersion]?.licenseTextDigest == licenseTextDigest
    }

    override suspend fun accept(
        modelId: String,
        modelVersion: String,
        licenseTextDigest: String,
    ): LicenseAcceptanceRecord = mutex.withLock {
        val record = LicenseAcceptanceRecord(
            modelId = modelId,
            modelVersion = modelVersion,
            licenseTextDigest = licenseTextDigest,
            acceptedAt = Instant.now().toString(),
        )
        records[modelId to modelVersion] = record
        val snapshot = sortedSnapshotLocked()
        writeLocked(snapshot)
        acceptances.value = snapshot
        record
    }

    override suspend fun getAcceptance(
        modelId: String,
        modelVersion: String,
    ): LicenseAcceptanceRecord? = mutex.withLock {
        records[modelId to modelVersion]
    }

    override suspend fun listAllAcceptances(): List<LicenseAcceptanceRecord> = mutex.withLock {
        sortedSnapshotLocked()
    }

    override fun watchAcceptances(): Flow<List<LicenseAcceptanceRecord>> = acceptances.asStateFlow()

    override suspend fun revoke(modelId: String, modelVersion: String): Boolean = mutex.withLock {
        val removed = records.remove(modelId to modelVersion) != null
        if (removed) {
            val snapshot = sortedSnapshotLocked()
            writeLocked(snapshot)
            acceptances.value = snapshot
        }
        removed
    }

    override suspend fun clear() {
        mutex.withLock {
            records.clear()
            writeLocked(emptyList())
            acceptances.value = emptyList()
        }
    }

    private fun sortedSnapshotLocked(): List<LicenseAcceptanceRecord> =
        records.values
            .sortedWith(compareByDescending<LicenseAcceptanceRecord> { it.acceptedAt }.thenByDescending { it.modelId })

    private fun readFromDisk(): List<LicenseAcceptanceRecord> {
        if (!file.isFile) return emptyList()
        return try {
            json.decodeFromString<List<LicenseAcceptanceRecord>>(file.readText())
        } catch (_: Exception) {
            runCatching { file.renameTo(File(file.parentFile, "${file.name}.corrupt")) }
            emptyList()
        }
    }

    private fun writeLocked(list: List<LicenseAcceptanceRecord>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(list))
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private fun parseJsonStringMap(raw: String): Map<String, String> = try {
    Json.parseToJsonElement(raw).jsonObject.mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.takeIf { it.isString || it.content.isNotEmpty() }?.content?.let { key to it }
    }.toMap()
} catch (_: Exception) {
    emptyMap()
}

private fun canonicalOrAbsolute(file: File): File = runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)

private fun sha256File(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    file.inputStream().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
