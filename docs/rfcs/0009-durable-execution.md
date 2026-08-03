# RFC-0009: Durable Execution Model

Status: Draft

## Abstract

This RFC selects and specifies the mechanism by which a Run survives process death,
Android background eviction, and device restart. It replaces the implicit assumption in
RFC-0006 that a suspended Kotlin coroutine can be persisted and resumed. Aidos uses a
**checkpointed step machine**: session logic is an interpreter over persisted Execution Graph
rows, and a "continuation" is a database query rather than a serialized stack.

## Motivation

RFC-0006 specifies that a Run persists a `Continuation` descriptor and later "resumes from the
continuation descriptor." RFC-0007 specifies that all session work is ordinary Kotlin `suspend`
functions on coroutine dispatchers.

These are incompatible. **Kotlin coroutine continuations are not serializable.** A suspended
coroutine's stack cannot be written to SQLite and restored in a new process. RFC-0006's
`Continuation` record stores a serialization-point label and an operation type — which
identifies *where* execution was, not *how to resume it*.

This is not a detail. It determines how every line of session logic is written, so it must be
settled before the session executor exists.

Android makes it urgent rather than merely important. The primary use case — making progress
on Git projects offline, on a phone — means the runtime executes in short, interruptible
windows and can be evicted at any moment (RFC-0049). A model that assumes an uninterrupted
process is not merely inelegant on Android; it does not function.

## Goals

1. Choose a durable execution strategy and justify the choice against the alternatives.
2. Define what is checkpointed, when, and atomically with what.
3. Define recovery, including idempotency and the "was the side effect applied?" problem.
4. Define how the model integrates with Android's execution windows.
5. Define the authoring rules that session logic must follow to remain durable.

## Non-goals

This RFC does not define the loop's semantics (RFC-0008) or scheduling policy (RFC-0005).
It does not define the Execution Graph schema (RFC-0019).
It does not define distributed execution.

## Design

### The three candidates

| Strategy | How resume works | Cost | Fit for Aidos |
|---|---|---|---|
| **(a) Interpreter over a persisted plan** | Read the next `PENDING` Task; execute it | Session logic must be data, not control flow | **Chosen** |
| (b) Deterministic replay of a workflow function | Re-execute from the top, serving recorded results from history | Requires strict determinism discipline and a large runtime | Rejected |
| (c) Re-derivation | Discard in-flight step; let the model re-plan | One wasted model call per interruption; imprecise audit | Rejected as the primary mechanism; retained as a fallback |

**Why (a).** The Execution Graph (RFC-0019) already models `Run → Task → Attempt`. The agent
loop (RFC-0008) is already a sequence of discrete steps with natural boundaries. Making the
executor a driver over those rows costs nothing structurally and produces four properties for
free: recovery is a query, the audit trail is a byproduct rather than a duplicate write,
Android eviction is indistinguishable from any other pause, and the step ceiling that bounds
spend (RFC-0028) is enforced by the same counter that drives execution.

**Why not (b).** Deterministic replay requires that all non-determinism flow through recorded
APIs. Aidos's session logic will call the filesystem, Git, and the clock freely, and it will be
written by contributors who have never used a workflow engine. The discipline would not hold,
and the failure mode is silent divergence.

**Why not (c) as primary.** One model call per interruption is acceptable on desktop. On
Android, where eviction is routine rather than exceptional, it converts every background
interruption into cost and latency.

### The step machine

A Run's execution state is entirely represented by rows:

```
Run(state, step_index, budget_remaining)
  └── Task(state, kind, ordinal)          -- the step list, appended as the model plans
        └── Attempt(state, ...)           -- one execution of a Task
```

The executor is:

```kotlin
suspend fun drive(runId: RunId) {
    while (true) {
        val run = loadRun(runId)
        if (run.state.isTerminal) return
        val task = nextRunnableTask(runId) ?: return finish(runId)
        when (task.state) {
            PENDING            -> execute(task)      // may append new Tasks
            AWAITING_APPROVAL,
            AWAITING_INPUT     -> return             // parked; an event resumes the drive
            else               -> advance(task)
        }
    }
}
```

`drive()` is re-entrant and idempotent. Calling it on an already-complete Run is a no-op.
Calling it after a crash resumes exactly where the rows say execution was. There is no
in-memory state that must survive.

**Consequence for authoring.** Session logic may not hold important state in local variables
across a step boundary. Anything that must survive is a column. This is a real constraint and
it is the price of durability; it is enforced by review and by the crash-recovery test suite
(RFC-0038).

### Checkpoints

A checkpoint is a single SQLite transaction that advances the step machine. The rule:

> **Every effect on the outside world is immediately preceded by an `INTENT` record and
> immediately followed by an `OUTCOME` record, and the executor never performs two effects
> within one checkpoint.**

```
BEGIN;
  UPDATE tasks SET state='RUNNING' WHERE id=?;
  INSERT INTO attempts(id, task_id, state, idempotency_key, ...) VALUES (?,?, 'RUNNING', ?, ...);
COMMIT;
-- ← process may die here; see recovery
   perform the effect
BEGIN;
  UPDATE attempts SET state='COMPLETED', output_ref=?, ... WHERE id=?;
  UPDATE tasks SET state='COMPLETED' WHERE id=?;
  UPDATE runs SET step_index=step_index+1, budget_remaining=? WHERE id=?;
COMMIT;
```

Checkpoint boundaries correspond exactly to RFC-0006's safe serialization points, which are
now derived rather than asserted: they are the points at which no effect is in flight.

### Recovery, and the "was it applied?" problem

On project open, for every `Attempt` in `RUNNING` state (an effect that may or may not have
landed), the executor consults the effect's **recovery class**, declared by the tool
(RFC-0030):

| Recovery class | Meaning | Recovery action |
|---|---|---|
| `PURE` | No external effect (model call with no side effect, read) | Re-execute |
| `IDEMPOTENT` | Re-executing is safe (write with full content, `git add`) | Re-execute |
| `CHECKABLE` | Effect can be observed after the fact (`git commit` → look for the commit) | Probe, then re-execute or adopt |
| `UNSAFE` | Cannot be re-run or observed (`git push`, notification, HTTP POST) | Do not retry; mark `FAILED(INDETERMINATE)` and surface to the user |

Every tool operation must declare its recovery class. This is the single most important thing
this RFC asks of tool authors, and it is why the effect taxonomy in RFC-0030 is typed.

`idempotency_key` on `Attempt` is `hash(task_id, attempt_number, canonical_arguments)`. Tools
in the `IDEMPOTENT` and `CHECKABLE` classes use it to deduplicate.

**Model calls are `PURE` for correctness but expensive.** Recovery therefore checks whether a
completed response was already persisted (checkpoint 6 in RFC-0008) before re-issuing. This is
the one place where re-execution is correct but undesirable, and the checkpoint exists
precisely to avoid paying twice.

### Android execution windows (RFC-0049)

Android does not grant long uninterrupted execution. The step machine turns this from a
blocker into a scheduling detail:

- An active Run executes inside a foreground service with a user-visible ongoing notification,
  declared with an appropriate FGS type. The user sees "Aidos: working on <session>".
- The executor checks a **deadline budget** at every step boundary. When the remaining window
  is smaller than the estimated cost of the next step, it stops cleanly at a checkpoint rather
  than starting work it cannot finish.
- If the service is killed anyway, recovery on next start resumes from the last committed
  checkpoint. Eviction and ordinary pausing follow the same code path.
- Deferred work (indexing, compaction) uses `WorkManager`. The runtime does not depend on
  exact timing: RFC-0044 timers are best-effort, and no session semantics may assume a wake
  occurred at a precise moment.

The desktop profile uses the same executor with an effectively unbounded window. **There is no
Android-specific execution code path** — only a different deadline budget. This is what keeps
Android-first from forking the runtime.

**Steps always fit their window** (decision D24). A foreground service holding a wake lock runs
long enough for a full local inference step, so the deadline budget never has to interrupt work
mid-step and no sub-step checkpointing is required. Where an FGS is unavailable, a local model
call is not attempted at all: the Run parks with `ForegroundRequired` (RFC-0006) and resumes in
the foreground.

This is why the deadline budget can be a simple "does the next step fit" check rather than a
preemption mechanism. Mid-generation checkpointing was considered and rejected — a KV cache is
hundreds of megabytes, and persisting it per window plausibly costs more than the inference it
would preserve.

### Relationship to RFC-0006

RFC-0006 remains the contract for yield, cancellation, and interrupt *semantics*. This RFC
supplies the mechanism. Where they disagreed, this RFC governs:

- `Continuation` no longer stores a resumable handle. It records what the Run was waiting for,
  for display and for correlating an inbound completion event.
- "Resume from the continuation descriptor" is replaced by "call `drive()`".
- Waiting on a child session (previously unrepresentable) is a `Task` in `AWAITING_INPUT`
  state with the child's Run ID; the child's completion event resumes the parent's drive.

## Data Model

```sql
ALTER TABLE runs ADD COLUMN step_index INTEGER NOT NULL DEFAULT 0;
ALTER TABLE runs ADD COLUMN max_steps INTEGER NOT NULL DEFAULT 24;
ALTER TABLE tasks ADD COLUMN ordinal INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tasks ADD COLUMN awaiting_run_id TEXT;   -- child session Run, if parked on one
ALTER TABLE attempts ADD COLUMN idempotency_key TEXT;
ALTER TABLE attempts ADD COLUMN recovery_class TEXT NOT NULL DEFAULT 'PURE';

CREATE INDEX idx_tasks_runnable ON tasks(run_id, state, ordinal);
CREATE INDEX idx_attempts_recovery ON attempts(state) WHERE state = 'RUNNING';
```

## Security

Recovery must not resurrect authority. Before resuming a Run, the executor re-resolves every
capability the remaining Tasks require (RFC-0018). Capabilities that expired or were revoked
while the process was down are not reinstated, and the Run fails cleanly rather than
continuing with stale authority.

`UNSAFE` effects left in an indeterminate state are surfaced to the user rather than retried.
Silently retrying a `git push` or an outbound notification is worse than failing.

## MVP

1. `drive()` executor over Run/Task/Attempt rows.
2. Two-phase checkpointing with `INTENT` and `OUTCOME` transactions.
3. Recovery classes `PURE`, `IDEMPOTENT`, and `UNSAFE` (`CHECKABLE` post-MVP).
4. Model-response checkpoint to avoid paying twice for a completed call.
5. Deadline budget with clean stop at a checkpoint.
6. Crash-recovery test suite: `kill -9` at every checkpoint boundary of a scripted Run.

Not in MVP: `CHECKABLE` probes, cost-aware step estimation, resumable streaming.

## Future Work

`CHECKABLE` recovery probes per tool, starting with Git.

Step cost estimation from historical `Attempt` durations, to make deadline decisions sharper.

Speculative continuation on desktop: begin the next step before the checkpoint commits, and
roll back if the commit fails.
