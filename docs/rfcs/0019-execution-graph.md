# RFC-0019: Execution Graph

Status: Draft

## Abstract

This RFC defines the Execution Graph: the operational record of how work is executed within sessions. It introduces Run, Task, Attempt, and Edge as concrete types with defined state machines, edge semantics, retry policies, and query models. The Execution Graph is the persistent record that links user intent to produced artifacts through a chain of AI reasoning and tool invocations.

## Motivation

The architecture distinguishes intent (what should be done, RFC-0012 Intent Graph) from execution (how it was done). RFC-0006 (Session Execution Contract) defines the runtime semantics of execution. This RFC defines the persistent data model that records execution for audit, debugging, and provenance.

Without a concrete Execution Graph schema:
- There is no way to answer "what exactly did the AI do to produce this file?"
- Retry and failure handling are implemented inconsistently across sessions.
- The audit trail cannot reconstruct the causal chain from a user request to a final artifact.
- Tool call sequences cannot be displayed in a frontend without custom session-level ad-hoc logic.

## Goals

1. Define the data model for Run, Task, Attempt, and Edge.
2. Define state machines for each node type.
3. Define edge types and their semantics.
4. Define retry policy representation.
5. Define the query model for frontends and audit.
6. Connect execution nodes to intent nodes and artifacts.

## Non-goals

This RFC does not define scheduling policies (RFC-0005).
It does not define session execution semantics (RFC-0006).
It does not define the audit log schema (the audit log is separate; the Execution Graph references audit log entries).
It does not define compensation protocols (future work).

## Design

### Conceptual Model

The Execution Graph is a directed acyclic graph (DAG) where:
- **Nodes** represent units of work: Runs, Tasks, and Attempts.
- **Edges** represent causal and dependency relationships between nodes.

The graph is project-scoped and stored in SQLite. It is operational state, not user-facing state — users see summaries, not raw graph nodes.

```
User Request (event)
    │
    ▼
Run (top-level execution for a user request)
    │
    ├── Task: "read auth.kt"       [Tool: filesystem:read]
    │       └── Attempt 1: SUCCESS
    │
    ├── Task: "AI: analyze auth"   [Model: claude-opus]
    │       └── Attempt 1: SUCCESS → produced AI response
    │
    ├── Task: "write auth.kt"      [Tool: filesystem:write]
    │       ├── Attempt 1: FAILED (capability denied)
    │       └── Attempt 2: SUCCESS (after capability grant)
    │
    └── Task: "git commit"         [Tool: git:write]
            └── Attempt 1: SUCCESS → produced Artifact: patch-123
```

### Run

A Run is the top-level unit of execution, created by the Session Execution Contract when a session wakes in response to an event. A Run contains one or more Tasks.

This is the **only** definition of `Run`. RFC-0006 previously carried a second, incompatible
one; it now references this.

```kotlin
data class Run(
    val id: UUID,
    val sessionId: UUID,
    val projectId: UUID,
    val triggerEventId: UUID,          // the event that initiated this run
    val intentNodeId: UUID?,           // optional link to the Intent Graph
    val startedAt: Instant,
    val endedAt: Instant?,
    val state: RunState,
    val error: AidosError?,            // RFC-0029
    val userMessageSummary: String?,   // short summary of what the user asked
    val retryPolicy: RetryPolicy,
    val stepIndex: Int,                // RFC-0009 executor position
    val maxSteps: Int,                 // RFC-0008 termination ceiling
    val taintLevel: TrustLevel,        // RFC-0027, monotonic within the Run
    val platformProfile: PlatformProfile  // RFC-0049, recorded for provenance
)

enum class RunState {
    PENDING,      // Created, not yet dispatched
    RUNNING,      // Session is actively processing
    YIELDED,      // Waiting for something that may outlive the process (RFC-0006)
    COMPLETED,    // All tasks completed successfully
    FAILED,       // One or more tasks failed unrecoverably
    CANCELLED,    // User cancelled the run
    INTERRUPTED   // Process terminated unexpectedly during this run
}
```

**`Run` no longer carries `artifactIds` or `taskIds`.** Both were denormalized JSON arrays
duplicating relationships that foreign keys already express, and they created the possibility
of disagreement with the authoritative rows. Tasks are found by `tasks.run_id ORDER BY ordinal`;
produced artifacts are found through `PRODUCED` edges. See "One fact, one place" below.

The composition rules between `RunState` and `TaskState` are defined in RFC-0006.

### Task

A Task is a discrete unit of work within a Run. Tasks correspond to individual AI calls, tool invocations, or structured sub-operations. A Task has one or more Attempts (due to retries).

```kotlin
data class Task(
    val id: UUID,
    val runId: UUID,
    val sessionId: UUID,
    val projectId: UUID,
    val ordinal: Int,                  // execution order within the Run
    val kind: TaskKind,
    val description: String,           // human-readable, e.g. "Read src/auth.kt"
    val toolName: String?,             // if kind == TOOL_CALL
    val modelCapability: String?,      // if kind == MODEL_CALL
    val state: TaskState,
    val startedAt: Instant?,           // null while PENDING
    val endedAt: Instant?,
    val awaitingRunId: UUID?,          // if parked on a child session's Run (RFC-0006)
    val retryPolicy: RetryPolicy
)

enum class TaskKind {
    MODEL_CALL,         // AI model invocation
    TOOL_CALL,          // Tool Broker invocation
    CAPABILITY_REQUEST, // Waiting for user to approve a capability
    USER_PROMPT,        // Waiting for user input mid-run
    COMPOSITE           // A group of sub-tasks (for structured tool sequences)
}

enum class TaskState {
    PENDING,
    RUNNING,
    AWAITING_APPROVAL,  // Waiting for capability grant or user confirmation
    AWAITING_INPUT,     // Waiting for user response
    COMPLETED,
    FAILED,
    CANCELLED,
    SKIPPED             // Not executed because a preceding task failed
}
```

### Attempt

An Attempt is a single execution of a Task. If a Task is retried, it has multiple Attempts.

```kotlin
data class Attempt(
    val id: UUID,
    val taskId: UUID,
    val attemptNumber: Int,            // 1-based
    val startedAt: Instant,
    val endedAt: Instant?,
    val state: AttemptState,
    val error: AttemptError?,
    val inputSnapshot: String?,        // JSON snapshot of inputs at attempt start
    val outputSnapshot: String?,       // JSON snapshot of output (for audit)
    val modelProvider: String?,        // if a model call: which provider was used
    val modelVersion: String?,         // if a model call: which model version
    val tokensInput: Int?,
    val tokensOutput: Int?,
    val toolResult: String?,           // if a tool call: structured result summary
    val capabilityId: UUID?,           // the capability exercised (if any)
    val auditRef: UUID                 // audit log entry for this attempt
)

enum class AttemptState {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

// Errors are AidosError (RFC-0029). `AttemptError` and the local ErrorCategory enum are
// removed: they duplicated RFC-0052's ErrorCode over the same domain with different members,
// and retryability is a property of the error class, not a per-site boolean.
```

Retry decisions read the error's **class** (RFC-0029): `TRANSIENT` and `RATE_LIMITED` retry,
everything else does not. `retryOnCategories` in `RetryPolicy` below is therefore a set of
error *classes*.

**Retry safety.** A Task may only be retried if its effect's `recovery_class` (RFC-0009) is
`PURE`, `IDEMPOTENT`, or `CHECKABLE`. Retrying an `UNSAFE` effect is forbidden regardless of
policy — an interrupted `git push` or outbound notification must never be silently repeated.

### Retry Policy

```kotlin
data class RetryPolicy(
    val maxAttempts: Int,              // default: 3 for tool calls, 1 for model calls
    val retryOnCategories: Set<ErrorCategory>,  // only retry these error categories
    val backoffStrategy: BackoffStrategy
)

sealed class BackoffStrategy {
    object None : BackoffStrategy()
    data class Fixed(val delaySeconds: Int) : BackoffStrategy()
    data class Exponential(val initialDelaySeconds: Int, val maxDelaySeconds: Int) : BackoffStrategy()
}
```

### Edges

Edges represent relationships between nodes in the Execution Graph.

```kotlin
data class ExecutionEdge(
    val id: UUID,
    val fromNodeId: UUID,
    val fromNodeKind: NodeKind,
    val toNodeId: UUID,
    val toNodeKind: NodeKind,
    val edgeKind: EdgeKind
)

enum class NodeKind { RUN, TASK, ATTEMPT, ARTIFACT, INTENT_NODE }

enum class EdgeKind {
    PRODUCED,          // Attempt PRODUCED ContentNode
    CONSUMED,          // Task CONSUMED ContentNode (used as input)
    IMPLEMENTS,        // Run IMPLEMENTS IntentNode (links execution to intent)
    RETRY_OF,          // Attempt RETRY_OF prior Attempt
    PRODUCED_CALL,     // MODEL_CALL Task PRODUCED_CALL the TOOL_CALL Tasks it emitted (RFC-0008)
    DEPENDS_ON         // Task DEPENDS_ON Task (explicit ordering beyond `ordinal`)
}
```

### One fact, one place

`CONTAINS`, `TRIGGERED_BY`, and `CANCELLED_BY` are removed. Each duplicated something a column
already stated: containment is `tasks.run_id` and `attempts.task_id`, triggering is
`runs.trigger_event_id`, and cancellation is the Attempt's state plus its audit record.

Provenance was previously representable in four places at once — `runs.artifact_ids`,
`tasks.output_ref`, `PRODUCED` edges here, and `provenance_edges` in RFC-0024 — with nothing
reconciling them. The rule now:

| Fact | Single source |
|---|---|
| Which Tasks a Run contains | `tasks.run_id`, ordered by `tasks.ordinal` |
| Which Attempts a Task contains | `attempts.task_id`, ordered by `attempt_number` |
| Which content an Attempt produced | `execution_edges` with `PRODUCED` |
| How content derives from other content | `provenance_edges` (RFC-0024) |
| Which event triggered a Run | `runs.trigger_event_id` |

`execution_edges` records execution→content facts; `provenance_edges` records content→content
facts. They answer different questions and neither is derivable from the other.

### Query Model

The Execution Graph supports the following key queries:

**"What happened during this Run?"**
```sql
SELECT t.id, t.kind, t.description, t.state,
       a.attempt_number, a.state, a.error_code, a.error_class
FROM tasks t
LEFT JOIN attempts a ON a.task_id = t.id
WHERE t.run_id = ?
ORDER BY t.ordinal, a.attempt_number;
```

`LEFT JOIN` and `ORDER BY ordinal`: an inner join hides `PENDING` and `SKIPPED` Tasks, which
have no Attempts and are exactly what the user needs to see when a Run failed partway.
Ordering by `started_at` would drop unstarted Tasks out of position.

**"What produced this Artifact?"**
```sql
SELECT a.id, t.description, t.kind, r.user_message_summary
FROM attempts a
JOIN execution_edges e ON e.from_node_id = a.id AND e.edge_kind = 'PRODUCED'
JOIN tasks t ON t.id = a.task_id
JOIN runs r ON r.id = t.run_id
WHERE e.to_node_id = ?;
```

**"What did the AI do to implement this intent?"**
```sql
SELECT r.id, r.started_at, r.state, r.user_message_summary
FROM runs r
JOIN execution_edges e ON e.from_node_id = r.id AND e.edge_kind = 'IMPLEMENTS'
WHERE e.to_node_id = ?
ORDER BY r.started_at;
```

**"Show me all failed runs in this session"**
```sql
SELECT r.*, ae.message as error
FROM runs r
WHERE r.session_id = ? AND r.state = 'FAILED'
ORDER BY r.started_at DESC;
```

## Data Model

```sql
CREATE TABLE runs (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    trigger_event_id TEXT NOT NULL,
    intent_node_id TEXT,
    started_at TEXT NOT NULL,
    ended_at TEXT,
    state TEXT NOT NULL,
    error_code TEXT,                                  -- RFC-0029
    error_class TEXT,
    error_detail_json TEXT,
    user_message_summary TEXT,
    retry_policy_json TEXT NOT NULL,
    step_index INTEGER NOT NULL DEFAULT 0,            -- RFC-0009
    max_steps INTEGER NOT NULL DEFAULT 24,            -- RFC-0008
    taint_level TEXT NOT NULL DEFAULT 'TRUSTED',      -- RFC-0027
    taint_source_node_id TEXT,
    platform_profile TEXT NOT NULL,                   -- RFC-0049
    network_available INTEGER NOT NULL DEFAULT 0,
    degraded_tools TEXT NOT NULL DEFAULT '[]',
    row_version INTEGER NOT NULL DEFAULT 1,
    FOREIGN KEY (session_id) REFERENCES sessions(id),
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

CREATE TABLE tasks (
    id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL,                         -- execution order within the Run
    kind TEXT NOT NULL,
    description TEXT NOT NULL,
    tool_name TEXT,
    model_capability TEXT,
    state TEXT NOT NULL,
    started_at TEXT,
    ended_at TEXT,
    awaiting_run_id TEXT,                             -- if parked on a child Run (RFC-0006)
    retry_policy_json TEXT NOT NULL,
    row_version INTEGER NOT NULL DEFAULT 1,
    FOREIGN KEY (run_id) REFERENCES runs(id),
    UNIQUE (run_id, ordinal)
);

CREATE TABLE attempts (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL,
    attempt_number INTEGER NOT NULL,
    started_at TEXT NOT NULL,
    ended_at TEXT,
    state TEXT NOT NULL,
    error_code TEXT,                                  -- RFC-0029
    error_class TEXT,
    error_detail_json TEXT,
    input_snapshot TEXT,                              -- AGED, compactable (RFC-0056)
    output_snapshot TEXT,                             -- AGED, compactable
    prompt_package_json TEXT,                         -- RFC-0025, AGED
    model_provider TEXT,
    model_version TEXT,
    tokens_input INTEGER,
    tokens_output INTEGER,
    cost_units INTEGER,                               -- RFC-0028
    capability_id TEXT,                               -- the authority actually exercised
    idempotency_key TEXT,                             -- RFC-0009
    recovery_class TEXT NOT NULL DEFAULT 'PURE',      -- RFC-0009
    audit_ref TEXT NOT NULL,
    FOREIGN KEY (task_id) REFERENCES tasks(id),
    FOREIGN KEY (capability_id) REFERENCES capabilities(id),
    UNIQUE (task_id, attempt_number)
);

-- Execution → content facts only. Content → content lineage is provenance_edges (RFC-0024).
CREATE TABLE execution_edges (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL,
    from_node_id TEXT NOT NULL,
    from_node_kind TEXT NOT NULL,
    to_node_id TEXT NOT NULL,
    to_node_kind TEXT NOT NULL,
    edge_kind TEXT NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id),
    UNIQUE (from_node_id, to_node_id, edge_kind)
);

CREATE INDEX idx_runs_session ON runs(session_id, started_at DESC);
CREATE INDEX idx_runs_state ON runs(project_id, state);
CREATE INDEX idx_tasks_run ON tasks(run_id, ordinal);
CREATE INDEX idx_tasks_runnable ON tasks(run_id, state, ordinal);
CREATE INDEX idx_attempts_task ON attempts(task_id, attempt_number);
CREATE INDEX idx_attempts_running ON attempts(state) WHERE state = 'RUNNING';
CREATE INDEX idx_edges_from ON execution_edges(from_node_id, edge_kind);
CREATE INDEX idx_edges_to ON execution_edges(to_node_id, edge_kind);
```

`execution_edges` spans heterogeneous node kinds, so SQLite cannot enforce referential
integrity on it. `project_id` and the uniqueness constraint bound the damage; a consistency
check (RFC-0038) verifies that every edge endpoint resolves. This is a known and accepted
limitation of a generic edge table, which is why containment was moved to real foreign keys
rather than being expressed here.

## Security

Execution Graph records are sensitive: they reveal the chain of capabilities exercised and the exact operations performed. Access is subject to the same audit and access controls as the rest of the project state.

The `input_snapshot` and `output_snapshot` fields must not include raw secret values. Tool results that contain secret material must be redacted before storage.

Attempt records are append-only: they may have their state updated but their content fields are immutable once written.

## MVP

The MVP implements:

1. Run, Task, and Attempt tables with the state machines and composition rules (RFC-0006).
2. `PRODUCED`, `PRODUCED_CALL`, and `RETRY_OF` edge types.
3. Retry driven by error class (RFC-0029) and gated by recovery class (RFC-0009); max 3
   attempts, no backoff.
4. Query: "what happened during this Run?" (CLI audit display).
5. Query: "what produced this content?" (provenance).
6. Per-Attempt `tokens_input`, `tokens_output`, `cost_units`, and `capability_id`.
7. Per-Run `taint_level` and `platform_profile` recording.

The MVP does not implement:
- `IMPLEMENTS` edges (requires Intent Graph integration).
- `DEPENDS_ON` edges and parallel task execution.
- Configurable retry policies (fixed retry count only).
- Compensation graphs.

## Future Work

`DEPENDS_ON` edges enabling parallel task execution within a Run (requires concurrent session execution model).

Compensation graphs: when a run fails mid-way, a compensation run can undo partial work.

Execution Graph visualization: a frontend component that renders the graph as a tree or timeline.

Cross-session provenance: tracking artifacts that are used as input to sessions that did not produce them.
