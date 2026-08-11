package dev.aidos.cli

import dev.aidos.api.CapabilityResult
import dev.aidos.api.CreateProjectRequest
import dev.aidos.api.CreateSessionRequest
import dev.aidos.api.EventFilter
import dev.aidos.api.GrantCapabilityRequest
import dev.aidos.api.ProjectLocation
import dev.aidos.api.ProjectResult
import dev.aidos.api.RuntimeClient
import dev.aidos.api.RunResult
import dev.aidos.api.SessionResult
import dev.aidos.api.UserMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * CLI frontend for the Aidos runtime (M10, RFC-0052, RFC-0004).
 *
 * Every command goes through [RuntimeClient], which may be a [dev.aidos.api.MockRuntimeClient]
 * in tests or a real in-process / socket-connected client in production. No command takes a
 * client-side filesystem path directly — project locations are passed as [ProjectLocation]
 * values resolved by the runtime.
 *
 * The event stream is resumable: [watchEvents] accepts a [sinceSequence] that replays buffered
 * events before continuing with live ones (RFC-0004).
 */
class AidosCli(private val client: RuntimeClient) {

    // ── Project commands ───────────────────────────────────────────────────────

    suspend fun createProject(name: String, description: String = ""): String {
        val result = client.projects.create(
            CreateProjectRequest(name, description, ProjectLocation.RuntimeManaged(name))
        )
        return when (result) {
            is ProjectResult.Success -> result.project.id
            is ProjectResult.Error   -> error("create-project failed: ${result.code} — ${result.message}")
        }
    }

    suspend fun listProjects(): List<String> =
        client.projects.list().map { "${it.id}\t${it.name}" }

    // ── Session commands ───────────────────────────────────────────────────────

    suspend fun createSession(projectId: String, name: String): String {
        val result = client.sessions.create(CreateSessionRequest(projectId, name))
        return when (result) {
            is SessionResult.Success -> result.session.id
            is SessionResult.Error   -> error("create-session failed: ${result.code} — ${result.message}")
        }
    }

    suspend fun listSessions(projectId: String): List<String> =
        client.sessions.list(projectId).map { "${it.id}\t${it.name}\t${it.state}" }

    suspend fun sendMessage(sessionId: String, content: String): String {
        val result = client.sessions.send(sessionId, UserMessage(content))
        return when (result) {
            is RunResult.Accepted -> result.runId
            is RunResult.Error    -> error("send failed: ${result.code} — ${result.message}")
        }
    }

    // ── Event stream ───────────────────────────────────────────────────────────

    /**
     * Watches the event stream, printing each event to [output].
     *
     * [sinceSequence] resumes from that point — buffered events whose sequence is greater than
     * [sinceSequence] are delivered before the live stream continues, so a reconnect fills the
     * gap rather than showing a hole (RFC-0004).
     *
     * Returns a [Job] the caller can cancel to stop watching.
     */
    fun watchEvents(
        scope: CoroutineScope,
        sinceSequence: Long? = null,
        output: (String) -> Unit = ::println,
    ): Job {
        val filter = EventFilter(sinceSequence = sinceSequence)
        return client.events.subscribe(filter)
            .onEach { event -> output("event: $event") }
            .launchIn(scope)
    }

    // ── Capability commands ────────────────────────────────────────────────────

    suspend fun grantCapability(sessionId: String, permission: String, scope: String?): String {
        val result = client.capabilities.grant(GrantCapabilityRequest(sessionId, permission, scope))
        return when (result) {
            is CapabilityResult.Success -> result.capabilityId
            is CapabilityResult.Error   -> error("grant failed: ${result.code} — ${result.message}")
        }
    }

    /**
     * Approves a pending capability request (user-interactive approval flow).
     */
    suspend fun approveCapability(requestId: String): String {
        val result = client.capabilities.approve(requestId)
        return when (result) {
            is CapabilityResult.Success -> result.capabilityId
            is CapabilityResult.Error   -> error("approve failed: ${result.code} — ${result.message}")
        }
    }

    suspend fun listPendingCapabilities(): List<String> =
        client.capabilities.listPending().map { "${it.requestId}\t${it.permission}" }

    /**
     * Approves a Run parked on `CAPABILITY_APPROVAL` (RFC-0008 step 8d) — a Run that hit
     * `RoutingDecision.RemotePendingApproval` under the `ASK` egress policy. `taskId` is left
     * empty: `continuations.run_id` is the table's own primary key, so resolution is keyed by
     * Run alone — [dev.aidos.api.CapabilityCommands.approveEffect]'s `taskId` parameter is
     * unused by the real implementation today (see `RealRuntimeClient`'s own comment).
     */
    suspend fun approveRun(runId: String): String {
        val result = client.capabilities.approveEffect(runId, taskId = "")
        return when (result) {
            is CapabilityResult.Success -> "approved: run $runId resumed"
            is CapabilityResult.Error   -> error("approve-run failed: ${result.code} — ${result.message}")
        }
    }

    /**
     * Denies a Run parked on `CAPABILITY_APPROVAL` or `TOOL_CALL` — fails it outright, nothing to
     * resume. Also reaches a parked `ask_user` question's decline path (see
     * `RuntimeCompositionRoot.resolveAnyApproval`'s own doc comment) — "no, I won't answer that"
     * is a denial too, even though answering one is a different command ([answerRun]).
     */
    suspend fun denyRun(runId: String, reason: String) {
        client.capabilities.denyEffect(runId, taskId = "", reason = reason)
    }

    /**
     * Answers a Run parked on `USER_PROMPT` (RFC-0008 step 8d) — the model called `ask_user` and
     * is waiting for a reply. Not `approveRun`: a question has no yes/no to approve, only an
     * answer to give.
     */
    suspend fun answerRun(runId: String, answer: String): String {
        val result = client.capabilities.answerPrompt(runId, answer)
        return when (result) {
            is CapabilityResult.Success -> "answered: run $runId resumed"
            is CapabilityResult.Error   -> error("answer-run failed: ${result.code} — ${result.message}")
        }
    }

    // ── Diff queries ───────────────────────────────────────────────────────────

    suspend fun diffChanges(projectId: String): String {
        val summary = client.diff.changes(projectId)
        return "${summary.filesChanged} file(s) changed, +${summary.linesAdded} -${summary.linesRemoved}"
    }

    // ── Artifacts ──────────────────────────────────────────────────────────────

    suspend fun listArtifacts(projectId: String): List<String> =
        client.artifacts.list(projectId).map { "${it.id}\t${it.contentType}\t${it.label}" }

    /**
     * Returns the audit trail for an artifact: a sequence of cause-and-effect entries that
     * reconstructs how the artifact was produced (M19, G2).
     */
    suspend fun auditTrail(artifactId: String): List<String> =
        client.artifacts.getAuditTrail(artifactId).map { entry ->
            "${entry.occurredAt}\t${entry.kind}\t${entry.actorKind}:${entry.actorId}"
        }

    // ── Runtime info ───────────────────────────────────────────────────────────

    suspend fun ping(): Boolean = client.runtime.ping()

    suspend fun version(): String {
        val v = client.runtime.version()
        return "${v.version} (api ${v.apiVersion}, profile ${v.profile})"
    }
}
