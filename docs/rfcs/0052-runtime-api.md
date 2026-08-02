# RFC-0052: Runtime API

Status: Draft

## Abstract

This RFC defines the contract between the Aidos headless runtime and its frontends. It specifies the transport mechanism, message protocol, event streaming model, authentication, API versioning, and testing contract. The Runtime API is the stable seam that allows frontends (Android, Desktop, CLI) to be developed independently from the runtime.

## Motivation

RFC-0002 (Runtime) establishes that frontends are "thin clients that communicate with the headless runtime via a stable API" and that "frontends are stateless." However, no RFC defines what that API is. Without this definition:

- Frontends cannot be built and tested independently from the runtime.
- The runtime and frontends are implicitly coupled through shared code rather than an explicit contract.
- Breaking changes in the runtime silently break frontends.
- Multiple frontends cannot co-exist against the same runtime.
- Testing is difficult because the contract has no mock implementation.

The Runtime API is the most important seam in the system. It should be defined before any frontend work begins.

## Goals

1. Define the transport mechanism for runtime-frontend communication.
2. Define the request-response protocol for commands and queries.
3. Define the event streaming protocol for real-time updates.
4. Define the authentication model between frontends and runtime.
5. Define API versioning and backward compatibility policy.
6. Define the testing contract (mock runtime interface).

## Non-goals

This RFC does not define the visual design of frontends.
It does not define the internal structure of the runtime (RFC-0002).
It does not define inter-session communication (that is internal to the runtime).
It does not define remote runtime access (all connections are local-to-device).

## Design

### Transport

All runtime-frontend communication is local to the device. There is no network transport between the runtime and its own frontends.

**Desktop (JVM)**:
The runtime is loaded as a library in the same JVM process as the Compose Multiplatform frontend. Communication uses in-process function calls wrapped behind the `RuntimeClient` interface. No IPC overhead.

**Android**:
The runtime runs in a foreground service within the same application process. Communication uses in-process calls through a bound service interface.

**CLI**:
The CLI is a thin process that connects to a running runtime daemon via a Unix domain socket (macOS/Linux) or named pipe (Windows). The CLI serializes commands as newline-delimited JSON.

**Test/mock**:
An in-memory `MockRuntimeClient` implements the same `RuntimeClient` interface, enabling frontends to be tested without a real runtime.

The transport abstraction is the `RuntimeClient` interface, defined in KMP common code. Each platform provides an implementation.

### RuntimeClient Interface

The `RuntimeClient` interface is the complete public surface of the runtime as seen by frontends. It is divided into domains:

```kotlin
interface RuntimeClient {
    val projects: ProjectCommands
    val sessions: SessionCommands
    val capabilities: CapabilityCommands
    val knowledge: KnowledgeQueries
    val artifacts: ArtifactQueries
    val events: EventSubscriptions
    val runtime: RuntimeInfo
}
```

### Project Commands

```kotlin
interface ProjectCommands {
    suspend fun create(request: CreateProjectRequest): ProjectResult
    suspend fun open(projectId: UUID): ProjectResult
    suspend fun close(projectId: UUID)
    suspend fun list(): List<ProjectSummary>
    suspend fun get(projectId: UUID): ProjectDetail
    suspend fun delete(projectId: UUID, confirm: Boolean)
    suspend fun importArchive(archivePath: Path): ProjectResult
    suspend fun exportArchive(projectId: UUID, destinationPath: Path)
}

data class CreateProjectRequest(
    val name: String,
    val description: String,
    val rootDirectory: Path,
    val initGit: Boolean = true,
    val templateId: UUID? = null
)

data class ProjectSummary(
    val id: UUID,
    val name: String,
    val description: String,
    val createdAt: Instant,
    val lastActiveAt: Instant,
    val sessionCount: Int,
    val artifactCount: Int
)

sealed class ProjectResult {
    data class Success(val project: ProjectSummary) : ProjectResult()
    data class Error(val code: ErrorCode, val message: String) : ProjectResult()
}
```

### Session Commands

```kotlin
interface SessionCommands {
    suspend fun create(request: CreateSessionRequest): SessionResult
    suspend fun send(sessionId: UUID, message: UserMessage): RunResult
    suspend fun cancel(sessionId: UUID, runId: UUID)
    suspend fun list(projectId: UUID): List<SessionSummary>
    suspend fun get(sessionId: UUID): SessionDetail
    suspend fun archive(sessionId: UUID)
    suspend fun delete(sessionId: UUID)
}

data class CreateSessionRequest(
    val projectId: UUID,
    val name: String,
    val role: SessionRole = SessionRole.DRIVER,
    val instructionSetId: UUID? = null
)

data class UserMessage(
    val content: String,
    val attachments: List<AttachmentRef> = emptyList(),
    val runOptions: RunOptions = RunOptions()
)

data class RunOptions(
    val maxTokens: Int? = null,
    val requireApprovalBeforeToolUse: Boolean = false,
    val timeoutSeconds: Int = 300
)

sealed class RunResult {
    data class Accepted(val runId: UUID) : RunResult()
    data class Error(val code: ErrorCode, val message: String) : RunResult()
}

data class SessionSummary(
    val id: UUID,
    val projectId: UUID,
    val name: String,
    val role: SessionRole,
    val state: SessionState,
    val createdAt: Instant,
    val lastActiveAt: Instant,
    val runCount: Int
)

data class SessionDetail(
    val summary: SessionSummary,
    val capabilities: List<CapabilitySummary>,
    val recentRuns: List<RunSummary>,
    val intentSummary: String?
)
```

### Capability Commands

```kotlin
interface CapabilityCommands {
    suspend fun grant(request: GrantCapabilityRequest): CapabilityResult
    suspend fun revoke(capabilityId: UUID)
    suspend fun list(sessionId: UUID): List<CapabilitySummary>
    suspend fun listPending(): List<PendingCapabilityRequest>
    suspend fun approve(requestId: UUID): CapabilityResult
    suspend fun deny(requestId: UUID, reason: String)
}

data class GrantCapabilityRequest(
    val sessionId: UUID,
    val permission: PermissionType,
    val scope: String?,
    val constraints: Map<String, String> = emptyMap(),
    val expiresAt: Instant? = null
)
```

### Knowledge Queries

```kotlin
interface KnowledgeQueries {
    suspend fun search(projectId: UUID, query: KnowledgeQuery): KnowledgeResult
    suspend fun indexStatus(projectId: UUID): IndexStatus
}

data class KnowledgeQuery(
    val text: String,
    val kinds: List<KnowledgeKind> = KnowledgeKind.entries,
    val limit: Int = 20,
    val semanticSearch: Boolean = false
)

data class KnowledgeResult(
    val items: List<KnowledgeItem>,
    val totalMatches: Int,
    val indexedAt: Instant
)
```

### Artifact Queries

```kotlin
interface ArtifactQueries {
    suspend fun list(projectId: UUID, filter: ArtifactFilter = ArtifactFilter()): List<ArtifactSummary>
    suspend fun get(artifactId: UUID): ArtifactDetail
    suspend fun getContent(artifactId: UUID): ByteArray
    suspend fun getAuditTrail(artifactId: UUID): List<AuditEntry>
}

data class ArtifactFilter(
    val sessionId: UUID? = null,
    val contentType: String? = null,
    val since: Instant? = null,
    val limit: Int = 50
)
```

### Event Subscriptions

Events are the primary mechanism for frontends to receive real-time updates. Frontends subscribe to event topics and receive a stream of events.

```kotlin
interface EventSubscriptions {
    fun subscribe(filter: EventFilter): Flow<RuntimeEvent>
}

data class EventFilter(
    val projectIds: List<UUID> = emptyList(),   // empty means all projects
    val sessionIds: List<UUID> = emptyList(),    // empty means all sessions
    val types: List<RuntimeEventType> = emptyList()  // empty means all types
)
```

The event stream is a Kotlin `Flow<RuntimeEvent>`. On platforms where the runtime is in-process, this is a direct coroutine flow. On platforms using a socket connection (CLI), the flow is backed by a stream reader.

### Runtime Events

```kotlin
sealed class RuntimeEvent {
    abstract val eventId: UUID
    abstract val timestamp: Instant
    abstract val projectId: UUID?
    abstract val sessionId: UUID?

    // Session lifecycle events
    data class SessionCreated(override val eventId: UUID, override val timestamp: Instant,
        override val projectId: UUID, override val sessionId: UUID,
        val name: String, val role: SessionRole) : RuntimeEvent()

    data class SessionStateChanged(override val eventId: UUID, override val timestamp: Instant,
        override val projectId: UUID, override val sessionId: UUID,
        val from: SessionState, val to: SessionState) : RuntimeEvent()

    // Run lifecycle events
    data class RunStarted(override val eventId: UUID, override val timestamp: Instant,
        override val projectId: UUID, override val sessionId: UUID,
        val runId: UUID) : RuntimeEvent()

    data class RunCompleted(override val eventId: UUID, override val timestamp: Instant,
        override val projectId: UUID, override val sessionId: UUID,
        val runId: UUID, val artifactIds: List<UUID>) : RuntimeEvent()

    data class RunFailed(override val eventId: UUID, override val timestamp: Instant,
        override val projectId: UUID, override val sessionId: UUID,
        val runId: UUID, val error: String) : RuntimeEvent()

    // AI response streaming
    data class AiResponseDelta(override val eventId: UUID, override val timestamp: Instant,
        override val projectId: UUID, override val sessionId: UUID,
        val runId: UUID, val delta: String, val isFinal: Boolean) : RuntimeEvent()

    // Tool events
    data class ToolCallStarted(override val eventId: UUID, override val timestamp: Instant,
        override val projectId: UUID, override val sessionId: UUID,
        val runId: UUID, val toolName: String, val preview: String) : RuntimeEvent()

    data class ToolCallCompleted(override val eventId: UUID, override val timestamp: Instant,
        override val projectId: UUID, override val sessionId: UUID,
        val runId: UUID, val toolName: String, val success: Boolean) : RuntimeEvent()

    // Capability events
    data class CapabilityRequested(override val eventId: UUID, override val timestamp: Instant,
        override val projectId: UUID, override val sessionId: UUID,
        val requestId: UUID, val permission: PermissionType, val reason: String) : RuntimeEvent()

    data class CapabilityGranted(override val eventId: UUID, override val timestamp: Instant,
        override val projectId: UUID, override val sessionId: UUID,
        val capabilityId: UUID, val permission: PermissionType) : RuntimeEvent()

    // Artifact events
    data class ArtifactCreated(override val eventId: UUID, override val timestamp: Instant,
        override val projectId: UUID, override val sessionId: UUID?,
        val artifactId: UUID, val contentType: String, val size: Long) : RuntimeEvent()

    // Project events
    data class ProjectOpened(override val eventId: UUID, override val timestamp: Instant,
        override val projectId: UUID, override val sessionId: UUID? = null) : RuntimeEvent()

    data class ProjectClosed(override val eventId: UUID, override val timestamp: Instant,
        override val projectId: UUID, override val sessionId: UUID? = null) : RuntimeEvent()

    // Knowledge Engine events
    data class IndexingStarted(override val eventId: UUID, override val timestamp: Instant,
        override val projectId: UUID, override val sessionId: UUID? = null) : RuntimeEvent()

    data class IndexingCompleted(override val eventId: UUID, override val timestamp: Instant,
        override val projectId: UUID, override val sessionId: UUID? = null,
        val itemsIndexed: Int) : RuntimeEvent()

    // Error events
    data class RuntimeError(override val eventId: UUID, override val timestamp: Instant,
        override val projectId: UUID?, override val sessionId: UUID?,
        val code: ErrorCode, val message: String) : RuntimeEvent()
}
```

### Runtime Info

```kotlin
interface RuntimeInfo {
    suspend fun getVersion(): RuntimeVersion
    suspend fun getStatus(): RuntimeStatus
    suspend fun listOpenProjects(): List<ProjectSummary>
}

data class RuntimeVersion(
    val apiVersion: Int,         // increments on breaking changes
    val minApiVersion: Int,      // oldest API version this runtime supports
    val buildVersion: String     // human-readable, e.g. "1.2.3"
)

data class RuntimeStatus(
    val uptime: Duration,
    val activeProjects: Int,
    val activeSessions: Int,
    val activeRuns: Int,
    val memoryUsage: MemoryUsage
)
```

### API Versioning

The Runtime API is versioned with a single integer (`apiVersion`). The version increments on any breaking change. A breaking change is:

- Removal of a command or query method
- Change in the required parameters of a command
- Removal or rename of a field in a response type
- Change in the semantics of an existing method

Adding new optional fields, new event types, and new methods is non-breaking.

When a frontend connects, it declares the minimum API version it requires. The runtime rejects connections from frontends that require a newer API version than it supports. The runtime accepts connections from frontends that require an older API version (subject to the `minApiVersion` floor).

**Compatibility policy:**
- Major releases may increment `apiVersion` and drop support for old `minApiVersion`.
- Minor releases never increment `apiVersion`.
- Patch releases never change the API.

### Authentication

In the MVP, all connections from the same device are trusted. Authentication is not required for local-only frontends.

Future work: When remote frontend access is added (accessing a home server runtime from a mobile device), the runtime requires a shared secret (device pairing token). This is not part of the MVP.

### Error Handling

All commands return either a success result or an error result. Errors are structured:

```kotlin
data class RuntimeError(
    val code: ErrorCode,
    val message: String,
    val details: Map<String, String> = emptyMap()
)

enum class ErrorCode {
    PROJECT_NOT_FOUND,
    SESSION_NOT_FOUND,
    RUN_NOT_FOUND,
    CAPABILITY_DENIED,
    CAPABILITY_NOT_FOUND,
    INVALID_REQUEST,
    RUNTIME_BUSY,
    RUNTIME_ERROR,
    NOT_SUPPORTED
}
```

Frontends should handle all error codes. Unknown error codes (from newer runtime versions) should be treated as `RUNTIME_ERROR`.

### Mock Runtime for Testing

The `MockRuntimeClient` is a test double that implements `RuntimeClient`. It:

- Stores all project and session state in memory.
- Accepts all capability grants without user approval.
- Generates deterministic AI responses from a configurable fixture map.
- Delivers pre-scripted tool results.
- Can be configured to inject failures and delays.

```kotlin
class MockRuntimeClient : RuntimeClient {
    fun configureAiResponse(sessionId: UUID, response: String)
    fun configureToolResult(toolName: String, result: ToolResult)
    fun injectError(command: String, error: RuntimeError)
    fun recordedCommands(): List<RecordedCommand>
}
```

Frontend tests use `MockRuntimeClient` to test all user interactions without a real runtime.

## Data Model

The Runtime API data model is defined by the `RuntimeClient` interface and the types it references. All types are defined in KMP common code and are serializable via kotlinx.serialization.

Transport serialization for the CLI socket connection uses JSON (kotlinx.serialization JSON). In-process communication uses direct Kotlin objects.

## Security

All Runtime API calls are subject to the runtime's capability checks. A frontend that requests a tool invocation on behalf of a session must have a session that holds the required capability.

In the MVP, frontends are trusted (same process or same device). Future remote frontend access requires authentication.

The event stream must not include sensitive field values (e.g., secret values, raw model outputs marked as sensitive). Events include metadata and identifiers; content retrieval requires explicit `ArtifactQueries.getContent()` calls, which are themselves capability-checked.

## MVP

The MVP implements:

1. `RuntimeClient` interface in KMP common code.
2. In-process implementation for Android and Desktop.
3. Unix socket + JSON implementation for CLI (desktop only).
4. `MockRuntimeClient` for testing.
5. All ProjectCommands, SessionCommands, and EventSubscriptions methods.
6. AI response streaming via `AiResponseDelta` events.
7. Capability request flow via `CapabilityRequested` events and `approve()`/`deny()` commands.
8. API version negotiation on connection.

The MVP does not implement:
- Remote frontend access (authentication, TLS).
- Named pipe transport for Windows.
- Full ArtifactQueries and KnowledgeQueries (read-only access is sufficient).

## Future Work

Remote access: encrypted, authenticated connections from mobile to desktop runtime (home server model).

GraphQL or gRPC transport for richer query capabilities.

Frontend capability grants: the user can restrict what a frontend can do (e.g., read-only frontend mode).

Session recording: a frontend can request that a session's entire Run be recorded as a replayable artifact.

Multiple runtime instances: a single frontend that spans multiple runtime instances (e.g., work laptop + home laptop with separate runtimes but shared project manifests via Git sync).
