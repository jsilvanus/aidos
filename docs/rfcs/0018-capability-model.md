# RFC-0018: Capability Model

Status: Accepted 2026-08-03

## Abstract

This RFC defines how capabilities are represented, granted, delegated, exercised, and revoked in the Aidos runtime. It turns the capability-based security model from RFC-0003 into enforceable implementation semantics: concrete data structures, grant and exercise flows, delegation and attenuation rules, revocation propagation, and mobile enforcement constraints.

## Motivation

RFC-0003 (Security) establishes capability-based access control as the security model. It defines what capabilities conceptually are, what permission categories exist, and how grants and revocations work at a user-visible level.

What RFC-0003 does not define — and what implementation requires — is:

- The exact in-memory and persistent representation of a capability.
- How the Tool Broker and AI Engine check capabilities at call sites.
- How a session delegates a narrowed capability to a worker session.
- How revocation propagates to in-flight operations.
- What the enforcement model looks like on Android, where process isolation is limited.
- How capabilities survive process restart.

Without these specifics, "check the capability before calling the tool" is guidance, not a contract. Different implementers will check different things in different ways, leading to security inconsistencies.

## Goals

1. Define the Capability struct with complete field semantics.
2. Define the grant flow: user grants a capability to a session.
3. Define the exercise flow: a session exercises a capability via the Tool Broker.
4. Define attenuation: a session delegates a narrowed capability to a worker.
5. Define revocation: immediate effect on future exercises, propagation to in-flight.
6. Define the enforcement model for both desktop (process isolation) and Android (application-layer).
7. Define how capabilities survive process restart.
8. Define confused-deputy mitigation.

## Non-goals

This RFC does not define cryptographic algorithms.
It does not define user interface flows for approval prompts (RFC-0003 covers the user experience).
It does not define capability transfer across devices (future work).

## Design

### Designation and authority are the same act

The central rule of this model, and the one that distinguishes it from an access-control list:

> **A subject exercises a *named* capability. The runtime never searches for an authority that
> would permit an operation.**

An earlier version of this RFC specified `check(subjectId, permission, resourceRef)`: the
caller presented an identity and a string, and the runtime looked for any grant held by that
identity that permitted it. That is ambient authority scoped to a subject — an ACL — and it
fails against the threat this model exists to address. If a session holds
`fs:write:/project/**`, that authority applies to whatever path arrives in the parameters,
including a path chosen by injected content. The confused-deputy problem is about *designation*,
not identity, so binding capabilities to `subjectId` alone does not solve it.

Two mechanisms implement the rule:

**1. Handles.** Capabilities over hierarchical resources are exercised through handles that
carry their own scope. Path resolution happens *inside* the handle, relative to a root fixed at
grant time.

```kotlin
interface DirHandle {
    val capabilityId: CapabilityId
    suspend fun read(relative: RelPath): ByteArray
    suspend fun write(relative: RelPath, content: ByteArray)
    suspend fun list(relative: RelPath): List<DirEntry>
}
```

`RelPath` rejects absolute paths, `..` segments, and NUL bytes at construction. The handle
resolves against its root with symlinks disallowed by default, so escape is prevented **by
construction** rather than by filtering. This removes the entire path-canonicalization attack
surface — `..`, symlinks, case-insensitive filesystems, Unicode normalization — instead of
attempting to validate against it, which is why no canonicalization rules appear in this RFC.

**2. Explicit exercise.** Non-hierarchical operations take a capability ID:

```kotlin
suspend fun <T> exercise(capabilityId: CapabilityId, operation: Operation<T>): Result<T>
```

The caller states which authority it is invoking. Denials become precise, audit records name
the exact grant, and no operation proceeds because *some* grant happened to cover it.

### The Capability Object

A capability is an immutable record created by the runtime's Capability Manager. Sessions cannot create or modify capabilities. They can only hold and exercise them.

```kotlin
data class Capability(
    val id: UUID,
    val permission: Permission,
    val subjectId: UUID,               // session or worker that holds this capability
    val subjectKind: SubjectKind,      // SESSION or WORKER
    val scope: CapabilityScope,        // the specific resource or domain this allows access to
    val constraints: CapabilityConstraints,
    val issuedAt: Instant,
    val issuedBy: GrantSource,         // USER or DELEGATION
    val parentCapabilityId: UUID?,     // if issued by delegation, the parent capability
    val expiresAt: Instant?,           // null means no expiry
    val revokedAt: Instant?,           // null means not revoked
    val revokedBy: String?,            // user ID or "system"
    val revocationEpoch: Long,         // see Revocation
    val allowsDelegation: Boolean,     // may the holder delegate a narrower copy?
    val auditRef: UUID                 // audit log entry for this grant
)

enum class SubjectKind {
    SESSION,     // a session acting for the user
    WORKER,      // a worker session, always holding delegated authority
    PLUGIN,      // a sandboxed plugin host (RFC-0043)
    MCP_SERVER,  // an MCP server adapter (RFC-0031)
    FRONTEND     // a connected frontend (RFC-0052, RFC-0055)
}

sealed class GrantSource {
    object User : GrantSource()
    data class Delegation(val delegatingSessionId: UUID) : GrantSource()
    data class Default(val sourceConfigId: UUID) : GrantSource()
}
```

**Why plugins and MCP servers are subjects.** Previously only sessions and workers were
subjects, which meant an MCP server's tool executed under the *calling session's* authority.
That reproduces the confused-deputy problem one level out: a hostile MCP server receives the
full authority of whatever session happened to call it. Making them first-class subjects means
an MCP server holds its own attenuated grant, and a session invoking it can delegate no more
than it holds itself.

Frontends are subjects for a different reason: on desktop the runtime is a daemon (RFC-0055)
and a connecting client must be authorized. `FRONTEND` capabilities govern which commands a
connection may issue — in particular, only a `user_interactive` frontend may approve capability
requests.

### Capability Scope

The scope defines what resource or domain the capability grants access to. Each permission type has its own scope type.

```kotlin
sealed class CapabilityScope {
    data class FilesystemScope(
        val projectId: UUID,
        val pathPattern: String     // e.g. "/project/src/**", "/project/tests/"
    ) : CapabilityScope()

    data class ShellScope(
        val projectId: UUID,
        val workingDirectory: String,
        val allowedCommands: List<String>?  // null means all commands within shell:exec
    ) : CapabilityScope()

    data class GitScope(
        val projectId: UUID,
        val allowedOperations: Set<GitOperation>  // READ, WRITE, PUSH, PULL, WORKTREE
    ) : CapabilityScope()

    data class NetworkScope(
        val allowedDomains: List<String>,  // e.g. ["api.anthropic.com", "*.github.com"]
        val allowedPorts: List<Int>?       // null means standard ports only
    ) : CapabilityScope()

    data class ModelScope(
        val allowedProviders: List<String>?,  // null means all configured providers
        val allowedCapabilities: Set<ModelCapabilityKind>
    ) : CapabilityScope()

    data class WorkerScope(
        val maxWorkerCount: Int,
        val allowedRoles: Set<SessionRole>
    ) : CapabilityScope()

    data class SecretsScope(
        val projectId: UUID,
        val allowedSecretIds: List<UUID>?  // null means all project secrets
    ) : CapabilityScope()
}
```

### Capability Constraints

```kotlin
data class CapabilityConstraints(
    val maxDurationSeconds: Int?,           // for shell: command timeout
    val maxBytesRead: Long?,                // for filesystem: read byte limit
    val maxBytesWritten: Long?,             // for filesystem: write byte limit
    val requiresApprovalPerUse: Boolean,    // user must approve each exercise
    val maxExerciseCount: Int?,             // null means unlimited uses
    val budget: Budget?                     // RFC-0028: tokens, cost, model calls, steps
)
```

`exercisedCount` is deliberately **not** a field on this immutable record. Counters are mutable
state and live in the `capability_usage` table, updated transactionally with the exercise
(RFC-0009's outcome checkpoint). Embedding a mutable counter in an immutable value was a
modelling error and had no backing column.

`budget` makes cost a security constraint rather than an accounting figure. A grant may permit
remote model access *and* cap what it may spend; exceeding it is a denial, not a warning
(RFC-0028).

### The Capability Manager

The Capability Manager is the sole authority for creating and revoking capabilities. It is a singleton within the runtime. No other subsystem creates `Capability` objects.

```kotlin
interface CapabilityManager {
    // Grant a new capability (called by user approval flow)
    suspend fun grant(
        sessionId: UUID,
        permission: Permission,
        scope: CapabilityScope,
        constraints: CapabilityConstraints,
        expiresAt: Instant?,
        grantedByUserId: String
    ): Capability

    // Delegate a capability from a session to a worker (attenuated)
    suspend fun delegate(
        parentCapability: Capability,
        toWorkerId: UUID,
        attenuatedScope: CapabilityScope,     // must be a subset of parentCapability.scope
        attenuatedConstraints: CapabilityConstraints  // must be equal or more restrictive
    ): Capability

    // Revoke a capability (immediate effect)
    suspend fun revoke(capabilityId: UUID, revokedBy: String)

    // Open a handle for a hierarchical capability. Path resolution happens inside the handle.
    suspend fun openHandle(
        subjectId: UUID,
        capabilityId: UUID
    ): Result<ResourceHandle>

    // Validate a named capability for a named operation, immediately before exercise.
    // Takes a capability ID: the caller states which authority it is invoking.
    suspend fun validate(
        subjectId: UUID,
        capabilityId: UUID,
        operation: Operation<*>,
        runTaint: TrustLevel          // RFC-0027: attenuates the effective grant
    ): CapabilityCheckResult

    // Load all active capabilities for a subject (on wake)
    suspend fun loadForSubject(subjectId: UUID): List<Capability>

    // Current revocation epoch for a project; caches compare against this
    fun currentEpoch(projectId: UUID): Long
}

sealed class CapabilityCheckResult {
    object Allowed : CapabilityCheckResult()
    data class Denied(val reason: DenialReason) : CapabilityCheckResult()
}

enum class DenialReason {
    NO_CAPABILITY,
    CAPABILITY_EXPIRED,
    CAPABILITY_REVOKED,
    SCOPE_MISMATCH,
    CONSTRAINT_EXCEEDED,
    REQUIRES_APPROVAL
}
```

### Grant Flow

1. A session requires a tool invocation that needs a capability it does not hold.
2. The Tool Broker calls `CapabilityManager.check()`. Result: DENIED (NO_CAPABILITY).
3. The Tool Broker publishes a `CapabilityRequested` event to the Event Bus.
4. The frontend receives the event and presents an approval UI to the user.
5. The user approves or denies.
6. If approved: the frontend calls `RuntimeClient.capabilities.grant(...)`.
7. The Capability Manager creates the `Capability` record, writes it to SQLite, writes an audit log entry, and returns the capability.
8. The Capability Manager adds the capability to the session's in-memory capability set.
9. The session is notified and retries the tool invocation.
10. The Tool Broker calls `CapabilityManager.check()`. Result: ALLOWED.

### Exercise Flow

The capability check happens at the Tool Broker boundary, before any external operation begins.

```kotlin
suspend fun executeTool(
    sessionId: UUID,
    call: ToolCall,               // RFC-0008: already schema-validated
    run: Run
): ToolCallResult {
    val tool = tools[call.toolName] ?: return failed(ErrorCode("tool.unknown"))

    // The session names the authority. The runtime does not search for one.
    val capabilityId = call.capabilityId
        ?: return denied(DenialReason.NO_CAPABILITY)

    val check = capabilityManager.validate(
        subjectId    = sessionId,
        capabilityId = capabilityId,
        operation    = tool.operationFor(call),
        runTaint     = run.taintLevel        // RFC-0027
    )
    if (check is Denied) {
        auditLog.record(CapabilityDenied(sessionId, capabilityId, call.toolName, check.reason))
        return denied(check.reason)          // returned to the model as data (RFC-0008)
    }

    budget.reserve(run, tool.estimatedCost(call))          // RFC-0028
    val handle = capabilityManager.openHandle(sessionId, capabilityId).getOrThrow()

    auditLog.record(ToolInvocationStarted(sessionId, capabilityId, call.toolName))
    val result = tool.execute(handle, call.arguments)      // paths resolve inside the handle
    auditLog.record(ToolInvocationCompleted(sessionId, capabilityId, result.outcome))

    return result
}
```

For hierarchical resources the handle carries the scope, so there is no separate `resourceRef`
to validate and no canonicalization step to get wrong.

**TOCTOU.** The validation and the operation are still not atomic at the OS level. The
guarantee is narrower and stated precisely: once validation passes for a specific exercise,
that exercise completes; revocation applies to subsequent validations. What has changed is that
in-flight work is now *bounded* — every effect sits between two checkpoints (RFC-0009), so the
window is one step rather than one Run, and `maxDurationSeconds` bounds it further.

### Attenuation (Delegation to Workers)

When a Driver session creates a Worker session, it may delegate a subset of its capabilities to the Worker. The Worker receives attenuated capabilities — narrower in scope and constraints than the parent.

Attenuation rules:
- The delegated scope must be contained within the parent scope. A session with `fs:read:/project/**` can delegate `fs:read:/project/src/**` but not `fs:read:/other-project/**`.
- The delegated constraints must be equal or more restrictive. A session with a 60s shell timeout can delegate a capability with a 30s timeout but not a 120s timeout.
- The delegated expiry must be equal or earlier than the parent's expiry.
- A Worker cannot further delegate a capability unless the parent capability had `allowsDelegation: true`.

Validation occurs at delegation time in `CapabilityManager.delegate()`. Invalid delegation requests are rejected with an error.

### Revocation

Revocation is immediate for all subsequent validations, and correctness does not depend on
cache invalidation messages arriving.

**Revocation epochs.** Each project holds a monotonically increasing `revocation_epoch`. Every
`Capability` records the epoch at which it was issued. Any revocation increments the project
epoch. A cached capability is valid only if its recorded epoch equals the current project
epoch; otherwise the cache entry is discarded and the capability re-read from SQLite.

This replaces cache-invalidation-by-notification, which was unsound. An earlier version relied
on removing entries from two separate in-memory caches, while RFC-0007 simultaneously declared
capability definitions "immutable once loaded" and safe to read from any dispatcher without
synchronization. Those two statements could not both hold. With epochs, a stale cache is
detected by comparison rather than corrected by a message, so a missed notification degrades to
a re-read instead of to a security failure.

Revocation propagates:
1. `revokedAt` is set and the project `revocation_epoch` is incremented, in one transaction.
2. All capabilities derived by delegation from the revoked capability are recursively revoked
   in the same transaction.
3. An audit log entry is written.
4. A `CapabilityRevoked` event is published (advisory; correctness does not depend on it).

**In-flight operations.** An exercise that has already passed validation completes. Revocation
takes effect at the next validation, which is at most one step away (RFC-0009). For long-running
`UNSAFE` effects this is a real limitation, and the UI must say so rather than implying that
revocation stops work already in progress: the runtime reports "revoked; one operation still
completing" rather than "revoked."

### Persistence Across Restarts

Capabilities are persisted in SQLite. When a session wakes, the Capability Manager loads all active (non-revoked, non-expired) capabilities for that session from SQLite.

This means capabilities survive process restart. A session that had filesystem write permission before the process crashed still has it after restart, unless the user revoked it while the process was down.

Expired capabilities are not loaded (the expiry check runs at load time). Revoked capabilities are not loaded.

### Enforcement per platform profile (RFC-0049)

Availability and authority are orthogonal: a tool may be available and forbidden, or permitted
and unavailable. This section covers enforcement strength, not what exists.

**All profiles — handle-mediated enforcement.** Sessions never call system APIs directly. Every
effect goes through a handle or an `exercise()` call, and the scope travels with the handle.
This is the primary mechanism and it is identical everywhere, which is what keeps one runtime
working across devices.

**MOBILE.** Android's app sandbox is a genuine OS-level boundary, and it is *stronger* than the
desktop default: the runtime cannot read outside its own storage regardless of what a
capability says, so a scope bug cannot become a whole-device compromise. There is no
general shell and no arbitrary subprocess (RFC-0049), which removes the largest class of
capability-escape entirely. Sessions on MOBILE do their work through Filesystem, Git, and
bundled tools.

The genuine weakness on MOBILE is that all sessions share one process, so a runtime code defect
— not a session — could bypass the Capability Manager. This is mitigated by the handle model
(there is no ambient path-taking API to misuse) and by audit, not by process isolation.

**DESKTOP / HEADLESS_SERVER.** Shell and subprocess execution exist, so OS-level containment is
required rather than optional: shell effects run in a child process with a restricted
environment, a working directory fixed by the capability scope, and platform sandboxing where
available. Plugin and MCP hosts run out of process as their own capability subjects
(RFC-0043, RFC-0055), with no access to the runtime's connection token.

**Audit as compensation.** Where OS enforcement is weaker, the audit record is richer: every
exercise records the capability ID, the subject, the resolved target, and the Run taint level.
Audit does not prevent anything; it makes what happened reconstructible, which is the honest
claim.

### Confused-Deputy Mitigation

A confused deputy attack occurs when a high-privilege component (the runtime) is tricked by a low-privilege caller (a session) into using its authority on behalf of the caller.

Aidos mitigates this structurally:

1. **Designation travels with authority**: a handle resolves paths against its own root, and
   `exercise()` takes a capability ID. The runtime never searches for an authority that would
   permit a requested operation, so a caller cannot induce the runtime to apply an authority it
   did not name. This is the substantive mitigation; the ones below are supporting.

2. **Capabilities are bound to subjects**: a capability records `subjectId`, and validation
   verifies it matches the requester. A session cannot exercise another session's capability
   even if it learns the ID.

3. **Every actor is a subject**: plugins, MCP servers, and frontends hold their own attenuated
   grants rather than borrowing the caller's. Without this, a hostile tool provider inherits
   whatever authority the calling session holds.

4. **Taint attenuates authority**: a Run whose context has admitted untrusted content operates
   under a reduced grant for the remainder of the Run (RFC-0027). This is what makes injected
   instructions bounded rather than fully empowered.

5. **No ambient authority for sessions**: the runtime's internal operations bypass capability
   checks as trusted infrastructure, and no session can invoke them directly. Note the honest
   limitation: on MOBILE all of this runs in one process, so this property is enforced by the
   absence of an ambient API rather than by a memory boundary.

## Data Model

```sql
CREATE TABLE capabilities (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL,
    permission TEXT NOT NULL,
    subject_id TEXT NOT NULL,
    subject_kind TEXT NOT NULL,
    scope_json TEXT NOT NULL,
    constraints_json TEXT NOT NULL,
    issued_at TEXT NOT NULL,
    issued_by TEXT NOT NULL,
    parent_capability_id TEXT,
    allows_delegation INTEGER NOT NULL DEFAULT 0,
    expires_at TEXT,
    revoked_at TEXT,
    revoked_by TEXT,
    revocation_epoch INTEGER NOT NULL,
    audit_ref TEXT NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (parent_capability_id) REFERENCES capabilities(id)
);

-- Mutable counters live outside the immutable capability record.
CREATE TABLE capability_usage (
    capability_id TEXT PRIMARY KEY,
    exercised_count INTEGER NOT NULL DEFAULT 0,
    bytes_read INTEGER NOT NULL DEFAULT 0,
    bytes_written INTEGER NOT NULL DEFAULT 0,
    budget_consumed_json TEXT NOT NULL DEFAULT '{}',
    last_exercised_at TEXT,
    FOREIGN KEY (capability_id) REFERENCES capabilities(id)
);

CREATE TABLE project_revocation_epoch (
    project_id TEXT PRIMARY KEY,
    epoch INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

CREATE INDEX idx_capabilities_subject ON capabilities(subject_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_capabilities_parent ON capabilities(parent_capability_id);
```

## Security

The Capability Manager is a security-critical component. It must be the only code path through which capabilities are created or validated. No code in the runtime may bypass it.

The `capabilities` table is written only by the Capability Manager. This is a **convention
enforced by code review and by a test**, not a database guarantee: in a single process sharing
one connection, any code holding that connection can write any table. Stating it as an
enforceable property was misleading. The test asserts that no module outside the Capability
Manager references the table.

The audit log entries for capability operations are append-only.

Capability IDs are UUIDs generated by the runtime's secure random source. They are not predictable.

## MVP

The MVP implements:

1. The `Capability` data structure with all fields, plus `capability_usage`.
2. `CapabilityManager` with `grant()`, `delegate()`, `openHandle()`, `validate()`, `revoke()`,
   `loadForSubject()`, and `currentEpoch()`.
3. `DirHandle` with `RelPath` rejecting absolute paths, `..`, and NUL; symlinks disallowed.
4. Explicit exercise: tool calls carry the capability ID they invoke.
5. Permission categories: `fs:read`, `fs:write`, `git:read`, `git:write`, `model:query`,
   `event:subscribe`, `worker:create`, `secrets:read`; `shell:exec` on DESKTOP only.
6. Constraint enforcement: expiry, `requiresApprovalPerUse`, `budget` (RFC-0028).
7. Revocation epochs with recursive revocation of delegated capabilities in one transaction.
8. Taint attenuation at validation (RFC-0027).
9. `CapabilityRequested` event and `approve()`/`deny()`, restricted to `user_interactive`
   frontends (RFC-0055).

**Delegation to workers is in MVP.** It was previously deferred "until Worker sessions are
implemented" while RFC-0011 listed worker sessions in its own MVP — a circular deferral whose
practical effect was that workers would run with undefined authority. Workers exist in MVP;
therefore attenuated delegation exists in MVP.

The MVP does not implement:
- Constraint enforcement for `maxBytesRead` / `maxBytesWritten` (recorded, not enforced).
- Capability bundles and the policy language.
- `PLUGIN` subjects (no plugin host in MVP); `MCP_SERVER` and `FRONTEND` subjects are in MVP.

## Future Work

Delegation protocol with full attenuation validation.

Capability certificates: signed tokens for cross-device capability transfer.

Policy language (RFC-0003 Future Work) for declarative capability sets.

Time-bounded capabilities with automatic renewal prompts.

Capability bundles: predefined sets of capabilities for common session roles (e.g., "coding session" bundle).
