# RFC-0011: Sessions

Status: Draft

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

Example: A test-writing worker created by the driver to write tests for a specific file. It runs in an isolated Git worktree, writes tests, and publishes the test file artifact.

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
  
  capabilities: CapabilitySet      # Permissions (RFC-0003)
  
  memory: SessionMemory {
    conversation_history: List<Message>
    learned_facts: Map<String, Any>
    task_state: Map<String, Any>
    variables: Map<String, Any>
  }
  
  artifacts: List<ArtifactId>      # All artifacts created
  
  intent_references: List<IntentNodeId>
  
  parent_session: SessionId?       # If worker, who created it
  subscriptions: List<SubscriptionId>
}
```

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
4. **Capabilities**: Explicit permissions per session.
5. **Artifacts**: Workers create and publish artifacts.
6. **Lifecycle**: Creation, waking, sleeping, archival.
7. **Coordination**: Through artifacts, events, and Intent Graph.
8. **Logging**: All session actions are logged for audit.

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

## Open Questions

- How should session memory be summarized as it grows? Should old conversation be compressed?
- Should a user be able to "fork" a session and explore an alternative path?
- How should worker sessions inherit capabilities from drivers? All, subset, explicit delegation?
- What should happen to pending workers if the driver session terminates unexpectedly?
- Should sessions have "failure budgets" (e.g., allowed to fail N times before being paused)?
- How should sessions handle contradictory instructions or conflicting permissions?
- Should there be a built-in mechanism to detect and break infinite loops in sessions?
