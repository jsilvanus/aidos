# RFC-0006: Session Execution Contract

Status: Draft

## Abstract

This RFC defines the formal execution contract for sessions: what it means for a session to execute, yield, resume, be interrupted, and be cancelled. It establishes the lifecycle of a Run within a session, the serialization points at which session state can be safely checkpointed, and the recovery semantics after unexpected process termination.

## Motivation

RFC-0011 (Sessions) defines the session lifecycle at a conceptual level — sessions sleep, wake, process work, and sleep again. RFC-0005 (Scheduler) defines how sessions are woken by events. But neither RFC specifies the execution mechanics:

- When a session yields while waiting for an async AI call, what is the runtime state?
- When the process terminates mid-session, what is recoverable?
- When a capability is revoked while a session is running, does the current operation complete?
- When a user cancels a session, does partial tool work get rolled back?
- When a session is woken while already processing a prior event, how are those events ordered?

Without a formal execution contract, each implementer will answer these questions differently, leading to undefined behavior at the boundaries that matter most: failure, cancellation, and recovery.

## Goals

1. Define the state machine for a Session Run, including all transitions.
2. Define safe serialization points at which session state can be persisted.
3. Define yield semantics for async operations (AI calls, tool calls, user prompts).
4. Define resumption semantics after process restart.
5. Define cancellation and interrupt semantics with effect propagation.
6. Define how revocation of a capability affects an in-flight operation.

## Non-goals

This RFC does not define the scheduling policy (RFC-0005).
It does not define the data model for the Execution Graph (RFC-0019).
It does not define the concurrency model for the runtime (RFC-0007).
It does not define session-to-session communication protocols.

## Design

### The Run

A Run is the unit of execution within a session. When a session wakes in response to an event, it creates a Run. The Run executes to completion, fails, or is cancelled. A session accumulates Runs over its lifetime.

A Run is not the same as a Session. A session persists for weeks or months. A Run typically lasts seconds to minutes. A session with a long history has many completed Runs.

```
Run
  id: UUID
  session_id: UUID
  trigger_event_id: UUID           # The event that woke this session
  started_at: Timestamp
  ended_at: Timestamp?
  state: RunState
  error: String?
  artifact_ids: List<UUID>         # Artifacts produced in this run
  attempt_count: Int               # How many Attempts this Run spawned
```

### Run State Machine

```
                     ┌──────────────────┐
                     │    PENDING       │  Run created, not yet dispatched
                     └───────┬──────────┘
                             │ dispatch
                     ┌───────▼──────────┐
                     │    RUNNING       │  Session is actively processing
                     └───────┬──────────┘
               ┌─────────────┼──────────────┐
               │             │              │
      ┌────────▼──┐  ┌───────▼─────┐  ┌────▼────────┐
      │ YIELDED   │  │  COMPLETED  │  │  CANCELLED  │
      │           │  │             │  │             │
      └─────┬─────┘  └─────────────┘  └─────────────┘
            │ resume
      ┌─────▼──────┐
      │  RUNNING   │  (back to running after yield)
      └────────────┘

RunState: PENDING | RUNNING | YIELDED | COMPLETED | FAILED | CANCELLED
```

A Run in YIELDED state is not blocking the scheduler. The session is dormant at a safe serialization point, waiting for an async operation to complete. When the async operation resolves, the run transitions back to RUNNING.

### Safe Serialization Points

A safe serialization point is a moment at which all in-progress state can be written to SQLite and the session can be safely suspended. The runtime only persists session state at serialization points.

The following are safe serialization points:

1. **Before an AI call**: All context is assembled, the prompt package is built, and the session is about to call the AI Engine. The session state at this point is: conversation history, current Run state, capability set, and the prompt package (for audit).

2. **After an AI call**: The model response has been received (fully, not streaming). The session has not yet acted on the response. This is the most important serialization point because the model response is the most expensive operation to reproduce.

3. **After each tool call**: A tool has completed and its result has been written to SQLite. The session has not yet processed the result.

4. **After an artifact is committed**: An artifact has been created and its record persisted. The session has not yet moved to the next task.

5. **At explicit yield points in instruction sequences**: The Instruction Engine can declare yield points in tool chains, allowing long sequences to be suspended and resumed.

**Unsafe operations** that must complete atomically within a single serialization window:
- SQLite transactions (must commit before yielding)
- Git index staging (must complete or abort before yielding)
- File writes protected by capability (must complete the write or not begin it)

### Yield Semantics

When a session is about to initiate an async operation (AI call, long tool call, user approval prompt), it yields:

1. Persist the current serialization state to SQLite.
2. Mark the Run as YIELDED with a continuation descriptor.
3. Return control to the runtime event loop.
4. Register a callback with the async operation.

The continuation descriptor contains:
- The serialization point identifier
- The suspended operation type (ai_call, tool_call, user_prompt)
- Any handles needed to resume (e.g., the AI call's correlation ID)

When the async operation completes:
1. The callback fires.
2. The runtime creates a wakeup event for the session.
3. The scheduler dispatches the session.
4. The session loads its serialization state from SQLite.
5. The session resumes from the continuation descriptor.
6. The Run transitions back to RUNNING.

### AI Streaming Calls

AI streaming presents a special challenge: the response arrives incrementally over seconds. The session should not block during streaming.

The streaming contract:

1. The session initiates the streaming call and immediately yields.
2. The AI Engine accumulates streamed tokens and publishes delta events to the Event Bus.
3. The frontend subscribes to delta events and renders them in real time.
4. When streaming completes, the AI Engine publishes a completion event.
5. The completion event wakes the session.
6. The session resumes with the full response available.

The session never processes partial responses. Partial responses are for the frontend display only. The session always operates on the complete response.

If the stream is interrupted (network failure, model error), the AI Engine publishes a failure event. The session resumes with a failure result and follows the retry/failure handling in its instruction sequence.

### Resumption After Process Restart

When the runtime process terminates unexpectedly (crash, OOM, device restart), sessions that were running or yielded must be recoverable.

Recovery sequence on process start:

1. Load all projects from the filesystem.
2. For each project, open its SQLite database.
3. Query for Runs in RUNNING or YIELDED state.
4. For each such Run, transition it to INTERRUPTED state.
5. For each INTERRUPTED Run, determine the last safe serialization point.
6. If the last serialization point was before an AI call: re-queue the AI call.
7. If the last serialization point was after an AI call but before a tool call: resume from the model response.
8. If the last serialization point was after a tool call but before the next step: resume from the tool result.
9. If the state cannot be determined reliably: transition the Run to FAILED with reason "process_restart".

The recovery sequence must be idempotent. If the same Run is recovered twice (e.g., due to two rapid crashes), the second recovery must detect that the Run was already handled and not duplicate work.

### Cancellation

When a user cancels a Run:

1. The frontend sends a CancelRun command to the runtime.
2. The runtime sets a cancellation flag on the Run.
3. If the Run is RUNNING: the session checks the cancellation flag at each safe point and transitions to CANCELLED.
4. If the Run is YIELDED waiting on an AI call: the AI call is cancelled (if the provider supports cancellation). The session resumes with a cancellation result.
5. If the Run is YIELDED waiting on a tool call: the Tool Broker is instructed to cancel the operation. If cancellation is not possible, the tool completes and the result is discarded.

Cancellation is cooperative, not preemptive. The session is responsible for checking the cancellation flag and stopping gracefully. A session that ignores the cancellation flag will eventually be stopped by a timeout.

Cancellation does not roll back completed work. Artifacts produced before cancellation remain. Tool operations that completed before cancellation are not undone. The audit log records the cancellation event.

### Capability Revocation During Execution

When a capability is revoked while a Run is using it:

1. Future checks against the revoked capability fail immediately.
2. The current in-flight operation completes if it has already passed the capability check.
3. Any subsequent operation in the same Run that requires the revoked capability fails.
4. The Run transitions to FAILED if it cannot continue without the revoked capability.

This is the TOCTOU resolution: the capability check and the operation execution are not atomic at the process level, but the runtime guarantees that once the capability check has passed for a specific operation invocation, that invocation completes. The revocation takes effect for the next check, not retroactively.

### Interrupt Handling (New Event While Running)

When the scheduler receives a new event for a session that is already in RUNNING state:

1. The new event is queued in the session's pending event queue.
2. The running Run is not interrupted.
3. When the current Run completes (or yields), the scheduler dequeues the next pending event and starts a new Run for it.
4. Runs within a session execute serially — never concurrently.

The only exception is a PRIORITY_INTERRUPT event (e.g., user sends a "stop immediately" command). A PRIORITY_INTERRUPT transitions the Run to CANCELLED and clears the pending event queue.

## Data Model

### Run

```kotlin
data class Run(
    val id: UUID,
    val sessionId: UUID,
    val triggerEventId: UUID,
    val startedAt: Instant,
    val endedAt: Instant?,
    val state: RunState,
    val error: String?,
    val artifactIds: List<UUID>,
    val lastSerializationPoint: SerializationPointId?
)

enum class RunState {
    PENDING, RUNNING, YIELDED, COMPLETED, FAILED, CANCELLED, INTERRUPTED
}
```

### Continuation

```kotlin
data class Continuation(
    val runId: UUID,
    val serializationPointId: SerializationPointId,
    val suspendedOperation: SuspendedOperation,
    val pendingResultCorrelationId: String?
)

sealed class SuspendedOperation {
    data class AiCall(val requestId: UUID, val modelCapability: ModelCapability) : SuspendedOperation()
    data class ToolCall(val toolName: String, val operationId: UUID) : SuspendedOperation()
    data class UserPrompt(val promptId: UUID, val question: String) : SuspendedOperation()
}
```

### SerializationState

```kotlin
data class SerializationState(
    val runId: UUID,
    val sessionMemorySnapshot: SessionMemorySnapshot,
    val pendingContinuation: Continuation?,
    val capturedAt: Instant,
    val sequenceNumber: Long  // monotonically increasing; latest wins on recovery
)
```

## Security

Serialization state stored in SQLite is subject to the same access controls as all other session state. Serialization snapshots must not include raw secret values — capabilities reference secret IDs, never plaintext secret values.

Cancellation commands must be authenticated. A frontend without write access to a session must not be able to cancel its runs.

The recovery sequence must verify that a recovered Run's capabilities are still valid before resuming. Capabilities that expired or were revoked during the downtime are not reinstated.

## MVP

The MVP implements:

1. Run creation and state transitions (PENDING → RUNNING → COMPLETED/FAILED).
2. Basic serialization at AI call boundaries (before and after).
3. Simple restart recovery (RUNNING → INTERRUPTED → FAILED on restart, with audit log entry).
4. Cancellation via frontend command.
5. Serial event processing (no concurrent Runs within a session).

The MVP does not implement:
- Streaming call yield/resume (stream completes before session proceeds).
- Full continuation-based resumption for all operation types.
- Capability revocation mid-run (checks happen at call boundaries only).

## Future Work

Full continuation-based resumption for all async operation types.

AI streaming with mid-stream yield support for interactive sessions.

Compensation protocols for partially completed tool sequences (RFC-0019 Execution Graph).

Fine-grained TOCTOU protection using atomic capability exercise records.

Multi-event priority queuing with configurable interrupt policies.
