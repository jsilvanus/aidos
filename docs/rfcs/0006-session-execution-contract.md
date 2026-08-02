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

**`Run` is defined once, in RFC-0019.** This RFC previously carried a second, incompatible
definition — one in which a Run spawned Attempts directly, contradicting RFC-0019 where a Run
contains Tasks and Tasks contain Attempts. The containment hierarchy is:

```
Run  ──contains──▶  Task  ──contains──▶  Attempt
```

A Run corresponds to one execution of the agent loop (RFC-0008); each loop step produces Tasks;
each execution of a Task is an Attempt. See RFC-0019 for the schema and RFC-0009 for how the
executor drives these rows.

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

RunState: PENDING | RUNNING | YIELDED | COMPLETED | FAILED | CANCELLED | INTERRUPTED
```

The enumeration is defined in RFC-0019 and reproduced here in full; the diagram above shows
the common path and omits the failure edges, which exist from every non-terminal state:

- any non-terminal state → `FAILED` (unrecoverable error, RFC-0029)
- any non-terminal state → `CANCELLED` (user cancellation, or `PRIORITY_INTERRUPT`)
- `RUNNING` or `YIELDED` → `INTERRUPTED` (process died; set by recovery, RFC-0009)

**Run state and Task state compose** as follows. Previously the two machines were defined
independently, so a Run whose Task was blocked on approval for three days was described as
`RUNNING` by RFC-0019 and `YIELDED` by this RFC, with nothing specifying which.

| Run state | Legal states of its Tasks |
|---|---|
| `PENDING` | all `PENDING` |
| `RUNNING` | exactly one `RUNNING`; others `PENDING`, terminal, or `SKIPPED` |
| `YIELDED` | **one or more** in `AWAITING_APPROVAL` or `AWAITING_INPUT`; no Task `RUNNING` |
| `COMPLETED` | all terminal; none `FAILED` |
| `FAILED` | at least one `FAILED`; no Task `RUNNING` |
| `CANCELLED` | no Task `RUNNING`; unstarted Tasks `SKIPPED` |
| `INTERRUPTED` | transient; recovery resolves to `RUNNING` or `FAILED` |

The invariant that makes this checkable: **at most one Task per Run is `RUNNING`**, because the
executor is a single-stepping driver (RFC-0009). It is asserted in tests.

Note that `YIELDED` permits *several* parked Tasks. A driver session that has fanned out to three
workers holds three `COMPOSITE` Tasks in `AWAITING_INPUT` simultaneously, each with its
`awaiting_run_id` set (RFC-0019). None of them is `RUNNING` — the work is happening in the child
Runs — so the one-running-Task invariant holds while genuine parallelism proceeds. An earlier
version required exactly one parked Task, which would have made worker fan-out unrepresentable.

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

When a Run must wait for something that may outlive the process — an approval, user input, or
a child session — it yields:

1. Commit the outcome of the current step (RFC-0009 checkpoint).
2. Mark the Run `YIELDED` and the awaiting Task `AWAITING_APPROVAL` / `AWAITING_INPUT`.
3. Return from `drive()`. No coroutine remains suspended.

The `Continuation` record describes **what is being waited for**, for display and for
correlating the completion event. It is not a resumable handle:

```kotlin
data class Continuation(
    val runId: UUID,
    val taskId: UUID,
    val suspendedOperation: SuspendedOperation,
    val pendingResultCorrelationId: String?
)

sealed class SuspendedOperation {
    data class AiCall(val requestId: UUID, val modelCapability: ModelCapability) : SuspendedOperation()
    data class ToolCall(val toolName: String, val operationId: UUID) : SuspendedOperation()
    data class UserPrompt(val promptId: UUID, val question: String) : SuspendedOperation()
    data class CapabilityApproval(val requestId: UUID, val permission: Permission) : SuspendedOperation()
    data class ChildRun(val childRunId: UUID, val childSessionId: UUID) : SuspendedOperation()
}
```

`ChildRun` and `CapabilityApproval` are new. Waiting on a worker session was previously
unrepresentable — the flagship Driver/Worker workflow in RFC-0011 had the driver "yield while
the worker runs", and no suspension type could express it.

When the awaited thing completes, its completion event resumes the Run by calling `drive()`
(RFC-0009). The executor reads the next runnable Task from SQLite. **There is no in-memory
continuation to restore**, which is why this survives process death.

Short waits — an in-process model call or a fast tool call within one execution window — do not
yield. They are ordinary `suspend` calls inside a single step, bracketed by checkpoints. Yield
is for waits that can outlive the process, not for every asynchronous operation.

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

Process termination is **routine, not exceptional**. On Android the foreground service can be
evicted at any moment (RFC-0049), so recovery is a normal code path exercised constantly rather
than a rare emergency procedure.

Recovery is specified in RFC-0009 and summarized here:

1. Acquire the project lock (RFC-0055) and open SQLite.
2. Reconcile against Git if the repository fingerprint moved (RFC-0053). A Run whose repository
   changed underneath it is failed with `git.repo_mutated` rather than resumed — resuming a
   plan built against a different tree is how silent corruption happens.
3. Release orphaned budget reservations (RFC-0028).
4. For each `Attempt` in `RUNNING` state, apply its **recovery class**: `PURE` and `IDEMPOTENT`
   re-execute, `CHECKABLE` probes first, `UNSAFE` is never retried and is surfaced to the user
   as `INDETERMINATE` (RFC-0029).
5. Re-validate the capabilities the remaining Tasks require. Authority that expired or was
   revoked during the downtime is not reinstated.
6. Call `drive()`. Execution continues from whatever the rows say.

Recovery is idempotent because it is derived from committed state rather than from a
reconstruction procedure: running it twice reaches the same rows. Yielded Runs are *not*
transitioned to INTERRUPTED — a Run waiting for an approval was not interrupted by the crash,
and marking it so would lose the wait.

**Note on the previous design.** Steps 5–8 of the earlier sequence assumed the runtime could
resume mid-procedure from a serialization-point label. That is only possible if session logic is
an interpretable step machine, which it now is (RFC-0009). Without that, the label identified
where execution *had been* but provided no way to continue from there.

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

`Run`, `Task`, and `Attempt` are defined in RFC-0019 and are not restated here. The executor's
additional columns (`step_index`, `max_steps`, `ordinal`, `awaiting_run_id`,
`idempotency_key`, `recovery_class`) are defined in RFC-0009.

This RFC contributes one table:

```sql
CREATE TABLE continuations (
    run_id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL,
    suspended_operation TEXT NOT NULL,     -- AI_CALL | TOOL_CALL | USER_PROMPT
                                           -- | CAPABILITY_APPROVAL | CHILD_RUN
    operation_detail_json TEXT NOT NULL,
    correlation_id TEXT,
    created_at TEXT NOT NULL,
    FOREIGN KEY (run_id) REFERENCES runs(id),
    FOREIGN KEY (task_id) REFERENCES tasks(id)
);

CREATE INDEX idx_continuations_correlation ON continuations(correlation_id);
```

There is no `SerializationState` table. Session state is not snapshotted into a blob at yield
points; it lives in the ordinary domain tables, which is what makes recovery a query rather
than a restore. The earlier `sessionMemorySnapshot` field would have rewritten the whole of a
session's memory on every yield — unbounded write amplification on the device least able to
afford it.

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
