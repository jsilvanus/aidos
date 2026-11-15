package dev.aidos.daemon

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.broker.AuditLog
import dev.aidos.broker.ToolBroker
import dev.aidos.amp.OpenAiHttpModelAdapter
import dev.aidos.mcp.LazyMcpTool
import dev.aidos.mcp.McpClientFactory
import dev.aidos.mcp.McpLifecycleManager
import dev.aidos.mcp.McpOperationAdoptionStore
import dev.aidos.mcp.McpSecretResolver
import dev.aidos.mcp.McpServerActivator
import dev.aidos.mcp.McpServerLoadOutcome
import dev.aidos.mcp.McpServerRegistry
import dev.aidos.mcp.McpServerStore
import dev.aidos.capability.SqliteCapabilityManager
import dev.aidos.executor.AgentLoopTaskRunner
import dev.aidos.executor.CapabilityApprovalResolution
import dev.aidos.executor.EventStore
import dev.aidos.executor.GuardianApprovalVerifier
import dev.aidos.executor.LlmGuardianApprovalVerifier
import dev.aidos.executor.RecalledMemoryItem
import dev.aidos.executor.RemoteModelEgressTool
import dev.aidos.executor.RunCreator
import dev.aidos.executor.SqlBudgetLedger
import dev.aidos.executor.SqliteExecutor
import dev.aidos.executor.TaintInputScreener
import dev.aidos.executor.WorkerSpawner
import dev.aidos.filesystem.FilesystemTool
import dev.aidos.filesystem.PathTrustPolicy
import dev.aidos.git.GitTool
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.CapabilityConstraints
import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRuntime
import dev.aidos.kernel.Permission
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RunId
import dev.aidos.kernel.SubjectKind
import dev.aidos.kernel.UserId
import dev.aidos.memory.Confidence
import dev.aidos.memory.SessionMemoryStore
import dev.aidos.prompt.PromptAssembler
import dev.aidos.retention.BlobStore
import dev.aidos.routing.EnforcedLocality
import dev.aidos.routing.PolicyInferenceRouter
import dev.aidos.routing.RoutingPolicy
import dev.aidos.settings.EgressPolicy
import dev.aidos.settings.ModelLocality
import dev.aidos.settings.ProjectSettingsLoader
import dev.aidos.settings.ProjectSettingsSnapshot
import dev.aidos.settings.SettingDescriptor
import dev.aidos.settings.SettingError
import dev.aidos.settings.SettingOrigin
import dev.aidos.settings.Settings
import dev.aidos.settings.SettingsStore
import dev.aidos.settings.TaintApprovalPolicy
import dev.aidos.settings.UserDbSettingsAudit
import dev.aidos.storage.SqliteContentNodeStore
import dev.aidos.vault.AnthropicAdapter
import dev.aidos.vault.ConsumerKind
import dev.aidos.vault.ConsumerRef
import dev.aidos.vault.RedactionBoundary
import dev.aidos.vault.Redactor
import dev.aidos.vault.SecretRequest
import dev.aidos.vault.SqliteSecretsVault
import dev.aidos.http.DefaultEgressClient
import dev.aidos.http.EgressClient
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The runtime composition root PIPELINE.md has been flagging as missing since the
 * AgentLoop↔executor bridge landed: a real `CapabilityManager` + `ToolBroker` with
 * `FilesystemTool`/`GitTool` registered, a real `InferenceRouter`, and the `PromptAssembler` that
 * together let [SqliteExecutor.drive] produce an actual model response instead of leaving a Run
 * `PENDING` forever. Also constructs [GitRunReconciler] (M13, RFC-0053) and passes it to
 * `SqliteExecutor` as its before-a-Run-starts reconciliation gate — this is the one seam in this
 * class that isn't itself deliberately half-wired; see [GitRunReconciler]'s own doc comment for
 * its scope.
 *
 * **Resolves capabilities for model-emitted tool calls (M19).** [CapabilityResolver] is wired into
 * `AgentLoopTaskRunner`, closing the gap this class's own doc comment used to name here — a Run
 * whose subject actually holds a matching, unexpired, unrevoked capability can now reach a real
 * `TOOL_CALL` execution instead of an automatic `capability.missing` denial. This class still
 * grants nothing itself: whatever capabilities exist for [sessionId] were issued elsewhere
 * (RFC-0018's ordinary grant/delegate flow), before `drive()` is ever called.
 *
 * **Where the model adapter comes from:** [anthropicApiKey] is a plain provider function, not a
 * vault lookup — `SqliteSecretsVault`'s JVM key handling generates a fresh in-memory key by
 * default (its own doc comment: "the key is held in memory"), so wiring live vault resolution
 * here would either silently lose previously-stored secrets across restarts or require deciding
 * a key-persistence strategy, which is exactly the kind of unreviewed architecture decision this
 * slice should not make as a side effect. The provider seam lets the caller source a key however
 * it currently can (an environment variable today, the vault once persistence is settled) without
 * this class caring which. No key configured means [remoteAdapters] is empty and
 * `PolicyInferenceRouter` reports every Run's `MODEL_CALL` as `UnavailableOffline` — a normal,
 * non-throwing `RoutingDecision` `AgentLoopTaskRunner` already handles by failing that one task,
 * not the whole `drive()` call (confirmed by reading both `AnthropicAdapter.invoke`, which wraps
 * its network call in `runCatching`, and `AgentLoopTaskRunner`'s `UnavailableOffline` branch).
 *
 * **Egress policy (M23, RFC-0020/0049/0023): `allowRemote` now reflects the user's own setting,
 * not just whether a key happens to be configured.** [userDriver], when supplied, is resolved
 * through [SettingsStore] for `Settings.routingRemoteEgress`; `EgressPolicy.ALLOW` is the only
 * value that permits automatic remote routing. `NEVER` blocks it outright and is reported as
 * [dev.aidos.kernel.RoutingDecision.UnavailableOffline]. `ASK` — despite its name suggesting a
 * per-Run prompt — also fails closed to "no automatic routing" today, since no per-Run approval
 * flow is wired yet (`AgentLoopTaskRunner`'s own doc comment: a `RemotePendingApproval` decision
 * fails the Run outright rather than parking it for approval — the `continuations` table already
 * has a `CAPABILITY_APPROVAL` slot for this, RFC-0008 step 8d, just nothing writes to it yet).
 * Unlike `NEVER`, though, `ASK` sets [RoutingPolicy.remoteRequiresApproval], so
 * [PolicyInferenceRouter] reports it as [dev.aidos.kernel.RoutingDecision.RemotePendingApproval]
 * naming the specific model that would have been used — a distinct, honest signal that approval
 * is the missing piece, not a silent identical-to-`NEVER` denial. [userDriver] absent (e.g. most
 * existing tests) resolves to the declared default, `ASK`.
 */
class RuntimeCompositionRoot(
    private val anthropicApiKey: () -> CharArray? = { null },
    private val userDriver: SqlDriver? = null,
    private val mcpSecretResolver: McpSecretResolver = envMcpSecretResolver,
    /**
     * RFC-0035: the user-scope vault. When present, every value in it is registered with each
     * Run's redactor, the Anthropic key is read from the entry [ANTHROPIC_KEY_SECRET] first
     * ([anthropicApiKey] is the fallback), and every resolution is audited per project.
     */
    private val vault: SqliteSecretsVault? = null,
    private val mcpClientFactory: McpClientFactory = McpClientFactory.Default,
    val mcpLifecycleManager: McpLifecycleManager = McpLifecycleManager(),
    /**
     * RFC-0036: where `aidos.toml` problems go (scope violations, invalid values, unknown keys,
     * each with file and line). The default prints them; a UI host replaces it to show the user.
     */
    private val settingsDiagnostics: (List<SettingError>) -> Unit = { errors ->
        errors.forEach { DaemonLog.err("aidos: settings ${it.errorClass} ${it.originPath ?: ""}: ${it.message}") }
    },
    /** RFC-0056: background compaction and storage-pressure maintenance after each driven Run. */
    internal val retention: RetentionMaintenance = RetentionMaintenance(),
    /** RFC-0110 Gate A: optional override for inbound prompt-injection screening. */
    private val taintInputScreener: TaintInputScreener? = null,
    /** RFC-0110 Gate B: optional override for the blind Guardian approval verifier. */
    private val guardianVerifier: GuardianApprovalVerifier? = null,
    /** RFC-0110 Gate A: Engine base URL used to construct [SystemOneTaintInputScreener] when [taintInputScreener] is null. */
    private val systemOneBaseUrl: () -> String? = {
        System.getenv("AIDOS_SYSTEMONE_URL") ?: System.getenv("AIDOS_ENGINE_URL")
    },
    /** Optional bearer token provider for [SystemOneTaintInputScreener]. */
    private val systemOneBearerToken: () -> String? = { System.getenv("AIDOS_ENGINE_TOKEN") },
    /** RFC-0020: optional [ModelRuntime] (e.g. GlobalModelRuntime) for local model lifecycle & routing. */
    private val modelRuntime: ModelRuntime? = null,
    /** RFC-0048 / RFC-0049: platform adapter supplying profile, dirs, and execution window. */
    val platform: PlatformAdapter = DesktopPlatformAdapter(),
    /** RFC-0045: live degradation-ladder monitor & `degradation_events` recorder. */
    val degradationMonitor: DaemonDegradationMonitor = DaemonDegradationMonitor(userDriver = userDriver),
) {
    /** RFC-0031: user-scope write path for `mcp_servers` when [userDriver] is present. */
    val mcpServerRegistry: McpServerRegistry? = userDriver?.let(::McpServerRegistry)

    /**
     * Drives [runId] to completion (or a parked/failed stop) using a freshly composed
     * `CapabilityManager`/`ToolBroker`/`InferenceRouter`/`PromptAssembler`/`AgentLoopTaskRunner`
     * stack. Mirrors [SqliteRunExecutor.send]'s own style of composing per-call rather than
     * holding project-scoped state, since [projectDriver] itself is already per-project.
     *
     * No-ops (leaves the Run `PENDING`, same as not calling this at all) if the project's own
     * `root_path` row can't be found — that should never happen for a Run just created against a
     * real session, but silently doing nothing is the D3-honest response to an inconsistency here,
     * not a crash in the middle of `sessions.send()`.
     */
    suspend fun drive(
        projectDriver: SqlDriver,
        runId: RunId,
        projectId: ProjectId,
        sessionId: String,
        deviceId: String,
        platformProfile: PlatformProfile,
        networkAvailable: Boolean,
        idGen: () -> String,
        nowIso: () -> String,
    ) {
        val rootPath = projectRootPath(projectDriver, projectId.value) ?: return
        buildExecutor(
            projectDriver = projectDriver,
            projectId = projectId,
            sessionId = sessionId,
            rootPath = rootPath,
            deviceId = deviceId,
            platformProfile = platformProfile,
            networkAvailable = networkAvailable,
            idGen = idGen,
            nowIso = nowIso,
            runId = runId,
        ).drive(runId)
        expireTaskStateIfTerminal(projectDriver, runId, nowIso())
        // RFC-0037 MVP: collect local, aggregated observability metrics after each driven Run.
        runCatching { MetricsCollector(projectDriver).collect(projectId.value) }
            .onFailure { DaemonLog.err("aidos: metrics collection failed: ${it.message}") }
        // RFC-0056: compaction runs after the Run, on a background dispatcher; it never fails a Run.
        if (!degradationMonitor.shouldPauseBackgroundMaintenance(projectDriver, projectId.value)) {
            runCatching { retention.schedule(projectDriver, projectId.value, rootPath, userDriver, idGen, nowIso) }
        }
    }

    /**
     * Resolves a Run parked on `CAPABILITY_APPROVAL` (RFC-0008 step 8d) — the `approve`/`deny`
     * entry point `daemon`'s socket server and the CLI both go through. Composes the identical
     * stack [drive] does (same router, same remote adapters resolved from the same
     * [anthropicApiKey]) so the adapter [AgentLoopTaskRunner] resumes with is the same one
     * [PolicyInferenceRouter] named when the Run parked, looked up again by model id rather than
     * carried across the park (see [AgentLoopTaskRunner]'s own `resolveRemoteAdapter` doc comment
     * for why).
     *
     * Takes only [runId] (plus the driver and id/time seams) — [sessionId], [projectId], and
     * [deviceId] are read back from the `runs` row itself rather than asked of the caller. They
     * are already durable there (the same row `drive()` wrote them to at Run creation), and a
     * caller resolving an approval by run id alone is the natural shape for `approve <run-id>` /
     * `deny <run-id>` at the CLI.
     *
     * Returns [CapabilityApprovalResolution.NotFound] (rather than throwing) when [runId] or its
     * project's `root_path` row can't be found — the same D3-honest "do nothing observable"
     * response [drive] gives an inconsistency like this, just surfaced as a value here since this
     * call has a caller waiting on a result instead of a fire-and-forget Run drive.
     */
    suspend fun resolveApproval(
        projectDriver: SqlDriver,
        runId: RunId,
        approved: Boolean,
        denialReason: String? = null,
        idGen: () -> String,
        nowIso: () -> String,
        approvalChannel: String = "tap",
        approvalPhrase: String? = null,
    ): CapabilityApprovalResolution {
        val (sessionId, projectId, deviceId) = runOwnership(projectDriver, runId)
            ?: return CapabilityApprovalResolution.NotFound(runId)
        val rootPath = projectRootPath(projectDriver, projectId)
            ?: return CapabilityApprovalResolution.NotFound(runId)
        if (approved) {
            val capMgr = SqliteCapabilityManager(projectDriver, UuidV7Generator(), nowIso, deviceId = deviceId)
            val existingActive = runBlocking { capMgr.loadForSubject(sessionId) }
                .any { it.permission == Permission.NETWORK_EGRESS && it.revokedAt == null }
            if (!existingActive && !capMgr.hasSubjectCapabilityHistory(sessionId, SubjectKind.SESSION, Permission.NETWORK_EGRESS)) {
                runBlocking {
                    capMgr.grant(
                        subjectId = sessionId,
                        subjectKind = SubjectKind.SESSION,
                        permission = Permission.NETWORK_EGRESS,
                        scope = anthropicScope(),
                        constraints = CapabilityConstraints(),
                        expiresAt = Instant.fromEpochSeconds(Instant.parse(nowIso()).epochSeconds + 300),
                        grantedBy = UserId("user"),
                    )
                }
            }
        }
        return buildExecutor(
            projectDriver = projectDriver,
            projectId = ProjectId(projectId),
            sessionId = sessionId,
            rootPath = rootPath,
            deviceId = deviceId,
            platformProfile = platform.profile,
            networkAvailable = runNetworkAvailable(projectDriver, runId),
            idGen = idGen,
            nowIso = nowIso,
            runId = runId,
        ).resolveCapabilityApproval(runId, approved, denialReason, approvalChannel, approvalPhrase).also {
            expireTaskStateIfTerminal(projectDriver, runId, nowIso())
        }
    }

    /**
     * Resolves a Run parked on `TOOL_CALL` (RFC-0008 step 8d) — a tool call denied with
     * `DenialReason.REQUIRES_APPROVAL` (`CapabilityConstraints.requiresApprovalPerUse`).
     *
     * On approve, grants a **fresh, short-lived** capability rather than trying to make the
     * original one pass `validate()` a second time — the original grant's `requiresApprovalPerUse`
     * means every use needs approval, so mutating it would silently exempt every future call too.
     * The fresh grant shares the original's subject/permission/scope, drops
     * `requiresApprovalPerUse`, and expires in a minute — long enough for the one immediate
     * re-drive this triggers, not a standing exemption. `AgentLoopTaskRunner.executeToolCall`'s
     * existing `resolveCapability()` call already re-resolves "the most recently issued, unexpired,
     * unrevoked match" fresh on every execution, so the resumed attempt finds the new grant with no
     * changes needed there.
     *
     * Deliberately does **not** add a bypass parameter to `EffectBroker.invoke()` — that would
     * touch `runtime/kernel/`, which is frozen at G0 (this file's own doc comment set says so
     * throughout; see `PIPELINE.md`'s "Where everything lives" table).
     */
    suspend fun resolveToolCallApproval(
        projectDriver: SqlDriver,
        runId: RunId,
        approved: Boolean,
        denialReason: String? = null,
        idGen: () -> String,
        nowIso: () -> String,
        approvalChannel: String = "tap",
        approvalPhrase: String? = null,
    ): CapabilityApprovalResolution {
        val (sessionId, projectId, deviceId) = runOwnership(projectDriver, runId)
            ?: return CapabilityApprovalResolution.NotFound(runId)
        val rootPath = projectRootPath(projectDriver, projectId)
            ?: return CapabilityApprovalResolution.NotFound(runId)
        val capabilityManager = SqliteCapabilityManager(projectDriver, UuidV7Generator(), nowIso)
        val networkAvailable = runNetworkAvailable(projectDriver, runId)

        return buildExecutor(
            projectDriver = projectDriver,
            projectId = ProjectId(projectId),
            sessionId = sessionId,
            rootPath = rootPath,
            deviceId = deviceId,
            platformProfile = platform.profile,
            networkAvailable = networkAvailable,
            idGen = idGen,
            nowIso = nowIso,
            runId = runId,
        ).resolveToolCallApproval(runId, approved, denialReason, approvalChannel, approvalPhrase) { detailJson ->
                val originalCapId = Json.parseToJsonElement(detailJson).jsonObject["capabilityId"]!!.jsonPrimitive.content
                val original = capabilityManager.loadForSubject(sessionId).find { it.id.value == originalCapId }
                    ?: return@resolveToolCallApproval
                capabilityManager.grant(
                    subjectId = original.subjectId,
                    subjectKind = original.subjectKind,
                    permission = original.permission,
                    scope = original.scope,
                    constraints = original.constraints.copy(requiresApprovalPerUse = false),
                    expiresAt = Instant.fromEpochSeconds(Instant.parse(nowIso()).epochSeconds + 60),
                    grantedBy = UserId("user"),
                )
            }
    }

    /**
     * Resolves a Run parked on **either** `CAPABILITY_APPROVAL` or `TOOL_CALL` — the single entry
     * point [SqliteEffectApprovalGateway] actually calls, so `approve-run`/`deny-run` at the CLI
     * work regardless of which continuation kind is waiting, without a caller needing to know
     * which one it is up front (it can't — that's exactly what it's asking).
     *
     * Tries [resolveApproval] first; [CapabilityApprovalResolution.WrongKind] is returned before
     * either resolver mutates anything (checked immediately after loading the continuation, in
     * both `SqliteExecutor.resolveCapabilityApproval` and `resolveToolCallApproval`), so falling
     * through to [resolveToolCallApproval] on that result is safe — never a double-resolve.
     */
    suspend fun resolveAnyApproval(
        projectDriver: SqlDriver,
        runId: RunId,
        approved: Boolean,
        denialReason: String? = null,
        idGen: () -> String,
        nowIso: () -> String,
        approvalChannel: String = "tap",
        approvalPhrase: String? = null,
    ): CapabilityApprovalResolution {
        val first = resolveApproval(projectDriver, runId, approved, denialReason, idGen, nowIso, approvalChannel, approvalPhrase)
        if (first !is CapabilityApprovalResolution.WrongKind) return first
        val second = resolveToolCallApproval(projectDriver, runId, approved, denialReason, idGen, nowIso, approvalChannel, approvalPhrase)
        // `deny-run` (approved = false) also reaches USER_PROMPT this way: "decline to answer" is
        // exactly resolveUserPrompt's answer = null path. `approve-run` cannot land here for a
        // parked question, though -- there is no yes/no to approve, only an answer to give, which
        // needs the free-text `answer-run <runId> <answer>` CLI command (resolveUserPromptAnswer).
        return if (second is CapabilityApprovalResolution.WrongKind && !approved) {
            resolveUserPromptAnswer(projectDriver, runId, answer = null, denialReason, idGen, nowIso)
        } else {
            second
        }
    }

    /**
     * Resolves a Run parked on `USER_PROMPT` (RFC-0008 step 8d): the model called `ask_user` and
     * is waiting for a reply. [answer] `null` declines (mirrors [resolveApproval]'s deny path,
     * reachable from [resolveAnyApproval] too — see its own doc comment); non-null answers and
     * resumes, via `answer-run <runId> <answer>` at the CLI (not `approve-run`, which has nothing
     * to say yes or no to here).
     */
    suspend fun resolveUserPromptAnswer(
        projectDriver: SqlDriver,
        runId: RunId,
        answer: String?,
        denialReason: String? = null,
        idGen: () -> String,
        nowIso: () -> String,
    ): CapabilityApprovalResolution {
        val (sessionId, projectId, deviceId) = runOwnership(projectDriver, runId)
            ?: return CapabilityApprovalResolution.NotFound(runId)
        val rootPath = projectRootPath(projectDriver, projectId)
            ?: return CapabilityApprovalResolution.NotFound(runId)
        return buildExecutor(
            projectDriver = projectDriver,
            projectId = ProjectId(projectId),
            sessionId = sessionId,
            rootPath = rootPath,
            deviceId = deviceId,
            platformProfile = platform.profile,
            networkAvailable = runNetworkAvailable(projectDriver, runId),
            idGen = idGen,
            nowIso = nowIso,
            runId = runId,
        ).resolveUserPrompt(runId, answer, denialReason)
    }

    private fun runOwnership(driver: SqlDriver, runId: RunId): Triple<String, String, String>? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT session_id, project_id, device_id FROM runs WHERE id = ?",
            mapper = { c ->
                QueryResult.Value(
                    if (c.next().value) Triple(c.getString(0)!!, c.getString(1)!!, c.getString(2)!!) else null
                )
            },
            parameters = 1,
        ) { bindString(0, runId.value) }.value

    private fun runNetworkAvailable(driver: SqlDriver, runId: RunId): Boolean =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT network_available FROM runs WHERE id = ?",
            mapper = { c ->
                QueryResult.Value(if (c.next().value) (c.getLong(0) ?: 0L) != 0L else false)
            },
            parameters = 1,
        ) { bindString(0, runId.value) }.value

    private fun buildExecutor(
        projectDriver: SqlDriver,
        sessionId: String,
        rootPath: String,
        deviceId: String,
        idGen: () -> String,
        nowIso: () -> String,
    ): SqliteExecutor = buildExecutor(
        projectDriver = projectDriver,
        projectId = ProjectId(""),
        sessionId = sessionId,
        rootPath = rootPath,
        deviceId = deviceId,
        platformProfile = platform.profile,
        idGen = idGen,
        nowIso = nowIso,
    )

    private fun buildExecutor(
        projectDriver: SqlDriver,
        projectId: ProjectId,
        sessionId: String,
        rootPath: String,
        deviceId: String,
        platformProfile: PlatformProfile = platform.profile,
        networkAvailable: Boolean = false,
        idGen: () -> String,
        nowIso: () -> String,
        runId: RunId? = null,
    ): SqliteExecutor {
        // RFC-0035: registered before any adapter, audit log, or MCP server ever sees a secret.
        val redactor = Redactor(sink = RedactionEventsSink(projectDriver, projectId.value, idGen, nowIso))
        vault?.registerAllWith(redactor)
        val audit = AuditLog(projectDriver, deviceId) { redactor.redactJson(it, RedactionBoundary.LOG) }
        val capabilityManager = SqliteCapabilityManager(projectDriver, UuidV7Generator(), nowIso, deviceId = deviceId)
        val broker = ToolBroker(
            capabilityManager = capabilityManager,
            audit = audit,
            idGen = idGen,
            nowIso = nowIso,
            projectIdResolver = { capId -> capabilityManager.projectIdForCapability(capId) ?: "" },
        )
        // RFC-0036: aidos.toml is untrusted input that arrives with the repository. Loaded once,
        // before any tool is built, because trust.untrusted_paths (RFC-0027) shapes the
        // filesystem tool's result labels and routing.remote_egress shapes the router below.
        val projectSettings = loadProjectSettings(projectDriver, projectId, rootPath, nowIso())
        broker.register(FilesystemTool(trustPolicy = PathTrustPolicy(resolveUntrustedPaths(userDriver, projectSettings))))
        broker.register(GitTool(File(rootPath), credentials = vault?.let { v ->
            VaultGitCredentialSource(v) { host -> auditSecretUse(audit, projectId.value, "RUNTIME", "git:$host", "git:$host", nowIso, idGen) }
        }))
        // RFC-0021 / RFC-0023: every remote model invocation routes through EffectBroker.
        broker.register(RemoteModelEgressTool())
        val memoryStore = SessionMemoryStore(
            driver = projectDriver,
            redact = { redactor.redact(it, RedactionBoundary.MEMORY) },
        )
        val egressClient: EgressClient = DefaultEgressClient(recordSink = SqliteEgressRecorder(projectDriver))
        val redactingMcpSecretResolver = object : McpSecretResolver {
            override suspend fun resolve(reference: String): Result<CharArray> = resolveFor("runtime", reference)

            override suspend fun resolveFor(serverId: String, reference: String): Result<CharArray> =
                mcpSecretResolver.resolveFor(serverId, reference).onSuccess { secret ->
                    redactor.register(id = "mcp-secret-$reference", name = reference, value = secret.copyOf())
                    auditSecretUse(audit, projectId.value, "MCP_SERVER", serverId, reference, nowIso, idGen)
                }
        }

        if (userDriver != null) {
            val serverStore = McpServerStore(userDriver)
            val adoptionStore = McpOperationAdoptionStore(projectDriver)
            adoptionStore.migrateAdoptionHashes(projectId.value)
            val activator = McpServerActivator(
                serverStore = serverStore,
                adoptionStore = adoptionStore,
                secretResolver = redactingMcpSecretResolver,
                clientFactory = mcpClientFactory,
            )
            for (outcome in serverStore.loadAll(platformProfile)) {
                if (outcome is McpServerLoadOutcome.Loaded && platformProfile in outcome.load.profiles) {
                    val serverId = outcome.load.registration.serverId
                    val adoptedSpecs = adoptionStore.adoptedCatalog(projectId.value, serverId)
                    if (adoptedSpecs.isNotEmpty()) {
                        ensureMcpServerCapability(
                            capabilityManager = capabilityManager,
                            projectId = projectId,
                            serverId = serverId,
                            transport = outcome.load.registration.transport,
                        )
                        val lazyTool = LazyMcpTool(
                            serverId = serverId,
                            projectId = projectId.value,
                            initialAdoptedSpecs = adoptedSpecs,
                            transport = outcome.load.registration.transport,
                            activator = { activator.activate(projectId.value, serverId, platformProfile) }
                        )
                        mcpLifecycleManager.register(lazyTool)
                        broker.register(lazyTool)
                    }
                }
            }
        }

        val providerKey = vaultAnthropicKey(audit, projectId.value, nowIso, idGen) ?: anthropicApiKey()
        val remoteAdapters = mutableListOf<dev.aidos.kernel.ModelAdapter>()
        if (providerKey != null) {
            redactor.register(id = "anthropic-api-key", name = ANTHROPIC_KEY_SECRET, value = providerKey)
            remoteAdapters += AnthropicAdapter(
                apiKeyChars = providerKey,
                transport = EgressJsonTransport(
                    egressClient = egressClient,
                    scope = anthropicScope(),
                    projectId = projectId.value,
                ),
            )
        }
        openAiAdapter(projectId.value, egressClient)?.let { remoteAdapters += it }
        // Loading aidos.toml (above) reports and audits a project's attempt at
        // routing.remote_egress (SECURITY); resolving the policy against the project scope here
        // makes that attempt provably not apply.
        val egressPolicy = resolveEgressPolicy(userDriver, projectSettings)
        if (egressPolicy == EgressPolicy.ALLOW && remoteAdapters.isNotEmpty() &&
            !capabilityManager.hasSubjectCapabilityHistory(sessionId, SubjectKind.SESSION, Permission.NETWORK_EGRESS)
        ) {
            runBlocking {
                capabilityManager.grant(
                    subjectId = sessionId,
                    subjectKind = SubjectKind.SESSION,
                    permission = Permission.NETWORK_EGRESS,
                    scope = anthropicScope(),
                    constraints = CapabilityConstraints(),
                    expiresAt = null,
                    grantedBy = UserId("runtime"),
                )
            }
        }
        val baselineLocality = resolveModelLocality(
            userDriver = userDriver,
            project = projectSettings,
            projectDriver = projectDriver,
            projectId = projectId,
            runId = null,
        )
        val baselinePreferredModelId = resolveRunModelId(
            userDriver = userDriver,
            project = projectSettings,
            projectDriver = projectDriver,
            projectId = projectId,
            runId = null,
            isWorkerSession = false,
        )
        val router = PolicyInferenceRouter(
            policy = RoutingPolicy(
                allowRemote = allowRemoteFor(egressPolicy),
                // M23: ASK denies automatically today (no per-Run approval flow is wired yet —
                // see RoutingPolicy.remoteRequiresApproval's own doc comment), but is reported
                // distinctly from an explicit NEVER, not silently identical to it.
                remoteRequiresApproval = egressPolicy == EgressPolicy.ASK,
                allowedRemoteModelIds = remoteAdapters.map { it.modelId }.toSet(),
                enforcedLocality = baselineLocality,
                preferredModelId = baselinePreferredModelId,
            ),
            remoteAdapters = remoteAdapters,
            modelRuntime = modelRuntime,
        )

        val capabilityResolver = CapabilityResolver(capabilityManager, nowIso)
        val runCreator = RunCreator(projectDriver, idGen, nowIso)
        val workerSpawner = WorkerSpawner(
            driver = projectDriver,
            capabilityManager = capabilityManager,
            runCreator = runCreator,
            idGen = idGen,
            nowIso = nowIso,
        )
        val resolvedScreener: TaintInputScreener? = taintInputScreener
            ?: systemOneBaseUrl()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { url ->
                    SystemOneTaintInputScreener(
                        baseUrl = url,
                        bearerToken = systemOneBearerToken,
                        modelIdProvider = {
                            resolveGuardianDecisionModel(
                                userDriver = userDriver,
                                project = projectSettings,
                                projectDriver = projectDriver,
                                projectId = projectId,
                                runId = runId,
                            )
                        },
                    )
                }
        val resolvedGuardianVerifier: GuardianApprovalVerifier = guardianVerifier
            ?: LlmGuardianApprovalVerifier(
                router = router,
                resolveRemoteAdapter = { modelId -> remoteAdapters.find { it.modelId == modelId } },
                profile = platformProfile,
                networkAvailable = { networkAvailable },
                preferredModelId = {
                    resolveGuardianLlmModel(
                        userDriver = userDriver,
                        project = projectSettings,
                        projectDriver = projectDriver,
                        projectId = projectId,
                        runId = runId,
                    )
                },
                selectAdapter = { routingCtx, modelId ->
                    val locality = resolveModelLocality(
                        userDriver = userDriver,
                        project = projectSettings,
                        projectDriver = projectDriver,
                        projectId = projectId,
                        runId = runId,
                    )
                    router.selectWithOverride(
                        kind = ModelKind.LLM,
                        context = routingCtx,
                        preferredModelId = modelId,
                        enforcedLocality = locality,
                    )
                },
            )
        val budgetLedger = SqlBudgetLedger(projectDriver, idGen, nowIso)
        val contentNodeStore = SqliteContentNodeStore(projectDriver)
        val taskRunner = AgentLoopTaskRunner(
            driver = projectDriver,
            audit = audit,
            idGen = idGen,
            nowIso = nowIso,
            router = router,
            assembler = PromptAssembler(
                redact = { redactor.redact(it, RedactionBoundary.PROMPT) },
                contentNodeLookup = { id -> contentNodeStore.get(id) },
            ),
            broker = broker,
            subjectId = sessionId,
            redact = { redactor.redact(it, RedactionBoundary.ATTEMPT) },
            detectSecrets = { _, text ->
                redactor.detections(text).also { kinds ->
                    // Records the detection (kind + boundary) in redaction_events; the result is discarded.
                    if (kinds.isNotEmpty()) redactor.redact(text, RedactionBoundary.PROMPT)
                }
            },
            resolveCapability = capabilityResolver::resolve,
            resolveRemoteAdapter = { modelId -> remoteAdapters.find { it.modelId == modelId } },
            workerSpawner = workerSpawner,
            resolveToolCapability = capabilityResolver::resolveForTool,
            // RFC-0056: large tool outputs are stored once, content-addressed, under .aidos/blobs.
            largeOutputs = BlobStore(projectDriver, File(rootPath, RetentionMaintenance.BLOBS_DIR), nowIso),
            resolveTaintApprovalPolicy = {
                resolveTaintApprovalPolicy(
                    userDriver = userDriver,
                    project = projectSettings,
                    projectDriver = projectDriver,
                    projectId = projectId,
                    runId = runId,
                )
            },
            taintInputScreener = resolvedScreener,
            guardianVerifier = resolvedGuardianVerifier,
            resolveTaintApprovalPolicyForRun = { activeRunId ->
                resolveTaintApprovalPolicy(
                    userDriver = userDriver,
                    project = projectSettings,
                    projectDriver = projectDriver,
                    projectId = projectId,
                    runId = activeRunId,
                )
            },
            resolveRunModelId = { activeRunId, isWorkerSession ->
                resolveRunModelId(
                    userDriver = userDriver,
                    project = projectSettings,
                    projectDriver = projectDriver,
                    projectId = projectId,
                    runId = activeRunId,
                    isWorkerSession = isWorkerSession,
                )
            },
            resolveRunLocality = { activeRunId ->
                resolveModelLocality(
                    userDriver = userDriver,
                    project = projectSettings,
                    projectDriver = projectDriver,
                    projectId = projectId,
                    runId = activeRunId,
                ).name.lowercase()
            },
            selectModelDecision = { kind, routingCtx, preferredModelId, enforcedLocality ->
                val loc = enforcedLocality?.let { raw ->
                    EnforcedLocality.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                }
                router.selectWithOverride(
                    kind = kind,
                    context = routingCtx,
                    preferredModelId = preferredModelId,
                    enforcedLocality = loc,
                )
            },
            budgetLedger = budgetLedger,
            invokeRemoteModelWithContext = { adapter, request, capId, pid, attemptId, runTaint ->
                withContext(
                    EgressTransportContextElement(
                        EgressTransportContext(
                            capabilityId = capId,
                            projectId = pid,
                            attemptId = attemptId,
                            runTaint = runTaint.name,
                        )
                    )
                ) {
                    adapter.invoke(request)
                }
            },
            recallSessionMemory = { sid, pid ->
                memoryStore.readForSessionAndProject(sid, pid, nowIso()).map { row ->
                    RecalledMemoryItem(
                        id = row.id,
                        kind = row.kind.name,
                        content = row.content,
                        trustLevel = row.trustLevel,
                        inferred = row.confidence == Confidence.INFERRED,
                    )
                }
            },
            onMemoryRecalled = { entryIds ->
                val now = nowIso()
                entryIds.forEach { memoryStore.refreshFactExpiry(it, now) }
            },
        )
        return SqliteExecutor(
            driver = projectDriver,
            audit = audit,
            events = EventStore(projectDriver) { redactor.redactJson(it, RedactionBoundary.EVENT) },
            idGen = idGen,
            nowIso = nowIso,
            taskRunner = taskRunner,
            reconciler = GitRunReconciler(idGen = idGen, nowIso = nowIso, deviceId = deviceId),
            workerSpawner = workerSpawner,
            workerCommitter = GitWorkerCommitter(projectDriver),
            budgetLedger = budgetLedger,
            capabilityManager = capabilityManager,
        )
    }

    private fun expireTaskStateIfTerminal(projectDriver: SqlDriver, runId: RunId, nowIso: String) {
        val state = projectDriver.executeQuery(
            identifier = null,
            sql = "SELECT state FROM runs WHERE id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) },
            parameters = 1,
        ) { bindString(0, runId.value) }.value
        if (state in setOf("COMPLETED", "FAILED", "CANCELLED")) {
            SessionMemoryStore(projectDriver).expireTaskStateForRun(runId.value, nowIso)
        }
    }

    private fun loadProjectSettings(
        projectDriver: SqlDriver, projectId: ProjectId, rootPath: String, nowIso: String,
    ): ProjectSettingsSnapshot {
        val file = File(rootPath, "aidos.toml")
        val tooBig = file.isFile && file.length() > MAX_AIDOS_TOML_BYTES
        // A symlinked aidos.toml could point anywhere; the file is repository-supplied.
        val readable = file.isFile && !tooBig && !java.nio.file.Files.isSymbolicLink(file.toPath())
        val content = if (readable) runCatching { file.readText() }.getOrNull() else null
        val load = ProjectSettingsLoader(projectDriver, userDriver?.let { UserDbSettingsAudit(it) })
            .load(projectId.value, content, nowIso)
        val problems = if (tooBig) load.diagnostics + SettingError(
            key = "", originPath = "aidos.toml", line = null,
            message = "aidos.toml is larger than $MAX_AIDOS_TOML_BYTES bytes. Ignored.",
            errorClass = dev.aidos.settings.SettingErrorClass.VALIDATION_FAILED,
        ) else load.diagnostics
        if (problems.isNotEmpty()) settingsDiagnostics(problems)
        return load.snapshot
    }

    /** RFC-0035 provider consumer: the Anthropic key from the vault, audited. Null if the vault has none. */
    private fun vaultAnthropicKey(audit: AuditLog, projectId: String, nowIso: () -> String, idGen: () -> String): CharArray? = kotlinx.coroutines.runBlocking {
        val v = vault ?: return@runBlocking null
        val entry = v.getByName(ANTHROPIC_KEY_SECRET).getOrNull() ?: return@runBlocking null
        val key = v.resolveFor(entry.id, SecretRequest(ConsumerRef(ConsumerKind.PROVIDER, "anthropic"))).getOrNull() ?: return@runBlocking null
        auditSecretUse(audit, projectId, "RUNTIME", "anthropic", ANTHROPIC_KEY_SECRET, nowIso, idGen)
        key
    }

    /** One `audit_log` row per resolution; names the secret and the consumer, never the value. */
    private fun auditSecretUse(audit: AuditLog, projectId: String, actorKind: String, consumerId: String, secretName: String, nowIso: () -> String, idGen: () -> String) {
        runCatching {
            audit.write(
                id = idGen(), projectId = projectId, kind = "SecretResolved", actorKind = actorKind, actorId = consumerId,
                subjectRef = "secret:$secretName", detailJson = "{\"consumer\":\"$actorKind:$consumerId\"}", nowIso = nowIso(),
            )
        }
    }

    private fun openAiAdapter(projectId: String, egressClient: EgressClient): OpenAiHttpModelAdapter? {
        val base = System.getenv("OPENAI_BASE_URL")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val model = System.getenv("OPENAI_MODEL")?.trim()?.takeIf { it.isNotEmpty() } ?: "gpt-4o-mini"
        val key = System.getenv("OPENAI_API_KEY")?.trim().orEmpty()
        val scope = runCatching { openAiScope(base) }.getOrNull() ?: return null
        return OpenAiHttpModelAdapter(
            baseUrl = base,
            modelId = model,
            apiKey = key,
            transport = EgressJsonTransport(
                egressClient = egressClient,
                scope = scope,
                projectId = projectId,
            ),
        )
    }

    private fun projectRootPath(driver: SqlDriver, projectId: String): String? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT root_path FROM projects WHERE id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) },
            parameters = 1,
        ) { bindString(0, projectId) }.value

    companion object {
        /** Pre-vault behaviour: a `secret_ref` names an environment variable. Used when no vault is open. */
        val envMcpSecretResolver: McpSecretResolver = McpSecretResolver { ref ->
            System.getenv(ref)?.toCharArray()?.let { Result.success(it) }
                ?: System.getenv(ref.uppercase())?.toCharArray()?.let { Result.success(it) }
                ?: Result.failure(NoSuchElementException("Secret not found: $ref"))
        }
        /**
         * M23: resolves `Settings.routingRemoteEgress` from [userDriver] (user scope only, per
         * the setting's SECURITY scope class), falling back to the declared default (`ASK`) when
         * no [userDriver] is supplied. `internal` so tests can verify the resolution without
         * driving a full Run.
         */
        internal fun resolveEgressPolicy(
            userDriver: SqlDriver?,
            project: ProjectSettingsSnapshot? = null,
        ): EgressPolicy =
            userDriver?.let {
                SettingsStore(userDriver = it, projectDriver = null)
                    .resolve(Settings.routingRemoteEgress, project = project).value
            } ?: Settings.routingRemoteEgress.default

        private fun <T> resolvePreference(
            descriptor: SettingDescriptor<T>,
            userDriver: SqlDriver? = null,
            project: ProjectSettingsSnapshot? = null,
            projectDriver: SqlDriver? = null,
            projectId: ProjectId? = null,
            runId: RunId? = null,
        ): T {
            val storeDriver = userDriver ?: projectDriver
            if (storeDriver != null) {
                return SettingsStore(userDriver = storeDriver, projectDriver = projectDriver)
                    .resolve(
                        descriptor,
                        projectId = projectId?.value,
                        project = project,
                        runId = runId?.value,
                    )
                    .value
            }
            val entry = project?.entries?.get(descriptor.key)?.value
            if (entry != null) {
                val decoded = descriptor.codec.decode(entry)
                if (decoded.isSuccess) return decoded.getOrThrow()
            }
            return descriptor.default
        }

        /**
         * RFC-0110: resolves `Settings.trustTaintApprovalPolicy` across Run -> Project -> User
         * -> Default (`MANUAL`) per PREFERENCE scoping.
         */
        internal fun resolveTaintApprovalPolicy(
            userDriver: SqlDriver?,
            project: ProjectSettingsSnapshot? = null,
            projectDriver: SqlDriver? = null,
            projectId: ProjectId? = null,
            runId: RunId? = null,
        ): TaintApprovalPolicy = resolvePreference(
            descriptor = Settings.trustTaintApprovalPolicy,
            userDriver = userDriver,
            project = project,
            projectDriver = projectDriver,
            projectId = projectId,
            runId = runId,
        )

        /**
         * Resolves `Settings.trustGuardianDecisionModel` across Run -> Project -> User -> Default.
         */
        internal fun resolveGuardianDecisionModel(
            userDriver: SqlDriver?,
            project: ProjectSettingsSnapshot? = null,
            projectDriver: SqlDriver? = null,
            projectId: ProjectId? = null,
            runId: RunId? = null,
        ): String = resolvePreference(
            descriptor = Settings.trustGuardianDecisionModel,
            userDriver = userDriver,
            project = project,
            projectDriver = projectDriver,
            projectId = projectId,
            runId = runId,
        ).trim().ifEmpty { Settings.trustGuardianDecisionModel.default }

        /**
         * Resolves `Settings.trustGuardianLlmModel` across Run -> Project -> User, returning null
         * when configured as empty (automatic routing).
         */
        internal fun resolveGuardianLlmModel(
            userDriver: SqlDriver?,
            project: ProjectSettingsSnapshot? = null,
            projectDriver: SqlDriver? = null,
            projectId: ProjectId? = null,
            runId: RunId? = null,
        ): String? = resolvePreference(
            descriptor = Settings.trustGuardianLlmModel,
            userDriver = userDriver,
            project = project,
            projectDriver = projectDriver,
            projectId = projectId,
            runId = runId,
        ).trim().takeIf { it.isNotEmpty() }

        /**
         * Resolves `Settings.routingModelLocality` across Run -> Project -> User -> Default,
         * mapping it to [EnforcedLocality].
         */
        internal fun resolveModelLocality(
            userDriver: SqlDriver?,
            project: ProjectSettingsSnapshot? = null,
            projectDriver: SqlDriver? = null,
            projectId: ProjectId? = null,
            runId: RunId? = null,
        ): EnforcedLocality = when (
            resolvePreference(
                descriptor = Settings.routingModelLocality,
                userDriver = userDriver,
                project = project,
                projectDriver = projectDriver,
                projectId = projectId,
                runId = runId,
            )
        ) {
            ModelLocality.LOCAL -> EnforcedLocality.LOCAL
            ModelLocality.CLUSTER -> EnforcedLocality.CLUSTER
            ModelLocality.REMOTE -> EnforcedLocality.REMOTE
        }

        /**
         * Resolves the preferred LLM model ID for a Run.
         *
         * For worker sessions (`role = WORKER`), `model.default_run_llm` takes precedence over
         * `model.default_llm`. For main sessions, a Run-scoped `model.default_run_llm` override (if
         * written explicitly for this Run) wins, followed by `model.default_llm` across Run ->
         * Project -> User.
         */
        internal fun resolveRunModelId(
            userDriver: SqlDriver?,
            project: ProjectSettingsSnapshot? = null,
            projectDriver: SqlDriver? = null,
            projectId: ProjectId? = null,
            runId: RunId? = null,
            isWorkerSession: Boolean = false,
        ): String? {
            if (isWorkerSession) {
                val workerModel = resolvePreference(
                    descriptor = Settings.modelDefaultRunLlm,
                    userDriver = userDriver,
                    project = project,
                    projectDriver = projectDriver,
                    projectId = projectId,
                    runId = runId,
                ).trim().takeIf { it.isNotEmpty() }
                if (workerModel != null) return workerModel
            } else if (runId != null && projectDriver != null) {
                val runValues = SettingsStore.readRunValues(projectDriver, runId.value)
                val runOverride = runValues[Settings.modelDefaultRunLlm.key]?.let { raw ->
                    runCatching { Json.parseToJsonElement(raw) }
                        .getOrNull()
                        ?.let { Settings.modelDefaultRunLlm.codec.decode(it).getOrNull() }
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }
                if (runOverride != null) return runOverride
            }
            return resolvePreference(
                descriptor = Settings.modelDefaultLlm,
                userDriver = userDriver,
                project = project,
                projectDriver = projectDriver,
                projectId = projectId,
                runId = runId,
            ).trim().takeIf { it.isNotEmpty() }
        }

        /**
         * RFC-0027 + RFC-0036: the effective `trust.untrusted_paths`. Specifying a path can only
         * tighten authority, so the user-scope (or declared default) list is a floor that a
         * project's `aidos.toml` can add to but never shrink; only the user may narrow it.
         * `internal` so tests can verify the resolution without driving a full Run.
         */
        internal fun resolveUntrustedPaths(
            userDriver: SqlDriver?,
            project: ProjectSettingsSnapshot? = null,
        ): List<String> {
            val descriptor = Settings.trustUntrustedPaths
            if (userDriver == null) return descriptor.default
            val store = SettingsStore(userDriver = userDriver, projectDriver = null)
            val base = store.resolve(descriptor).value
            val fromProject = store.resolve(descriptor, project = project)
                .takeIf { it.origin == SettingOrigin.PROJECT }?.value.orEmpty()
            return (base + fromProject).distinct()
        }

        /** `aidos.toml` comes with the repository; bound what is read from it. */
        internal const val MAX_AIDOS_TOML_BYTES = 256 * 1024L

        /**
         * M23: only `ALLOW` permits automatic remote routing. `NEVER` blocks it outright; `ASK`
         * fails closed to the same result, since no per-Run approval flow exists yet to honor
         * what "ASK" actually promises (see this class's own doc comment). `internal` so tests
         * can verify the mapping directly.
         */
        internal fun allowRemoteFor(egressPolicy: EgressPolicy): Boolean = egressPolicy == EgressPolicy.ALLOW
    }
}
