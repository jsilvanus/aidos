# RFC-0040: Storage

Status: Accepted 2026-08-03

## Abstract

Aidos persists to **three SQLite databases**, one per scope: `user.db`, `vault.db`, and a
per-project `state.db`. This RFC does not list their tables — [`schema/`](../../schema/) is the
canonical DDL and CI executes it. What this RFC defines is everything the schema file cannot say:
why there are three databases and not one, why there is no storage provider abstraction, how a
transaction survives a phone killing the process mid-write, how migrations run, and what is
deliberately kept *out* of the database.

## Motivation

The previous version of this document described a `StorageProvider` interface with
`query(sql, params)`, a roadmap to PostgreSQL, RocksDB, DuckDB, and distributed storage, and a
table layout that resembles nothing the system actually has — a JSON-blob `intent_graph`, an
`embeddings` table inside the project database, `resources` and `artifacts` tables superseded by
RFC-0024. It was written before `schema/` existed and before most of the decisions in
`docs/decisions.md` were made.

Extracting the canonical schema changed this RFC's job. It is no longer the place where tables
are described — that duplication is exactly how the corpus drifted. It is the place where the
*storage engine's behaviour* is specified, and that turns out to be the part nobody had written
down: what happens when Android kills the process during a write.

## Goals

1. State why storage is three databases and where each lives.
2. State why there is no backend abstraction, and what is refused with it.
3. Define durability under process eviction — the Android-first requirement.
4. Define the transaction and concurrency rules the runtime must obey.
5. Define migration behaviour, including the read-only case.
6. State what is deliberately not stored in SQLite.

## Non-goals

This RFC does not define tables, columns, indexes, or constraints. `schema/` governs, and where
this RFC and the schema disagree, the schema is right.

This RFC does not define retention or compaction policy (RFC-0056), performance budgets
(RFC-0045), or the export format (RFC-0041).

This RFC does not define secret storage beyond where the file sits (RFC-0035).

## Design

### Three databases

| File | Scope | Contents | Why separate |
|---|---|---|---|
| `user.db` | user | device identity, project registry, workspaces, model catalog, installed models, MCP servers, user settings, known instruction sets | Survives deleting any project. Model weights are multi-gigabyte and shared; a per-project catalogue would re-download them |
| `vault.db` | user, isolated | secrets only (RFC-0035) | **Blast radius.** A project export, a diagnostic bundle, or a backup of `state.db` cannot carry credentials, because they are not in the file. Structural, not a filter someone has to remember to apply |
| `<project>/.aidos/state.db` | project | everything about one project: sessions, runs, tasks, attempts, capabilities, content nodes, intent, audit | Travels with the project directory and is Git-ignored (D2). Deleting the directory deletes it, with no orphan left behind in a central store |

The previous version placed project state at `~/.aidos/projects/<name>/storage.db`, which put
runtime state *outside* the project and implied Aidos owns a copy of your repository. It does
not — see D2, and RFC-0054 for the scope model.

Per profile:

```
DESKTOP   ~/.aidos/user.db, ~/.aidos/secrets/vault.db, <project>/.aidos/state.db
MOBILE    /data/data/fi.italeino.aidos/files/… — same layout, app-private (RFC-0050)
```

### There is no storage provider abstraction, and that is a decision

SQLite, directly. No `StorageProvider`, no `query(sql, params)` pass-through, no second backend.

The previous version proposed one, with PostgreSQL, RocksDB, and DuckDB named as future
providers. All three are wrong for this product: PostgreSQL means a server, which contradicts
offline-first and single-user; RocksDB has no SQL, so nothing above it would survive the swap;
DuckDB is an analytics engine for a workload Aidos does not have.

More importantly, an interface whose widest method is `query(sql, params)` is not an abstraction.
It is a pass-through that forbids using anything SQLite is good at — FTS5, partial indexes, `WITHOUT
ROWID`, WAL semantics — in exchange for portability to a backend nobody will build. The cost is
paid on every query forever; the benefit begins never.

**The real portability boundary is `schema/` plus typed repository functions.** If a second
engine is ever genuinely needed, the schema file is the specification to port and the repository
functions are the surface to reimplement. That is a better position than a lowest-common-
denominator interface written a decade earlier by someone guessing.

### Durability under eviction

This is the section the previous version lacked entirely, and it is the one that matters most on
the target platform. Android kills the process without warning (D3, D24). A phone is not a
server that shuts down politely.

**`journal_mode = WAL`, `synchronous = NORMAL`, as the default.**

The reasoning is specific rather than cargo-culted. In WAL mode, `NORMAL` means a committed
transaction survives **process death** — the OS page cache holds it and the kernel writes it out
regardless of what happened to the process — but may be lost on **power failure or kernel
panic**. On a phone, process death is routine and power failure is rare. Paying an fsync on every
commit to defend against the rare case would slow every checkpoint write in the system, and
checkpoint writes are the inner loop of durable execution (RFC-0009).

**The exception, and it is the interesting one:**

```
Before an effect whose RecoveryClass is UNSAFE:
    write the attempt row with synchronous = FULL
    fsync
    then execute the effect
```

An `UNSAFE` effect — `git push`, an HTTP POST, a notification — cannot be re-run and cannot be
observed afterwards. If the record that it was *attempted* is lost, recovery has no way to know
it happened, and the user gets a duplicate push. Every other recovery class tolerates a lost
record, because `PURE` and `IDEMPOTENT` re-execute safely and `CHECKABLE` can probe. So the fsync
is paid exactly where losing the write is unrecoverable, and nowhere else.

This falls directly out of `RecoveryClass` existing. Without the taxonomy, the only options would
have been fsync everything (slow) or fsync nothing (silently duplicate a push).

**A naming collision worth flagging.** SQLite calls moving the WAL back into the main database a
*checkpoint*. RFC-0009 calls a durable step boundary a *checkpoint*. They are unrelated. In code
and comments, say "WAL checkpoint" or "execution checkpoint" — never the bare word.

`wal_autocheckpoint` is left at its default page count, with an explicit WAL checkpoint at project
close and during the deterministic-preparation window (RFC-0050). An unbounded `-wal` file on a
phone is a storage-exhaustion bug waiting for a long session.

### Transactions and concurrency

**One writer at a time.** SQLite permits a single writer per database; that is a constraint the
runtime must design around rather than discover under load.

Three rules:

1. **A write transaction may never span an external wait.** Not a model call, not a tool effect,
   not user input. A transaction held open across an inference is a transaction held open for
   fifty seconds, and every other Run blocks behind it. Write the checkpoint, commit, then act.
2. **One runtime per project** (RFC-0055). The project lock is what makes SQLite's single-writer
   model sufficient — the second runtime never opens the database, so `SQLITE_BUSY` between
   processes is prevented rather than handled.
3. **Concurrent Runs within one runtime do contend.** D15 puts parallelism across Runs, so two
   Runs may write at once. `busy_timeout` is set to 5 seconds, and any write that hits it is a
   bug in rule 1, not a condition to retry indefinitely.

The previous version illustrated concurrency with two sessions writing the Intent Graph and
resolving by version retry. That contradicts D14 and D15 and describes a conflict-detection
scheme the schema does not implement. Optimistic concurrency in Aidos is the `row_version` column
(RFC-0017), and the Intent Graph is nodes and edges, not a versioned JSON blob.

### Migrations

Forward-only, run at open, before anything else touches the database.

```
open(db):
    read schema_versions.version        -- singleton row
    if version == current               → proceed
    if version <  current               → apply migrations in order, record each
                                          in migration_history, then proceed
    if version >  current               → open READ-ONLY, storage.migration_required
```

**A newer database opens read-only rather than refusing** (RFC-0017). Version skew between a
phone and a desktop is the *normal* state in a mobile-first product, not an error — someone who
upgraded on one device and opens the project on the other should still be able to read their
work. Reading is safe because unknown columns are simply ignored; writing is not, because the
older runtime cannot honour constraints it does not know about. RFC-0029 carries
`storage.migration_required`; RFC-0039 defines the migration contract.

An earlier version of this RFC refused outright. That was stricter than necessary and worse for
the user, and it contradicted RFC-0017 — which had the better answer first.

**Each database versions independently.** A user may upgrade the app, open one project, and leave
another closed for months. `user.db` will be current while that project's `state.db` is three
versions behind, and each is migrated when it is opened. There is no global schema version.

### Size and growth

The budget is RFC-0045's: **under 512MB per active project after 90 days of use** with default
retention. Three mechanisms hold it:

- **Retention and compaction** (RFC-0056) prune what is prunable. The audit log is not — it is
  `PERMANENT` and never compacted.
- **`auto_vacuum = INCREMENTAL`.** A full `VACUUM` rewrites the whole database and blocks; on a
  phone with a 400MB project that is a visible freeze and a storage spike of equal size.
  Incremental vacuum reclaims a bounded number of pages per call and runs during the
  deterministic-preparation window.
- **Bulk content is not in the database** — see below.

### What is deliberately not stored in SQLite

| Data | Where it lives | Why |
|---|---|---|
| Embeddings | separate file, own format (D21) | Vectors are large, written in bulk, read by similarity — none of which SQLite rows are good at, and they would dominate the database's size |
| Secrets | `vault.db` (RFC-0035) | Blast radius, above |
| File content and history | the Git object database | Git is authoritative for content (RFC-0017). Storing a second copy invites the two to disagree |
| Model weights | user-scope files (RFC-0054) | Gigabytes |
| Content ≥ 512 KB | content-addressed blob store; SQLite holds hash and size (RFC-0017, RFC-0024) | Large blobs bloat the page cache and slow every unrelated query |
| Bulk event and tool payloads | content nodes, referenced by ID (RFC-0004) | The event bus carries references, not bodies |
| Diagnostic logs | rotating files (RFC-0037) | Disposable, unlike the audit log |

The rule underneath: **SQLite holds relationships, state, and small content; the filesystem and
Git hold bulk.** The threshold is 512 KB and it is stated once, in RFC-0017's ownership table —
content below it lives inline as a BLOB, above it out-of-line by hash.

## Data Model

[`schema/`](../../schema/) is canonical: `user.sql`, `vault.sql`, `project.sql`, and `check.py`
which executes all three in CI, verifies foreign keys resolve, verifies no table is defined in two
files, and verifies every table named in RFC DDL exists.

**This RFC deliberately contains no DDL.** The previous version described a dozen tables in a
prose pseudo-syntax (`Table: projects`, `id: UUID (PRIMARY KEY)`), none of which matched the real
schema and none of which CI could see — the check greps for `CREATE TABLE`, and prose is
invisible to it. That is how a storage RFC came to describe an `embeddings` table that D21
forbids.

## Security

1. **Project isolation is by file.** Sessions in one project cannot reach another project's
   database, because it is a different file behind a different capability scope (RFC-0018).
2. **Secrets are in a different file**, with its own path and its own permissions. Exporting a
   project cannot leak them by omission of a filter.
3. **SQL statements are never logged.** The previous version proposed logging queries for audit,
   which is both the wrong record and a leak — parameter values appear in statements. The audit
   trail is `audit_log`'s typed events (RFC-0003), redacted per RFC-0035.
4. **Encryption at rest** is the platform's: full-disk encryption on Android and whatever the
   desktop provides. Per-database encryption is Future Work, and the honest current statement is
   that an unlocked device exposes the projects.
5. **A database file is untrusted input** when it arrives from outside — import validates schema
   version and integrity before use (RFC-0041), and never executes anything from it.

## MVP

1. Three databases, created from `schema/` at first open, at the paths above.
2. WAL, `synchronous = NORMAL`, with `FULL` before `UNSAFE` effects.
3. Forward-only migrations, per database; a newer schema opens read-only rather than refusing.
4. The three transaction rules, with a test that fails a write transaction held across an await.
5. Incremental vacuum during preparation windows.
6. `busy_timeout` at 5s, with contention treated as a bug rather than absorbed.

Not in the MVP: alternative backends (not ever, absent a reason that does not currently exist),
encryption at rest beyond the platform's, replication, sharding, query caching.

## Future Work

- **Per-database encryption** keyed by the device keystore, for the stolen-unlocked-phone case
  that full-disk encryption does not cover.
- **FTS5 over content nodes**, once the knowledge engine's retrieval design settles (RFC-0015).
  It is deliberately not built ahead of that decision.
- **Incremental export** (RFC-0041), so moving a large project does not mean re-copying it.
- **Storage diagnostics** — per-table size attribution in the diagnostic bundle, so "why is this
  project 800MB" has an answer that is not a guess.

## Open Questions

None. The previous version's list was answered elsewhere as the architecture settled: embeddings
live outside the operational database (D21), retention is RFC-0056, WAL growth is bounded by
explicit checkpoints above, and export is RFC-0041. Sharding and backup-key management were
questions about a distributed product this one is not.
