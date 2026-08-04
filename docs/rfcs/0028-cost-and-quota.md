# RFC-0028: Cost, Quota, and Runaway Control

Status: Accepted 2026-08-03

## Abstract

This RFC defines how Aidos bounds resource consumption: tokens, money, model calls, steps,
wall-clock time, and session wake amplification. Budgets are capability constraints enforced
transactionally, not accounting figures observed after the fact.

## Motivation

Aidos runs autonomous sessions that are woken by events and invoke paid remote models. Before
this RFC nothing bounded that:

- `CapabilityConstraints` (RFC-0018) had byte and duration limits and no cost limit.
- `session_usage` (RFC-0020) was an in-memory map with no persistence and no enforcement.
- RFC-0005's "at most N events per run" does not prevent cross-session cycles: session A wakes
  B, B wakes A, forever, each cycle spending money.
- Boot replays the pending event queue (RFC-0005), which can produce a wake storm and a burst
  of model calls on startup.
- RFC-0045 defined a `ResourceBudget` shape with no enforcement semantics.

A wake cycle between two sessions using a remote model is an unbounded bill, reachable by
accident rather than by attack. For a single-user open-source product, one user's runaway spend
is an existential reputational event, and it will be discovered through an incident rather than
a test.

## Goals

1. Define budget dimensions and the scopes at which they apply.
2. Make budgets enforceable at the point of spend, transactionally.
3. Define wake-amplification control and cycle breaking.
4. Define degradation behaviour when a budget is exhausted.
5. Define what the user sees, before and after.

## Non-goals

This RFC does not define pricing or provider billing APIs.
It does not define CPU/memory profiling (RFC-0045 retains hardware budgets).

## Design

### Dimensions

```kotlin
data class Budget(
    val modelCalls: Int?,        // count
    val inputTokens: Long?,
    val outputTokens: Long?,
    val costUnits: Long?,        // micro-units of the user's display currency
    val steps: Int?,             // agent loop steps (RFC-0008)
    val wallClockSeconds: Int?,
    val toolInvocations: Int?
)
```

`costUnits` is integer micro-currency, never floating point. Money in a `Double` is a bug
waiting for a rounding complaint.

### Scopes

Budgets nest, and **every level is enforced**; the binding constraint is whichever is exhausted
first.

| Scope | Typical use | Default |
|---|---|---|
| Run | one user request | 24 steps, 8 model calls |
| Session | a long-lived line of work | unset |
| Project | a body of work | unset |
| User / period | "no more than X per day" | user-configured, off by default |
| Capability | a specific grant | inherited from the grant |

Defaults are deliberately conservative at Run scope and absent above it. A per-Run ceiling
catches the runaway case; nagging users about monthly limits they did not ask for does not.

### Enforcement

Budget is a **capability constraint**, added to `CapabilityConstraints` (RFC-0018):

```kotlin
data class CapabilityConstraints(
    // ... existing fields ...
    val budget: Budget? = null,
    val budgetConsumed: Budget = Budget.ZERO
)
```

Enforcement happens at two points, both mandatory:

**Before the spend — reservation.** The agent loop reserves an estimated cost before invoking a
model. If any applicable budget cannot cover the reservation, the call does not happen and the
Run terminates with `FAILED(BUDGET_EXHAUSTED)` (RFC-0029).

**After the spend — settlement.** Actual usage is recorded in the *same SQLite transaction* as
the `Attempt` outcome (RFC-0009's OUTCOME checkpoint). Reservation is released; actual is
charged.

Charging in the outcome transaction is what makes this correct across crashes: a model call
whose cost was incurred but whose record was lost would otherwise be free, and the budget would
drift permanently. Reservation-then-settlement also means a crash between the two leaves a
reservation that recovery releases, which errs toward under-spending.

Estimation before a call is imprecise (output length is unknown). The reservation uses
`maxOutputTokens` as the upper bound, so reservations are conservative and settlements refund.

### Wake amplification

Every event carries a **causal depth**, derived from `Event.causality` (RFC-0004):

```
depth(event) = 0                      if externally originated (user, timer, filesystem, Git)
             = depth(cause) + 1       if published by a session
```

Rules:

1. An event with `depth > maxCausalDepth` (default 8) is dropped, an audit record is written,
   and a `WakeAmplificationBlocked` event at depth 0 notifies the user.
2. A **per-project wake rate limiter** bounds session wakes to a sliding window (default 60 per
   minute). Exceeding it trips a circuit breaker: the project stops waking sessions, all
   sessions are parked, and the user is told which pair of sessions was cycling.
3. **Boot does not replay.** RFC-0005's "pending events are replayed on boot, waking relevant
   sessions" is replaced: on boot, pending events are *coalesced and summarized* into a single
   wake per session per topic, and events older than a configurable staleness window (default
   1 hour) are recorded and discarded rather than delivered. A phone that was off overnight must
   not wake up and spend money catching up on file-change events.

Rule 3 matters most for the mobile use case, where the runtime is off far more than it is on.

### Cycle detection

Beyond depth limiting, the runtime maintains per-project a small ring buffer of recent
`(session_id, event_type, topic)` triples. Three identical triples within one minute trip the
circuit breaker for that session, independent of depth. This catches two-session ping-pong that
stays under the depth limit by alternating.

### Degradation

When a budget is exhausted, behaviour depends on which:

| Exhausted | Behaviour |
|---|---|
| Run steps or model calls | Terminate the Run, preserve all work, offer "continue with a new budget" |
| Cost at user/period scope | Block remote model calls; **local models remain available**; notify |
| Wall clock | Stop at the next checkpoint, park the Run as resumable |
| Tool invocations | Terminate the Run |

Cost exhaustion falling back to local models rather than stopping entirely is the offline-first
behaviour: the user should be able to keep working without a network or a bill.

### Visibility

Cost is shown before it is incurred, not only after. The frontend receives:

- Per-Run live consumption via `RunBudgetUpdated` events.
- A pre-flight estimate when a Run is started with a non-trivial expected cost.
- A per-project and per-period summary.

Local model usage reports tokens and time but zero cost. Making "this was free" visible is
what teaches users the value of the offline path.

## Data Model

```sql
CREATE TABLE budget_ledger (
    id TEXT PRIMARY KEY,
    scope TEXT NOT NULL,           -- 'run' | 'session' | 'project' | 'user' | 'capability'
    scope_id TEXT NOT NULL,
    period_start TEXT,             -- NULL for non-periodic scopes
    model_calls INTEGER NOT NULL DEFAULT 0,
    input_tokens INTEGER NOT NULL DEFAULT 0,
    output_tokens INTEGER NOT NULL DEFAULT 0,
    cost_units INTEGER NOT NULL DEFAULT 0,
    steps INTEGER NOT NULL DEFAULT 0,
    tool_invocations INTEGER NOT NULL DEFAULT 0,
    limit_json TEXT,               -- the Budget applied to this scope; NULL = unlimited
    updated_at TEXT NOT NULL
);

CREATE UNIQUE INDEX idx_budget_scope ON budget_ledger(scope, scope_id, period_start);

CREATE TABLE budget_reservations (
    id TEXT PRIMARY KEY,
    attempt_id TEXT NOT NULL,
    reserved_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY (attempt_id) REFERENCES attempts(id)
);

ALTER TABLE events ADD COLUMN causal_depth INTEGER NOT NULL DEFAULT 0;
```

Recovery (RFC-0009) releases orphaned reservations whose `Attempt` is not `RUNNING`.

## Security

Budget exhaustion is a denial-of-service vector against the user's own agent: content that
induces many tool calls can burn a Run's budget. This is acceptable — the failure is bounded,
visible, and recoverable, which is the entire point.

Budget limits are enforced by the runtime, never by the model's cooperation. A model asked to
"be economical" is not a control.

Cost figures are derived from a provider price table shipped with the runtime and are
best-effort. They are labelled as estimates in the UI. They are not billing records.

## MVP

1. `Budget` on capabilities; Run-scope defaults (24 steps, 8 model calls).
2. Reservation before model calls; settlement in the outcome transaction.
3. `budget_ledger` at run, session, and project scope.
4. Causal depth on events, `maxCausalDepth = 8`.
5. Per-project wake rate limiter and circuit breaker.
6. Boot coalescing and staleness discard.
7. Live per-Run consumption events; cost fallback to local models.

Not in MVP: user/period budgets, cycle-detection ring buffer, pre-flight estimates.

## Future Work

Cost-aware routing: choosing a cheaper model when the remaining budget is tight (RFC-0020).

Learned step-cost estimation from historical Attempts, for sharper reservations and better
mobile deadline decisions (RFC-0009).

Per-provider quota tracking against real rate limits, so budget exhaustion and provider
throttling are distinguishable.
