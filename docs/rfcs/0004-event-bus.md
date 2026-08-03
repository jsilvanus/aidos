# RFC-0004: Event Bus

Status: Accepted 2026-08-03

## Abstract

The Event Bus is the central coordination mechanism for the Aidos runtime. It is an in-process, persistent event stream that connects all subsystems. Events flow through the bus in response to user actions, timers, external notifications, and tool completions. Subscribers (primarily sessions) react to events, perform work, and publish new events. The Event Bus enables reactive, event-driven behaviour while maintaining an audit trail sufficient to reconstruct what happened. Event payloads carry references, not bulk content, and subscriptions are capability-checked.

## Motivation

Polling is a poor fit for interactive systems. If the scheduler queries "is there work to do?" every 100ms, it wastes CPU and introduces latency. If it queries every 10s, work is delayed. Neither is satisfactory.

Events are the natural model. When something significant happens—the user issues a command, a timer fires, a file changes, a tool completes—an event is generated and broadcast. Interested parties (sessions, other subsystems) react immediately.

An event system also provides other benefits:

- **Observability**: Every significant action is a discrete event with a timestamp. The event log is a complete record of system behavior.
- **Audit reconstruction**: Because events are ordered and persisted, what happened can be reconstructed after the fact. This is not deterministic replay — see "Audit reconstruction, not deterministic replay" below.
- **Loose Coupling**: Subsystems do not need to know about each other. They publish events and subscribe to events. The Event Bus mediates.
- **Testability**: Tests can inject events and observe reactions, without needing to mock complex system interactions.
- **Extensibility**: New event sources (webhooks, custom tools, notifications) can be added without modifying existing code.

## Goals

1. **Define the event model**: What is an event? What properties does it have?

2. **Establish event types and topics** so that subscribers can filter events of interest.

3. **Specify the subscription model**: How do sessions and subsystems subscribe to events?

4. **Define the persistence model**: How are events stored and retrieved?

5. **Establish event ordering and causality**: How do we ensure events are processed in the correct order?

6. **Specify event lifecycle**: When is an event created, when is it persisted, when can it be deleted?

## Non-goals

This RFC does not specify the underlying storage mechanism (that is RFC-0040).

This RFC does not mandate a specific event format encoding (JSON, Protocol Buffers, etc.). The semantics are what matter; encoding is implementation.

This RFC does not address distributed events (when Aidos spans multiple machines). That is future work.

This RFC does not specify the exact API (synchronous, callback-based, streaming RPC). Implementation details will follow in the runtime API design.

## Design

### The Event Model

An **event** is a discrete, timestamped record of something significant happening in the system.

```
Event {
  id: UUID                          # Unique identifier
  type: EventType                   # eg. UserCommand, TimerFired, FileChanged, etc.
  timestamp: Timestamp              # When the event occurred
  source: EventSource               # Where the event came from
  topic: String?                    # For filtering (eg. "filesystem:/project/src")
  payload: Map<String, Any>         # Event-specific data
  causality: UUID?                  # ID of the event that caused this one (for tracing)
  metadata: Map<String, Any>?       # Implementation-specific metadata
}
```

Every event has a unique ID, a timestamp, and a type. The timestamp reflects when the event occurred in the real world, not when it was processed — and it is **not** the ordering key. Ordering is by the per-project `sequence` assigned at publication (see Event Ordering below); timestamps come from sources the runtime does not control, including filesystem mtimes and remote servers.

The `causality` field links related events. If event A causes event B to be generated, B's causality field points to A. This creates a causal graph that helps with debugging.

The `topic` field allows events to be labeled with a hierarchical topic (eg. `filesystem:/project/src`, `git:master`, `session:abc123`). Subscribers can filter by topic pattern, receiving only events they care about.

### Event Types

Events fall into several categories:

#### User Interaction

- **UserCommand**: The user issued a command (via frontend or CLI).
- **UserInput**: The user provided input (text, file upload, etc.).
- **UserApproval**: The user approved or rejected a proposal.

Example:
```json
{
  "type": "UserCommand",
  "source": "frontend:android",
  "payload": {
    "session_id": "sess-123",
    "command": "write a test for add()",
    "context": { "file": "src/main.rs", "function": "add" }
  }
}
```

#### Time-Based

- **TimerFired**: A timer or schedule event fired.
- **ScheduledTask**: A scheduled task is due.

Example:
```json
{
  "type": "TimerFired",
  "source": "scheduler",
  "payload": {
    "timer_id": "timer-456",
    "interval": "1h"
  }
}
```

#### Filesystem

- **FileCreated**: A file was created.
- **FileModified**: A file was modified.
- **FileDeleted**: A file was deleted.
- **DirectoryCreated**: A directory was created.

Example:
```json
{
  "type": "FileModified",
  "source": "filesystem",
  "topic": "filesystem:/project/src/main.rs",
  "payload": {
    "path": "src/main.rs",
    "size": 2048,
    "timestamp": "2025-08-01T12:34:56Z"
  }
}
```

#### Git

- **GitCommit**: A new commit was created (or detected).
- **GitPush**: Changes were pushed to a remote.
- **GitPull**: Changes were pulled from a remote.
- **GitBranchCreated**: A branch was created.
- **GitMerge**: Branches were merged.

Example:
```json
{
  "type": "GitCommit",
  "source": "git",
  "topic": "git:master",
  "payload": {
    "commit": "abc123def456...",
    "author": "session:789",
    "message": "Add tests for add() function",
    "files": ["test/test_math.rs"]
  }
}
```

#### Tool Completion

- **ToolCompleted**: An external tool (shell command, API call, MCP) completed.
- **ToolFailed**: A tool execution failed.

Example:
```json
{
  "type": "ToolCompleted",
  "source": "tool:shell",
  "payload": {
    "tool_id": "shell-111",
    "task_id": "task-...",
    "operation": "shell.exec",
    "exit_code": 0,
    "result_ref": "content-node-uuid",
    "result_bytes": 1284
  }
}
```

#### MCP

- **MCPServerConnected**: An MCP server connected.
- **MCPServerDisconnected**: An MCP server disconnected.
- **MCPToolCallCompleted**: An MCP tool call completed.

#### Session

- **SessionCreated**: A new session was created.
- **SessionWoken**: A dormant session was woken.
- **SessionSleeping**: An active session is going dormant.
- **SessionArchived**: A session was archived.
- **SessionError**: A session encountered an error.

#### AI Engine

- **ModelQueryInitiated**: A session initiated a query to an AI model.
- **ModelQueryCompleted**: An AI model responded.
- **ModelStreamingStarted**: Streaming output from a model began.
- **ModelStreamingStopped**: Streaming ended.

#### Permission

- **PermissionRequested**: A session requested a permission.
- **PermissionGranted**: A permission was granted.
- **PermissionRevoked**: A permission was revoked.
- **PermissionDenied**: An operation was denied due to permission.

#### Artifact

- **ArtifactCreated**: A new artifact was created.
- **ArtifactUpdated**: An artifact was modified (though artifacts should be immutable, metadata can be updated).
- **ArtifactPublished**: An artifact was made available to other sessions.

#### System

- **SystemStarted**: The runtime started.
- **SystemShuttingDown**: The runtime is shutting down.
- **ErrorOccurred**: A system error occurred (separate from session errors).

### Topics and Filtering

Events can be organized by topic for efficient filtering. Topics use a hierarchical, dot-separated format:

```
filesystem:/project/src/main.rs
filesystem:/project/**              # All files under /project
git:master
git:*                              # All git events
session:sess-123
session:**                          # All sessions
tool:shell:cmd-456
ai:model:claude-3-sonnet
permission:*
```

Subscribers specify topic patterns using wildcards:
- `filesystem:/project/src/*` — Files directly in src.
- `filesystem:/project/**` — All files under project (recursive).
- `git:*` — All git events.
- `*` — All events.

### Subscription Model

Subscribers express interest in events via a subscription:

```
Subscription {
  id: UUID
  subscriber_id: UUID               # Session or subsystem ID
  topic_patterns: List<String>      # eg. ["filesystem:/project/**", "git:*"]
  event_types: List<EventType>?     # Optional: filter by type
  since: Timestamp?                 # Optionally start from a time in the past
  is_persistent: Boolean            # Does subscription persist across restarts?
}
```

A session can have multiple subscriptions. For example:

```
Session S1 subscribes to:
  - Topics: ["filesystem:/project/src/**", "git:master"]
  - Event types: [FileModified, GitCommit]
```

The scheduler maintains a mapping of events to interested subscribers and wakes them when events of interest occur.

### Event Categories

Events are one of three categories. Mixing them is how an event bus accumulates policy and
becomes impossible to secure, so the distinction is structural rather than conventional.

| Category | Meaning | May be published by | Delivery |
|---|---|---|---|
| `FACT` | Something happened. Immutable, past tense. | any subsystem | broadcast to authorized subscribers |
| `COMMAND` | Something is requested. | frontends and the runtime only | routed to exactly one handler |
| `SIGNAL` | Lossy progress or wakeup hint. | any subsystem | best-effort, coalescible, not persisted |

Sessions may publish `FACT` and `SIGNAL`. **Sessions may not publish `COMMAND`.** A session that
could publish commands could instruct the runtime to grant capabilities or cancel other
sessions; routing that through the same channel as observations would make the security model
depend on topic-string hygiene.

`SIGNAL` events are not persisted. Streaming token deltas, indexing progress, and similar
high-frequency updates would otherwise dominate the event table.

### Subscription Authorization

Subscriptions are capability-checked. A subscription is a read of other components' activity,
and before this rule the bus was a side channel around the entire capability model: any session
could subscribe to `**` and observe every tool result, model response, and file operation in
the project, including from sessions with wider authority than its own.

Every event carries a `visibility`:

| Visibility | Who may receive it |
|---|---|
| `PUBLIC` | any subscriber in the project |
| `SESSION` | the originating session, its parent session, and the user |
| `PRIVILEGED` | the user and runtime components only (capability grants, secrets access) |

Rules:

1. A session subscribing to topics beyond its own requires the `event:subscribe` permission
   (RFC-0018), scoped to topic patterns.
2. Wildcard subscriptions matching `PRIVILEGED` events are never granted to sessions.
3. Frontends acting for the user receive everything, subject to the redaction rules below.
4. `ToolCompleted` payloads carry a result *reference*, not the full output. Large tool output
   lives in the Execution Graph and content store, where it is capability-checked on read.
   Earlier examples embedded full command `stdout` in the event payload, which made the bus a
   bulk exfiltration path.

### Event Persistence

Events are persisted in the order they occur. This creates a complete audit trail and enables replay.

**Storage**: Events are stored in the project's SQLite database with the following structure:

```sql
CREATE TABLE events (
  id TEXT PRIMARY KEY,
  project_id TEXT NOT NULL,
  sequence INTEGER NOT NULL,               -- monotonic per project; the ordering key
  type TEXT NOT NULL,
  schema_version INTEGER NOT NULL,         -- payload schema version for this type
  category TEXT NOT NULL,                  -- FACT | COMMAND | SIGNAL
  timestamp TEXT NOT NULL,                 -- when it occurred; NOT an ordering key
  source TEXT NOT NULL,
  topic TEXT,
  visibility TEXT NOT NULL,                -- see Subscription Authorization
  payload TEXT NOT NULL,                   -- JSON-encoded
  causality TEXT,
  causal_depth INTEGER NOT NULL DEFAULT 0,
  metadata TEXT                            -- JSON-encoded
);

CREATE UNIQUE INDEX idx_events_sequence ON events(project_id, sequence);
CREATE INDEX idx_events_topic ON events(project_id, topic);
CREATE INDEX idx_events_type ON events(project_id, type, sequence);
```

`schema_version` is present from day one. Events are the longest-lived records in the system
and their payloads will change; treating payload versioning as future work guarantees an
unreadable history. Readers must tolerate unknown versions of a known type by falling back to
the event header.

**Retention**: Event headers are `PERMANENT`; payloads are `AGED` and are compacted after the
policy window (RFC-0056). "Retained for the lifetime of the project" was not survivable on
mobile devices.

**Querying**: Subscribers can query past events:

```
Get all events of type FileModified on topic "filesystem:/project/src/**" 
between 2025-07-01 and 2025-08-01
```

This enables catching up after a session resumes.

### Event Ordering and Causality

Events are processed **in publication order within a project**, not in wall-clock timestamp
order. Each project has a single FIFO channel; a monotonic `sequence` is assigned at
publication and is the authoritative order (RFC-0007).

An earlier design mandated processing in wall-clock timestamp order. That is not
implementable: it would require knowing that no earlier-timestamped event will arrive later,
which requires unbounded buffering. It also made ordering depend on inputs the runtime does not
control — filesystem mtimes, MCP server clocks, and NTP steps. The `timestamp` field remains,
as a record of when the event occurred in the world; it is not an ordering key.

Across projects there is no ordering guarantee. Projects are independent.

**Causality tracking**: The `causality` field links events that are related. If event A causes the system to generate event B, then B.causality = A.id. This creates a directed acyclic graph (DAG) of causality.

Each event also carries `causal_depth` (RFC-0028): zero for externally originated events, and
one greater than its cause otherwise. Depth bounds wake amplification and is what stops two
sessions from waking each other indefinitely.

Example:
```
Event A: UserCommand(session=S1, command="write a test")
Event B: SessionWoken(session=S1, causality=A.id)
Event C: ToolCompleted(tool=shell, causality=B.id)
Event D: ArtifactCreated(artifact=test_file, causality=C.id)
```

The causality chain A → B → C → D traces the lineage of work.

### Event Lifecycle

1. **Generation**: Something significant happens (user action, timer, filesystem change). The relevant subsystem generates an event.

2. **Queuing**: The event is added to the Event Bus's queue (in-memory).

3. **Persistence**: The event is written to persistent storage (before or concurrent with processing, depending on durability guarantees).

4. **Notification**: Subscribers are notified (asynchronously or synchronously, depending on implementation).

5. **Processing**: Subscribers react to the event (sessions wake up, subsystems perform work).

6. **Causation**: Subscribers may generate new events, which re-enter the cycle.

7. **Expiration**: Old events can be pruned (but this is destructive for replay, so it is not the default).

### Audit reconstruction, not deterministic replay

The event log supports **audit reconstruction**: answering what happened, in what order, caused
by what. It does **not** support deterministic replay, and the architecture does not claim it.

Deterministic replay would require re-executing the system and arriving at the same state. That
is unachievable here, because the inputs are not deterministic: model outputs (including
sampling), remote model versions, wall-clock time, filesystem races, Git state mutated by the
user, shell output, MCP server behaviour, network responses, and generated IDs. Capturing
enough to make re-execution identical means capturing every output — at which point what exists
is a recording, not a replay.

What the log therefore provides:

- **Debugging**: "what did the system do, and in what order?"
- **Auditing**: "did the AI try to delete that file, and under what authority?"
- **Provenance**: "which run, prompt, and model produced this artifact?" (RFC-0019, RFC-0025)
- **Timeline UI**: a step-through view of a completed Run.

What it does not provide: re-execution to an identical state, or restoring the project to
yesterday. Restoration is Git's job for content (RFC-0053) and export/import's job for
project state (RFC-0041).

This distinction is load-bearing. Promising deterministic replay and delivering approximate
reconstruction would undermine exactly the trust the audit trail exists to build.

### Event Ordering Under Concurrency

Event sources are genuinely concurrent: OS filesystem events, MCP notifications, timers, and
multiple frontends all publish independently. Ordering is established at publication, not at
occurrence:

- Each project's bus assigns a monotonically increasing `sequence` on publication.
- Delivery to subscribers within a project is FIFO by `sequence`.
- Subscribers may lag, but never observe events out of `sequence` order.
- Effects are not ordered by delivery. A subscriber that writes to SQLite hops to the `io`
  dispatcher; ordering guarantees cover delivery, not completion (RFC-0007).

## Data Model

### Event (Core)

```
Event {
  id: UUID                          # Unique identifier
  type: EventType                   # eg. UserCommand, FileModified, etc.
  timestamp: Timestamp              # Wall-clock time of the event
  logical_timestamp: UInt64         # Monotonic ordering
  source: String                    # Subsystem that generated the event
  topic: String?                    # Hierarchical topic for filtering
  payload: JSON                     # Event-specific data
  causality: UUID?                  # ID of the causative event (if any)
  metadata: JSON?                   # Implementation metadata
}
```

### Subscription

```
Subscription {
  id: UUID
  subscriber_id: UUID               # Session, subsystem, or frontend
  topic_patterns: List<String>      # eg. ["filesystem:/project/**", "git:*"]
  event_types: List<EventType>?     # Filter by type (optional)
  since: Timestamp?                 # Start from this time (for catchup)
  is_persistent: Boolean            # Survive runtime restart?
}
```

## Security

Events can contain sensitive information (file paths, command outputs, API responses). Security considerations:

- **Access Control**: Enforced by the `visibility` field and the `event:subscribe` capability
  described above, not by convention. The claim "a session cannot read another session's
  events" is only true because subscriptions are authorized; without that mechanism it was
  false, since topic patterns admit `*`.
- **Payloads carry references, not bulk content.** Tool output, model responses, and file
  contents are referenced by ID and fetched through capability-checked reads.
- **Logging of Secrets**: Events must not contain unencrypted secrets. Payloads pass through
  the redactor (RFC-0035) before persistence, and `SECRET`-labelled content (RFC-0024) is never
  admitted to a payload.
- **Audit Trail**: Event headers are append-only and permanent. Payloads may be compacted per
  RFC-0056; a compaction never removes the record that the event occurred.

Permission events (PermissionRequested, PermissionGranted, PermissionDenied) are especially sensitive and auditable.

## MVP

The MVP Event Bus includes:

1. **Event Model**: Unique IDs, timestamps, types, sources, topics, payloads.
2. **Event Types**: UserCommand, TimerFired, FileModified/Created/Deleted, GitCommit, ToolCompleted, PermissionRequested/Granted/Denied, SessionWoken/Sleeping, ArtifactCreated, Error.
3. **Persistence**: Events stored in SQLite, ordered by timestamp.
4. **Subscriptions**: Sessions and subsystems can subscribe to topic patterns and event types.
5. **Replay**: Events can be queried by time range and topic.
6. **Causality Tracking**: Events link to causative events.
7. **Ordering**: Events processed in wall-clock order (or logical order for concurrent sources).

The MVP does not include:
- Distributed event bus (events span multiple machines).
- Complex event processing or aggregation.
- Event stream archival or hot/cold storage.
- Event compression or deduplication.

## Future Work

### Real-Time Event Streaming

The MVP uses request/response. Future versions should support streaming subscriptions where the client receives events as they occur (via WebSocket, gRPC streaming, or similar).

### Event Compression

For long-running projects with millions of events, compression could reduce storage:
- Aggregate low-level events (100 FileModified events → 1 DirectoryScanned event).
- Summarize causality chains (verbose trace → high-level outcome).

### Distributed Events

When Aidos spans multiple machines:
- Events originate from different machines but are unified in a single logical order.
- Use logical clocks or vector clocks to order events across machines.
- Preserve causal ordering across machines; audit reconstruction remains the goal, not
  deterministic replay.

### Event Semantics Versioning

As event types evolve, old events must remain interpretable. Event versioning ensures backward compatibility:

```
Event(
  type: "FileModified",
  version: 1,
  payload: { path: "...", size: 123 }
)
```

### Complex Event Processing

Support rules like:
- "If FileModified AND ToolCompleted(successful) within 5 minutes, then notify."
- "Pattern: FileModified → GitCommit → TestsPass"

### Time-Travel Debugging

Build UI to step through event history and inspect system state at any point in time.

### Event Source Plugins

Allow external systems to generate events:
- Calendar events ("it's 9 AM").
- Email events ("you got a message").
- Custom webhooks.

These flow through the Event Bus alongside system events.
