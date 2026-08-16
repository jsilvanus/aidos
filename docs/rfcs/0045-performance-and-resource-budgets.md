# RFC-0045: Performance and Resource Budgets

Status: Accepted 2026-08-03

## Abstract

This RFC defines the latency, memory, and battery targets Aidos designs against, and the
degradation ladder it climbs when a device cannot meet them. Its scope is deliberately narrow:
token and money budgets are capability constraints (RFC-0028), storage reclamation is retention
(RFC-0056), and measurement is observability (RFC-0037). What remains here is *how fast, how
heavy, and what gives way first.*

## Motivation

Targets that are not written down are not targets. The specific risk for Aidos is that every
performance decision looks acceptable on the machine it was developed on — a desktop with 32 GB
of RAM and no battery — and the product is meant to run on a mid-range phone.

Three constraints that only bind on mobile:

- **Memory is hard-capped and enforced by death.** Android's low-memory killer terminates the
  process; there is no back-pressure, no warning, and no recovery except the one already built
  (RFC-0009).
- **Battery is the metric users actually judge on.** An app that drains 20% overnight is
  uninstalled regardless of what it accomplished.
- **A local model is the single largest resource decision in the product.** Nothing else in the
  runtime is within an order of magnitude of it.

## Goals

1. State latency, memory, and battery targets per profile.
2. Define the degradation ladder.
3. Define the local model admission decision.
4. Define what is measured and what triggers action.

## Non-goals

Token and cost budgets: RFC-0028. Storage: RFC-0056. Measurement plumbing: RFC-0037.
This RFC does not define hardware-specific optimisation.

## Design

### Targets

Targets, not guarantees. Missing one is a bug to be filed, not an error to be raised.

| Operation | MOBILE | DESKTOP |
|---|---|---|
| Runtime API call (local query) | < 50 ms | < 20 ms |
| Project open (warm) | < 500 ms | < 200 ms |
| Project open (cold, with recovery) | < 3 s | < 1 s |
| Checkpoint write | < 20 ms | < 10 ms |
| Knowledge query (indexed) | < 200 ms | < 100 ms |
| Local model load (small) | < 8 s | < 4 s |
| Local first token | < 10 s | < 3 s |
| Foreground service memory (idle) | < 120 MB | n/a |
| Foreground service memory (running, no model) | < 250 MB | n/a |

Checkpoint write time is the one to watch. It happens at every step boundary (RFC-0009), so a
slow checkpoint taxes every operation in the system, and on flash storage a synchronous write
per step is exactly the pattern that ages a device.

**Battery:** an idle project with no active Run consumes no measurable power — no polling, no
timers, no watchers on MOBILE. This follows from the event-driven design (RFC-0005) and is worth
stating as a target because it is easy to break with one well-meaning background poll.

### The degradation ladder

When a device cannot meet targets, the runtime degrades in a fixed order, most-recoverable
first. Each rung is user-visible.

| Rung | Trigger | Action |
|---|---|---|
| 1 | sustained background pressure | pause indexing and compaction |
| 2 | memory pressure | unload the loaded model; keep weights on disk *(on MOBILE, no longer Aidos Agent's action to take — see Amendment below)* |
| 3 | continued pressure | drop knowledge index caches; queries degrade to keyword |
| 4 | low battery, not charging | suspend all `DEFERRED` and `OPPORTUNISTIC` work (RFC-0044) |
| 5 | critical memory | park active Runs at the next checkpoint; do not start new ones |
| 6 | thermal throttling | disable local inference; route remote or report `UNAVAILABLE_OFFLINE` *(on MOBILE, Agent can stop calling, not disable — see Amendment below)* |

Rung 5 is why the whole architecture is checkpointed: **the runtime can always stop cleanly**,
because there is always a recent safe point to stop at. A system that had to abandon in-flight
work under memory pressure would lose it.

Degradation is announced, not silent. A user whose semantic search quietly became keyword search
concludes the search is bad; a user told "index paused to save battery" understands and can
override.

### The local model admission decision

Loading a local model is the largest single resource commitment the runtime makes. It is an
explicit admission decision, taken device-globally (RFC-0020), not an implicit consequence of a
query.

Admission requires: available memory ≥ model working set × 1.3; not thermally throttled; and
either charging or above a battery floor for large models. Failing admission is not an error —
routing resolves to a smaller local model, to remote (subject to policy), or to
`UNAVAILABLE_OFFLINE` (RFC-0020).

Only one large model is resident at a time. Two 4 GB models on a phone is not a tuning question.

**Amendment 2026-08-14 (RFC-0103) — on MOBILE, this section describes DESKTOP/HEADLESS_SERVER
and pre-split MOBILE; it no longer describes what Aidos Agent itself controls.** RFC-0103 moves
local model hosting off Aidos Agent onto a separate app, **Aidos Engine**, reached over a loopback
transport. Two consequences this RFC did not anticipate:

- **The admission decision above is Aidos Engine's, not "the runtime['s]."** Engine "loads a model
  on first request for it... [and] may [keep] multiple models... resident simultaneously if the
  device's available memory allows it," evicting least-recently-used only when it doesn't — a
  policy extended "from one project's models to every modality and every client on the device"
  (RFC-0103, "Concurrency and memory policy"). **"Only one large model is resident at a time" is no
  longer true in general on MOBILE**: Engine may hold several residents at once across several
  client apps, none of which is Aidos Agent's decision to make or veto.
- **Rungs 2 and 6 are not actions Aidos Agent can perform on MOBILE.** Agent has no handle on
  Engine's residency and no authority to unload a model Engine is also serving to a different
  client, or to disable local inference device-wide — it can only stop calling Engine itself,
  which surfaces to a Run as `UnavailableOffline`/`UNAVAILABLE_OFFLINE`, same as today's "nothing
  fits" case. Whether Engine implements its own analogous degradation ladder under its own memory/
  thermal pressure is Engine's design question, not specified by this RFC and not designed by
  RFC-0103 either (RFC-0103 states memory-based eviction only; it does not mention thermal
  throttling at all).
- **This section's admission math (`≥ model working set × 1.3`, battery-floor gating) still
  describes the right computation — it has just moved.** It is now Aidos Engine's admission
  policy to implement, not Aidos Agent's. This RFC's own Non-goals already exclude "the inference
  engine's internals" (implicitly RFC-0022's territory before the split, Aidos Engine's after it),
  so this amendment does not relocate scope this RFC ever claimed to own outright — it corrects
  which component the existing text was describing.

Not amended by this note, and unaffected: rungs 1, 3, 4, 5 (indexing, knowledge caches, background
work, and Run parking are all still Aidos Agent's own resources and remain exactly as specified).
DESKTOP and HEADLESS_SERVER are unaffected — RFC-0103 is MOBILE-only (its own Non-goals).

### What is measured, and what acts on it

Measurement is RFC-0037. What matters here is which measurements have a **consequence**:

| Measured | Consequence |
|---|---|
| Checkpoint write duration | > 50 ms sustained → investigate; it taxes everything |
| Foreground service wall-clock | the battery proxy; drives rung 4 |
| Model load time and peak memory | feeds the admission decision |
| Step duration distribution | feeds the execution-window deadline (RFC-0009) |
| Index staleness | drives rung 1 resumption |

A metric with no consequence is telemetry for its own sake, and this runtime does not ship
telemetry.

### Benchmarks, not gates

Performance is tracked over time on a reference device, not asserted in CI. Absolute numbers are
machine-dependent, so a pass/fail threshold in a test suite produces flaky builds and trains
people to ignore them (RFC-0038). What is tracked is **regression**: a 2× change in checkpoint
write time between releases is a finding, whatever the absolute value.

## Data Model

```sql
CREATE TABLE resource_budgets (
    scope TEXT NOT NULL,              -- 'user' | 'project'
    scope_id TEXT,
    key TEXT NOT NULL,                -- 'memory_mb' | 'battery_floor_pct' | ...
    value INTEGER NOT NULL,
    PRIMARY KEY (scope, scope_id, key)
);

CREATE TABLE degradation_events (
    id TEXT PRIMARY KEY,
    rung INTEGER NOT NULL,
    trigger TEXT NOT NULL,
    entered_at TEXT NOT NULL,
    exited_at TEXT,
    project_id TEXT
);
```

`degradation_events` exists so "why was it slow last Tuesday?" is answerable — degradation that
leaves no record looks identical to a defect.

## Security

Resource exhaustion is a denial-of-service vector against the user's own agent: content that
induces very large tool outputs or very long Runs can consume memory and battery. The bounds are
step ceilings and budgets (RFC-0008, RFC-0028) and response size limits (RFC-0042); the failure
is bounded, visible, and recoverable.

Degradation must never weaken a security control. Under memory pressure the runtime drops caches
and parks Runs; it does not skip capability validation, redaction, or audit writes. Those are
small and are never on the degradation ladder.

## MVP

1. Targets recorded and measured on a reference mid-range device.
2. Degradation rungs 1, 2, 4, and 5, with user-visible announcements.
3. Model admission control with the memory and thermal checks.
4. `degradation_events` recording.
5. Regression benchmarking of checkpoint write, project open, and first token.

Not in MVP: rungs 3 and 6, per-project resource budgets, adaptive step-cost estimation.

## Future Work

Adaptive execution windows using observed step durations, so the mobile deadline decision
(RFC-0009) becomes sharper than a fixed estimate.

Battery attribution per project, answering "which project is costing me power".

Model quantisation selection by device class, so a mid-range phone gets a smaller variant
automatically rather than failing admission.
