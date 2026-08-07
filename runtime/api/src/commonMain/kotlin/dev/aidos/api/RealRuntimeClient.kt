package dev.aidos.api

import dev.aidos.kernel.FileDiff
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Real RuntimeClient implementation (RFC-0052, M9+).
 *
 * This is the production implementation of RuntimeClient that replaces MockRuntimeClient.
 * It maintains the same resumable event stream and structured diff semantics as the mock
 * but is structured to integrate with real services (storage, capability manager, git tool, etc.)
 * as they become available.
 *
 * For MVP, project and session state is stored in memory with the same semantics as the mock.
 * As Phase 4 progresses:
 * - ProjectService will provide persistent storage for projects
 * - SessionService will provide persistent storage for sessions
 * - GitService will provide real diff operations
 * - KnowledgeService will provide semantic search
 * - CapabilityManager will enforce permissions (already available)
 *
 * Event stream is resumable via [EventFilter.sinceSequence] (RFC-0004):
 * buffered events with sequence > sinceSequence are replayed before the live stream continues,
 * allowing clients to reconnect and receive the gap.
 */
class RealRuntimeClient : RuntimeClient {

    // ── Mutable state ──────────────────────────────────────────────────────────
    // For MVP, these are in-memory. Persistent storage arrives with Phase 4.

    private val _projects = mutableMapOf<String, ProjectSummary>()
    private val _sessions = mutableMapOf<String, SessionSummary>()
    private val _runs = mutableMapOf<String, RunSummary>()
    private val _capabilities = mutableMapOf<String, CapabilitySummary>()
    private val _pendingCapabilities = mutableListOf<PendingCapabilityRequest>()

    /** Buffered events with sequence numbers for sinceSequence replay (RFC-0004). */
    private val _eventBuffer = mutableListOf<SequencedEvent>()
    private val _eventFlow = MutableSharedFlow<SequencedEvent>(extraBufferCapacity = 256)
    private var _nextSequence = 1L

    private var _idCounter = 1

    // ── Id generation ──────────────────────────────────────────────────────────

    fun nextId(): String = "aidos-${_idCounter++}"

    // ── Publish events (for use by internal services) ────────────────────────

    fun emit(event: RuntimeEvent) {
        val seq = _nextSequence++
        val sequenced = SequencedEvent(event, seq)
        _eventBuffer.add(sequenced)
        _eventFlow.tryEmit(sequenced)
    }

    /** Injects a pending capability request for testing approval workflows. */
    fun injectPendingCapability(request: PendingCapabilityRequest) {
        _pendingCapabilities.add(request)
    }

    // ── RuntimeClient ──────────────────────────────────────────────────────────

    override val projects: ProjectCommands = object : ProjectCommands {
        override suspend fun create(request: CreateProjectRequest): ProjectResult {
            val id = nextId()
            val now = Clock.System.now()
            val summary = ProjectSummary(
                id = id, name = request.name, description = request.description,
                createdAt = now, lastActiveAt = now, sessionCount = 0,
            )
            _projects[id] = summary
            emit(RuntimeEvent.SessionCreated(
                eventId = nextId(), timestamp = now, projectId = id, sessionId = id,
                name = request.name, role = SessionRole.DRIVER,
            ))
            return ProjectResult.Success(summary)
        }

        override suspend fun open(projectId: String): ProjectResult {
            val p = _projects[projectId]
                ?: return ProjectResult.Error("project.not_found", "Project $projectId not found")
            return ProjectResult.Success(p)
        }

        override suspend fun close(projectId: String) { /* no-op for MVP */ }

        override suspend fun list(): List<ProjectSummary> = _projects.values.toList()

        override suspend fun get(projectId: String): ProjectDetail? {
            val p = _projects[projectId] ?: return null
            return ProjectDetail(p, "/projects/$projectId", "generic")
        }

        override suspend fun delete(projectId: String, confirm: Boolean) {
            if (confirm) _projects.remove(projectId)
        }
    }

    override val sessions: SessionCommands = object : SessionCommands {
        override suspend fun create(request: CreateSessionRequest): SessionResult {
            val id = nextId()
            val now = Clock.System.now()
            val summary = SessionSummary(
                id = id, projectId = request.projectId, name = request.name,
                role = request.role, state = SessionState.CREATED,
                createdAt = now, lastActiveAt = now, runCount = 0,
            )
            _sessions[id] = summary
            emit(RuntimeEvent.SessionCreated(
                eventId = nextId(), timestamp = now, projectId = request.projectId,
                sessionId = id, name = request.name, role = request.role,
            ))
            return SessionResult.Success(summary)
        }

        override suspend fun send(sessionId: String, message: UserMessage): RunResult {
            if (!_sessions.containsKey(sessionId)) {
                return RunResult.Error("session.not_found", "Session $sessionId not found")
            }
            val runId = nextId()
            val now = Clock.System.now()
            val run = RunSummary(id = runId, state = "RUNNING", startedAt = now, endedAt = null, stepCount = 0)
            _runs[runId] = run
            val session = _sessions[sessionId]!!
            emit(RuntimeEvent.RunStarted(
                eventId = nextId(), timestamp = now, projectId = session.projectId,
                sessionId = sessionId, runId = runId,
            ))
            return RunResult.Accepted(runId)
        }

        override suspend fun cancel(sessionId: String, runId: String) {
            _runs[runId]?.let { _runs[runId] = it.copy(state = "CANCELLED") }
        }

        override suspend fun list(projectId: String): List<SessionSummary> =
            _sessions.values.filter { it.projectId == projectId }

        override suspend fun get(sessionId: String): SessionDetail? {
            val s = _sessions[sessionId] ?: return null
            return SessionDetail(s, emptyList())
        }

        override suspend fun archive(sessionId: String) {
            _sessions[sessionId]?.let {
                _sessions[sessionId] = it.copy(state = SessionState.ARCHIVED)
            }
        }

        override suspend fun delete(sessionId: String) { _sessions.remove(sessionId) }
    }

    override val capabilities: CapabilityCommands = object : CapabilityCommands {
        override suspend fun grant(request: GrantCapabilityRequest): CapabilityResult {
            val id = nextId()
            _capabilities[id] = CapabilitySummary(id, request.permission, request.scope, request.expiresAt)
            return CapabilityResult.Success(id)
        }

        override suspend fun revoke(capabilityId: String) { _capabilities.remove(capabilityId) }

        override suspend fun list(sessionId: String): List<CapabilitySummary> =
            _capabilities.values.toList()

        override suspend fun listPending(): List<PendingCapabilityRequest> =
            _pendingCapabilities.toList()

        override suspend fun approve(requestId: String): CapabilityResult {
            val req = _pendingCapabilities.firstOrNull { it.requestId == requestId }
                ?: return CapabilityResult.Error("capability.not_found", "Request $requestId not found")
            _pendingCapabilities.removeAll { it.requestId == requestId }
            val id = nextId()
            _capabilities[id] = CapabilitySummary(id, req.permission, null, null)
            return CapabilityResult.Success(id)
        }

        override suspend fun deny(requestId: String, reason: String) {
            _pendingCapabilities.removeAll { it.requestId == requestId }
        }

        override suspend fun approveEffect(runId: String, taskId: String): CapabilityResult =
            CapabilityResult.Success(nextId())

        override suspend fun denyEffect(runId: String, taskId: String, reason: String) {}
    }

    override val knowledge: KnowledgeQueries = object : KnowledgeQueries {
        override suspend fun search(projectId: String, query: KnowledgeQuery): KnowledgeResult =
            KnowledgeResult(emptyList(), 0, null)

        override suspend fun indexStatus(projectId: String): IndexStatus =
            IndexStatus(projectId, null, 0, false)
    }

    override val diff: DiffQueries = object : DiffQueries {
        override suspend fun changes(projectId: String, range: DiffRange): DiffSummary =
            DiffSummary(range, emptyList(), 0, 0, 0)

        override suspend fun hunks(projectId: String, range: DiffRange, path: String): Result<FileDiff> =
            Result.failure(UnsupportedOperationException("RealRuntimeClient: hunks not implemented"))

        override suspend fun unified(projectId: String, range: DiffRange, path: String): Result<String> =
            Result.success("")

        override suspend fun stage(projectId: String, hunks: List<HunkId>): Result<Unit> =
            Result.success(Unit)

        override suspend fun revert(projectId: String, hunks: List<HunkId>): Result<Unit> =
            Result.success(Unit)

        override suspend fun commit(projectId: String, message: String): CommitResult {
            if (message.isBlank()) return CommitResult.Error("commit.empty_message", "Commit message must not be empty")
            return CommitResult.Success(commitHash = "aidos-${message.take(8).replace(" ", "-")}", shortMessage = message.lines().first().take(72))
        }
    }

    override val artifacts: ArtifactQueries = object : ArtifactQueries {
        override suspend fun list(projectId: String, filter: ArtifactFilter): List<ArtifactSummary> =
            emptyList()

        override suspend fun get(artifactId: String): ArtifactDetail? = null

        override suspend fun getAuditTrail(artifactId: String): List<AuditEntry> = emptyList()
    }

    override val events: EventSubscriptions = object : EventSubscriptions {
        /**
         * Subscribes to events with resumable stream support (RFC-0004, RFC-0052).
         *
         * If [EventFilter.sinceSequence] is non-null, buffered events with sequence >
         * sinceSequence are replayed before the live stream continues. This allows
         * clients to disconnect and reconnect while receiving the gap.
         */
        override fun subscribe(filter: EventFilter): Flow<RuntimeEvent> {
            return channelFlow {
                val sinceSeq = filter.sinceSequence

                // Snapshot buffered events up to the current last sequence
                val replayUntilSeq = _eventBuffer.lastOrNull()?.projectSequence ?: 0L
                
                // Replay buffered events that match the filter and are after sinceSequence
                for (sequenced in _eventBuffer) {
                    if (sinceSeq == null || sequenced.projectSequence > sinceSeq) {
                        if (matchesFilter(sequenced.event, filter)) {
                            send(sequenced.event)
                        }
                    }
                }

                // Subscribe to live events after the buffered ones
                _eventFlow.collect { sequenced ->
                    if (sequenced.projectSequence > replayUntilSeq &&
                        matchesFilter(sequenced.event, filter)
                    ) {
                        send(sequenced.event)
                    }
                }
            }
        }

        private fun matchesFilter(event: RuntimeEvent, filter: EventFilter): Boolean =
            (filter.projectIds.isEmpty() || (event.projectId != null && event.projectId in filter.projectIds)) &&
                (filter.sessionIds.isEmpty() || (event.sessionId != null && event.sessionId in filter.sessionIds))
    }

    override val runtime: RuntimeInfo = object : RuntimeInfo {
        override suspend fun version(): RuntimeVersion =
            RuntimeVersion(version = "0.1.0-alpha", apiVersion = 1, profile = "REAL")

        override suspend fun ping(): Boolean = true
    }
}
