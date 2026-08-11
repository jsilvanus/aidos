package dev.aidos.api

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.identity.ProjectRegistry
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.FileDiff
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ProjectId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
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
    private val _projectPaths = mutableMapOf<String, String>()  // projectId → filesystem path
    private val _sessions = mutableMapOf<String, SessionSummary>()
    private val _runs = mutableMapOf<String, RunSummary>()
    private val _capabilities = mutableMapOf<String, CapabilitySummary>()
    private val _pendingCapabilities = mutableListOf<PendingCapabilityRequest>()

    /** runId → projectId, populated in `sessions.send()`. Lets `approveEffect`/`denyEffect` open
     *  the right project driver from a bare runId, without widening either method's signature. */
    private val _runProjectIds = mutableMapOf<String, String>()

    /** Buffered events with sequence numbers for sinceSequence replay (RFC-0004). */
    private val _eventBuffer = mutableListOf<SequencedEvent>()
    private val _eventFlow = MutableSharedFlow<SequencedEvent>(extraBufferCapacity = 256)
    private var _nextSequence = 1L

    private var _idCounter = 1

    /**
     * Optional knowledge service backend (Phase 2 integration).
     * Wired by external infrastructure (e.g., AppComponent in androidapp).
     */
    var knowledgeService: KnowledgeService? = null

    // ── Persistent storage (RFC-0010, RFC-0040) ───────────────────────────────
    // All three are unset by default, preserving the pre-Phase-4 in-memory-only behavior.
    // Wired by external infrastructure once available (JVM: daemon's RuntimeClientFactory;
    // Android's own driver/locker wiring is follow-up work, same status as capability's
    // SqliteDirHandle -- not guessed at here).

    /** user.db driver, for the project registry (project_id -> path cache). */
    var userDriver: SqlDriver? = null

    /** Opens (creating and migrating if needed) a project's own `.aidos/state.db`. */
    var projectDbFactory: ((projectRootPath: String) -> SqlDriver)? = null

    /** RFC-0055 per-project advisory locking. */
    var projectLocker: ProjectLocker? = null

    /**
     * Base directory for [ProjectLocation.RuntimeManaged]/[ProjectLocation.CloneOf] projects --
     * "the runtime picks the directory from its storage root" (that sealed interface's own doc
     * comment). Unset keeps the pre-persistence placeholder (`/projects/<slug>`, never real I/O
     * against it); once [projectDbFactory] is wired for real, this must be too, or project
     * creation fails opening a `state.db` under a path nothing owns.
     */
    var runtimeManagedProjectsRoot: String? = null

    /**
     * Creates the durable Run for a user message (RFC-0008/0009), once storage is wired.
     * Unset preserves the pre-persistence in-memory `RunSummary`/`RunResult.Accepted` stub in
     * `sessions.send()` below. See [RunExecutor]'s own doc comment for why this is a seam rather
     * than `RealRuntimeClient` depending on `executor` directly (a module cycle).
     */
    var runExecutor: RunExecutor? = null

    /**
     * Resolves a Run parked on `CAPABILITY_APPROVAL` (RFC-0008 step 8d), once storage is wired.
     * Unset preserves the pre-wiring no-op stub `approveEffect`/`denyEffect` had before this seam
     * existed. See [EffectApprovalGateway]'s own doc comment for why this is a seam rather than
     * `RealRuntimeClient` depending on `executor` directly (a module cycle, same as [runExecutor]).
     */
    var effectApprovalGateway: EffectApprovalGateway? = null

    /** Recorded on every Run for provenance (RFC-0049); no device-profile detection exists here. */
    var platformProfile: PlatformProfile = PlatformProfile.DESKTOP

    /** No network-reachability detection exists here; callers wire this once one does. */
    var networkAvailable: Boolean = false

    private val instanceId: String by lazy { UuidV7Generator().next() }

    // Project and session ids are persisted (once userDriver/projectDbFactory are wired) and must
    // survive process restarts without colliding -- nextId()'s counter resets to 1 every
    // construction, which is safe for the still-in-memory-only entity types below but not for
    // these two.
    private val projectIdGen = UuidV7Generator()
    private val sessionIdGen = UuidV7Generator()

    private val _openProjectDrivers = mutableMapOf<String, SqlDriver>()

    private fun projectRegistry(): ProjectRegistry? =
        userDriver?.let { ProjectRegistry(it, projectIdGen) }

    /** Opens (or reuses an already-open) driver for a project's own state.db. */
    private fun ensureProjectDriverOpen(id: String, path: String): SqlDriver? {
        val dbFactory = projectDbFactory ?: return null
        return _openProjectDrivers.getOrPut(id) { dbFactory(path) }
    }

    /** Reads a project's own `projects` row and populates the in-memory cache from it. */
    private fun hydrateProjectSummary(id: String, path: String): ProjectSummary? {
        val driver = ensureProjectDriverOpen(id, path) ?: return null
        val row = driver.executeQuery(
            identifier = null,
            sql = "SELECT name, description, created_at FROM projects WHERE id = ?",
            mapper = { cursor ->
                QueryResult.Value(
                    if (cursor.next().value) {
                        Triple(cursor.getString(0)!!, cursor.getString(1), cursor.getString(2)!!)
                    } else {
                        null
                    }
                )
            },
            parameters = 1,
        ) { bindString(0, id) }.value ?: return null

        val (name, description, createdAtIso) = row
        val createdAt = Instant.parse(createdAtIso)
        val summary = ProjectSummary(
            id = id, name = name, description = description ?: "",
            projectPath = path, createdAt = createdAt, lastActiveAt = createdAt,
            sessionCount = _sessions.values.count { it.projectId == id },
        )
        _projects[id] = summary
        _projectPaths[id] = path
        return summary
    }

    private fun lockedByOtherError(outcome: ProjectLockOutcome.HeldByOther): ProjectResult.Error =
        ProjectResult.Error(
            "runtime.locked_by_other_instance",
            "Project path is locked by another Aidos instance (${outcome.instanceId}, since ${outcome.acquiredAt})",
        )

    // ── Path resolution ────────────────────────────────────────────────────────

    /**
     * Resolve a filesystem path from a ProjectLocation (RFC-0010, RFC-0052).
     * LocalPath is used as-is; RuntimeManaged creates a standard directory.
     */
    private fun resolveProjectPath(location: ProjectLocation): String {
        val managedRoot = runtimeManagedProjectsRoot ?: "/projects"
        return when (location) {
            is ProjectLocation.LocalPath -> location.path
            is ProjectLocation.RuntimeManaged -> "$managedRoot/${location.slug}"
            is ProjectLocation.CloneOf -> "$managedRoot/${location.slug}"
        }
    }

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

    /**
     * Resolve a projectId to its filesystem path (Phase 1: projectId-to-projectPath mapping).
     */
    fun resolveProjectPath(projectId: String): String? = _projectPaths[projectId]

    // ── RuntimeClient ──────────────────────────────────────────────────────────

    override val projects: ProjectCommands = object : ProjectCommands {
        override suspend fun create(request: CreateProjectRequest): ProjectResult {
            val id = projectIdGen.next()
            val now = Clock.System.now()
            val nowIso = now.toString()
            val projectPath = resolveProjectPath(request.location)

            val locker = projectLocker
            if (locker != null) {
                when (val outcome = locker.tryAcquire(id, projectPath, instanceId)) {
                    is ProjectLockOutcome.HeldByOther -> return lockedByOtherError(outcome)
                    is ProjectLockOutcome.AcquiredAfterBreakingStale,
                    ProjectLockOutcome.Acquired -> Unit
                    // RFC-0055: "locks are never broken silently" -- AcquiredAfterBreakingStale
                    // should surface to the user and be recorded (LockBreakRecord). No audit-log
                    // write path exists yet from this class; not invented here, flagged in
                    // PIPELINE.md instead of silently swallowing it.
                }
            }

            val driver = ensureProjectDriverOpen(id, projectPath)
            if (driver != null) {
                driver.execute(
                    identifier = null,
                    sql = "INSERT INTO projects " +
                        "(id, name, description, root_path, project_type, state, created_at, updated_at, state_updated_at) " +
                        "VALUES (?, ?, ?, ?, 'generic', 'OPEN', ?, ?, ?)",
                    parameters = 7,
                ) {
                    bindString(0, id)
                    bindString(1, request.name)
                    bindString(2, request.description)
                    bindString(3, projectPath)
                    bindString(4, nowIso)
                    bindString(5, nowIso)
                    bindString(6, nowIso)
                }
                projectRegistry()?.register(ProjectId(id), projectPath, nowIso = nowIso)
            }

            val summary = ProjectSummary(
                id = id, name = request.name, description = request.description,
                projectPath = projectPath,
                createdAt = now, lastActiveAt = now, sessionCount = 0,
            )
            _projects[id] = summary
            _projectPaths[id] = projectPath
            emit(RuntimeEvent.SessionCreated(
                eventId = nextId(), timestamp = now, projectId = id, sessionId = id,
                name = request.name, role = SessionRole.DRIVER,
            ))
            return ProjectResult.Success(summary)
        }

        override suspend fun open(projectId: String): ProjectResult {
            val cached = _projects[projectId]
            if (cached != null && projectId in _openProjectDrivers) {
                return ProjectResult.Success(cached)
            }

            val path = cached?.projectPath
                ?: projectRegistry()?.resolveById(ProjectId(projectId))?.getOrNull()
                ?: return ProjectResult.Error("project.not_found", "Project $projectId not found")

            val locker = projectLocker
            if (locker != null) {
                when (val outcome = locker.tryAcquire(projectId, path, instanceId)) {
                    is ProjectLockOutcome.HeldByOther -> return lockedByOtherError(outcome)
                    is ProjectLockOutcome.AcquiredAfterBreakingStale,
                    ProjectLockOutcome.Acquired -> Unit
                }
            }

            val summary = cached ?: hydrateProjectSummary(projectId, path)
            if (summary == null) {
                locker?.release(projectId)
                return ProjectResult.Error("project.not_found", "Project $projectId has no recorded state")
            }
            if (cached != null) ensureProjectDriverOpen(projectId, path)
            return ProjectResult.Success(summary)
        }

        override suspend fun close(projectId: String) {
            projectLocker?.release(projectId)
            _openProjectDrivers.remove(projectId)?.close()
        }

        override suspend fun list(): List<ProjectSummary> {
            projectRegistry()?.listAll()?.forEach { (id, path) ->
                if (id.value !in _projects) hydrateProjectSummary(id.value, path)
            }
            return _projects.values.toList()
        }

        override suspend fun get(projectId: String): ProjectDetail? {
            val p = _projects[projectId]
                ?: projectRegistry()?.resolveById(ProjectId(projectId))?.getOrNull()
                    ?.let { path -> hydrateProjectSummary(projectId, path) }
                ?: return null
            return ProjectDetail(p, p.projectPath, "generic")
        }

        override suspend fun delete(projectId: String, confirm: Boolean) {
            if (confirm) {
                projectLocker?.release(projectId)
                _openProjectDrivers.remove(projectId)?.close()
                projectRegistry()?.unregister(ProjectId(projectId))
                _projects.remove(projectId)
                _projectPaths.remove(projectId)
                // Deliberately does not touch the project's own directory or state.db -- RFC-0010's
                // MVP is archive, not destroy. This only stops the runtime tracking the project.
            }
        }
    }

    override val sessions: SessionCommands = object : SessionCommands {
        override suspend fun create(request: CreateSessionRequest): SessionResult {
            val id = sessionIdGen.next()
            val now = Clock.System.now()
            val nowIso = now.toString()
            val summary = SessionSummary(
                id = id, projectId = request.projectId, name = request.name,
                role = request.role, state = SessionState.CREATED,
                createdAt = now, lastActiveAt = now, runCount = 0,
            )

            // Persisted once the project's own driver is open -- unset (or the project never
            // opened) preserves the pre-persistence in-memory-only behavior. A session needs a
            // real row before RunExecutor can create a Run for it: runs.session_id and
            // tasks.session_id are foreign keys onto this table.
            _openProjectDrivers[request.projectId]?.execute(
                identifier = null,
                sql = "INSERT INTO sessions (id, project_id, name, role, state, created_at, " +
                    "last_active_at, state_updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                parameters = 8,
            ) {
                bindString(0, id)
                bindString(1, request.projectId)
                bindString(2, request.name)
                bindString(3, request.role.name)
                bindString(4, SessionState.CREATED.name)
                bindString(5, nowIso)
                bindString(6, nowIso)
                bindString(7, nowIso)
            }

            _sessions[id] = summary
            emit(RuntimeEvent.SessionCreated(
                eventId = nextId(), timestamp = now, projectId = request.projectId,
                sessionId = id, name = request.name, role = request.role,
            ))
            return SessionResult.Success(summary)
        }

        override suspend fun send(sessionId: String, message: UserMessage): RunResult {
            val session = _sessions[sessionId]
                ?: return RunResult.Error("session.not_found", "Session $sessionId not found")

            val executor = runExecutor
            val driver = _openProjectDrivers[session.projectId]
            val result = if (executor != null && driver != null) {
                executor.send(
                    projectDriver = driver,
                    projectId = session.projectId,
                    sessionId = sessionId,
                    message = message,
                    platformProfile = platformProfile,
                    // instanceId already identifies this runtime instance for project locking
                    // (RFC-0055); reusing it for RFC-0046 "which machine ran this" provenance
                    // avoids inventing a second identifier for the same concept.
                    deviceId = instanceId,
                    networkAvailable = networkAvailable,
                )
            } else {
                val runId = nextId()
                _runs[runId] = RunSummary(
                    id = runId, state = "RUNNING",
                    startedAt = Clock.System.now(), endedAt = null, stepCount = 0,
                )
                RunResult.Accepted(runId)
            }

            if (result is RunResult.Accepted) {
                _runProjectIds[result.runId] = session.projectId
                emit(RuntimeEvent.RunStarted(
                    eventId = nextId(), timestamp = Clock.System.now(), projectId = session.projectId,
                    sessionId = sessionId, runId = result.runId,
                ))
            }
            return result
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

        override suspend fun approveEffect(runId: String, taskId: String): CapabilityResult {
            val projectId = _runProjectIds[runId]
                ?: return CapabilityResult.Error("run.not_found", "Run $runId not found")
            val driver = _openProjectDrivers[projectId]
                ?: return CapabilityResult.Error("run.project_not_open", "Project for run $runId is not open")
            val gateway = effectApprovalGateway
                ?: return CapabilityResult.Error("run.approval_not_wired", "No effect approval gateway is wired")
            return when (gateway.resolve(driver, runId, approved = true, denialReason = null)) {
                is EffectResolution.Resumed -> CapabilityResult.Success(runId)
                is EffectResolution.Denied ->
                    CapabilityResult.Error("run.already_denied", "Run $runId was already denied")
                is EffectResolution.NotFound ->
                    CapabilityResult.Error("continuation.not_found", "No pending approval for run $runId")
            }
        }

        override suspend fun denyEffect(runId: String, taskId: String, reason: String) {
            val projectId = _runProjectIds[runId] ?: return
            val driver = _openProjectDrivers[projectId] ?: return
            effectApprovalGateway?.resolve(driver, runId, approved = false, denialReason = reason)
        }
    }

    override val knowledge: KnowledgeQueries = object : KnowledgeQueries {
        override suspend fun search(projectId: String, query: KnowledgeQuery): KnowledgeResult {
            val projectPath = resolveProjectPath(projectId)
                ?: return KnowledgeResult(emptyList(), 0, null)
            return knowledgeService?.search(projectPath, query)
                ?: KnowledgeResult(emptyList(), 0, null)
        }

        override suspend fun indexStatus(projectId: String): IndexStatus {
            val projectPath = resolveProjectPath(projectId)
                ?: return IndexStatus(projectId, null, 0, false)
            return knowledgeService?.indexStatus(projectPath)
                ?: IndexStatus(projectId, null, 0, false)
        }
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
