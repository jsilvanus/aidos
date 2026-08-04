package dev.aidos.kernel

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/**
 * The contract between the headless runtime and its frontends (RFC-0052).
 *
 * The same interface backs three transports: a socket on DESKTOP (where the runtime is a daemon),
 * in-process on MOBILE, and an in-memory mock in tests. Frontends are written against a boundary
 * that is *real* on the platform where multiple frontends exist, so the seam cannot silently
 * erode into shared mutable state.
 *
 * **No method takes or returns a client-side filesystem path.** Paths in this API would mean
 * paths on the runtime's filesystem, and the two are only the same when the runtime is
 * in-process. This is reserved for a future remote client (D19) — the cost now is a sum type;
 * the cost later would be an API version bump and every frontend rewritten.
 */
interface RuntimeClient {
    val projects: ProjectCommands
    val sessions: SessionCommands
    val capabilities: CapabilityCommands
    val knowledge: KnowledgeQueries
    val diff: DiffQueries
    val events: EventSubscriptions
    val runtime: RuntimeInfo
}

interface ProjectCommands {
    suspend fun create(request: CreateProjectRequest): Result<ProjectSummary>
    suspend fun open(projectId: ProjectId): Result<ProjectSummary>
    suspend fun close(projectId: ProjectId)
    suspend fun list(): List<ProjectSummary>
    suspend fun delete(projectId: ProjectId, confirm: Boolean): Result<Unit>
}

data class CreateProjectRequest(
    val name: String,
    val description: String,
    val location: ProjectLocation,
    val initGit: Boolean = true,
    val templateId: String? = null,
)

/** Where a project lives is resolved by the runtime, not dictated by the client. */
sealed interface ProjectLocation {
    /** The runtime picks the path. The only form valid over a remote transport. */
    data class RuntimeManaged(val slug: String) : ProjectLocation

    /** In-process transport only. */
    data class LocalPath(val path: String) : ProjectLocation

    data class CloneOf(val remoteUrl: String, val slug: String) : ProjectLocation
}

data class ProjectSummary(
    val id: ProjectId,
    val name: String,
    val description: String,
    val createdAt: Instant,
    val lastActiveAt: Instant,
    val sessionCount: Int,
)

interface SessionCommands {
    suspend fun create(request: CreateSessionRequest): Result<SessionSummary>
    suspend fun send(sessionId: SessionId, message: UserMessage): Result<RunId>
    suspend fun cancel(sessionId: SessionId, runId: RunId)
    suspend fun list(projectId: ProjectId): List<SessionSummary>
    suspend fun archive(sessionId: SessionId)
}

data class CreateSessionRequest(
    val projectId: ProjectId,
    val name: String,
    val role: SessionRole = SessionRole.DRIVER,
)

data class UserMessage(
    val content: String,
    /** Content references resolved by the runtime, never client paths. */
    val attachments: List<ContentNodeId> = emptyList(),
    val options: RunOptions = RunOptions(),
)

data class RunOptions(
    val maxSteps: Int? = null,
    val requireApprovalBeforeMutation: Boolean = false,
    val timeoutSeconds: Int = 300,
)

data class SessionSummary(
    val id: SessionId,
    val projectId: ProjectId,
    val name: String,
    val role: SessionRole,
    val state: SessionState,
    val createdAt: Instant,
    val lastActiveAt: Instant,
    val runCount: Int,
)

/** The canonical session state machine (RFC-0017). There is exactly one. */
enum class SessionState { CREATED, SLEEPING, RUNNING, ARCHIVED }

/**
 * All of these require a `user_interactive` connection (RFC-0055).
 *
 * Without that, a stdio MCP server spawned by the runtime could connect back and approve its own
 * pending request, defeating the human-in-the-loop the whole security model rests on.
 */
interface CapabilityCommands {
    suspend fun grant(request: GrantCapabilityRequest): Result<CapabilityId>
    suspend fun revoke(capabilityId: CapabilityId)
    suspend fun list(sessionId: SessionId): List<Capability>
    suspend fun listPending(): List<PendingApproval>
    suspend fun approve(requestId: String): Result<CapabilityId>
    suspend fun deny(requestId: String, reason: String)

    /** Per-call approval for a previewed mutation or a tainted escalation. */
    suspend fun approveEffect(runId: RunId, taskId: TaskId): Result<Unit>
    suspend fun denyEffect(runId: RunId, taskId: TaskId, reason: String)
}

data class GrantCapabilityRequest(
    val sessionId: SessionId,
    val permission: Permission,
    val scope: CapabilityScope,
    val constraints: CapabilityConstraints = CapabilityConstraints(),
    val expiresAt: Instant? = null,
)

data class PendingApproval(
    val requestId: String,
    val sessionId: SessionId,
    val runId: RunId,
    val permission: Permission,
    val reason: String,
    /** Names the specific untrusted content, when taint is the cause (RFC-0027). */
    val taintSource: String?,
    val preview: Preview?,
)

interface KnowledgeQueries {
    suspend fun search(projectId: ProjectId, query: KnowledgeQuery): List<ContextItem>
    suspend fun indexStatus(projectId: ProjectId): IndexStatus
}

/**
 * Diff review (RFC-0052, D25). Structured hunks, never a formatted diff string.
 *
 * [changes] lists files; [hunks] fetches one file's content when the card stack reaches it.
 * [unified] is the raw fallback view, one tap away.
 */
interface DiffQueries {
    suspend fun changes(projectId: ProjectId, range: DiffRange = DiffRange.WorkingTree): DiffSummary

    suspend fun hunks(projectId: ProjectId, range: DiffRange, path: String): Result<FileDiff>

    suspend fun unified(projectId: ProjectId, range: DiffRange, path: String): Result<String>

    /** Stage a subset of hunks. Fails `diff.base_moved` if any named base has moved. */
    suspend fun stage(projectId: ProjectId, hunks: List<HunkId>): Result<Unit>

    /**
     * Revert a subset of hunks in the working tree.
     *
     * An ordinary `Mutate` through the broker with an audit row, but no approval: its subject is
     * the **user**, who is the authority an approval would be consulting. Identical to an editor
     * save (RFC-0050).
     */
    suspend fun revert(projectId: ProjectId, hunks: List<HunkId>): Result<Unit>
}

interface EventSubscriptions {
    fun subscribe(filter: EventFilter): Flow<RuntimeEvent>
}

data class EventFilter(
    val projectIds: List<ProjectId> = emptyList(),
    val sessionIds: List<SessionId> = emptyList(),
    val types: List<String> = emptyList(),

    /**
     * Resume point. A client that disconnects reconnects with the last sequence it observed and
     * receives the gap, rather than a fresh stream with a silent hole in it.
     */
    val sinceSequence: Long? = null,
)

sealed interface RuntimeEvent {
    val eventId: EventId
    val sequence: Long
    val timestamp: Instant
    val projectId: ProjectId?

    data class RunStarted(
        override val eventId: EventId,
        override val sequence: Long,
        override val timestamp: Instant,
        override val projectId: ProjectId,
        val sessionId: SessionId,
        val runId: RunId,
    ) : RuntimeEvent

    data class RunStateChanged(
        override val eventId: EventId,
        override val sequence: Long,
        override val timestamp: Instant,
        override val projectId: ProjectId,
        val runId: RunId,
        val from: RunState,
        val to: RunState,
        val error: AidosError?,
    ) : RuntimeEvent

    data class AiResponseDelta(
        override val eventId: EventId,
        override val sequence: Long,
        override val timestamp: Instant,
        override val projectId: ProjectId,
        val runId: RunId,
        val delta: String,
        val isFinal: Boolean,
    ) : RuntimeEvent

    data class ToolCallStarted(
        override val eventId: EventId,
        override val sequence: Long,
        override val timestamp: Instant,
        override val projectId: ProjectId,
        val runId: RunId,
        val toolName: String,
        val preview: Preview?,
    ) : RuntimeEvent

    data class ApprovalRequested(
        override val eventId: EventId,
        override val sequence: Long,
        override val timestamp: Instant,
        override val projectId: ProjectId,
        val approval: PendingApproval,
    ) : RuntimeEvent

    data class BudgetUpdated(
        override val eventId: EventId,
        override val sequence: Long,
        override val timestamp: Instant,
        override val projectId: ProjectId,
        val runId: RunId,
        val consumed: Budget,
        val remaining: Budget?,
    ) : RuntimeEvent

    data class RuntimeFailure(
        override val eventId: EventId,
        override val sequence: Long,
        override val timestamp: Instant,
        override val projectId: ProjectId?,
        val error: AidosError,
    ) : RuntimeEvent
}

interface RuntimeInfo {
    suspend fun version(): RuntimeVersion

    /** Frontends render availability rather than discovering it by failure (RFC-0049). */
    suspend fun profile(): PlatformProfile
    suspend fun availability(projectId: ProjectId): AvailabilityReport
}

data class RuntimeVersion(
    val apiVersion: Int,
    val minApiVersion: Int,
    val buildVersion: String,
)
