# RFC-0011: Sessions

Status: Draft — body not audited against settled decisions (see docs/decisions.md)

## Abstract

A Session is a long-lived worker within a project that performs AI-augmented work. Sessions are not conversations; they are persistent, pausable actors with identity, state, capabilities, and memory. Sessions wake in response to events, perform work (querying AI, calling tools, creating artifacts, updating the Intent Graph), and then sleep. Multiple sessions can exist within a project, coordinating through artifacts and shared state. The Session model is the foundation of Aidos's event-driven, asynchronous execution model.

## Motivation

Existing AI tools treat interaction as stateless conversation: user sends message, AI responds, conversation ends. This model is suitable for chatbots but unsuitable for multi-step, long-running work.

Aidos needs a different model: long-lived, pausable workers that:

- **Persist across time**: A session remains even after the user closes the app. It can resume later.
- **Maintain context**: A session remembers what it was doing, what it learned, what constraints apply.
- **Operate independently**: Sessions work asynchronously. One session can be running while others sleep.
- **Coordinate**: Multiple sessions within a project can work toward a shared goal by coordinating through artifacts and events.
- **Be interrupted**: A session can be paused gracefully and resumed later without losing state.
- **Be audited**: Every action a session takes is logged and auditable.

The session model solves these requirements. It is inspired by:

- **Unix processes**: Long-lived entities with identity, state, and permissions.
- **Actors in actor systems**: Isolated units of work that communicate via message passing.
- **Git branches**: Parallel lines of work that can be coordinated and merged.
- **Threads in collaborative documents**: Multiple writers working on a document concurrently.

## Goals

1. **Define session semantics**: What is a session? What distinguishes a session from a conversation?

2. **Establish session identity and lifecycle**: How are sessions created, activated, paused, and archived?

3. **Specify session roles**: What are the different types of sessions (driver, worker)?

4. **Define session capabilities and permissions**: What permissions do sessions have? How are they granted?

5. **Establish session coordination mechanisms**: How do sessions communicate and share state?

6. **Clarify session isolation and scheduling**: How are sessions isolated from each other? How are they scheduled?

7. **Explain session memory and state**: What is a session's state? How is it persisted?

## Non-goals

This RFC does not specify the exact format or structure of session state. That is implementation detail.

This RFC does not mandate specific session roles or templates. The MVP has basic sessions; future work can introduce specialized roles.

This RFC does not address multi-user session permissions. Single-user is the design assumption.

This RFC does not specify how sessions log their work (that is the artifact and event system).

This RFC does not define session inter-process communication details. The coordination model is event-based; the mechanics are separate.

## Design

### What Is a Session?

A **Session** is a persistent, event-driven worker within a project. It is an execution context for AI-augmented work. A session has:

- **Identity**: A unique ID, name, and metadata.
- **Role**: Driver (coordinates work) or Worker (performs isolated tasks).
- **Capabilities**: Permissions granted by the user (filesystem, shell, model access, etc., from RFC-0003).
- **Memory**: Context, conversation history, local variables, learned facts.
- **State**: Whether it is sleeping, woken, running, yielding, or archived.
- **Mailbox**: Incoming events it is subscribed to.
- **Artifacts**: Outputs it has created.

A session is not a conversation. Conversation is ephemeral; the session is persistent. Conversation is stateless; the session maintains state. Conversation is interactive; the session is asynchronous.

### Session Roles

#### Driver Session

A driver session coordinates work within a project. It:

- Listens to user commands and events.
- Maintains awareness of the overall project goal (Intent Graph).
- Creates worker sessions to perform isolated tasks.
- Coordinates workers through events and artifacts.
- Updates the Intent Graph based on progress.

Example: A main coding session that listens for user commands, creates worker sessions for specific tasks (write tests, refactor, etc.), and coordinates their work.

#### Worker Session

A worker session performs a specific, isolated task:

- It is created by a driver session (with permission).
- It has a narrow, well-defined scope (e.g., "write unit tests for this module").
- It performs work and produces artifacts.
- It publishes results to its mailbox.
- When finished, it archives itself.

Example: A test-writing worker created by the driver to write tests for a specific file. It builds its commit directly against the object database on `refs/aidos/workers/<id>` — no second checkout (RFC-0053) — and publishes the test file artifact.

### Session Lifecycle

#### Creation

A session is created by:

1. User request (via frontend): "Create a session named 'Feature Development'".
2. Another session: A driver session creates a worker session: "Create a session to write tests".
3. System: Automatic sessions (future) for background tasks.

Creation involves:

```
Allocate session ID
Assign name, role, description
Initialize capabilities (from project defaults or explicit grants)
Initialize empty memory/context
Set initial state to "sleeping"
Register subscriptions (driver listens to user events; worker listens to completion signals)
Store session record
```

#### Activation (Wake)

A session wakes when:

1. **User command**: User interacts with this session via frontend.
2. **Event subscription**: An event matching the session's subscriptions arrives.
3. **Scheduled task**: A timer for this session fires.
4. **Dependency completion**: A worker this session depends on completes.

When a session wakes:

```
Load session state from storage
Load mailbox (pending events)
Assess context (what has changed since sleep?)
Decide what to do
Execute (run AI queries, call tools, create artifacts)
Optionally yield (wait for external work)
```

#### Execution

While running, a session:

- Queries AI services (subject to permissions and rate limits).
- Calls tools (filesystem, shell, Git) via the Tool Broker.
- Reads and may modify the Intent Graph.
- Publishes artifacts.
- Generates events.
- Logs decisions and reasoning.

Execution is subject to:

- **Timeouts**: Max execution time per run (e.g., 5 minutes).
- **Resource limits**: Max memory, max events generated.
- **Permission checks**: Every tool access is checked.

#### Yielding

A session yields when it is waiting for external work:

- AI model is responding (async call).
- Tool is executing (shell command, API call).
- Waiting for another session or event.

While yielding:

- The session remains in memory.
- It does not consume CPU (it is waiting).
- It can be interrupted (though this may abort the external work).
- When the external work completes, it wakes again.

#### Completion

A session completes when its work is done:

- For a worker: The assigned task is finished.
- For a driver: The user marks it as complete or it reaches a natural stopping point.

On completion:

```
Finalize artifacts
Update Intent Graph (mark goal as achieved)
Log completion
Persist final state
Publish completion event
Archive session (move to "finished" state)
Release resources
```

#### Archival

An archived session is:

- Not active (no longer woken by events).
- Preserved (all state, artifacts, logs are kept).
- Queryable (user can review what the session did).
- Replayable (session can be re-run with a different prompt or context).

Archived sessions are the audit trail of the project. They record who did what, when, and why.

### Session State

A session maintains state divided into:

#### Persistent State

Stored in the project database:

```
Session Persistent State {
  id: UUID
  project_id: UUID
  name: String
  role: SessionRole (driver | worker)
  description: String?

  created_at: Timestamp
  last_active: Timestamp
  archived_at: Timestamp?
  state: SessionState              # CREATED | SLEEPING | RUNNING | ARCHIVED (RFC-0017)

  parent_session: SessionId?       # If worker, who created it
}
```

Three things are deliberately **not** fields on the session record:

**Capabilities.** Authority lives in the `capabilities` table keyed by `subject_id`
(RFC-0018). An embedded `CapabilitySet` would be a second source of truth for security state,
and the two could disagree after a revocation.

**Artifacts.** Found by querying content nodes and `PRODUCED` edges. A growing list column would
be rewritten on every artifact creation.

**Memory as one blob.** See below.

### Session memory

Memory is bounded and queryable, not an ever-growing list rewritten on each wake.

```
session_memory_entries
  id, session_id, kind, content, tokens, created_at, superseded_by, taint_level
```

| Kind | Meaning | Bound |
|---|---|---|
| `SUMMARY` | rolled-up summary of prior Runs | one active per session |
| `FACT` | a durable learned fact | soft cap, LRU eviction |
| `DECISION` | a choice made and its reason | uncapped; these are small and valuable |
| `TASK_STATE` | current work state | one active per session |

**Raw conversation turns are not session memory.** They belong to a Run's transcript
(RFC-0008), which is `AGED` and compacted (RFC-0056). When a Run completes, its transcript is
summarized into a `SUMMARY` entry and the raw turns age out.

This closes the highest-probability debt identified in review: an unbounded
`conversation_history: List<Message>` inside the session row would grow without limit, be
rewritten wholesale on every wake, degrade AI quality as it filled the context window, and be
unqueryable. The rolling summary keeps context useful and cost bounded, and it is enforced by
the schema rather than by a note recommending periodic cleanup.

Memory entries carry `taint_level` (RFC-0027): a summary of a tainted Run taints the Run that
includes it.

#### Ephemeral State

Held in memory while running:

- Loaded context from resources and knowledge.
- Current AI model responses.
- In-flight tool calls.
- Intermediate computations.

This is discarded when the session yields or completes.

### Session Permissions and Capabilities

Sessions operate under explicit permissions (RFC-0003). Permissions are granted per session:

```
Session S1:
  Capabilities:
    - fs:read:/project
    - fs:write:/project/src
    - git:read (any branch)
    - git:write:feature/*, git:write:main
    - shell:exec (timeout=60s, pwd=/project)
    - model:query (Claude API only)
    - worker:create

Session S2 (worker):
  Capabilities:
    - fs:read:/project/tests
    - fs:write:/project/tests
    - shell:exec (timeout=30s, pwd=/project/tests)
    - git:read
```

Permissions can be:

- **Granted by user**: Explicit user action grants permission.
- **Default**: Project configuration specifies defaults per session role.
- **Delegated**: A session delegates a capability to a worker it creates.
- **Requested**: A session requests permission for an operation (user is prompted).

### Driver Orchestration

The driver/worker model is what distinguishes Aidos from a chat loop, so its mechanics are
specified here rather than left to convention.

#### The cycle

```
Driver Run
  Task 1  MODEL_CALL   decompose the goal        → proposes a plan (RFC-0019)
  Task 2  USER_PROMPT  approve the plan          ← YIELDED; may be hours
  Task 3  COMPOSITE    worker: schema            → spawns child Run, parks
  Task 4  COMPOSITE    worker: endpoints         DEPENDS_ON 3
  Task 5  COMPOSITE    worker: tests             DEPENDS_ON 3
  Task 6  MODEL_CALL   review and integrate      DEPENDS_ON 4, 5
```

Tasks 4 and 5 park simultaneously; their child Runs execute in parallel. The driver's Run has no
`RUNNING` Task while they work, so the one-running-Task invariant holds (RFC-0006) and the
driver's own audit trail stays a single ordered sequence.

A worker's completion event resumes the driver's `drive()` (RFC-0009). The driver may be resumed
on a different process, days later, on a different device.

#### Spawning a worker

Creating a worker is a capability (`worker:create`) and involves four things:

1. **A scoped brief** — what the worker is to accomplish, as its initial user message. Not the
   driver's whole context: a worker that inherits the driver's transcript inherits its taint and
   its token cost.
2. **Attenuated capabilities** (RFC-0018) — a strict subset of the driver's, narrowed to what the
   brief requires. A worker writing tests gets `fs:write` scoped to the test directory, not the
   whole project.
3. **A split budget** — this is the one most easily got wrong. Budget attenuates like any other
   constraint (RFC-0028): a driver holding 10,000 cost units delegating to three workers
   **divides** that allowance. It does not multiply it. Without this rule, fan-out is an
   unbounded spend multiplier, and orchestration becomes the most expensive way to use the
   product.
4. **An isolation mechanism** — **treeless, on every profile** (RFC-0053). A worker builds its
   commit against the object database on `refs/aidos/workers/<id>` and never touches a working
   tree. There is exactly one kind of worker; nothing chooses between mechanisms, because a
   choice would mean two code paths through the component that writes the user's history.

#### What a worker returns

A worker produces a **commit**, not a patch in a message and not a set of edits to the shared
working tree. The driver reviews a diff, and the user can review the same diff independently.

The driver then merges, cherry-picks, requests changes, or discards. Nothing a worker did
reaches the user's branch without the driver acting — and, for anything the user configured as
requiring approval, without the user acting.

#### Failure and partial success

Partial success is the normal case, not the exception. Three workers, two succeed:

- The driver sees per-worker outcomes and decides: integrate what succeeded, retry the failure
  with a revised brief, reassign, or stop and ask.
- A `FAILED` dependency marks dependents `SKIPPED` (RFC-0019) rather than running them against
  missing prerequisites.
- The driver's own Run does not fail merely because a worker did. Orchestration failure and task
  failure are different outcomes, and collapsing them loses the successful work.

If the **driver** terminates, its workers are cancelled and their delegated capabilities are
revoked recursively — which happens automatically, since delegated capabilities are children of
the driver's (RFC-0018). Work already committed to a worker ref survives as an artifact; only
the authority and the pending Runs end.

#### When not to orchestrate

The failure mode is over-orchestration: spawning workers for everything, multiplying cost and
producing coordination overhead larger than the work. Workers are for **isolation**, not speed:

| Use a worker when | Do it inline when |
|---|---|
| the work is independently reviewable | it is a few steps |
| it needs different capabilities | it needs the driver's context anyway |
| it would flood the driver's context | the result is immediately consumed |
| it can genuinely proceed in parallel | ordering is inherent |

On MOBILE the parallelism is real in structure but limited in practice: workers plan
concurrently, but their model calls queue on the device-global inference slot (RFC-0020). Five
workers on a phone is not five times faster, and the plan-approval step should say so.

### Session Coordination

Sessions within a project coordinate through:

#### Artifacts

A session publishes artifacts. Other sessions can read them and react.

Example:

```
Driver session creates artifact: "architectural_decision.md"
  ↓
Worker session 1 reads it, generates compatible code
Worker session 2 reads it, generates compatible tests
  ↓
Workers publish code and test artifacts
```

#### Events

Sessions publish events. Other sessions subscribe to events via topic filters.

Example:

```
Worker session publishes event: TestsCompleted(tests_passed=95, tests_failed=2)
Driver session is subscribed to events matching "worker:*:completed"
Driver wakes, reads the event, decides next steps
```

#### Shared Intent Graph

All sessions in a project can read and propose modifications to the Intent Graph (RFC-0012). The Intent Graph is the common frame of reference.

Example:

```
Driver maintains Intent Graph:
  Goal: Build web app
    - Subtask 1: Design API (status: done)
    - Subtask 2: Implement backend (status: in progress)
    - Subtask 3: Write tests (status: waiting)

Worker 1 notifies driver: "I completed backend"
Driver updates Intent Graph: Subtask 2 → done
Driver creates new worker for Subtask 3
```

#### Shared Resources

All sessions can read project resources (RFC-0013): architecture documents, coding standards, design decisions. Resources are the common knowledge base.

### Session Memory

A session's memory is its context: what it knows, what it has learned, what state it maintains. Memory includes:

- **Conversation history**: Messages exchanged with users or AI.
- **Learned facts**: Things the session discovered (file locations, API endpoints, design patterns).
- **Task state**: Current status of work (what's done, what's pending, blockers).
- **Variables**: Local state the session needs to remember.

Memory persists across wake/sleep cycles. When a session wakes, it reloads its memory. When it sleeps, memory is saved.

Memory is not unlimited. Sessions should periodically clean up or summarize old memory to prevent unbounded growth.

### Isolation and Scheduling

Sessions are isolated by the Scheduler (RFC-0005):

- **Namespace**: Session IDs are unique per project.
- **Storage**: Session state is stored separately.
- **Permissions**: Each session operates under its own capability set.
- **Execution**: Sessions run sequentially (one at a time) to avoid concurrency issues.
- **Failure**: Errors in one session do not affect others.

The Scheduler wakes sessions one at a time, based on events and fairness.

## Data Model

> **Schema note.** `schema/project.sql` is the canonical DDL. The block below is the same
> definition, reproduced here so this RFC is readable on its own; where the two ever differ,
> the schema file governs and this RFC is the bug.

```sql
CREATE TABLE sessions (
    id                   TEXT PRIMARY KEY,
    project_id           TEXT NOT NULL,
    name                 TEXT NOT NULL,
    role                 TEXT NOT NULL,               -- DRIVER | WORKER
    description          TEXT,
    state                TEXT NOT NULL,               -- CREATED|SLEEPING|RUNNING|ARCHIVED (RFC-0017)
    parent_session_id    TEXT,                        -- set for workers
    worker_ref           TEXT,                        -- refs/aidos/workers/<id> (RFC-0049)
    consecutive_failures INTEGER NOT NULL DEFAULT 0,  -- failure budget; 3 parks the session
    created_at           TEXT NOT NULL,
    last_active_at       TEXT NOT NULL,
    archived_at          TEXT,
    state_updated_at     TEXT NOT NULL,
    row_version          INTEGER NOT NULL DEFAULT 1,
    FOREIGN KEY (project_id)        REFERENCES projects(id),
    FOREIGN KEY (parent_session_id) REFERENCES sessions(id)
);

CREATE INDEX idx_sessions_project ON sessions(project_id, state);
CREATE INDEX idx_sessions_parent  ON sessions(parent_session_id);

CREATE TABLE memory_entries (
    id               TEXT PRIMARY KEY,
    session_id       TEXT NOT NULL,
    project_id       TEXT NOT NULL,
    kind             TEXT NOT NULL,                   -- SUMMARY|FACT|DECISION|TASK_STATE
    content          TEXT NOT NULL,
    source_refs_json TEXT NOT NULL,                   -- never '[]' (RFC-0026)
    confidence       TEXT NOT NULL,                   -- OBSERVED|INFERRED|USER_STATED
    trust_level      TEXT NOT NULL DEFAULT 'UNTRUSTED',
    created_at       TEXT NOT NULL,
    expires_at       TEXT,
    superseded_by    TEXT,
    FOREIGN KEY (session_id)    REFERENCES sessions(id),
    FOREIGN KEY (project_id)    REFERENCES projects(id),
    FOREIGN KEY (superseded_by) REFERENCES memory_entries(id)
);

CREATE INDEX idx_memory_active ON memory_entries(session_id, kind) WHERE superseded_by IS NULL;
```

Three absences are the design, not omissions:

- **No `capabilities` column.** Authority lives in the `capabilities` table keyed by
  `subject_id` (RFC-0018). An embedded set would be a second source of truth for security state
  that could disagree after a revocation.
- **No `artifacts` list.** Found through `PRODUCED` edges (RFC-0019). A growing column would be
  rewritten on every artifact creation.
- **No `conversation_history` blob.** Raw turns belong to a Run's transcript (RFC-0008), which is
  `AGED` and compacted (RFC-0056). `memory_entries` holds conclusions, bounded and queryable.

`parent_session_id` is what makes orphan cancellation a query: when a driver reaches a terminal
state, its workers are found by reverse lookup and cancelled, and their delegated capabilities
are revoked recursively (RFC-0018).

## Data Model (Conceptual)

```
Session {
  id: UUID                          # Unique within project
  project_id: UUID
  name: String
  role: SessionRole                 # driver | worker
  description: String?
  created_at: Timestamp
  last_active: Timestamp
  archived_at: Timestamp?           # If archived
  
  capabilities: CapabilitySet       # Permissions (RFC-0003)
  
  state: SessionState {
    persistent: SessionPersistentState
    ephemeral: SessionEphemeralState (in-memory)
  }
  
  memory: SessionMemory {
    conversation_history: List<Message>
    learned_facts: Map<String, Any>
    task_state: Map<String, Any>
    variables: Map<String, Any>
  }
  
  artifacts: List<ArtifactId>       # All created
  subscriptions: List<SubscriptionId>
  
  parent_session: SessionId?        # If worker
  worker_sessions: List<SessionId>  # If driver
  
  last_events: List<Event>?         # Recent events for context
  
  metadata: Map<String, Any>?
}
```

## Lifecycle Examples

### Example 1: Driver Session Workflow

```
1. User creates driver session "Feature Development"
   State: sleeping
   
2. User inputs: "Add user authentication"
   Event: UserCommand generated
   Session wakes, processes command
   
3. Session reads Intent Graph, adds goal: "Implement user auth"
   Queries AI: "Design the auth system"
   Creates artifact: "auth_design.md"
   
4. Session creates worker session W1 for "Implement auth backend"
   Yields (worker is running)
   
5. Worker W1 generates code artifact: "auth.rs"
   Publishes event: WorkerCompleted
   
6. Driver wakes, reads artifacts from W1
   Creates worker session W2 for "Write auth tests"
   Yields
   
7. Worker W2 generates test artifact: "auth_tests.rs"
   Publishes event: WorkerCompleted
   
8. Driver wakes, reviews all artifacts
   Updates Intent Graph: "Implement user auth" → done
   Publishes completion event
   Moves to sleeping
```

### Example 2: Worker Session Workflow

```
1. Driver creates worker session W: "Refactor parsing module"
   Capabilities: fs:read/write on /project/src/parsing
   State: sleeping
   
2. Driver publishes event: WorkerStart(task=...)
   W wakes
   
3. W reads project code via Knowledge Engine
   Reads design document (Resource)
   Queries AI: "Suggest refactoring approach"
   
4. W makes changes to /project/src/parsing/mod.rs
   Runs shell tests: `cargo test parsing`
   Tests pass
   
5. W creates artifact: "refactoring_patch.md" (description and rationale)
   Publishes event: WorkCompleted(tests_passed=true)
   Moves to archived
   
6. Driver reads artifact and event
   Decides whether to merge changes
```

## Security Considerations

### Capability Isolation

Each session has an explicit set of capabilities. A session cannot exceed its capabilities. Attempts to access denied resources are logged and fail safely.

### Memory Privacy

One session cannot access another's memory directly. Coordination happens through artifacts and events (which are controlled and logged).

### Audit Trail

Every action a session takes is recorded. The session history is immutable and auditable by the user.

### Worker Creation Control

Worker creation is a capability. A session can only create workers if it has permission. This prevents runaway session proliferation.

## MVP Scope

The MVP session model includes:

1. **Driver sessions**: Main worker listening for user events.
2. **Worker sessions**: Scoped tasks created by drivers.
3. **Session state**: Persistent memory, credentials, task state.
4. **Capabilities**: attenuated delegation to workers (RFC-0018), including split budgets.
5. **Orchestration**: declared plans with user approval; `COMPOSITE` fan-out; parked parents.
6. **Worker output as commits** on `refs/aidos/workers/<id>`; treeless on MOBILE.
7. **Lifecycle**: creation, waking, sleeping, archival; orphan cancellation on driver termination.
8. **Coordination**: through artifacts, events, and the Intent Graph.
9. **Logging**: all session actions audited with actor attribution (RFC-0046).

The MVP does not include:

- Specialized session types or templates (future).
- Distributed sessions across machines (future).
- Advanced memory management or summarization (future).
- Session snapshots or branching (future).

## Future Work

### Session Rooms

Group related sessions into "rooms" for coordination:

```
Room: "Weather App Development"
  Sessions: S1 (driver), S2 (backend), S3 (frontend), S4 (tests)
  Shared context, synchronized subscriptions
```

### Session-to-Session Messaging

Direct messaging between sessions (in addition to artifacts and events):

```
S1 sends message to S2: "I'm blocked on the API response"
S2 responds: "Try endpoint /api/v2/..."
```

### Distributed Sessions

Sessions on different machines, coordinated via Git and events:

```
Local session runs AI query (no internet)
Remote session offloads heavy computation to cloud
Results are synced back via Git
```

### Session Branching and Merging

Create alternative branches of a session's work:

```
Main session: Feature A
Branch 1: Try approach X
Branch 2: Try approach Y
Merge best version back to main
```

### Session Replay and Debugging

Replay a session's execution step-by-step, inspecting state at each point:

```
Replay session from T=10:00, step through decisions,
inspect memory and artifacts at each step
```

### Specialized Session Types

Introduce session templates for common patterns:

```
CodeReviewSession: Reviews code, produces audit artifacts
ResearchSession: Searches, synthesizes, produces summaries
PlanningSession: Breaks down goals, produces task breakdowns
```

### Session Pooling and Templates

Reusable session configurations:

```
Template: "Testing Session"
  Role: Worker
  Capabilities: fs:read/write tests, shell:exec with 30s timeout
  Memory template: test results tracking
  
New worker sessions inherit template defaults
```

## Resolved questions

These were open questions. Each is load-bearing semantics rather than a refinement, so each is
now decided.

**How is session memory summarized as it grows?** Rolling `SUMMARY` entries; raw turns live in
Run transcripts and are compacted on a retention schedule (see Session memory above,
RFC-0056).

**How do workers inherit capabilities?** By explicit attenuated delegation only, never by
inheritance (RFC-0018). A worker receives a strict subset of its driver's authority, narrower
in scope, constraints, and expiry, and may re-delegate only if `allowsDelegation` was set. A
worker never holds ambient project permissions.

**What happens to pending workers if the driver terminates?** Orphaned workers are cancelled.
When a driver reaches a terminal state, every worker whose `parent_session` is that driver and
whose Run is non-terminal is cancelled with `run.parent_terminated`, and the delegated
capabilities are revoked recursively — which happens automatically, since delegated
capabilities are children of the driver's (RFC-0018). Work already committed by the worker
survives as artifacts; only the authority and the pending Run end.

**Do sessions have failure budgets?** Yes. A session with three consecutive `FAILED` Runs and
no intervening success is parked in SLEEPING and stops responding to non-user events until the
user intervenes. This prevents an event-driven session from failing in a loop against a
persistent condition.

**Is there loop detection?** Yes, at three levels: `max_steps` per Run (RFC-0008), no-progress
detection on repeated identical tool calls (RFC-0008), and causal-depth plus wake-rate limits
across sessions (RFC-0028). The last is the one that catches driver/worker ping-pong.

**Contradictory instructions?** Resolved by the precedence hierarchy in RFC-0025; conflicts
between instruction sources are surfaced to the user rather than silently ordered.

## Open Questions

- Should a user be able to fork a session and explore an alternative path? (Attractive, but it
  interacts with worker refs and provenance in ways not yet worked through.)
- Should sessions be able to message each other directly, or is coordination through artifacts,
  events, and child Runs sufficient?
