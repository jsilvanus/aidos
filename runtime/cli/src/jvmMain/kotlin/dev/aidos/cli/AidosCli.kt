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

    // ── Diff queries ───────────────────────────────────────────────────────────

    suspend fun diffChanges(projectId: String): String {
        val summary = client.diff.changes(projectId)
        return "${summary.filesChanged} file(s) changed, +${summary.linesAdded} -${summary.linesRemoved}"
    }

    // ── Runtime info ───────────────────────────────────────────────────────────

    suspend fun ping(): Boolean = client.runtime.ping()

    suspend fun version(): String {
        val v = client.runtime.version()
        return "${v.version} (api ${v.apiVersion}, profile ${v.profile})"
    }
}
