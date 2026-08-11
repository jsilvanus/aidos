package dev.aidos.daemon

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.broker.AuditLog
import dev.aidos.broker.ToolBroker
import dev.aidos.capability.SqliteCapabilityManager
import dev.aidos.executor.AgentLoopTaskRunner
import dev.aidos.executor.CapabilityApprovalResolution
import dev.aidos.executor.EventStore
import dev.aidos.executor.SqliteExecutor
import dev.aidos.filesystem.FilesystemTool
import dev.aidos.git.GitTool
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RunId
import dev.aidos.prompt.PromptAssembler
import dev.aidos.routing.PolicyInferenceRouter
import dev.aidos.routing.RoutingPolicy
import dev.aidos.settings.EgressPolicy
import dev.aidos.settings.Settings
import dev.aidos.settings.SettingsStore
import dev.aidos.vault.AnthropicAdapter
import dev.aidos.vault.Redactor
import java.io.File

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
) {

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
        buildExecutor(projectDriver, sessionId, rootPath, deviceId, idGen, nowIso).drive(runId)
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
    ): CapabilityApprovalResolution {
        val (sessionId, projectId, deviceId) = runOwnership(projectDriver, runId)
            ?: return CapabilityApprovalResolution.NotFound(runId)
        val rootPath = projectRootPath(projectDriver, projectId)
            ?: return CapabilityApprovalResolution.NotFound(runId)
        return buildExecutor(projectDriver, sessionId, rootPath, deviceId, idGen, nowIso)
            .resolveCapabilityApproval(runId, approved, denialReason)
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

    private fun buildExecutor(
        projectDriver: SqlDriver,
        sessionId: String,
        rootPath: String,
        deviceId: String,
        idGen: () -> String,
        nowIso: () -> String,
    ): SqliteExecutor {
        val audit = AuditLog(projectDriver, deviceId)
        val capabilityManager = SqliteCapabilityManager(projectDriver, UuidV7Generator(), nowIso)
        val broker = ToolBroker(
            capabilityManager = capabilityManager,
            audit = audit,
            idGen = idGen,
            nowIso = nowIso,
            projectIdResolver = { capId -> capabilityManager.projectIdForCapability(capId) ?: "" },
        )
        broker.register(FilesystemTool())
        broker.register(GitTool(File(rootPath)))

        // RFC-0035: registered before the adapter ever sees the key, so the very first
        // output_snapshot that could echo it back is already covered.
        val redactor = Redactor()
        val remoteAdapters = anthropicApiKey()?.let { key ->
            redactor.register(id = "anthropic-api-key", name = "anthropic_api_key", value = key)
            listOf(AnthropicAdapter(key))
        } ?: emptyList()
        val egressPolicy = resolveEgressPolicy(userDriver)
        val router = PolicyInferenceRouter(
            policy = RoutingPolicy(
                allowRemote = allowRemoteFor(egressPolicy),
                // M23: ASK denies automatically today (no per-Run approval flow is wired yet —
                // see RoutingPolicy.remoteRequiresApproval's own doc comment), but is reported
                // distinctly from an explicit NEVER, not silently identical to it.
                remoteRequiresApproval = egressPolicy == EgressPolicy.ASK,
                allowedRemoteModelIds = remoteAdapters.map { it.modelId }.toSet(),
            ),
            remoteAdapters = remoteAdapters,
        )

        val capabilityResolver = CapabilityResolver(capabilityManager, nowIso)
        val taskRunner = AgentLoopTaskRunner(
            driver = projectDriver,
            audit = audit,
            idGen = idGen,
            nowIso = nowIso,
            router = router,
            assembler = PromptAssembler(),
            broker = broker,
            subjectId = sessionId,
            redact = redactor::redact,
            resolveCapability = capabilityResolver::resolve,
            resolveRemoteAdapter = { modelId -> remoteAdapters.find { it.modelId == modelId } },
        )
        return SqliteExecutor(
            driver = projectDriver,
            audit = audit,
            events = EventStore(projectDriver),
            idGen = idGen,
            nowIso = nowIso,
            taskRunner = taskRunner,
            reconciler = GitRunReconciler(idGen = idGen, nowIso = nowIso),
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
        /**
         * M23: resolves `Settings.routingRemoteEgress` from [userDriver] (user scope only, per
         * the setting's SECURITY scope class), falling back to the declared default (`ASK`) when
         * no [userDriver] is supplied. `internal` so tests can verify the resolution without
         * driving a full Run.
         */
        internal fun resolveEgressPolicy(userDriver: SqlDriver?): EgressPolicy =
            userDriver?.let {
                SettingsStore(userDriver = it, projectDriver = null).resolve(Settings.routingRemoteEgress).value
            } ?: Settings.routingRemoteEgress.default

        /**
         * M23: only `ALLOW` permits automatic remote routing. `NEVER` blocks it outright; `ASK`
         * fails closed to the same result, since no per-Run approval flow exists yet to honor
         * what "ASK" actually promises (see this class's own doc comment). `internal` so tests
         * can verify the mapping directly.
         */
        internal fun allowRemoteFor(egressPolicy: EgressPolicy): Boolean = egressPolicy == EgressPolicy.ALLOW
    }
}
