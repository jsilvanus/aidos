# RFC-0017: State Model

Status: Draft

## Abstract

This RFC defines the canonical state model for Aidos projects. It establishes the authoritative store for each object type, the lifecycle of state transitions, and the rules for crash recovery, snapshots, and migration. It answers the question: "When SQLite and Git contain different state after a crash, who wins?"

## Motivation

Aidos persists state across multiple stores: SQLite for structured operational state, Git for versioned content objects, and the filesystem for file content. These stores are modified independently and may diverge after an unexpected process termination. Without a clear model of which store is authoritative for each object type, recovery after failure is undefined. Implementers will make incompatible choices, leading to data loss or duplication.

The state model must be strong enough to guarantee correctness across crashes, while remaining simple enough to implement without a distributed coordination protocol.

## Goals

1. Define the authoritative storage location for each object type.
2. Define the lifecycle of each object class (creation, mutation, archival, deletion).
3. Define transaction boundaries across SQLite, filesystem, and Git.
4. Define crash recovery procedures that are correct and idempotent.
5. Define migration strategy for schema changes.

## Non-goals

This RFC does not define the SQL schema for every column.
It does not prescribe a distributed or multi-user replication model.
It does not define the Execution Graph schema (RFC-0019) or Resource Graph schema (RFC-0024).

## Design

### Storage Layers

Aidos uses three storage layers with different roles:

**SQLite (authoritative for operational state)**
SQLite is the single source of truth for: session state, run state, capability records, audit log, artifact metadata, intent graph metadata, and all object identity records. If SQLite says an object exists in a given state, that is the ground truth.

**Git (authoritative for content versions)**
Git is the version history for objects that users care about tracking and diffing: resources (RFC-0013), instruction files, intent graph snapshots (committed periodically), and project configuration. Git is *not* authoritative for operational state (session state, run state). Git is authoritative only for content-addressed, user-visible versioned objects.

**Filesystem (authoritative for current file content)**
The filesystem holds the working copy of files. It is always consistent with the most recent Git commit for tracked files, plus any uncommitted changes. For untracked session artifacts (e.g., scratch files), the filesystem is authoritative until the artifact is committed to SQLite.

### Canonical Ownership Table

| Object Type | Authoritative Store | Secondary Store |
|-------------|--------------------|--------------------|
| Project identity and metadata | SQLite | — |
| Session state (sleeping/running/archived) | SQLite | — |
| Run state and execution records | SQLite | — |
| Capability records | SQLite | — |
| Audit log entries | SQLite | — |
| Artifact metadata | SQLite | — |
| Artifact content (small, < 1MB) | SQLite BLOB | — |
| Artifact content (large, ≥ 1MB) | Filesystem | SQLite holds path + hash |
| Resource metadata | SQLite | — |
| Resource content (versioned) | Git | SQLite holds current hash |
| Intent graph metadata | SQLite | Git (periodic snapshots) |
| Intent graph structure (nodes/edges) | SQLite | — |
| Project configuration (aidos.toml) | Git | SQLite cache |
| Instruction files (AGENTS.md, etc.) | Git | — |
| Knowledge Engine index | Filesystem | (rebuild-able from sources) |

### Conflict Resolution Rule

**The authoritative store wins. Always.**

If SQLite says a session is in SLEEPING state but the process crashed while the session was running, SQLite wins: the session is SLEEPING. The recovery procedure resets it correctly (RFC-0006).

If Git has a resource at version V2 but SQLite's resource table says version V1, Git wins for content; SQLite is updated to reflect the latest Git content hash on next open.

There are no cases where two stores have conflicting authority over the same field. The table above assigns each field to exactly one authoritative store.

### Object Lifecycle

Every object in the state model has a defined lifecycle. Objects transition through states; the SQLite record always reflects the current state.

#### Project Lifecycle

```
CREATING → OPEN → CLOSING → CLOSED → (DELETED)
```

- CREATING: git init and SQLite schema are being set up. Not visible to frontends until OPEN.
- OPEN: normal operating state.
- CLOSING: the project is being closed gracefully (active sessions are being paused).
- CLOSED: SQLite is closed. The project can be reopened.
- DELETED: the project directory has been removed (irreversible).

#### Session Lifecycle

```
CREATED → SLEEPING → RUNNING → SLEEPING
                ↓           ↓
             ARCHIVED    ARCHIVED
```

- CREATED: session record exists, no Runs yet.
- SLEEPING: session is dormant, waiting for events.
- RUNNING: session is processing a Run.
- ARCHIVED: session is no longer active but history is preserved.

Sessions never enter a final "DELETED" state — they become ARCHIVED. Historical session data is valuable for audit and replay.

#### Artifact Lifecycle

```
CREATING → COMMITTED → (EXPORTED)
```

- CREATING: artifact content is being written (filesystem or SQLite). Not yet visible.
- COMMITTED: artifact is fully written and its SQLite record is finalized. Immutable.
- EXPORTED: artifact has been included in a project export.

Artifacts are never mutated after COMMITTED. A new artifact is created instead.

#### Resource Lifecycle

```
CREATED → CURRENT → SUPERSEDED
```

- CREATED: resource exists in Git with an initial commit.
- CURRENT: the resource is the active version in Git HEAD.
- SUPERSEDED: a newer version exists in Git HEAD; older versions remain in history.

Resources are never deleted from Git history. They may be deleted from the working tree (which creates a deletion commit).

### Transaction Boundaries

**SQLite transactions**: All writes to SQLite must occur within explicit transactions. Multi-step operations that must succeed atomically (e.g., creating an artifact record + updating the session's artifact list) use a single transaction. No partial writes should be visible.

**Git commits**: Git operations are not transactional in the SQLite sense. A Git commit either succeeds fully or fails. The strategy for Git-SQLite consistency:
1. Write to SQLite first (set status to PENDING_GIT_COMMIT).
2. Perform the Git operation.
3. If Git succeeds: update SQLite to COMMITTED + store the commit hash.
4. If Git fails: the SQLite record remains PENDING_GIT_COMMIT. Recovery can retry.

**Filesystem writes**: File writes are not atomic below the page size on most filesystems. For large artifacts stored on the filesystem, use the write-to-temp-then-rename pattern:
1. Write artifact content to a temp file in the same directory.
2. Record the temp file path in SQLite as PENDING.
3. Rename the temp file to the final path (atomic on the same filesystem).
4. Update SQLite to COMMITTED.

### Crash Recovery

Recovery runs every time the runtime opens a project. It is idempotent.

**Step 1: Detect incomplete writes**
Query SQLite for all objects in PENDING states:
- CREATING projects, sessions, artifacts
- PENDING_GIT_COMMIT resources and intent snapshots

**Step 2: Validate each incomplete object**
For each incomplete object, determine its actual state:
- Does the SQLite record contain complete data?
- Does the referenced Git commit exist (if applicable)?
- Does the referenced filesystem path exist (if applicable)?

**Step 3: Resolve**
- If complete: advance the object to its COMMITTED/OPEN state.
- If incomplete and recoverable (e.g., content exists but commit record is missing): complete the missing step and advance.
- If incomplete and unrecoverable: transition to FAILED state, write an audit log entry, and notify the user.

**Step 4: Recover sessions (RFC-0006)**
Query for sessions in RUNNING state. These were interrupted by the crash. For each: apply RFC-0006 recovery procedure to their in-progress Runs.

**Recovery is idempotent**: Running it twice produces the same result as running it once. If the process crashes during recovery, the next recovery pass detects the same pending objects and retries.

### Schema Migration

When the SQLite schema changes between runtime versions, a migration runs on project open:

1. The runtime reads the schema version from the SQLite `schema_versions` table.
2. The runtime applies any pending migration scripts in sequence.
3. Each migration script is a transaction; if it fails, the project does not open.
4. On success, the `schema_versions` table is updated.

Migrations are forward-only in the MVP. Downgrade is not supported.

Migration scripts must be idempotent where possible (use `CREATE TABLE IF NOT EXISTS`, `ADD COLUMN IF NOT EXISTS`).

### Knowledge Engine Index

The Knowledge Engine index is fully derived state. It can always be rebuilt from the authoritative sources (Git history, filesystem, SQLite artifact metadata). It is not part of the state model for correctness purposes.

If the index is absent or corrupt, it is rebuilt. This may cause a temporary degradation in query quality until indexing is complete. This is acceptable.

## Data Model

### SchemaVersion table

```sql
CREATE TABLE schema_versions (
    id INTEGER PRIMARY KEY CHECK (id = 1),  -- singleton row
    version INTEGER NOT NULL,
    applied_at TEXT NOT NULL,
    runtime_version TEXT NOT NULL
);
```

### Object State Fields

Every table that participates in the state model includes:

```sql
-- Standard object fields
id TEXT PRIMARY KEY,           -- UUID
created_at TEXT NOT NULL,      -- ISO 8601 timestamp
updated_at TEXT NOT NULL,      -- ISO 8601 timestamp
state TEXT NOT NULL,           -- lifecycle state enum
state_updated_at TEXT NOT NULL,
audit_ref TEXT,                -- UUID of the audit log entry that caused the last state change
version INTEGER NOT NULL DEFAULT 1  -- monotonic version counter
```

### Pending Operation Tracking

```sql
CREATE TABLE pending_operations (
    id TEXT PRIMARY KEY,
    object_type TEXT NOT NULL,    -- 'artifact', 'resource', 'intent_snapshot'
    object_id TEXT NOT NULL,
    operation TEXT NOT NULL,      -- 'git_commit', 'filesystem_write', 'schema_init'
    started_at TEXT NOT NULL,
    details TEXT,                 -- JSON with operation-specific context for recovery
    retry_count INTEGER NOT NULL DEFAULT 0
);
```

## Security

The state model preserves security invariants:

- Capability records are in SQLite, which is the authoritative store. A capability that is revoked in SQLite is revoked, regardless of any in-memory cache.
- Audit log entries are append-only. The SQLite audit table uses `INSERT OR FAIL` semantics — no audit entry can be overwritten.
- Recovery procedures must be logged in the audit trail. A project that was recovered after a crash should have an audit entry describing the recovery actions.

## MVP

The MVP implements:

1. The canonical ownership table as specified.
2. SQLite transactions for all multi-step writes.
3. Write-to-temp-then-rename for large filesystem artifacts.
4. Git-SQLite consistency using PENDING_GIT_COMMIT state.
5. Crash recovery on project open (Steps 1-4).
6. Schema version table and initial migration infrastructure.

The MVP does not implement:
- Automatic detection of SQLite-Git content hash drift (can be added with a background reconciliation job).
- Advanced migration tooling.

## Future Work

Content-hash reconciliation: a background job that periodically verifies that SQLite's stored hashes match the actual Git content, alerting the user if drift is detected.

Backup and point-in-time recovery: export a consistent snapshot of SQLite + Git at a given timestamp.

Multi-device sync: when Git sync across devices is added, a merge protocol is needed for SQLite operational state that has no Git equivalent.
