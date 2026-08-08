package dev.aidos.api

import dev.aidos.kernel.FileDiff
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/**
 * The complete public surface of the Aidos runtime as seen by frontends (RFC-0052).
 *
 * Transport is abstracted: in-process on Android (mobile), socket on desktop. Both sides
 * see the same interface. No method takes or returns a **client-side filesystem path** —
 * the reservation that lets a remote transport land without an API version bump.
 */
interface RuntimeClient {
    val projects: ProjectCommands
    val sessions: SessionCommands
    val capabilities: CapabilityCommands
    val knowledge: KnowledgeQueries
    val diff: DiffQueries
    val artifacts: ArtifactQueries
    val events: EventSubscriptions
    val runtime: RuntimeInfo
}

// ─── Project Commands ─────────────────────────────────────────────────────────

interface ProjectCommands {
    suspend fun create(request: CreateProjectRequest): ProjectResult
    suspend fun open(projectId: String): ProjectResult
    suspend fun close(projectId: String)
    suspend fun list(): List<ProjectSummary>
    suspend fun get(projectId: String): ProjectDetail?
    suspend fun delete(projectId: String, confirm: Boolean)
}

data class CreateProjectRequest(
    val name: String,
    val description: String,
    val location: ProjectLocation,
    val initGit: Boolean = true,
    val templateId: String? = null,
)

/** Where a project lives — resolved by the runtime, not dictated by the client (RFC-0052). */
sealed interface ProjectLocation {
    /** Runtime picks the directory from its storage root. */
    data class RuntimeManaged(val slug: String) : ProjectLocation
    /** Absolute path on the runtime's filesystem — in-process transport only. */
    data class LocalPath(val path: String) : ProjectLocation
    /** Clone from remote. */
    data class CloneOf(val remoteUrl: String, val slug: String) : ProjectLocation
}

data class ProjectSummary(
    val id: String,
    val name: String,
    val description: String,
    val projectPath: String,
    val createdAt: Instant,
    val lastActiveAt: Instant,
    val sessionCount: Int,
)

data class ProjectDetail(
    val summary: ProjectSummary,
    val rootPath: String,
    val projectType: String,
)

sealed interface ProjectResult {
    data class Success(val project: ProjectSummary) : ProjectResult
    data class Error(val code: String, val message: String) : ProjectResult
}

// ─── Session Commands ─────────────────────────────────────────────────────────

interface SessionCommands {
    suspend fun create(request: CreateSessionRequest): SessionResult
    suspend fun send(sessionId: String, message: UserMessage): RunResult
    suspend fun cancel(sessionId: String, runId: String)
    suspend fun list(projectId: String): List<SessionSummary>
    suspend fun get(sessionId: String): SessionDetail?
    suspend fun archive(sessionId: String)
    suspend fun delete(sessionId: String)
}

data class CreateSessionRequest(
    val projectId: String,
    val name: String,
    val role: SessionRole = SessionRole.DRIVER,
    val instructionSetId: String? = null,
)

enum class SessionRole { DRIVER, WORKER }

data class UserMessage(
    val content: String,
    val attachments: List<String> = emptyList(),
    val runOptions: RunOptions = RunOptions(),
)

data class RunOptions(
    val maxTokens: Int? = null,
    val requireApprovalBeforeToolUse: Boolean = false,
    val timeoutSeconds: Int = 300,
)

data class SessionSummary(
    val id: String,
    val projectId: String,
    val name: String,
    val role: SessionRole,
    val state: SessionState,
    val createdAt: Instant,
    val lastActiveAt: Instant,
    val runCount: Int,
)

enum class SessionState { CREATED, SLEEPING, RUNNING, ARCHIVED }

data class SessionDetail(
    val summary: SessionSummary,
    val recentRuns: List<RunSummary>,
)

data class RunSummary(
    val id: String,
    val state: String,
    val startedAt: Instant,
    val endedAt: Instant?,
    val stepCount: Int,
)

sealed interface SessionResult {
    data class Success(val session: SessionSummary) : SessionResult
    data class Error(val code: String, val message: String) : SessionResult
}

sealed interface RunResult {
    data class Accepted(val runId: String) : RunResult
    data class Error(val code: String, val message: String) : RunResult
}

// ─── Capability Commands ──────────────────────────────────────────────────────

interface CapabilityCommands {
    suspend fun grant(request: GrantCapabilityRequest): CapabilityResult
    suspend fun revoke(capabilityId: String)
    suspend fun list(sessionId: String): List<CapabilitySummary>
    suspend fun listPending(): List<PendingCapabilityRequest>
    suspend fun approve(requestId: String): CapabilityResult
    suspend fun deny(requestId: String, reason: String)
    suspend fun approveEffect(runId: String, taskId: String): CapabilityResult
    suspend fun denyEffect(runId: String, taskId: String, reason: String)
}

data class GrantCapabilityRequest(
    val sessionId: String,
    val permission: String,
    val scope: String? = null,
    val constraints: Map<String, String> = emptyMap(),
    val expiresAt: Instant? = null,
)

data class CapabilitySummary(
    val id: String,
    val permission: String,
    val scope: String?,
    val expiresAt: Instant?,
)

data class PendingCapabilityRequest(
    val requestId: String,
    val sessionId: String,
    val permission: String,
    val reason: String,
)

sealed interface CapabilityResult {
    data class Success(val capabilityId: String) : CapabilityResult
    data class Error(val code: String, val message: String) : CapabilityResult
}

// ─── Knowledge Queries ────────────────────────────────────────────────────────

interface KnowledgeQueries {
    suspend fun search(projectId: String, query: KnowledgeQuery): KnowledgeResult
    suspend fun indexStatus(projectId: String): IndexStatus
}

data class KnowledgeQuery(
    val text: String,
    val limit: Int = 20,
    val semanticSearch: Boolean = false,
)

data class KnowledgeResult(
    val items: List<KnowledgeItem>,
    val totalMatches: Int,
    val indexedAt: Instant?,
)

data class KnowledgeItem(
    val id: String,
    val kind: String,
    val title: String,
    val snippet: String,
    val score: Float,
)

data class IndexStatus(
    val projectId: String,
    val indexedAt: Instant?,
    val nodeCount: Int,
    val isIndexing: Boolean,
)

// ─── Diff and Review Queries ──────────────────────────────────────────────────

/**
 * Structured diff API (D25, RFC-0052).
 *
 * Returns structured hunks, never a formatted diff string. Structure crosses the wire once;
 * re-formatting is done by each client. `unified()` is the fallback view, one tap away.
 */
interface DiffQueries {
    /** File-level change set (hunk counts, not content). */
    suspend fun changes(projectId: String, range: DiffRange = DiffRange.WorkingTree): DiffSummary

    /** Hunks for one file — fetched when the user reaches that file in the card stack. */
    suspend fun hunks(projectId: String, range: DiffRange, path: String): Result<FileDiff>

    /** Raw unified diff — fallback view. */
    suspend fun unified(projectId: String, range: DiffRange, path: String): Result<String>

    /** Stage a subset of hunks. Fails with `diff.base_moved` if any named base has moved. */
    suspend fun stage(projectId: String, hunks: List<HunkId>): Result<Unit>

    /** Revert a subset of hunks in the working tree (user-subject mutation — no approval). */
    suspend fun revert(projectId: String, hunks: List<HunkId>): Result<Unit>

    /**
     * Commit everything in the index with [message] (M31, RFC-0032).
     *
     * The staged index is the commit boundary: [stage] controls what goes in.
     * Committing with an empty index returns [CommitResult.NothingStaged] — never
     * creates an empty commit, which would mislead later history inspection.
     */
    suspend fun commit(projectId: String, message: String): CommitResult
}

sealed interface CommitResult {
    data class Success(val commitHash: String, val shortMessage: String) : CommitResult
    data object NothingStaged : CommitResult
    data class Error(val code: String, val message: String) : CommitResult
}

sealed interface DiffRange {
    data object WorkingTree : DiffRange
    data object Staged : DiffRange
    data class Refs(val base: String, val head: String) : DiffRange
}

/**
 * Hunk identity (RFC-0052).
 *
 * Keyed on `(path, baseBlobHash, index)` so staleness is detectable. If the base moves, staging
 * fails with `CONFLICT` rather than applying a decision made about different content.
 */
data class HunkId(
    val path: String,
    val baseBlobHash: String,
    val index: Int,
)

data class DiffSummary(
    val range: DiffRange,
    val files: List<FileChange>,
    val filesChanged: Int,
    val linesAdded: Int,
    val linesRemoved: Int,
)

data class FileChange(
    val path: String,
    val previousPath: String?,
    val kind: FileChangeKind,
    val baseBlobHash: String?,
    val headBlobHash: String?,
    val binary: Boolean,
    val modeChanged: Boolean,
    val hunkCount: Int,
    val linesAdded: Int,
    val linesRemoved: Int,
    val review: ReviewState,
    val origin: ChangeOrigin,
)

enum class FileChangeKind { ADDED, MODIFIED, DELETED, RENAMED, COPIED, TYPE_CHANGED }
enum class ReviewState { APPROVED_IN_RUN, NOT_REVIEWED }
enum class ChangeOrigin { SESSION, USER_EDIT, FETCH, UNKNOWN }

// ─── Artifact Queries ─────────────────────────────────────────────────────────

interface ArtifactQueries {
    suspend fun list(projectId: String, filter: ArtifactFilter = ArtifactFilter()): List<ArtifactSummary>
    suspend fun get(artifactId: String): ArtifactDetail?
    suspend fun getAuditTrail(artifactId: String): List<AuditEntry>
}

data class ArtifactFilter(
    val sessionId: String? = null,
    val contentType: String? = null,
    val since: Instant? = null,
    val limit: Int = 50,
)

data class ArtifactSummary(
    val id: String,
    val contentType: String,
    val label: String?,
    val createdAt: Instant,
)

data class ArtifactDetail(
    val summary: ArtifactSummary,
    val payloadSize: Long,
)

data class AuditEntry(
    val id: String,
    val kind: String,
    val occurredAt: Instant,
    val actorKind: String,
    val actorId: String,
    val subjectRef: String?,
)

// ─── Event Subscriptions ──────────────────────────────────────────────────────

/**
 * Event subscription interface (RFC-0052, RFC-0004).
 *
 * `sinceSequence` makes the stream **resumable**: a client that disconnects reconnects with the
 * last sequence it saw and receives the gap rather than a fresh stream with a hole in it.
 */
interface EventSubscriptions {
    fun subscribe(filter: EventFilter): Flow<RuntimeEvent>
}

data class EventFilter(
    val projectIds: List<String> = emptyList(),
    val sessionIds: List<String> = emptyList(),
    val types: List<RuntimeEventType> = emptyList(),
    /** Resume point: null means start from now. */
    val sinceSequence: Long? = null,
)

enum class RuntimeEventType {
    SESSION_CREATED,
    SESSION_STATE_CHANGED,
    RUN_STARTED,
    RUN_COMPLETED,
    RUN_FAILED,
    RUN_STEP_COMPLETED,
    AI_RESPONSE_DELTA,
    TOOL_APPROVAL_REQUIRED,
    TOOL_APPROVED,
    TOOL_DENIED,
}

/** Per-event sequence number (RFC-0004) — monotonic within the project. */
data class SequencedEvent(
    val event: RuntimeEvent,
    val projectSequence: Long,
)

sealed class RuntimeEvent {
    abstract val eventId: String
    abstract val timestamp: Instant
    abstract val projectId: String?
    abstract val sessionId: String?

    data class SessionCreated(
        override val eventId: String,
        override val timestamp: Instant,
        override val projectId: String,
        override val sessionId: String,
        val name: String,
        val role: SessionRole,
    ) : RuntimeEvent()

    data class SessionStateChanged(
        override val eventId: String,
        override val timestamp: Instant,
        override val projectId: String,
        override val sessionId: String,
        val from: SessionState,
        val to: SessionState,
    ) : RuntimeEvent()

    data class RunStarted(
        override val eventId: String,
        override val timestamp: Instant,
        override val projectId: String,
        override val sessionId: String,
        val runId: String,
    ) : RuntimeEvent()

    data class RunCompleted(
        override val eventId: String,
        override val timestamp: Instant,
        override val projectId: String,
        override val sessionId: String,
        val runId: String,
        val artifactIds: List<String>,
    ) : RuntimeEvent()

    data class RunFailed(
        override val eventId: String,
        override val timestamp: Instant,
        override val projectId: String,
        override val sessionId: String,
        val runId: String,
        val errorCode: String,
        val errorMessage: String,
    ) : RuntimeEvent()

    data class RunStepCompleted(
        override val eventId: String,
        override val timestamp: Instant,
        override val projectId: String,
        override val sessionId: String,
        val runId: String,
        val taskId: String,
        val stepIndex: Int,
        val taskState: String,
    ) : RuntimeEvent()

    data class AiResponseDelta(
        override val eventId: String,
        override val timestamp: Instant,
        override val projectId: String,
        override val sessionId: String,
        val runId: String,
        val delta: String,
        val isFinal: Boolean,
    ) : RuntimeEvent()

    data class ToolApprovalRequired(
        override val eventId: String,
        override val timestamp: Instant,
        override val projectId: String,
        override val sessionId: String,
        val runId: String,
        val taskId: String,
        val toolName: String,
        val previewDescription: String,
    ) : RuntimeEvent()
}

// ─── Runtime Info ─────────────────────────────────────────────────────────────

interface RuntimeInfo {
    suspend fun version(): RuntimeVersion
    suspend fun ping(): Boolean
}

data class RuntimeVersion(
    val version: String,
    val apiVersion: Int,
    val profile: String,
)

// ─── Knowledge Service ────────────────────────────────────────────────────────

/**
 * Backend service for knowledge indexing and search (Phase 1-4 integration).
 * 
 * Wired by external infrastructure and injected into RealRuntimeClient.
 * Handles filesystem paths directly; the RuntimeClient maps projectId→projectPath.
 */
interface KnowledgeService {
    /**
     * Search for [query] in the project at [projectPath].
     * Returns results suitable for prompt context injection.
     */
    suspend fun search(projectPath: String, query: KnowledgeQuery): KnowledgeResult

    /**
     * Get indexing status for the project at [projectPath].
     */
    suspend fun indexStatus(projectPath: String): IndexStatus

    /**
     * Start/resume indexing for the project at [projectPath].
     * Reports progress via [onProgress] callback.
     */
    suspend fun startIndexing(projectPath: String, onProgress: (IndexingProgress) -> Unit = {})
}

/**
 * Indexing progress report (Phase 4 integration).
 */
data class IndexingProgress(
    val commitsProcessed: Int,
    val blobsSeen: Int,
    val blobsIndexed: Int,
    val blobsFailed: Int,
)
