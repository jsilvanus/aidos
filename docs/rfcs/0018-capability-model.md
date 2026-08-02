# RFC-0018: Capability Model

Status: Draft

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
    val auditRef: UUID                 // audit log entry for this grant
)

enum class SubjectKind { SESSION, WORKER }

sealed class GrantSource {
    object User : GrantSource()
    data class Delegation(val delegatingSessionId: UUID) : GrantSource()
    data class Default(val sourceConfigId: UUID) : GrantSource()
}
```

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
    val exercisedCount: Int = 0             // tracked in SQLite
)
```

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

    // Check whether a capability exists and is valid for a given operation
    suspend fun check(
        subjectId: UUID,
        permission: Permission,
        resourceRef: String
    ): CapabilityCheckResult

    // Load all active capabilities for a session (on session wake)
    suspend fun loadForSubject(subjectId: UUID): List<Capability>
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
suspend fun executeTool(sessionId: UUID, toolName: String, params: ToolParams): ToolResult {
    val requiredPermission = tools[toolName]!!.requiredPermission
    val resourceRef = params.toResourceRef()

    val checkResult = capabilityManager.check(sessionId, requiredPermission, resourceRef)

    if (checkResult is CapabilityCheckResult.Denied) {
        auditLog.record(AuditEvent.CapabilityDenied(sessionId, toolName, checkResult.reason))
        return ToolResult.CapabilityDenied(checkResult.reason)
    }

    auditLog.record(AuditEvent.ToolInvocationStarted(sessionId, toolName, resourceRef))
    val result = tools[toolName]!!.execute(params)
    auditLog.record(AuditEvent.ToolInvocationCompleted(sessionId, toolName, result.success))

    return result
}
```

The capability check is not atomic with the operation execution (avoiding the TOCTOU issue would require OS-level enforcement). The approach: once a check passes and the operation begins, it runs to completion. Revocation applies to future checks.

### Attenuation (Delegation to Workers)

When a Driver session creates a Worker session, it may delegate a subset of its capabilities to the Worker. The Worker receives attenuated capabilities — narrower in scope and constraints than the parent.

Attenuation rules:
- The delegated scope must be contained within the parent scope. A session with `fs:read:/project/**` can delegate `fs:read:/project/src/**` but not `fs:read:/other-project/**`.
- The delegated constraints must be equal or more restrictive. A session with a 60s shell timeout can delegate a capability with a 30s timeout but not a 120s timeout.
- The delegated expiry must be equal or earlier than the parent's expiry.
- A Worker cannot further delegate a capability unless the parent capability had `allowsDelegation: true`.

Validation occurs at delegation time in `CapabilityManager.delegate()`. Invalid delegation requests are rejected with an error.

### Revocation

Revocation is immediate: once `revoke()` is called, all subsequent `check()` calls for that capability return DENIED (CAPABILITY_REVOKED).

Revocation propagates:
1. The `revokedAt` field is set in SQLite.
2. The capability is removed from the Capability Manager's in-memory cache.
3. All worker capabilities derived from the revoked capability (via delegation) are recursively revoked.
4. An audit log entry is written.
5. A `CapabilityRevoked` event is published to the Event Bus.
6. The session's in-memory capability set is updated.

In-flight operations: if an operation has already passed the capability check and is executing, it continues to completion. The revocation takes effect on the next `check()` call. This is documented, acceptable behavior — fully atomic revocation would require OS-level process termination.

### Persistence Across Restarts

Capabilities are persisted in SQLite. When a session wakes, the Capability Manager loads all active (non-revoked, non-expired) capabilities for that session from SQLite.

This means capabilities survive process restart. A session that had filesystem write permission before the process crashed still has it after restart, unless the user revoked it while the process was down.

Expired capabilities are not loaded (the expiry check runs at load time). Revoked capabilities are not loaded.

### Mobile Enforcement (Android)

On Android, process-level isolation between sessions and the OS is more limited than on Linux/macOS. The Capability Manager cannot rely on process boundaries to enforce capabilities for filesystem and shell operations.

The Android enforcement model:

1. **Application-layer enforcement**: All tool invocations pass through the Capability Manager in-process. This is the primary enforcement mechanism. It cannot be bypassed by a session because sessions never call system APIs directly — they call Tool Broker methods that check capabilities.

2. **No shell execution on Android**: The `shell:exec` permission is not granted on Android. Shell command execution is not available in the Android runtime. Sessions that need shell-like capabilities use the Filesystem, Git, and dedicated tool adapters instead.

3. **Filesystem sandboxing**: Android's app sandbox restricts file access to the app's own directory. The Capability Manager's filesystem scope enforcement operates within this OS-level sandbox. A session with `fs:write:/project/src/**` can only write within the app's project directory — it cannot escape the app sandbox even with a wide capability scope.

4. **Audit compensates for enforcement limitations**: Where OS-level enforcement is unavailable, the audit log is more detailed. Every capability exercise on Android records additional context (thread ID, call stack hash) to enable after-the-fact forensics.

### Confused-Deputy Mitigation

A confused deputy attack occurs when a high-privilege component (the runtime) is tricked by a low-privilege caller (a session) into using its authority on behalf of the caller.

Aidos mitigates this structurally:

1. **Tools check capabilities, not callers check tools**: The capability check is inside the Tool Broker, not in the session. A session cannot "bypass" the check by calling a tool in an unexpected way because all tool execution paths go through the same check.

2. **Capabilities are bound to subjects**: A `Capability` record includes `subjectId`. The `check()` call takes `subjectId` as an argument. The runtime verifies that the capability's `subjectId` matches the requesting session — a session cannot use another session's capability.

3. **No ambient authority**: The runtime itself does not have a "superuser" mode that tools can escalate to. The runtime's internal operations are explicitly excepted from capability checks (they are trusted infrastructure), but no session can invoke internal operations directly.

## Data Model

```sql
CREATE TABLE capabilities (
    id TEXT PRIMARY KEY,
    permission TEXT NOT NULL,
    subject_id TEXT NOT NULL,
    subject_kind TEXT NOT NULL,
    scope_json TEXT NOT NULL,
    constraints_json TEXT NOT NULL,
    issued_at TEXT NOT NULL,
    issued_by TEXT NOT NULL,
    parent_capability_id TEXT,
    expires_at TEXT,
    revoked_at TEXT,
    revoked_by TEXT,
    audit_ref TEXT NOT NULL,
    FOREIGN KEY (parent_capability_id) REFERENCES capabilities(id)
);

CREATE INDEX idx_capabilities_subject ON capabilities(subject_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_capabilities_parent ON capabilities(parent_capability_id);
```

## Security

The Capability Manager is a security-critical component. It must be the only code path through which capabilities are created or checked. No code in the runtime may bypass it.

The SQLite `capabilities` table must not be writable by any code path except the Capability Manager.

The audit log entries for capability operations are append-only.

Capability IDs are UUIDs generated by the runtime's secure random source. They are not predictable.

## MVP

The MVP implements:

1. The `Capability` data structure with all fields.
2. `CapabilityManager` with `grant()`, `check()`, `revoke()`, and `loadForSubject()`.
3. Persistence in SQLite with the capabilities table.
4. Exercise flow in the Tool Broker (check before every tool invocation).
5. Permission categories: `fs:read`, `fs:write`, `shell:exec` (desktop only), `git:read`, `git:write`, `model:query`.
6. Constraint enforcement: expiry check, `requiresApprovalPerUse`.
7. Revocation with in-memory cache invalidation.
8. `CapabilityRequested` event and `approve()`/`deny()` API in the Runtime API.

The MVP does not implement:
- Delegation to workers (deferred until Worker sessions are implemented).
- Constraint enforcement for `maxBytesRead`, `maxBytesWritten`, `maxExerciseCount`.
- Recursive revocation of derived capabilities.
- Android-specific audit enhancements.

## Future Work

Delegation protocol with full attenuation validation.

Capability certificates: signed tokens for cross-device capability transfer.

Policy language (RFC-0003 Future Work) for declarative capability sets.

Time-bounded capabilities with automatic renewal prompts.

Capability bundles: predefined sets of capabilities for common session roles (e.g., "coding session" bundle).
