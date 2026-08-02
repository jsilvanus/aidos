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
    val error: String?,                // if FAILED, why
    val artifactIds: List<UUID>,       // artifacts produced during this run
    val taskIds: List<UUID>,           // tasks in execution order
    val userMessageSummary: String?,   // short summary of what the user asked
    val retryPolicy: RetryPolicy
)

enum class RunState {
    PENDING,      // Created, not yet dispatched
    RUNNING,      // Session is actively processing
    YIELDED,      // Waiting for async operation (AI call, tool call, approval)
    COMPLETED,    // All tasks completed successfully
    FAILED,       // One or more tasks failed unrecoverably
    CANCELLED,    // User cancelled the run
    INTERRUPTED   // Process terminated unexpectedly during this run
}
```

### Task

A Task is a discrete unit of work within a Run. Tasks correspond to individual AI calls, tool invocations, or structured sub-operations. A Task has one or more Attempts (due to retries).

```kotlin
data class Task(
    val id: UUID,
    val runId: UUID,
    val sessionId: UUID,
    val projectId: UUID,
    val kind: TaskKind,
    val description: String,           // human-readable, e.g. "Read /project/src/auth.kt"
    val toolName: String?,             // if kind == TOOL_CALL
    val modelCapability: String?,      // if kind == MODEL_CALL, e.g. "claude-opus/chat"
    val inputRef: UUID?,               // artifact or resource used as input
    val outputRef: UUID?,              // artifact produced as output
    val state: TaskState,
    val startedAt: Instant,
    val endedAt: Instant?,
    val attemptIds: List<UUID>,
    val currentAttemptId: UUID?,
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

data class AttemptError(
    val code: ErrorCode,
    val message: String,
    val isRetryable: Boolean,
    val category: ErrorCategory
)

enum class ErrorCategory {
    CAPABILITY_DENIED,
    TOOL_ERROR,
    MODEL_ERROR,
    TIMEOUT,
    CANCELLED,
    UNKNOWN
}
```

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
    CONTAINS,          // Run CONTAINS Task; Task CONTAINS Attempt
    PRODUCED,          // Attempt PRODUCED Artifact
    CONSUMED,          // Task CONSUMED Artifact (used as input)
    TRIGGERED_BY,      // Run TRIGGERED_BY Event
    IMPLEMENTS,        // Run IMPLEMENTS IntentNode (links execution to intent)
    RETRY_OF,          // Attempt RETRY_OF prior Attempt
    CANCELLED_BY,      // Attempt CANCELLED_BY event or user action
    DEPENDS_ON         // Task DEPENDS_ON Task (ordering within a run)
}
```

### Query Model

The Execution Graph supports the following key queries:

**"What happened during this Run?"**
```sql
SELECT t.id, t.kind, t.description, t.state, a.attempt_number, a.state, a.error_json
FROM tasks t
JOIN attempts a ON a.task_id = t.id
WHERE t.run_id = ?
ORDER BY t.started_at, a.attempt_number;
```

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
    error TEXT,
    artifact_ids TEXT NOT NULL DEFAULT '[]',  -- JSON array
    user_message_summary TEXT,
    retry_policy_json TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES sessions(id)
);

CREATE TABLE tasks (
    id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    description TEXT NOT NULL,
    tool_name TEXT,
    model_capability TEXT,
    input_ref TEXT,
    output_ref TEXT,
    state TEXT NOT NULL,
    started_at TEXT NOT NULL,
    ended_at TEXT,
    retry_policy_json TEXT NOT NULL,
    FOREIGN KEY (run_id) REFERENCES runs(id)
);

CREATE TABLE attempts (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL,
    attempt_number INTEGER NOT NULL,
    started_at TEXT NOT NULL,
    ended_at TEXT,
    state TEXT NOT NULL,
    error_json TEXT,
    input_snapshot TEXT,
    output_snapshot TEXT,
    model_provider TEXT,
    model_version TEXT,
    tokens_input INTEGER,
    tokens_output INTEGER,
    tool_result TEXT,
    capability_id TEXT,
    audit_ref TEXT NOT NULL,
    FOREIGN KEY (task_id) REFERENCES tasks(id)
);

CREATE TABLE execution_edges (
    id TEXT PRIMARY KEY,
    from_node_id TEXT NOT NULL,
    from_node_kind TEXT NOT NULL,
    to_node_id TEXT NOT NULL,
    to_node_kind TEXT NOT NULL,
    edge_kind TEXT NOT NULL
);

CREATE INDEX idx_runs_session ON runs(session_id);
CREATE INDEX idx_tasks_run ON tasks(run_id);
CREATE INDEX idx_attempts_task ON attempts(task_id);
CREATE INDEX idx_edges_from ON execution_edges(from_node_id, edge_kind);
CREATE INDEX idx_edges_to ON execution_edges(to_node_id, edge_kind);
```

## Security

Execution Graph records are sensitive: they reveal the chain of capabilities exercised and the exact operations performed. Access is subject to the same audit and access controls as the rest of the project state.

The `input_snapshot` and `output_snapshot` fields must not include raw secret values. Tool results that contain secret material must be redacted before storage.

Attempt records are append-only: they may have their state updated but their content fields are immutable once written.

## MVP

The MVP implements:

1. Run, Task, and Attempt tables with state machines as defined.
2. `CONTAINS`, `PRODUCED`, `TRIGGERED_BY`, and `RETRY_OF` edge types.
3. Basic retry logic for tool calls (max 3 attempts, no backoff).
4. Query: "what happened during this Run?" (for the CLI audit display).
5. Query: "what produced this Artifact?" (for artifact provenance).
6. `tokens_input` and `tokens_output` tracking per Attempt.

The MVP does not implement:
- `IMPLEMENTS` edges (requires Intent Graph integration).
- `DEPENDS_ON` edges (requires multi-task planning within a session).
- Configurable retry policies (fixed retry count only).
- Compensation graphs.

## Future Work

`DEPENDS_ON` edges enabling parallel task execution within a Run (requires concurrent session execution model).

Compensation graphs: when a run fails mid-way, a compensation run can undo partial work.

Execution Graph visualization: a frontend component that renders the graph as a tree or timeline.

Cross-session provenance: tracking artifacts that are used as input to sessions that did not produce them.
