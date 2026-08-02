# RFC-0055: Runtime Instances and Process Concurrency

Status: Draft

## Abstract

This RFC settles whether the Aidos runtime is a library or a daemon, and defines how a project
is protected from concurrent access by two runtime instances, by a second frontend, or by the
user's own Git tooling.

## Motivation

RFC-0007 defines careful in-process concurrency: a per-project session mutex and a single
SQLite writer. It says nothing about two processes, and the architecture makes two processes
normal:

- RFC-0052 loads the runtime as a library inside the desktop application process *and* defines
  a CLI that connects to "a running runtime daemon."
- RFC-0002 claims multiple frontends can interact with the same runtime, which an in-process
  library cannot provide across applications.
- Projects are Git repositories, so the user will run `git` in a terminal on the same directory
  while Aidos holds derived state about it.

Two runtimes on one project will not corrupt SQLite — WAL handles that — but they will corrupt
everything above it: two Capability Managers with independent caches, two schedulers waking the
same sessions, two executors driving the same Run, two writers to the Git index.

## Goals

1. Decide the deployment shape per platform profile.
2. Define project locking and liveness.
3. Define behaviour when a lock cannot be acquired.
4. Define coexistence with the user's own Git usage.

## Non-goals

This RFC does not define the Runtime API surface (RFC-0052) or remote access.

## Design

### Deployment shape

**The runtime is a daemon on DESKTOP and HEADLESS_SERVER, and an in-process service on MOBILE.**

| Profile | Shape | Rationale |
|---|---|---|
| DESKTOP | Separate process; frontends connect over a Unix domain socket / named pipe | Multiple frontends (GUI, CLI, editor plugin) are a real requirement; a crashed UI must not kill a running Run |
| HEADLESS_SERVER | Daemon | Same |
| MOBILE | In-process, inside a foreground service | Android has no meaningful multi-process story for this, and a second process would be evicted independently |

This resolves the RFC-0052 contradiction. The `RuntimeClient` interface stays identical across
shapes — that is its purpose — but the desktop implementation is a socket client, not a direct
call. **Frontends are written against a boundary that is real on the platform where multiple
frontends exist**, so the seam cannot silently erode into shared mutable state.

On MOBILE the in-process shape is still mediated by `RuntimeClient`; the boundary is a
discipline rather than a process. This is acceptable because MOBILE has exactly one frontend by
construction.

### Project locking

A project is owned by at most one runtime instance at a time.

```
<project-root>/.aidos/instance.lock
```

Contents:

```toml
instance_id  = "01J..."      # UUIDv7, per runtime process
pid          = 48213
profile      = "DESKTOP"
hostname     = "..."         # for the message; not for correctness
acquired_at  = "2026-08-02T09:14:22Z"
heartbeat_at = "2026-08-02T09:41:07Z"
```

Acquisition:

1. Take an OS advisory lock on the file (`FileChannel.tryLock`). This is the actual mutual
   exclusion; the file contents are for diagnostics.
2. Write the metadata and start a heartbeat, updating `heartbeat_at` every 30 seconds.
3. Release on clean shutdown.

Advisory file locks are released by the OS when the process dies, so a crashed runtime does not
leave a project permanently locked. The heartbeat exists for the case the OS lock cannot be
trusted — network filesystems, some Android storage volumes — where a lock whose heartbeat is
older than 3 minutes is treated as stale and may be broken with an audit record.

**Locks are never broken silently.** Breaking a stale lock is a user-visible action with an
explanation, because the alternative is two executors driving one Run.

### Failure to acquire

Opening a project already held returns `runtime.locked_by_other_instance` (RFC-0029) with the
holder's metadata. Frontends offer:

- **Connect to the holder** (desktop: the socket path is derivable from the instance ID) — the
  correct action almost always.
- **Open read-only** — browse sessions, artifacts, and history with no Run execution and no
  writes. Genuinely useful, and safe.
- **Break the lock** — only offered when the heartbeat is stale, with an explicit warning.

Read-only mode is worth the implementation cost: "let me look at what that other window is
doing" is a common need, and the alternative is users breaking locks.

### The single writer, restated

Within the owning instance, RFC-0007's discipline holds unchanged: one SQLite write connection
per project, serialized through `ProjectWriteContext`; WAL mode for concurrent reads.

Read-only instances open the database read-only and **do not** run recovery, migrations, or
indexing. A read-only opener that ran migrations would defeat the entire lock.

### Coexistence with the user's Git

Aidos does not lock the user out of their own repository, and must not. The user running
`git checkout` in a terminal is expected behaviour, not a fault.

Coexistence rules:

1. Aidos holds no long-lived Git lock. JGit operations acquire and release `.git/index.lock`
   for the duration of a single operation only.
2. Before any Git write, the runtime re-checks the repository fingerprint (RFC-0053). If it
   moved, the write is abandoned and reconciliation runs.
3. If `.git/index.lock` is held by another process, Git writes fail with `TRANSIENT` and are
   retried with backoff rather than forcing the lock.
4. Aidos never removes a lock file it did not create.

This is the one place where the architecture accepts optimistic concurrency with an outside
party, because pessimistic locking against the user's own tooling would make the product
hostile.

### Migrations and version skew

Schema migrations require the lock and are forward-only (RFC-0017). A project written by a
newer runtime opens read-only with `storage.migration_required` rather than failing outright,
so a user whose phone updated before their laptop can still read their work.

This is a direct consequence of the mobile-first, multi-device use case: version skew between a
phone and a desktop is the normal state, not an exception.

## Data Model

No persistent schema. The lock file is transient state on disk.

```sql
-- Recorded when a lock is broken, because it is a correctness-relevant event.
CREATE TABLE lock_breaks (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL,
    previous_instance_id TEXT NOT NULL,
    previous_heartbeat_at TEXT,
    broken_by_instance_id TEXT NOT NULL,
    broken_at TEXT NOT NULL,
    audit_ref TEXT NOT NULL
);
```

## Security

The daemon socket is created with owner-only permissions (`0600`) in the user's runtime
directory. Additionally, and this closes a real hole in RFC-0052's "all local connections are
trusted" model:

1. The runtime mints a **connection token** at startup, stored in a file readable only by the
   user, and every client must present it.
2. **Child processes spawned by the Tool Broker — stdio MCP servers, plugin hosts, shell
   commands — receive a scrubbed environment with no token and no socket path.**

Without rule 2, a stdio MCP server could connect to the runtime that spawned it and call
`capabilities.approve()` on its own pending request, defeating the human-in-the-loop that the
entire security model rests on. This is the highest-value item in this RFC.

3. Commands that grant or approve authority additionally require a connection flagged
   `user_interactive`, which only a frontend that can present UI may claim.

## MVP

1. Daemon on desktop, in-process on Android; `RuntimeClient` unchanged across both.
2. Advisory file lock with heartbeat; clean release on shutdown.
3. `runtime.locked_by_other_instance` with holder metadata; connect-to-holder path.
4. Connection token; scrubbed environment for spawned children; `user_interactive` flag for
   approval commands.
5. Fingerprint re-check before Git writes; no forcing of `.git/index.lock`.

Not in MVP: read-only open mode, lock breaking UI, Windows named pipes.

## Future Work

Read-only instances with live event subscription to the owning instance.

Paired remote runtime: a phone connecting to a desktop runtime to execute `PLATFORM`-tier steps
it cannot run locally (RFC-0049 Future Work). This is where the daemon shape pays off.
