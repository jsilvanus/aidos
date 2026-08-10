package dev.aidos.daemon

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.broker.AuditLog
import dev.aidos.broker.ToolBroker
import dev.aidos.capability.SqliteCapabilityManager
import dev.aidos.executor.AgentLoopTaskRunner
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
import dev.aidos.vault.AnthropicAdapter
import java.io.File

/**
 * The runtime composition root PIPELINE.md has been flagging as missing since the
 * AgentLoop↔executor bridge landed: a real `CapabilityManager` + `ToolBroker` with
 * `FilesystemTool`/`GitTool` registered, a real `InferenceRouter`, and the `PromptAssembler` that
 * together let [SqliteExecutor.drive] produce an actual model response instead of leaving a Run
 * `PENDING` forever.
 *
 * **Deliberately does not resolve capabilities for model-emitted tool calls.**
 * `AgentLoopTaskRunner` always sets `ToolCall.capabilityId = null` (its own doc comment names this
 * as a known, accepted gap — a `(subjectId, toolName) -> CapabilityId` resolver is a separate,
 * not-yet-designed subsystem). `ToolBroker.invoke`'s own step 2 therefore denies every tool call
 * with `capability.missing` regardless of what this class does, so no capability is granted here
 * either — granting one nothing will ever consult would just be dead code dressed up as progress.
 * The `CapabilityManager` is still constructed and passed to `ToolBroker`, because `ToolBroker`
 * needs one to exist at all (RFC-0030's `validate()` step), not because anything grants through it
 * yet. **What this means concretely: a driven Run can reach a real model response (`MODEL_CALL`),
 * but any `TOOL_CALL` it emits will be denied.** That is the honest, current state of the bridge,
 * not a bug in this composition.
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
 */
class RuntimeCompositionRoot(
    private val anthropicApiKey: () -> CharArray? = { null },
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

        val remoteAdapters = anthropicApiKey()?.let { key -> listOf(AnthropicAdapter(key)) } ?: emptyList()
        val router = PolicyInferenceRouter(
            policy = RoutingPolicy(
                allowRemote = remoteAdapters.isNotEmpty(),
                allowedRemoteModelIds = remoteAdapters.map { it.modelId }.toSet(),
            ),
            remoteAdapters = remoteAdapters,
        )

        val taskRunner = AgentLoopTaskRunner(
            driver = projectDriver,
            audit = audit,
            idGen = idGen,
            nowIso = nowIso,
            router = router,
            assembler = PromptAssembler(),
            broker = broker,
            subjectId = sessionId,
        )
        val executor = SqliteExecutor(
            driver = projectDriver,
            audit = audit,
            events = EventStore(projectDriver),
            idGen = idGen,
            nowIso = nowIso,
            taskRunner = taskRunner,
        )
        executor.drive(runId)
    }

    private fun projectRootPath(driver: SqlDriver, projectId: String): String? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT root_path FROM projects WHERE id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) },
            parameters = 1,
        ) { bindString(0, projectId) }.value
}
