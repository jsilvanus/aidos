# RFC-0029: Error Taxonomy and Failure Semantics

Status: Draft

## Abstract

This RFC defines a single error taxonomy shared by the Execution Graph, the Tool Broker, the
agent loop, and the Runtime API. Each error class declares its retryability, its recovery
class, its visibility, and whether it is reported to the model, the user, or both.

## Motivation

Two disjoint enumerations of the same domain existed: `ErrorCategory` in RFC-0019
(`CAPABILITY_DENIED`, `TOOL_ERROR`, `MODEL_ERROR`, `TIMEOUT`, `CANCELLED`, `UNKNOWN`) and
`ErrorCode` in RFC-0052 (`PROJECT_NOT_FOUND`, `SESSION_NOT_FOUND`, `CAPABILITY_DENIED`, …).
They overlap, disagree, and neither says what to *do* with an error.

In a system whose defining behaviour is long-running autonomous work, failure handling is not
an edge case — it is most of the interesting behaviour. Retry policy (RFC-0019), recovery class
(RFC-0009), taint escalation (RFC-0027), and budget exhaustion (RFC-0028) all key off error
identity, and they cannot do so consistently against two enumerations.

## Goals

1. Define one error type used everywhere.
2. Define, per class: retryability, recovery class, audience, and terminal effect.
3. Define how errors reach the model versus the user.
4. Define stability rules so error identity can be relied on by frontends and plugins.

## Non-goals

This RFC does not define logging (RFC-0037) or user-facing copy.

## Design

### The error type

```kotlin
data class AidosError(
    val code: ErrorCode,
    val message: String,            // developer-facing, English, not user copy
    val detail: Map<String, String> = emptyMap(),
    val cause: AidosError? = null
)
```

`code` is a stable string identifier, namespaced by domain: `capability.denied`,
`tool.invalid_arguments`, `model.rate_limited`, `git.repo_mutated`. String codes rather than an
enum, because plugins and MCP adapters introduce codes the core does not know, and an enum
would force them all into `UNKNOWN`.

### Error classes

Every code belongs to exactly one class, and the class determines behaviour.

| Class | Retryable | Recovery class | Audience | Terminal effect |
|---|---|---|---|---|
| `TRANSIENT` | yes, with backoff | `IDEMPOTENT` | model (as data) | none; retry |
| `RATE_LIMITED` | yes, honour retry-after | `IDEMPOTENT` | model + user if prolonged | none; retry |
| `INVALID_INPUT` | no | `PURE` | **model** | none; model corrects itself |
| `DENIED` | no, until authority changes | `PURE` | model + user (approval) | none; model adapts or Run fails |
| `UNAVAILABLE` | no | `PURE` | model + user | Run fails if required |
| `EXHAUSTED` | no | `PURE` | user | Run terminates |
| `CONFLICT` | no, needs reconciliation | `PURE` | user | Run terminates |
| `INDETERMINATE` | **never** | `UNSAFE` | user | Run terminates, manual review |
| `INTERNAL` | no | `PURE` | user | Run terminates, bug report |

The two classes that matter most and did not previously exist:

**`INVALID_INPUT` is routed to the model, not the user.** A model that emits arguments failing
schema validation must be told, so it can fix them. Surfacing that to the user as an error is
noise, and failing the Run is a waste of the work so far. This is the largest practical
difference between a runtime that works and one that is infuriating.

**`INDETERMINATE` is never retried.** An `UNSAFE` effect (RFC-0009) that may or may not have
landed — a `git push` interrupted mid-flight, an outbound notification — is reported to the
user with what is known. Silently retrying is how duplicate pushes and double notifications
happen.

### Registry

| Code | Class | Notes |
|---|---|---|
| `tool.unknown` | `INVALID_INPUT` | model named a tool that does not exist |
| `tool.invalid_arguments` | `INVALID_INPUT` | schema validation failed; detail carries violations |
| `tool.execution_failed` | `TRANSIENT` | tool ran and failed recoverably |
| `tool.timeout` | `TRANSIENT` | |
| `tool.unavailable_on_profile` | `UNAVAILABLE` | RFC-0049; detail carries tool and profile |
| `capability.denied` | `DENIED` | detail carries `DenialReason` |
| `capability.requires_approval` | `DENIED` | resolvable by user approval |
| `capability.attenuated_by_taint` | `DENIED` | RFC-0027; detail names the tainting node |
| `model.rate_limited` | `RATE_LIMITED` | |
| `model.context_overflow` | `INVALID_INPUT` | prompt exceeded window; assembly retries smaller |
| `model.refused` | `INVALID_INPUT` | provider refusal; returned to loop |
| `model.provider_error` | `TRANSIENT` | |
| `model.unavailable_offline` | `UNAVAILABLE` | offline and no local model satisfies the request |
| `budget.exhausted` | `EXHAUSTED` | RFC-0028; detail names the dimension and scope |
| `run.step_limit` | `EXHAUSTED` | |
| `run.no_progress` | `EXHAUSTED` | RFC-0008 repeated-call detection |
| `git.repo_mutated` | `CONFLICT` | RFC-0053 |
| `git.intent_conflicted` | `CONFLICT` | RFC-0053 |
| `git.push_indeterminate` | `INDETERMINATE` | |
| `storage.migration_required` | `CONFLICT` | project written by a newer runtime |
| `storage.corrupt` | `INTERNAL` | |
| `runtime.locked_by_other_instance` | `CONFLICT` | RFC-0055 |
| `internal.*` | `INTERNAL` | |

### Reaching the model versus the user

`ToolCallResult.outcome` (RFC-0008) carries `Failed(AidosError)` or `Denied`. The agent loop
renders errors whose audience includes the model into the transcript as tool results, in a
consistent, minimal form:

```
<tool_result call_id="..." status="error" code="tool.invalid_arguments">
  Parameter "path" must be a string relative to the project root.
</tool_result>
```

Errors whose audience is the user only are never rendered into the transcript. Leaking internal
failures into the model's context wastes tokens and invites the model to speculate about
runtime internals.

### Stability

Error codes are part of the public contract. A code may be added at any time; a code may not be
removed or have its class changed without an API version increment (RFC-0052). Frontends and
plugins must treat unknown codes as their declared class if present, and `INTERNAL` otherwise.

`ErrorCode` in RFC-0052 and `ErrorCategory` in RFC-0019 are both replaced by this registry.
`AttemptError` (RFC-0019) becomes `AidosError` plus the class, which is derivable from the code.

## Data Model

```sql
-- Replaces AttemptError's ad hoc shape.
ALTER TABLE attempts ADD COLUMN error_code TEXT;
ALTER TABLE attempts ADD COLUMN error_class TEXT;
ALTER TABLE attempts ADD COLUMN error_detail_json TEXT;

CREATE INDEX idx_attempts_error ON attempts(error_class) WHERE error_class IS NOT NULL;
```

Indexing by class rather than code is what makes "how often are Runs failing for reasons the
user must act on?" a cheap query.

## Security

Error messages and `detail` maps are subject to secret redaction (RFC-0035) before storage or
transmission. Tool errors frequently embed command lines and paths, and command lines
frequently embed credentials.

`DENIED` errors must not disclose the existence of resources the session may not know about.
"Capability denied" is the correct message; "capability denied for /home/user/.ssh/id_rsa" is
an information leak to a possibly-injected model.

## MVP

1. `AidosError`, string codes, the nine classes, the registry above.
2. Class-driven retry in RFC-0019's retry policy.
3. Model-audience errors rendered as tool results; user-audience errors surfaced via the
   Runtime API.
4. `INDETERMINATE` never retried, always surfaced.
5. Redaction of messages and details before persistence.

Not in MVP: localized user copy, error-rate telemetry.

## Future Work

Per-code user-facing copy and suggested remediation, owned by the frontend.

Error-rate monitoring to detect a provider or tool degrading over time (RFC-0037).
