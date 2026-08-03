# RFC-0035: Secrets and Credentials

Status: Accepted 2026-08-03

## Abstract

This RFC defines the secrets vault: a user-scope store backed by the platform keystore, from
which secrets are resolved by reference at the point of use and never written into
configuration, prompts, logs, events, or exports. It also defines the redactor — the filter every
outbound and persisted string passes through — because in an AI runtime the greater risk is not
a stolen vault but a credential that leaks into a prompt.

## Motivation

Aidos handles credentials of several kinds: model provider API keys, Git remote credentials, MCP
server tokens, and later plugin secrets. Two failure modes matter, and they are not equally
likely.

**The obvious one:** secrets stored insecurely. Addressed by a vault with platform-backed
encryption.

**The one that will actually happen:** a secret ends up somewhere it was never meant to be. A
model reads a `.env` file and the value enters the transcript, which is persisted as a prompt
package, included in a diagnostic bundle, and sent to a remote provider. Or a tool returns a
command line containing a token, which is stored in an Attempt and published as an event.

The second failure needs no attacker. It is the default behaviour of a system that records
everything for auditability — which this one does, deliberately. **Auditability and secret
hygiene are in direct tension, and the redactor is where that tension is resolved.**

Prior RFCs made it worse by example: RFC-0031 once documented an MCP configuration containing a
literal API key, in a file RFC-0017 declares Git-authoritative.

## Goals

1. Define the vault, its scope, and its storage.
2. Define reference-based resolution and injection.
3. Define the redactor and where it runs.
4. Define detection of secrets in content.
5. Define rotation, revocation, and breach response.

## Non-goals

This RFC does not select cryptographic algorithms; it specifies platform-backed storage.
It does not define user authentication to the device.
It does not define capability semantics (RFC-0018).

## Design

### The vault is user scope

Secrets live at user scope (RFC-0054), never project scope. A credential belongs to a person,
not to a repository — and project state travels: `aidos.toml` is Git-tracked, exports are
shareable, and a project directory can be copied to another machine.

```
~/.aidos/secrets/
├── vault.db          encrypted values, keyed by secret id
└── (key material)    platform keystore — never on disk in the clear
```

| Platform | Key storage |
|---|---|
| Android | Android Keystore, hardware-backed where available |
| macOS | Keychain |
| Linux | Secret Service (libsecret); passphrase-derived key as fallback |
| Windows | DPAPI |

The vault database holds ciphertext. The key that decrypts it is held by the platform keystore
and never written to the filesystem by Aidos. Where no keystore exists, a passphrase-derived key
is used, and the user is told plainly that protection is weaker.

### Secrets are referenced, never embedded

Nothing outside the vault holds a secret value. Consumers hold a `secret_ref`:

```toml
# ~/.aidos/mcp/servers.toml  — user scope, still only a reference
[[server]]
name       = "github"
secret_ref = "github_token"
```

```kotlin
data class SecretEntry(
    val id: SecretId,
    val name: String,
    val kind: SecretKind,              // API_KEY | TOKEN | PASSWORD | SSH_KEY | GENERIC
    val allowedConsumers: List<ConsumerRef>,   // which providers/servers may resolve it
    val createdAt: Instant,
    val expiresAt: Instant?,
    val lastUsedAt: Instant?,
    val lastRotatedAt: Instant?
)
```

Resolution requires a capability (`secrets:read` naming specific IDs — never a wildcard,
RFC-0054) and happens at the point of use:

- **Provider adapters** receive the value in memory when constructing a request.
- **MCP child processes** receive it as an environment variable at spawn, in an otherwise
  scrubbed environment (RFC-0055).
- **Git remotes** receive it through a credential provider at connection time.

The value is never returned to a session, never placed in a `PromptPackage`, and never crosses
the Runtime API. **A session cannot read a secret; it can only cause one to be used.** That
distinction is the whole design: an injected instruction can ask the model to exfiltrate a
credential, and the model has no way to comply because it has never seen one.

Values are held in `CharArray`/`ByteArray` and zeroed after use rather than in `String`, which
is immutable and lingers on the JVM heap until collected.

### The redactor

The redactor is the filter every string crosses before it is persisted, transmitted, or shown.
It runs unconditionally — not at a log level, not behind a flag.

Where it runs:

| Boundary | Reason |
|---|---|
| Event payloads (RFC-0004) | events are permanent |
| Attempt input/output snapshots (RFC-0019) | the audit trail |
| Prompt packages (RFC-0025) | before any model sees it |
| Diagnostic logs and bundles (RFC-0037) | before it can be shared |
| Memory entries (RFC-0026) | before a secret becomes a remembered "fact" |
| Exports (RFC-0041) | archives get shared |

Two detection strategies, both needed:

**Known values.** Every vault value is registered with the redactor at load. Any occurrence in
any outbound string is replaced with `«redacted:github_token»`. This catches the case that
matters most — a credential the runtime itself injected coming back in a tool's output or error
message.

**Pattern detection.** Provider key formats, JWTs, PEM blocks, connection strings, and
high-entropy strings in credential-shaped assignments (`API_KEY=…`). Patterns are necessarily
imperfect: they miss unusual formats and occasionally fire on innocuous strings. That trade is
accepted in this direction — a false positive costs a truncated log line, a false negative costs
a credential.

Detection is reported without recording the value: the audit entry says *what kind was found and
where*, never the match.

### Secrets found in content

When the detector matches content being read into the runtime — a `.env` file, a config file, a
tool result — the containing ContentNode is labelled `SECRET` (RFC-0024). That label means:

- excluded from all prompts, local and remote;
- excluded from exports;
- excluded from diagnostic bundles;
- deleted immediately rather than aged, if it was runtime-produced (RFC-0056).

The user is told, once, non-modally: *"`.env` looks like it contains credentials. It has been
excluded from AI context."* Not a blocking dialog — this is common and the user usually knows —
but not silent either, because a user who does not know their secrets are being skipped will
wonder why the model cannot see their configuration.

### Rotation and revocation

- **Rotation** replaces the value under the same `secret_ref`. Consumers are unaffected; that is
  the point of references. The prior value is removed from the vault and remains registered with
  the redactor for a grace period, so a leaked old value is still filtered.
- **Deletion** removes the value. Consumers referencing it fail with `secrets.not_found`
  (RFC-0029) — clearly, rather than by producing an unauthenticated request.
- **Expiry** marks a secret expired; resolution fails and the user is prompted.

### Breach response

If a secret is believed exposed, the runtime provides the evidence to act on:

1. **Where it went** — `egress_records` (RFC-0042) filtered by the Attempts that resolved it:
   which destinations, when, how many times.
2. **What retained it** — provider retention policies recorded at call time (RFC-0026).
3. **What to purge** — the content nodes, attempts, and logs where a match was detected.

Aidos cannot rotate a credential at the provider; that is the user's action at the provider.
What it can do is answer "where has this been?" precisely, which is the question that determines
how bad an exposure is — and it is unanswerable in most systems.

## Data Model

```sql
-- ~/.aidos/secrets/vault.db  (user scope; values encrypted)
CREATE TABLE secrets (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    kind TEXT NOT NULL,
    ciphertext BLOB NOT NULL,
    nonce BLOB NOT NULL,
    allowed_consumers_json TEXT NOT NULL DEFAULT '[]',
    created_at TEXT NOT NULL,
    expires_at TEXT,
    last_used_at TEXT,
    last_rotated_at TEXT
);

-- Audit of resolution. Project-scope; never contains a value.
CREATE TABLE secret_accesses (
    id TEXT PRIMARY KEY,
    secret_id TEXT NOT NULL,
    consumer_kind TEXT NOT NULL,      -- 'PROVIDER' | 'MCP_SERVER' | 'GIT_REMOTE'
    consumer_id TEXT NOT NULL,
    attempt_id TEXT,
    capability_id TEXT NOT NULL,
    occurred_at TEXT NOT NULL
);

-- Detections. Records that something was found, never what.
CREATE TABLE redaction_events (
    id TEXT PRIMARY KEY,
    project_id TEXT,
    boundary TEXT NOT NULL,           -- 'prompt' | 'event' | 'log' | 'export' | 'memory'
    detection_kind TEXT NOT NULL,     -- 'known_value' | 'pattern:jwt' | ...
    content_node_id TEXT,
    occurred_at TEXT NOT NULL
);
```

## Security

1. **Values never leave the vault except into a consumer at the point of use.** Not into
   sessions, prompts, the Runtime API, or any persisted record.
2. **The redactor runs unconditionally** at every boundary listed above, in every build.
3. **Known-value redaction is exact**; pattern detection is best-effort and biased toward false
   positives.
4. **Resolution requires an explicit capability naming specific secret IDs.** No wildcard grant
   exists.
5. **Detections are audited without recording values.**
6. **A tainted Run cannot resolve secrets at all** (RFC-0027) — a Run that has read untrusted
   content is denied `secrets:read` for its remainder. This is the specific mitigation for
   "read a hostile file, then use my credentials."
7. **Zeroed buffers**, not `String`, for values in memory.

## MVP

1. Vault at user scope with platform-keystore-backed encryption; passphrase fallback on Linux.
2. `secret_ref` resolution for provider adapters, MCP spawn, and Git remotes.
3. The redactor at all six boundaries, with known-value registration.
4. Pattern detection for common provider key formats, JWTs, and PEM blocks.
5. `SECRET` labelling of content containing matches, with a non-modal notice.
6. `secrets:read` capability naming specific IDs; taint denial.
7. `secret_accesses` and `redaction_events` audit.

Not in MVP: rotation grace periods, expiry prompts, the breach-response report, SSH key
management.

## Future Work

The breach-response report as a single view: where a secret went, what retained it, what to
purge.

Provider-assisted rotation for providers whose APIs support it.

Hardware-backed attestation on Android, so a secret can be bound to the device rather than only
encrypted on it.
