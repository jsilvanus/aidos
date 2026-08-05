# RFC-0005: Scheduler

Status: Accepted 2026-08-03

## Abstract

The Scheduler decides **which sleeping session wakes, and when**. That is its whole job. It
matches events to subscriptions, admits woken sessions in an order, and refuses to wake when
waking would be dishonest — on a phone with no foreground service, a wake that immediately needs
a model call produces a Run that parks in the same breath. Everything else this RFC once
described now belongs to a document that owns it properly.

## Motivation

Sessions are idle by default (RFC-0017). Something must decide when one stops being idle, and
that decision is not trivial: a filesystem event can match six subscriptions, waking six sessions
is a battery decision on a phone, and a session woken by an event it itself caused is a loop.

The previous version of this RFC was written before most of the runtime existed and accumulated
everything adjacent to scheduling: the session lifecycle, the execution model, timeouts and
resource limits, cross-session coordination, graceful shutdown, and its own `SessionState` enum.
Each of those now has an owner, and duplicating them here produced three descriptions of one
state machine, two of them stale.

**What this RFC deliberately no longer contains, and where it went:**

| Was here | Owner now |
|---|---|
| Session state machine | RFC-0017 — one canonical definition, mirrored in `runtime/kernel/` |
| Execution, checkpointing, resume | RFC-0009 |
| Timeouts, step ceilings, budgets | RFC-0028 |
| Concurrency, locks, parallelism | RFC-0007, D14, D15 |
| Background execution mechanism | RFC-0044, D24 |
| Boot recovery and shutdown | RFC-0009 `recover()`, RFC-0055 |

## Goals

1. Define how events are matched to sessions.
2. Define admission order when several sessions match.
3. Define when the Scheduler declines to wake.
4. Define cycle and amplification control.

## Non-goals

Everything in the table above.

This RFC does not define *what a woken session does* — it creates a Run, and RFC-0009 drives it.

## Design

### Matching

Sessions subscribe by topic pattern and event type (RFC-0004). When an event is published, the
Scheduler selects sessions whose subscription matches both:

```
event:  FileChanged  ·  topic  fs:/src/http/Client.kt

S1  topics ["fs:/src/**"]      types [FileChanged]        → match
S2  topics ["fs:/docs/**"]     types [FileChanged]        → no
S3  topics ["fs:/src/**"]      types [GitCommit]          → no
```

Matching is pure and cheap; it never reads the filesystem and never calls a model. A subscription
that requires work to evaluate is a subscription that does that work on every event, and on a
phone that is a battery bug with a design cause.

### Admission

Matched sessions are **candidates**, not Runs. Admission applies, in order:

1. **The project lock** (RFC-0055). No runtime holding it, no wake.
2. **Availability.** A session whose subscription implies a tool this profile cannot offer is not
   woken to discover that (RFC-0049).
3. **Foreground, on MOBILE.** See below.
4. **Budget.** A session out of wake budget is not woken (RFC-0028).
5. **Fairness.** Among the remainder, least-recently-run first. Deliberately not priority: a
   priority scheme needs a source of priorities, and inventing one would be D10's mistake in
   another costume.

Concurrency past this point is RFC-0007's: parallelism is across Runs, the worktree is the lock
(D15), and at most one effectful Task per Run is `RUNNING` (D14).

### Declining to wake

**A wake that cannot make progress is worse than no wake.** It costs battery, produces a Run that
parks immediately, and puts a notification in front of the user that resolves to "nothing
happened".

On MOBILE without a foreground service, the Scheduler wakes a session **only if deterministic
work is available** — indexing, fetching, reconciling Git, assembling context (D24). A handler
that reaches a model call parks with `ForegroundRequired`, which is correct behaviour but should
be entered deliberately rather than stumbled into.

```
wake if   deterministic work is available
   else   defer — the next foreground session picks it up
```

Deferral is not loss. The event is durable (RFC-0004) and the session is woken when the user next
opens the app — *faster* than it would otherwise have been, because the deterministic preparation
already happened.

### Cycles and amplification

A session woken by an event, which emits an event, which wakes it again, will flatten a battery
before anyone notices.

Two bounds, both already in the schema:

- **`events.causal_depth`** increments along a causal chain. Past a ceiling the wake is refused
  and recorded (RFC-0028).
- **Self-wake is refused by default.** A session is not woken by an event it sourced, unless its
  subscription opts in. The opt-in exists because a few workflows genuinely want it; the default
  exists because most such loops are accidents.

Refusals are **recorded, not silent** (RFC-0037). A subscription that stops firing without
explanation is indistinguishable from a broken one.

## Data Model

Subscriptions live with sessions (`schema/project.sql`); `events.causal_depth` carries the
amplification bound.

The Scheduler holds **no persistent state of its own.** Its candidate set is derived from
subscriptions and the event being processed. A work queue that survived a restart would be a
second source of truth about what should run — and under D3, state that must survive is a column,
while this need not survive at all.

## Security

The Scheduler is `RUNTIME`-actor code (RFC-0046) and can wake any session, so it is trusted. But
it **grants nothing**: a woken session runs with exactly the capabilities it already held. Waking
is not an authority event.

An attacker able to forge an event could cause a session to *run* — which is why event
publication is itself capability-controlled (RFC-0004) — but could not cause it to run with more
authority than it had.

Wake refusals being auditable matters more than it sounds: *"why did my scheduled session never
run"* and *"why is my battery dead"* have the same answer source.

## MVP

1. **Event-driven wake** — topic and type matching, so a session wakes from a subscribed event.
   The load-bearing case is a driver waking when its worker completes; without it the
   driver/worker model does not function (RFC-0011, D15). **M5.**
2. **Deterministic-only wake on MOBILE without a foreground service** (D24). **M21.**
3. **`causal_depth` ceiling and self-wake refusal, both recorded.** **M6.** This is a runaway
   bound in the same class as the step and budget ceilings (RFC-0028), not a scheduling feature:
   an event loop that can feed itself is the one failure that does not stop on its own.

Not in the MVP: **timers and scheduled triggers**, the full admission policy (lock, availability,
budget, least-recently-run ordering), priorities, deadline scheduling, speculative pre-waking,
cross-project scheduling. `scheduled_jobs` exists in the schema and nothing writes it before G4.

The split is deliberate: **waking from an event is part of the session model; waking on a clock is
a feature.** The first is required for workers to report back at all; the second is what RFC-0044's
recurring sessions need, and those are post-MVP.

## Future Work

- **Coalescing.** A hundred `FileChanged` events from a branch switch should wake a session once,
  not a hundred times. Needs a debounce window per subscription and a rule for what the session
  is told it missed.
- **Predictive wake** — preparing context before the user opens the app, based on when they
  usually do. Cheap to get wrong in a way that costs battery.
- **Cross-project scheduling**, once more than one project can be open at once.

## Open Questions

- Should a subscription declare *"I only need deterministic work"*, so the Scheduler can wake it
  confidently in the background rather than inferring the answer from its handler?
- When a wake is deferred on MOBILE and the same event class fires forty more times before the
  user opens the app, is the session woken once with a summary or once per event? Coalescing is
  the general answer; the deferred case needs it first.
