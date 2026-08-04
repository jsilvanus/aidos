# RFC-0046: Identity, Actors, and Future Collaboration

Status: Accepted 2026-08-04

## Abstract

Aidos is single-user by design. This RFC does **not** add collaboration. It defines the actor
model the runtime needs today — every audited action must name who took it — and reserves the
minimum set of fields that would let collaboration be added later without rewriting the audit
trail. It also names the real blocker to collaboration, which is not permissions.

## Motivation

Single-user is a design assumption, not a temporary limitation. But two things are true at once:

**An actor model is needed now.** Every audit record, capability grant, content node, and memory
entry already answers "who did this" — user, session, worker, MCP server, or the runtime itself.
That is an actor model whether or not it is called one, and leaving it implicit means five
subsystems each inventing their own `created_by` convention. Some already had: `created_by TEXT`
as an untyped identifier that might be a session or a user, with no discriminator.

**Retrofitting identity later is expensive.** Audit logs and provenance edges are permanent
(RFC-0056). Records written today without an actor identity can never be attributed
retroactively. Adding a nullable column later gives a history with a hole in it exactly where
attribution matters most.

The correct response is neither "build collaboration" nor "ignore identity". It is to model
actors properly and reserve three fields.

## Goals

1. Define the actor model in use today.
2. Define device identity.
3. Specify exactly what is reserved for future collaboration, and what is not.
4. State the actual blocker to multi-user support.

## Non-goals

This RFC does not define multi-user semantics, sharing, permissions between users,
authentication protocols, or real-time collaboration. **None of these are being built.**
It does not define capability semantics (RFC-0018).

## Design

### Actors

An actor is anything that can cause an audited action.

```kotlin
data class ActorRef(
    val kind: ActorKind,
    val id: String        // a UUIDv7, carried as a String like every ID (RFC-0054)
)

enum class ActorKind {
    USER,        // the human. exactly one, today
    SESSION,     // a session acting under delegated authority
    WORKER,      // a worker session
    MCP_SERVER,  // an MCP server (RFC-0031)
    PLUGIN,      // reserved; no plugin host in v1 (RFC-0043)
    RUNTIME      // the runtime itself: recovery, migration, compaction
}
```

`ActorKind` deliberately mirrors `SubjectKind` in RFC-0018, with `RUNTIME` added. The two answer
different questions — *who did this* versus *who may do this* — but an actor that cannot be a
capability subject cannot take an authorised action, so the sets align by construction.

**Every attributed record carries a typed pair — a kind column and an id column — never one
polymorphic identifier.** The pattern this replaces, `created_by TEXT` meaning "session or user
ID", made it impossible to join, impossible to constrain with a foreign key, and ambiguous
whenever a UUID could be either.

**The pair is named for the question the table answers, not uniformly `actor_*`.** An earlier
version of this RFC mandated `actor_kind`/`actor_id` everywhere, which the schema does not do and
should not: the three questions are genuinely different, and collapsing their names would lose
that.

| Table | Columns | Question |
|---|---|---|
| `audit_log` | `actor_kind` / `actor_id` | who *took this action* |
| `content_nodes`, `intent_nodes`, `memory_entries` | `created_by_kind` / `created_by_id` | who *authored this record* |
| `capabilities` | `subject_kind` / `subject_id` | who *may act* (RFC-0018), plus `issued_by_kind` / `issued_by_id` for who granted it |

What is invariant is the *shape*: two columns, one of them a closed enum drawn from `ActorKind`.
What varies is the noun, and it varies on purpose.

`RUNTIME` matters more than it looks. Crash recovery transitions Runs, migrations rewrite rows,
compaction discards payloads. Attributing those to the user would be a lie, and attributing them
to nothing would leave the audit trail with unexplained changes.

### Device identity

One installation on one device has one identity, generated at first start:

```kotlin
data class DeviceIdentity(
    val deviceId: UUID,               // UUIDv7, generated locally
    val displayName: String,          // user-editable: "Pixel", "work laptop"
    val platformProfile: PlatformProfile,
    val createdAt: Instant
)
```

It is stored as a **single row in `device_identity` in `user.db`** (user scope, RFC-0054), not
as a JSON file — an earlier version of this RFC said `~/.aidos/identity/device.json`, which the
canonical schema contradicts, and the schema is right. A one-row table gets the same
transactionality, backup, and migration handling as everything else at user scope; a sidecar JSON
file would be the only piece of runtime state with its own read/write path and its own corruption
mode.

Local, self-assigned, never registered anywhere. It exists because a project directory can be
copied between devices (RFC-0041) and "which machine did this Run happen on?" is a question the
audit trail should answer — particularly given that the same project is expected to move between
a phone and a desktop, and that platform profile determines what was possible at the time
(RFC-0049).

`deviceId` is **not** a stable user identifier and is not transmitted anywhere. It appears in
local audit records and in exports the user chooses to make.

### What is reserved

Three things, all cheap now and expensive later:

1. **A typed actor pair on every attributed record**, named per the table above. In use today
   with a single user; the shape does not change when there are several.
2. **`device_id` on audit records and Runs.** In use today for provenance.
3. **A nullable `signature` column on audit records.** Unused, unpopulated, and unread in v1.
   It exists so that a future signed audit trail does not require rewriting the table that must
   never be rewritten.

That is the entire reservation. Ownership models, sharing semantics, per-user permissions,
identity providers, and merge protocols are **not** reserved, because reserving a design you
have not validated is worse than reserving nothing — it constrains the eventual solution to a
guess made years earlier.

### The actual blocker to collaboration

Worth recording, because it is not what people assume.

The blocker is **not** permissions — RFC-0018's capability model already handles multiple
subjects with attenuated authority, and adding a second human subject is not structurally hard.

The blocker is that **operational state lives in SQLite outside Git and therefore has nothing to
merge** (RFC-0017, RFC-0054). Two people working on one project would produce two divergent
sequences of Runs, sessions, capability grants, and audit entries, with no defined merge. Git
merges content; nothing merges execution history.

Any future collaboration work therefore starts there — deciding which operational state is
shareable, which is device-local, and what a merge means — not with user accounts. Anyone
picking this up should read RFC-0017 before RFC-0018.

There is also a product question that precedes the technical one: whether "collaboration" here
means shared projects (hard) or handoff via export/import and Git (already supported, and
arguably sufficient for the intended audience).

## Data Model

The canonical DDL is `schema/`; this restates only the shape it commits to.

```sql
-- The typed pair, named per the table above. The kind column is always drawn
-- from ActorKind; PLUGIN never appears in v1 (RFC-0043).
<noun>_kind TEXT NOT NULL,       -- 'USER' | 'SESSION' | 'WORKER' | 'MCP_SERVER' | 'RUNTIME'
<noun>_id   TEXT NOT NULL,

-- Provenance of place, on audit_log and runs.
device_id   TEXT NOT NULL,

-- audit_log only. Reserved; never written in v1.
signature   TEXT
```

`device_identity` is one row in `user.db`, keyed `CHECK (id = 1)`.

## Security

`deviceId` is a local correlation identifier, not an authentication credential. It grants
nothing and is never presented as proof of anything.

Actor attribution is written by the runtime, never by a session. A session cannot claim to be
another actor, because it does not supply its own identity — the runtime supplies it at the call
site, from the execution context.

The reserved `signature` column stays unpopulated in v1. A signature scheme designed now,
against no threat model and no key management story, would be worse than none — and an empty
column is honest about that.

## MVP

1. `ActorRef` with the six kinds; two-column attribution on every attributed record.
2. Device identity generated at first start, user-editable name.
3. `device_id` recorded on Runs and audit entries.
4. Reserved `signature` column, unwritten.

Not in MVP, and not planned: users beyond one, sharing, ownership transfer, authentication,
signed audit records.

## Future Work

Signed audit records, if and when a threat model requires tamper evidence — with key management
designed at the same time, not after.

Operational state merge semantics (RFC-0017), which is the actual prerequisite for shared
projects.

Multi-device attribution reporting: "this project has been worked on from three devices", which
is useful for a single user today and needs nothing beyond what is already reserved.
