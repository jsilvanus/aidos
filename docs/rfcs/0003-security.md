# RFC-0003: Security

Status: Accepted

## Abstract

Aidos uses capability-based security to protect user data and control what AI agents can do. The runtime enforces explicit permissions: every tool access requires explicit grant, every permission is auditable and revocable, and there is no implicit trust. This RFC establishes the security model that protects the user and their projects.

## Motivation

AI systems are powerful and opaque. An uncontrolled AI agent running with full filesystem and shell access is a serious risk. Yet users want to grant AI systems enough capability to be useful.

Capability-based security resolves this tension. Instead of:
- "The AI has full access to your computer" (unsafe).
- "The AI has no access to your computer" (useless).

Aidos allows:
- "The AI can read files in this directory, write patches to this file, and run tests, but cannot modify configuration or access the network."

The security model must satisfy several properties:

1. **Fine-grained**: Permissions are specific, not blanket.
2. **Explicit**: Permissions are granted by the user, not assumed.
3. **Auditable**: All permission grants and exercises are logged.
4. **Revocable**: Permissions can be revoked immediately without affecting other sessions or operations.
5. **Composable**: A session can delegate a capability to a worker without granting its full capability set.

Traditional role-based access control (RBAC) fails on these criteria. The admin role has all permissions; there is no way to revoke just shell access. Aidos uses capability-based access control (CapBAC) instead.

## Goals

1. **Establish a capability-based access control model** that is fine-grained, auditable, and revocable.

2. **Define the threat model**: What are we protecting against? What are we not protecting against?

3. **Specify the permission categories** (filesystem, shell, network, model usage, etc.).

4. **Establish audit logging**: All permission-relevant events are logged.

5. **Define secret storage**: Credentials and API keys must be protected.

6. **Ensure that no implicit trust exists**: Every operation is checked.

## Non-goals

This RFC does not specify cryptographic details (encryption algorithms, key management). Those are implementation concerns.

This RFC does not address physical security (securing the device itself) or OS-level security (protecting against a compromised OS). We assume the OS and hardware are trustworthy.

This RFC does not address encrypted transport or authentication between frontends and runtime. That is a separate network security concern, not part of capability-based access control.

This RFC does not address collaboration security (how to share capabilities across users). Multi-user scenarios are future work; Aidos is single-user by design.

## Design

### Threat Model

**Threat 1: Rogue AI Agent**

An AI session is tricked into performing malicious actions (e.g., deleting files, exfiltrating data, running malware). Mitigation: Capabilities restrict what a session can do. A session without filesystem write permission cannot delete files.

**Threat 2: Malicious Tool Integration**

An external tool (MCP server, shell script) is compromised and attempts to escalate privileges or access protected resources. Mitigation: Tools are invoked through the Tool Broker, which checks permissions. A tool cannot access resources the session does not have capability for.

**Threat 3: Secret Leakage**

A session accidentally includes an API key in an artifact or log. Mitigation: Secrets are stored encrypted at rest. Loggers are configured to redact secrets. Sessions must explicitly request secret access.

**Threat 4: Session-to-Session Interference**

One session attempts to access another session's data or modify another session's state. Mitigation: Sessions are isolated. They can communicate through artifacts and events, but they cannot directly access each other's memory or storage.

**Threat 5: Privilege Escalation**

A low-privilege session attempts to acquire high-privilege capabilities. Mitigation: Capabilities are granted by the user, not by sessions. A session cannot grant itself additional capabilities.

**What We Do Not Protect Against:**

- **Compromised OS**: If the operating system is compromised, Aidos cannot guarantee safety. We assume the OS is trustworthy.
- **Untrusted Hardware**: We assume the hardware is not malicious.
- **Side-channel Attacks**: We do not protect against timing attacks, cache attacks, or other side-channel exploits.
- **Coerced User**: If the user is coerced to grant dangerous permissions, Aidos cannot stop them.

### Capability-Based Access Control

A **capability** is a token that grants access to a specific resource or operation. Capabilities have the following properties:

- **Unforgeable**: A session cannot create a capability; only the runtime can.
- **Fine-grained**: A capability grants access to specific operations (e.g., read files in /project, write to /project/src/main.rs, run shell with timeout).
- **Delegable**: A capability can be passed to a worker or subordinate session.
- **Revocable**: A capability can be revoked immediately.
- **Auditable**: Every exercise of a capability is logged.

### Permission Categories

Permissions fall into the following categories:

#### Filesystem

- **fs:read**: Read files from specified paths.
- **fs:write**: Write files to specified paths.
- **fs:delete**: Delete files from specified paths.
- **fs:metadata**: Read file metadata (size, timestamps, permissions).
- **fs:watch**: Subscribe to filesystem change events.

Each permission can be scoped to a specific directory tree. A session might have `fs:read` for `/project` and `fs:write` for `/project/src`.

#### Git

- **git:read**: Read Git history, branches, tags.
- **git:write**: Create commits, branches, tags.
- **git:push**: Push to remote repositories.
- **git:pull**: Pull from remote repositories.
- **git:worktree**: Create and manage worktrees.

#### Shell

- **shell:exec**: Execute shell commands.

This is a single broad permission because shell access is powerful. It can be further restricted by:
- Timeout (commands must complete within T seconds).
- Environment isolation (limited env vars).
- Process sandbox (via OS-level isolation).

#### Network

- **net:http**: Make HTTP/HTTPS requests.
- **net:webhook**: Receive incoming webhooks.

Each can be scoped to specific domains or IP ranges.

#### Model Usage

- **model:query**: Query a model (LLM, embedding, vision, etc.).
- **model:fine-tune**: Fine-tune a model (future).

This permission can be scoped to specific models or providers. A session might have permission to query Claude but not GPT-4.

#### AI Engine

- **ai:generate**: Generate text/code.
- **ai:analyze**: Analyze code or documents.
- **ai:plan**: Generate plans and sequences of actions.

#### Worker

- **worker:create**: Create a new session or worker.
- **worker:delegate**: Pass a capability to a worker.

#### Secrets

- **secrets:read**: Read encrypted secrets.
- **secrets:write**: Write encrypted secrets.

Secrets are not returned in plain text; they are only provided to specific operations (AI queries, tool invocations).

#### Audit

- **audit:read**: Read audit logs (restricted; only the user can do this).
- **audit:write**: Write to audit logs (internal; sessions do not have this).

### Permission Grants

Permissions are granted in one of three ways:

#### Explicit Grant

The user explicitly grants a permission via the frontend or configuration. This creates an audit log entry and stores the capability in the session's capability set.

```
User: "Session S, I grant you fs:write:/project/src"
System: Creates capability, logs grant, adds to session.
```

#### Implicit Grant from User Intent

When the user requests an action that requires a permission the session doesn't have, the system prompts the user to approve it.

```
Session: "I need to write a patch to /project/src/main.rs"
System: Session lacks fs:write:/project/src
System: Prompt user: "Allow session to write to /project/src?"
User: "Yes"
System: Grant capability, log, proceed.
```

#### Default Capabilities

Some sessions may have default capabilities assigned by the project configuration. For example, a coding session might always have `fs:read:/project` and `fs:write:/project/src`.

### Revocation

Permissions can be revoked immediately:

```
User: "Revoke fs:write from Session S"
System: Removes capability, logs revocation
System: Future operations by S requiring fs:write are denied
System: In-flight operations are canceled/rolled back where possible
```

Revocation is immediate and complete. A revoked capability cannot be exercised, even if the session has a stale copy of it.

### Audit Logging

Every permission-relevant event is logged:

- **Permission Grant**: Who granted it, when, which capability, to which session.
- **Permission Revocation**: Who revoked it, when, which capability, from which session.
- **Permission Exercise**: Which session, when, which operation, on which resource, success or denial reason.

Logs are immutable and tamper-evident (stored in Git or signed).

### Secret Storage

Credentials (API keys, passwords, private keys) are stored encrypted at rest:

1. Each project has a master encryption key (user-provided or derived from a passphrase).
2. Secrets are encrypted with this key.
3. Secrets are only decrypted when needed (e.g., when making an API call).
4. Decrypted secrets are held in memory briefly and wiped when no longer needed.
5. Secrets are never logged or included in artifacts.

The MVP may omit encryption at rest. Future versions should support it.

### Isolation

Sessions are isolated from each other:

- **Memory**: Separate processes or isolated memory spaces.
- **Storage**: Sessions do not directly access each other's data (they communicate through artifacts and events).
- **Capabilities**: Each session has its own capability set.

Process-level isolation (separate processes per session) is recommended for shell execution to prevent cross-contamination.

### No Implicit Trust

The entire security model is based on the principle that nothing is implicitly trusted:

- Sessions do not have implicit access to resources.
- External tools do not have implicit access to secrets or files.
- Frontends must authenticate with the runtime.
- Operations do not proceed without explicit permission checks.

## Data Model

### Capability

```
Capability {
  id: UUID
  permission: PermissionType        # fs:read, git:write, model:query, etc.
  session_id: UUID
  granted_at: Timestamp
  granted_by: UserId | "system"
  resource_scope: String?           # /project/src, domain.com, etc.
  constraints: Map<String, Any>?    # timeout, env, etc.
  revoked_at: Timestamp?
  revoked_by: UserId | "system"?
}
```

### AuditLogEntry

```
AuditLogEntry {
  id: UUID
  timestamp: Timestamp
  actor: UserId | SessionId
  action: AuditAction               # Permission grant/revoke, operation attempt
  resource: String?
  permission: String?
  success: Boolean
  reason: String?                   # If denied, why
  details: Map<String, Any>?
}
```

## Security Considerations

### Threat: Malicious Prompt Injection

An attacker tries to trick an AI session into bypassing permission checks. Mitigation: Permission checks are performed by the runtime, not by the session. The session cannot bypass them.

### Threat: Capability Hijacking

An attacker tries to steal a capability from a session. Mitigation: Capabilities are tied to session identity. They cannot be transferred or forged. Capabilities are stored securely (encrypted at rest).

### Threat: Denial of Service

An attacker creates many sessions or triggers many events to exhaust resources. Mitigation: The system enforces quotas (max sessions per project, max events per second, etc.).

### Threat: Time-of-Check-Time-of-Use (TOCTOU)

The permission is checked, but then revoked before the operation. Mitigation: Operations are atomic. The permission check and operation are in a single transaction.

## MVP

The MVP security model includes:

1. **Capability-based access control** for filesystem, Git, and shell.
2. **Explicit permission grants** (user grants permissions via configuration or prompts).
3. **Audit logging** (all permission exercises are logged).
4. **Session isolation** (separate processes for shell execution).
5. **Secret storage** (unencrypted initially, but accessed through a secrets manager API).
6. **Permission revocation** (user can revoke permissions immediately).

The MVP does not include:
- Encrypted secrets at rest (future).
- Advanced OS-level sandboxing (future).
- Cryptographic verification of audit logs (future).
- Multi-user permission delegation (future).

## Future Work

### Encrypted Secrets

Secrets should be encrypted at rest with a user-provided key. The encryption key can be:
- Derived from a user passphrase.
- Stored in a hardware security module (HSM) or TPM.
- Managed by the OS keychain.

### OS-Level Sandboxing

For sensitive workloads (untrusted tools, experimental AI systems), Aidos should support OS-level sandboxing:
- Linux: seccomp, cgroups.
- macOS: sandbox, restricted entitlements.
- Android: native app sandboxing.

### Zero-Knowledge Proofs

Future versions could support zero-knowledge proofs of capability exercises, allowing third-party verification without revealing sensitive details.

### Capability Certificates

Capabilities could be signed and transferred (with user approval) to workers or other sessions, enabling secure delegation.

### Network Security

When distributed execution is added:
- Capabilities should be transmitted securely (TLS, signed).
- Frontends should authenticate with the runtime.
- Remote sessions should be encrypted end-to-end.

### Policy Language

A domain-specific language for permission policies:
```
allow fs:read on /project
allow fs:write on /project/src
allow shell:exec with timeout 60s
deny network:*
```

This allows projects to express complex policies declaratively.
