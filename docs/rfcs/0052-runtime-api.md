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
The runtime is a **separate daemon process**. Frontends — the Compose GUI, the CLI, and any
editor integration — connect over a Unix domain socket (macOS/Linux) or named pipe (Windows),
exchanging newline-delimited JSON.

An earlier version loaded the runtime as a library inside the GUI process. That could not
deliver RFC-0002's "multiple frontends may interact with the same runtime", made a GUI crash
kill a running Run, and left the API seam free to erode into shared mutable state because
nothing enforced it. It also created the possibility of two runtimes on one project (RFC-0055).

**Android**:
The runtime runs in-process inside a foreground service, behind the same `RuntimeClient`
interface. MOBILE has exactly one frontend by construction, so the boundary is a discipline
rather than a process — acceptable, and the interface is identical either way.

**CLI**:
A thin client of the desktop daemon, using the same socket and protocol as the GUI. It is not a
special case.

**Test/mock**:
An in-memory `MockRuntimeClient` implements the same interface.

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
    // All of these require a `user_interactive` connection (see Authentication).
    suspend fun grant(request: GrantCapabilityRequest): CapabilityResult
    suspend fun revoke(capabilityId: UUID)
    suspend fun list(sessionId: UUID): List<CapabilitySummary>
    suspend fun listPending(): List<PendingCapabilityRequest>
    suspend fun approve(requestId: UUID): CapabilityResult
    suspend fun deny(requestId: UUID, reason: String)

    // Per-call approval for a previewed effect (RFC-0030) or a tainted escalation (RFC-0027).
    suspend fun approveEffect(runId: UUID, taskId: UUID): CapabilityResult
    suspend fun denyEffect(runId: UUID, taskId: UUID, reason: String)
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

    // RFC-0049: frontends render tool availability rather than discovering it by failure.
    suspend fun getProfile(): PlatformProfile
    suspend fun getAvailability(projectId: UUID): AvailabilityReport
}

data class AvailabilityReport(
    val profile: PlatformProfile,
    val networkAvailable: Boolean,
    val satisfied: List<String>,      // tool families available
    val degraded: List<String>,       // declared optional, unavailable here
    val unsatisfied: List<String>     // declared required, unavailable here
)

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

**Local connections are authenticated. "Same device implies trusted" is not the model.**

The runtime spawns child processes it does not trust — stdio MCP servers, shell commands, and
later plugin hosts. If any local process could connect, a spawned MCP server could call
`capabilities.approve()` on its own pending request and defeat the human-in-the-loop that the
entire security model rests on.

The mechanism (RFC-0055):

1. The socket is created with owner-only permissions (`0600`) in the user's runtime directory.
2. At startup the runtime mints a **connection token**, written to a file readable only by the
   user. Every client presents it on connect.
3. **Child processes spawned by the Tool Broker receive a scrubbed environment** with no token
   and no socket path.
4. A connection declares whether it is `user_interactive` — able to present UI to a human.
   Commands that grant or approve authority (`capabilities.grant`, `approve`, `deny`) require
   it. A CLI in a pipeline cannot silently approve on the user's behalf.

Each connection is a `FRONTEND` capability subject (RFC-0018), so a connection's permitted
command set is expressible and auditable rather than all-or-nothing.

Future work: remote frontend access — a phone driving a desktop runtime — adds device pairing
and transport encryption. The token model above is the local case of the same mechanism.

### Error Handling

All commands return either a success result or an error result. Errors are structured:

Errors use `AidosError` from RFC-0029, which is the single taxonomy shared by the Runtime API,
the Tool Broker, and the Execution Graph. The enum previously defined here duplicated
RFC-0019's `ErrorCategory` over the same domain with different members and said nothing about
what to do with an error.

```kotlin
data class AidosError(
    val code: String,                 // stable, namespaced: "capability.denied"
    val errorClass: ErrorClass,       // TRANSIENT | DENIED | EXHAUSTED | CONFLICT | ...
    val message: String,
    val detail: Map<String, String> = emptyMap()
)
```

Codes are strings, not an enum, because plugins and MCP adapters introduce codes the core does
not know and an enum would flatten them all to `UNKNOWN`. Frontends switch on `errorClass`,
which is closed and small, and use `code` for specific messaging where they have it. A code may
be added at any time; removing one or changing its class requires an `apiVersion` increment.

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
