# Aidos schema

The canonical database schema. **Where an RFC shows DDL, these files govern** — RFC fragments
are illustrative and often incremental (`ALTER TABLE`) for readability.

```
schema/
├── user.sql      → ~/.aidos/user.db              11 tables
├── vault.sql     → ~/.aidos/secrets/vault.db      2 tables
├── project.sql   → <project>/.aidos/state.db     40 tables
└── check.py      validation, run in CI
```

```bash
python3 schema/check.py
```

Checks that every file executes, that foreign keys resolve, that no table is defined in two
files, and that every FK target exists in the same database.

## Why three files

The scope model (RFC-0054) puts different state in different places, and the schema has to
reflect it or the separation is theoretical:

- **`user.db`** — about the person and the device: workspaces, project registry, user and
  workspace settings, model catalogue and installed weights, MCP registrations, crash records.
- **`vault.db`** — secrets only. Separate file, separate permissions, ciphertext values with the
  key held by the platform keystore (RFC-0035).
- **`state.db`** — everything project-scoped. Git-ignored, lives inside the project directory,
  moves with it.

Three databases means **no foreign keys across scopes**. `secret_accesses.secret_id` (in
`state.db`) refers to `secrets.id` (in `vault.db`) and is deliberately not an FK. This is
correct — a project must not be able to constrain or enumerate the user's vault — but it means
referential integrity there is the application's job, and the consistency check (RFC-0038) has to
cover it.

## What building this found

The exercise was proposed because a schema that runs is a cheaper architectural test than any
amount of review. It was.

### Five tables were referenced by ~20 foreign keys and defined nowhere

`projects`, `sessions`, `audit_log`, `intent_nodes`, `intent_edges`.

Every RFC that needed them wrote `FOREIGN KEY (project_id) REFERENCES projects(id)` and moved on.
The Intent Graph RFC specified node *properties* in prose and Kotlin, and a proposals table, but
never the nodes themselves. These are now defined here, and the definitions are load-bearing
enough that they should be reflected back into RFC-0010, RFC-0011, RFC-0003, and RFC-0012 rather
than living only in SQL.

Notable consequence: `intent_nodes` has **no `status` column**, because status is derived
(RFC-0012). Writing the table is what makes that decision concrete — it is much harder to
accidentally add a status field to a table that visibly does not have one.

### `settings` was defined twice, differently

RFC-0036 has `set_at` and `set_by_kind`; RFC-0054 does not. RFC-0036's is used, and the table is
split by scope: user and workspace settings in `user.db`, project and session settings in
`state.db`. That split is what enforces "SECURITY and SPEND settings are user-scope only"
(RFC-0036) structurally rather than by validation alone.

### `schema_versions` was defined twice, identically

RFC-0017 and RFC-0039. Harmless, and each database needs its own copy — they version
independently.

### The `ALTER TABLE` fragments were nearly all redundant

RFC-0009, 0025, 0027, 0029, and 0049 each present column additions to `runs`, `tasks`, and
`attempts`. RFC-0019's canonical DDL already includes all of them, because it was updated as
those RFCs landed. Only `attempts.provider_retention_json` (RFC-0026) was genuinely missing.

The fragments are still useful as prose — they show which RFC owns which column — but they are
not the schema, and this file is where they reconcile.

### No RFC said which database a table belongs to

RFC-0054 gives the storage layout; the DDL fragments do not reference it. Splitting the files
forced a decision for every table, and three were non-obvious:

| Table | Placed in | Why |
|---|---|---|
| `crash_records` | `user.db` | a crash may prevent a project opening at all |
| `secret_accesses` | `state.db` | it is project activity; the secret itself is not here |
| `resource_budgets` | `user.db` | device limits; the `scope` column still allows project rows |

`metric_samples` lives in `state.db` with a nullable `project_id`, so user-scope metrics have a
home without a second table. This is a compromise and worth revisiting if user-scope metrics
grow.

## Conventions

| | |
|---|---|
| IDs | UUIDv7 as `TEXT`, globally unique (RFC-0054) |
| Timestamps | ISO-8601 UTC `TEXT` |
| Booleans | `INTEGER` 0/1 |
| JSON | `TEXT`, `_json` suffix |
| Concurrency | `row_version` — `UPDATE … SET row_version = row_version + 1 WHERE id = ? AND row_version = ?` |
| Foreign keys | `PRAGMA foreign_keys = ON` — required, not optional |
| Journal | WAL, for concurrent reads with a single writer (RFC-0007) |

## What is deliberately not here

- **The knowledge index.** Files under `.aidos/index/`, never in `state.db` — embedding writes
  would contend with the single writer and inflate the file the user backs up with entirely
  rebuildable data (RFC-0015).
- **Blob content.** Content-addressed files under `.aidos/blobs/`; only `blob_refs` metadata is
  in SQLite (RFC-0056).
- **Diagnostic logs.** Files, not rows. High-volume log lines would contend with the single
  writer (RFC-0037).
- **The instance lock.** Transient state on disk, not a table (RFC-0055).

## Migrations

Forward-only, transactional, run under the project lock (RFC-0055). A database written by a
newer runtime opens **read-only** with `storage.migration_required` rather than failing — version
skew between a phone and a desktop is the normal state, not an exception (RFC-0039).

Migration files will live in `schema/migrations/NNNN-description.sql` once there is more than
one version. Until then these files *are* version 1.

## Keeping this honest

`check.py` runs in CI. When an RFC changes a table, change it here in the same commit — the RFC
explains the *why*, this file is the *what*, and a divergence between them is how the corpus
drifted before.
