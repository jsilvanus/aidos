# RFC-0037: Observability

Status: Draft

## Abstract

This RFC defines how Aidos is diagnosed: structured logging, metrics, tracing, crash reporting,
and the diagnostic bundle a user can share when something goes wrong. It establishes that the
Execution Graph **is** the trace, that diagnostic logs are disposable while the audit log is a
permanent security record, and that no diagnostic data leaves the device unless the user sends
it.

## Motivation

Aidos runs long-lived autonomous sessions, local model inference, and background indexing — on a
phone, in short execution windows, frequently while the user is not watching. When something
goes wrong, the user was usually asleep.

Three failure classes are otherwise undiagnosable:

- **"It stopped and I don't know why."** A Run parked at a checkpoint and never resumed.
- **"It did something I didn't expect."** Which context, which model, which capability.
- **"My battery and storage disappeared."** Which subsystem consumed them, and when.

Without structured observability these are unanswerable, and the maintainer's only recourse is
asking the user to reproduce — which, for a background agent on a phone, means asking them to
reproduce conditions they cannot describe.

There is also a privacy tension specific to this product. Diagnostic data for an AI runtime
contains prompts, file contents, and command output. **The usual answer — ship telemetry to the
maintainer — is unavailable to us**, and designing as though it were available would betray the
offline-first promise. Everything below assumes the data stays on the device.

## Goals

1. Define the logging model, levels, and redaction rules.
2. Define the metric set, with mobile observation cost as a first-class constraint.
3. Define tracing and its relationship to the Execution Graph.
4. Define crash reporting and the diagnostic bundle.
5. Define the privacy boundary for all of the above.

## Non-goals

This RFC does not define the audit log's contents (RFC-0003, RFC-0018) — only its relationship
to diagnostic logging.
It does not define performance budgets (RFC-0045) — only how usage is observed.
It does not define a hosted telemetry service. There is none.

## Design

### Three separate records, deliberately not unified

A recurring mistake is to merge these. They have different lifetimes, audiences, and privacy
rules, and merging them means the strictest rule governs all three — or, worse, the loosest.

| | Audit log | Execution Graph | Diagnostic log |
|---|---|---|---|
| Answers | "what was authorised and exercised?" | "what did the agent do?" | "why did the code misbehave?" |
| Audience | user, forensics | user, UI | maintainer, developer |
| Lifetime | `PERMANENT` (RFC-0056) | skeleton `PERMANENT`, payloads `AGED` | `AGED`, days |
| Leaves device | only in a user-made export | same | only in a user-sent bundle |
| Can be disabled | **no** | no | yes |

The audit log is not a log level. It cannot be turned down, sampled, or dropped under pressure.

### Structured logging

Every record is structured, never a formatted string:

```kotlin
log.warn("model.call.retry") {
    field("run_id", runId)
    field("task_id", taskId)
    field("attempt", 2)
    field("error_code", "model.rate_limited")   // RFC-0029
    field("provider", "anthropic")
}
```

| Level | Meaning | Default |
|---|---|---|
| `ERROR` | the runtime failed at something it promised | on |
| `WARN` | degraded but continuing, with a user-visible consequence | on |
| `INFO` | lifecycle: project open, Run start/end, model load/unload | on |
| `DEBUG` | step detail: prompt sizes, routing decisions, cache hits | off |
| `TRACE` | payload detail — see redaction | off, heavily constrained |

**Correlation is mandatory.** Every record carries `run_id`, `task_id`, and `attempt_id` where
they exist. A log line that cannot be tied to a Run is nearly useless in a system where several
sessions interleave.

### Redaction applies to logs, unconditionally

Log records pass through the same redactor as event payloads (RFC-0035) before being written.
Detected secret patterns are replaced; `SECRET`-labelled content (RFC-0024) is never admitted.
This is not conditional on level, build type, or configuration.

At `TRACE`, prompt and tool-result payloads may be recorded. Because that is the highest-risk
setting in the product, `TRACE`:

- is enabled explicitly per subsystem, never globally;
- auto-disables after 30 minutes;
- writes to a separate file excluded from diagnostic bundles unless separately opted in;
- shows a persistent UI indicator while active.

A debug setting that silently persists and quietly captures every prompt is a data-exfiltration
feature waiting to be found.

### The Execution Graph is the trace

Aidos does not implement a separate tracing system. Distributed-tracing vocabulary maps onto
structures that already exist:

| Tracing concept | Aidos |
|---|---|
| Trace | `Run` |
| Span | `Task` |
| Span retry | `Attempt` |
| Span attributes | Attempt columns: model, tokens, cost, capability, recovery class |
| Causal parent | `PRODUCED_CALL` edge (RFC-0008), `causality` on events |

The Execution Graph already records timings, outcomes, and parentage, and is already persisted
and queryable (RFC-0019). Adding spans beside it would duplicate every write on a device where
writes cost battery, and produce two records that can disagree.

OpenTelemetry export is available as an **adapter over** the Execution Graph on desktop — a
read path, not a second write path.

### Metrics

Metrics are aggregates in SQLite, sampled rather than streamed. On mobile the observation cost
must stay well below the cost of the thing observed.

**Runtime health**
- Run outcomes by state and error class (RFC-0029)
- Step duration distribution; checkpoint write duration
- Recovery events: interruptions, resumptions, `INDETERMINATE` outcomes
- Capability denials by reason; taint escalations (RFC-0027)

**Resource** — what users actually complain about
- Storage per project by retention class (RFC-0056)
- Model load time, inference latency, tokens/sec, peak memory
- Foreground service wall-clock time, as a battery proxy
- Index size and staleness

**Cost**
- Tokens and cost units by model, session, and period (RFC-0028)
- **Local versus remote call ratio** — the single number that says whether offline-first is real

Metrics are local. There is no aggregation endpoint. The user sees their own numbers.

### Crash reporting

On an uncaught exception the runtime writes a crash record — stack trace, runtime version,
platform profile, the last 200 log records from a ring buffer, and the IDs of in-flight Runs —
to `.aidos/crashes/`. **Nothing is transmitted.**

On next start the user is told and offered a bundle. Platform crash reporting is not wired to a
backend by default; a distribution build that enables it must disclose that in its privacy
notice.

### The diagnostic bundle

The primary support mechanism. `aidos diagnose`, or *Report a problem*, produces one reviewable
file:

```
aidos-diagnostic-<timestamp>.zip
├── manifest.json         runtime version, profile, OS, device class, enabled features
├── logs/                 recent diagnostic logs, redacted
├── crashes/              crash records
├── metrics.json          aggregates, no content
├── execution/            Execution Graph skeletons for selected Runs:
│                         states, timings, error codes — no prompts, no file content
├── schema.json           schema version and applied migrations
└── redaction-report.txt  what was removed, and why
```

Three properties make it usable:

1. **Redacted by default**, with the redaction report making the redaction itself auditable.
2. **Inspectable before sending** — plain files, no opaque blob. The user can read exactly what
   they are about to hand over.
3. **Selective** — the user picks which Runs to include; the default is the failing one.

Prompts and file contents are **excluded by default** and require a separate explicit opt-in per
bundle, because that is where the user's actual work lives.

### Platform notes (RFC-0049)

**MOBILE.** Log writes are batched and flushed at checkpoints, not per record — a synchronous
write per line on flash is a real battery and latency cost. `DEBUG` is unavailable in release
builds. Ring buffers are bounded to 200 records. Log files age out at 7 days, shorter than
desktop.

**DESKTOP.** Logs stream to file and optionally stderr; all levels available. The daemon exposes
a status endpoint over the same authenticated local socket as the Runtime API (RFC-0055) —
never an open port.

## Data Model

```sql
CREATE TABLE metric_samples (
    id TEXT PRIMARY KEY,
    project_id TEXT,                  -- NULL for user-scope metrics
    name TEXT NOT NULL,
    value REAL NOT NULL,
    unit TEXT NOT NULL,
    labels_json TEXT NOT NULL DEFAULT '{}',
    bucket_start TEXT NOT NULL,       -- aggregation window start
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

CREATE INDEX idx_metrics_name ON metric_samples(name, bucket_start);

CREATE TABLE crash_records (
    id TEXT PRIMARY KEY,
    occurred_at TEXT NOT NULL,
    runtime_version TEXT NOT NULL,
    platform_profile TEXT NOT NULL,
    error_code TEXT,
    stack_hash TEXT NOT NULL,         -- deduplicates repeated crashes
    detail_path TEXT NOT NULL,        -- file under .aidos/crashes/
    in_flight_run_ids TEXT NOT NULL DEFAULT '[]',
    reported INTEGER NOT NULL DEFAULT 0
);
```

Diagnostic logs are files, not rows. High-volume log lines in the operational database would
contend with the single writer (RFC-0007) and inflate the file the user backs up.

## Security

Observability is a data-exfiltration surface, and this section is the control.

1. **No telemetry.** Aidos ships no automatic reporting of any kind.
2. **Redaction is unconditional**, at every level and in every build.
3. **`TRACE` is time-limited, per-subsystem, separately stored, and visibly indicated.**
4. **Bundles are user-initiated, inspectable, and redacted**, with content excluded by default.
5. **Logs must not weaken denials.** A `capability.denied` record logs the capability ID and
   operation, not the target a possibly-injected model requested. A log is not exempt from the
   information-disclosure rule the error itself is subject to (RFC-0029).
6. **The status endpoint is on the authenticated local socket only.**

## MVP

1. Structured logging with correlation IDs and unconditional redaction.
2. Levels `ERROR`/`WARN`/`INFO`; `DEBUG` on desktop.
3. Metrics: Run outcomes, step duration, storage per project, tokens and cost, local-vs-remote
   ratio.
4. Crash records written locally with a stack hash.
5. Diagnostic bundle with redaction report; content excluded by default.
6. Execution Graph as the trace — no separate tracing system.

Not in MVP: `TRACE` mode, OpenTelemetry adapter, metric retention beyond the default window,
desktop status endpoint.

## Future Work

`TRACE` mode with the safeguards above, once a real debugging need justifies it.

OpenTelemetry export adapter over the Execution Graph for desktop users with existing tooling.

Anomaly surfacing: telling the user their session failure rate or spend has changed, rather than
waiting for them to notice.
