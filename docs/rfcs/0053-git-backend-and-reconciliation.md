# RFC-0053: Git Backend and External Mutation Reconciliation

Status: Accepted 2026-08-03

## Abstract

This RFC settles two questions that "Git-first" left open: which Git implementation Aidos uses
on each platform, and what happens when the user or another tool changes the repository behind
the runtime's back. It selects JGit as the universal backend, states the consequences honestly,
and defines the reconciliation protocol that keeps SQLite-held state consistent with a Git tree
that can change at any moment.

## Motivation

No RFC named a Git implementation. For a Git-first architecture whose first platform is
Android, this is the single most consequential unmade decision:

- On Android there is no `git` binary and no way to ship one that can be executed as a general
  subprocess (RFC-0049).
- JGit is pure JVM and runs on Android, but does not implement `git worktree`, which RFC-0032
  called first-class.
- libgit2 via JNI supports worktrees but requires per-ABI native builds, complicates the build
  for every contributor, and adds a native crash surface to the most safety-critical subsystem.

Separately, RFC-0017 asserted that no two stores hold conflicting authority over the same
field, and then never addressed the defining property of a Git-first system: **Git is a shared
mutable store with other writers.** `git checkout`, `git pull`, `git rebase`, `git stash`, and
an IDE on the same directory all change the working tree and history while Aidos holds derived
state about it.

## Goals

1. Select the Git backend per platform profile and state the consequences.
2. Define which Git operations Aidos performs through the object database versus the working
   tree.
3. Define detection of external mutation.
4. Define the reconciliation protocol for each affected object class.
5. Define what the user sees when reconciliation cannot be automatic.

## Non-goals

This RFC does not define the Git tool's surface (RFC-0032) or capability scopes (RFC-0018).
It does not define multi-device sync (RFC-0041 covers export/import).

## Design

### Backend selection

**JGit on all profiles.** One implementation everywhere.

Rationale: a single backend means one set of behaviours to test and reason about, no native
build matrix, no JNI crash surface in the component that writes the user's source history, and
identical semantics between the phone and the desktop — which is the whole point of a project
that moves between them.

Consequences, stated plainly rather than discovered later:

| Consequence | Response |
|---|---|
| No `git worktree` support | Worker isolation uses treeless workers (RFC-0049); desktop worktrees are post-v1 and, if implemented, sit behind the same artifact contract |
| Slower than C Git on very large repositories | Acceptable for the target use case; measured in RFC-0045 budgets; large-repo support is a known limit, not a surprise |
| No hooks execution | Correct behaviour for Aidos. Executing repository-supplied hooks would be arbitrary code execution on clone (RFC-0057) |
| Partial clone / sparse checkout support is limited | Documented limitation; matters for very large repos on mobile, revisit if it becomes a real constraint |
| No `.gitattributes` filter drivers (clean/smudge), no LFS by default | Declared unsupported; a project using LFS reports an unsatisfied requirement (RFC-0049) |

The decision is revisitable, but only as a whole: adopting libgit2 later means adopting it on
every profile, not forking behaviour between them.

### Object database versus working tree

Aidos prefers the object database. Working-tree operations are the exception, not the default.

| Operation | Mechanism |
|---|---|
| Read file content at a ref | Object DB (`TreeWalk`) — no checkout needed |
| Read history, blame, diff | Object DB |
| Session edits a file | Working tree (the user is looking at it) |
| Worker produces changes | Object DB, in-memory `DirCache` → tree → commit (RFC-0049 treeless workers) |
| Commit user-visible work | Working tree → index → commit |
| Build/test | Working tree; `PLATFORM` tier only |

This preference is what makes the mobile use case efficient: reading and understanding a
codebase requires no checkout of anything, and a worker can produce a reviewable commit without
materializing a second copy of the tree on a phone.

### Detecting external mutation

The runtime records a **repository fingerprint** whenever it completes an operation:

```kotlin
data class RepoFingerprint(
    val headRef: String,          // e.g. "refs/heads/main"
    val headCommit: String,
    val indexChecksum: String,    // JGit DirCache checksum
    val dirtyPathCount: Int,
    val observedAt: Instant
)
```

Mutation is detected at three points:

1. **On project open** — always. This is the cheap, reliable path and it covers the common
   case (the user pulled while Aidos was closed).
2. **Before any Run starts** — a fingerprint comparison, single-digit milliseconds.
3. **On filesystem watch events** for `.git/HEAD`, `.git/index`, and `.git/refs/**` on profiles
   that support watching. On MOBILE, watching is unreliable and points 1 and 2 carry the load.

A fingerprint mismatch publishes `RepoMutatedExternally` on the Event Bus, carrying the old and
new fingerprints and a classification: `HEAD_MOVED`, `BRANCH_SWITCHED`, `HISTORY_REWRITTEN`
(the previous HEAD commit is no longer reachable), `INDEX_CHANGED`, or `WORKTREE_DIRTIED`.

### Reconciliation

Reconciliation runs before any Run may start on a repository with a mismatched fingerprint. It
is per object class, and every action is auditable.

| Object class | Reconciliation |
|---|---|
| **Knowledge index** | Derived; invalidate affected paths and re-index in background. Queries during re-index return stale-marked results rather than blocking (RFC-0015). |
| **ContentNode with `FilesystemPath`** | Re-hash. If content changed, create a new version for `VERSIONED` nodes; for `IMMUTABLE` nodes, the node now *dangles* — mark `state = DANGLING`, retain the record, and resolve future reads from the recorded `contentHash` in the object DB if reachable. |
| **ContentNode with `GitObject`** | Blob hashes are immutable. Reachability may change after `HISTORY_REWRITTEN`; mark unreachable nodes `DANGLING` rather than deleting them. |
| **Resource content hashes in SQLite** | Update to match Git. Git is authoritative for content (RFC-0017). |
| **Intent Graph** | SQLite is authoritative for structure. If a Git-committed intent snapshot is *newer than* the last snapshot the runtime wrote, this is a genuine conflict — see below. |
| **In-flight Run** | If a Run is parked and the repository moved underneath it, the Run is terminated with `FAILED(REPO_MUTATED)` rather than resumed. Resuming a plan built against a different tree is how silent corruption happens. |
| **Execution Graph history** | Never rewritten. It records what was true at the time, including a now-unreachable commit. |

`DANGLING` is added to `ContentNodeState` (RFC-0024). It is not an error state; it is an honest
one. A phone that switched branches has artifacts that reference content not currently checked
out, and pretending otherwise is worse than saying so.

### The intent-snapshot conflict

This is the one case where two stores genuinely contend, and RFC-0017's claim that no such case
exists was wrong.

SQLite holds the live Intent Graph. Git holds periodic snapshots so that intent is diffable and
travels with the repository. If a user runs `git revert` on a snapshot commit, or pulls a
branch whose snapshot differs, the two disagree.

Resolution:

1. Each snapshot records the SQLite `intent_version` it was written from.
2. On reconciliation, if the Git snapshot's `intent_version` is **not an ancestor** of the
   current SQLite version, a conflict exists.
3. Conflicts are **never resolved automatically.** The runtime marks the Intent Graph
   `CONFLICTED`, blocks AI-proposed intent edits (user edits remain allowed), and presents a
   three-way view: the common ancestor, the local state, and the incoming snapshot.
4. The user picks. The choice is recorded in the audit log.

Automatic merge of intent is explicitly rejected. Intent is the one structure whose whole
purpose is to represent what the human wants; silently merging it defeats it.

### Branch switching

Aidos switches branches. JGit supports it — `CheckoutCommand`, and `stashCreate`/`stashApply`
since JGit 2.0 — so nothing here is blocked by D4.

**Uncommitted changes are discarded, after an explicit warning.** This is a deliberate departure
from Git's own behaviour, which carries uncommitted changes across a checkout and only refuses
when they would be clobbered. Carrying them is worse here: it silently moves edits onto a branch
they were not written for, and the user finds out later, on a phone, with no easy way to
untangle it. Aidos would rather say what it is about to destroy.

```
switch to <branch>:
    if working tree is clean          → switch
    else                              → warn, naming the files and line counts,
                                        and offer to commit first.
                                        On confirmation: discard, then switch.
```

The warning is a `Preview` like any other mutation, so it lists exactly what will be lost rather
than saying "you have uncommitted changes". **Committing first is the primary action** in that
prompt; discarding is available but is not the default button. That is a UI consequence, not a
new mechanism.

Per-branch working state — leaving edits behind on the branch they were made on, restoring them
on return — was considered and is **not** built. It would need a WIP ref per branch under
`refs/aidos/`, and it trades a rule the user already knows from Git for one only Aidos has.

#### The effect is irreversible, and the type system now says so

A destructive checkout is `Mutate(IN_PROJECT, reversible = false)`.

The `reversible` flag exists because of this operation. It is **not** the same question as
`RecoveryClass`, which asks whether an effect can be safely re-run after a crash: a checkout is
perfectly re-runnable and still annihilates an hour of typing. Without the distinction, a
destructive checkout satisfies D26's benign class — in-project, not `UNSAFE`, untainted — and
becomes approvable by saying *"approve"* while cycling. **D26's benign class now requires
`reversible = true`.**

#### What a switch invalidates

| | On branch switch |
|---|---|
| Knowledge index | **Nothing.** Index identity is the blob hash (RFC-0015), so entries stay valid across any ref movement |
| Treeless workers | **Nothing.** Workers build against the object database on `refs/aidos/workers/<id>` and never read the working tree |
| Reviewed/unreviewed marks | Invalidated — keyed on `(path, base blob hash)`, and the base moved (D25) |
| Uncommitted working-tree changes | Discarded, after the warning above |
| A Run parked on a working-tree mutation | **Its approval is invalidated** |

That last row is the sharp one. A Run parked waiting for approval to write `Client.kt` refers to
a file state that no longer exists after the switch. The pending approval is discarded and the
Run re-parks against the new state, or fails with a named error (RFC-0029). An approval given for
one content state is never silently applied to another — the same rule as review marks not
surviving a rebase, for the same reason.

A branch switch performed by Aidos records a `BRANCH_SWITCHED` reconciliation exactly as an
external one does. There is no privileged path: the runtime's own checkout is observed by the
same fingerprint machinery that observes the user's.

### What Aidos does not do to the user's repository

- Never runs repository-supplied hooks.
- Never rewrites history the user did not ask to rewrite.
- Never force-pushes.
- Never commits automatically without an explicit capability grant and a recorded intent.
- Never modifies `.git/config` beyond adding its own namespaced section.
- Worker refs live under `refs/aidos/**` and are pruned on session archive, so they never
  pollute the user's branch namespace.

## Data Model

```sql
CREATE TABLE repo_fingerprints (
    project_id TEXT PRIMARY KEY,
    head_ref TEXT NOT NULL,
    head_commit TEXT NOT NULL,
    index_checksum TEXT NOT NULL,
    dirty_path_count INTEGER NOT NULL,
    observed_at TEXT NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

CREATE TABLE reconciliations (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL,
    classification TEXT NOT NULL,       -- HEAD_MOVED | BRANCH_SWITCHED | HISTORY_REWRITTEN | ...
    from_commit TEXT,
    to_commit TEXT,
    nodes_invalidated INTEGER NOT NULL,
    nodes_dangling INTEGER NOT NULL,
    runs_terminated INTEGER NOT NULL,
    intent_conflicted INTEGER NOT NULL,
    performed_at TEXT NOT NULL,
    audit_ref TEXT NOT NULL
);
```

## Security

Cloning or importing a project must not execute anything (RFC-0003, RFC-0041). JGit not running hooks is
load-bearing for this property, not incidental.

A destructive branch switch is `reversible = false`, which excludes it from D26's benign class.
Discarding a user's uncommitted work is not something to be agreed to by a single spoken word
while cycling, and the classifier now has the vocabulary to say so.

`HISTORY_REWRITTEN` is a security-relevant event: it can remove the commits an audit trail
references. The Execution Graph retains its own record independently of Git reachability, so a
rewritten history cannot erase what Aidos recorded.

## MVP

1. JGit on all profiles; object-DB reads; treeless workers.
2. Fingerprinting on open and before each Run.
3. Reconciliation for the knowledge index, content-node re-hashing, and `DANGLING` marking.
4. `FAILED(REPO_MUTATED)` for parked Runs whose repository moved.
5. `refs/aidos/**` namespace; no hooks; no force-push.

Not in MVP: filesystem watching of `.git`, intent-snapshot three-way merge UI (MVP marks
`CONFLICTED` and blocks AI edits), desktop worktrees.

## Future Work

Desktop worktree workers, behind the same commit-artifact contract as treeless workers.

Reachability-aware garbage collection coordination, so Aidos-referenced objects are not pruned
by a user's `git gc`.

Evaluate libgit2 if large-repository performance on desktop becomes a real constraint —
adopted on all profiles or none.
