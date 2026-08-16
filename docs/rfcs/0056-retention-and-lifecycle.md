# RFC-0056: Retention, Compaction, and Storage Lifecycle

Status: Accepted 2026-08-03

## Abstract

This RFC defines what Aidos deletes, when, and under whose authority. It closes a gap in which
every store was append-only and nothing ever reclaimed space — a design that fails first and
worst on the mobile devices Aidos targets first.

## Motivation

Before this RFC, the architecture accumulated without bound:

- Events retained "for the lifetime of the project" (RFC-0004).
- Attempts append-only with input and output snapshots (RFC-0019).
- A `PromptPackage` JSON per Attempt (RFC-0025).
- Artifacts undeletable while referenced by provenance edges, and provenance edges never
  deletable at all (RFC-0024).
- Tool output stored in the event, in the Attempt, and often as an artifact — three copies.

RFC-0099 lists "< 1GB per active project" as a success metric with no mechanism to achieve it.
On a phone, storage exhaustion is the failure users notice first and forgive least.

There is a real tension here, and it should be named: the audit trail is a product feature.
"What did the AI actually do?" is a trust-building differentiator. Retention policy must
therefore preserve *what happened* while discarding *the bulk of what it happened to*.

## Goals

1. Define retention classes and default policies per object class.
2. Define compaction: how history is summarized rather than deleted.
3. Define content deduplication.
4. Define storage pressure response on mobile.
5. Define what is never deleted.

## Non-goals

This RFC does not define backup (RFC-0041) or encryption (RFC-0035).

## Design

### The principle

**Skeletons are permanent; flesh is reclaimable.**

The structural record — that a Run happened, which Tasks it contained, which capabilities were
exercised, which artifacts were produced, what the outcome was — is small, bounded by activity
rather than by content size, and never deleted. The large payloads hanging off that skeleton —
prompt texts, tool stdout, intermediate snapshots, model responses — are reclaimable on a
schedule.

A three-year-old Run should still answer "what did it do and what did it change." It need not
still contain the 400KB of `stdout` from a test run.

### Retention classes

```kotlin
enum class RetentionClass {
    PERMANENT,      // never deleted
    DURABLE,        // deleted only on explicit user action
    AGED,           // deleted or compacted after a policy window
    EPHEMERAL,      // deleted as soon as it is no longer referenced
    DERIVED         // deleted freely; rebuildable from other data
}
```

| Object | Class | Default policy |
|---|---|---|
| Run / Task skeleton, outcomes, timings | PERMANENT | — |
| Audit log entries | PERMANENT | — |
| Capability grants and revocations | PERMANENT | — |
| Provenance edges | PERMANENT | — |
| ContentNode records (metadata) | PERMANENT | — |
| Artifact content the user kept or promoted | DURABLE | user action only |
| Commits and Git objects | DURABLE | Git's own gc; Aidos never prunes |
| Intent Graph | DURABLE | — |
| Attempt `input_snapshot` / `output_snapshot` | AGED | compact after 30 days |
| `PromptPackage` full text | AGED | compact after 30 days |
| Run transcripts (RFC-0008) | AGED | compact after 30 days |
| Tool result payloads over 64KB | AGED | truncate-with-hash after 7 days |
| Event payloads | AGED | compact after 30 days; headers kept permanently |
| Session memory raw turns | AGED | summarized per RFC-0025 |
| Knowledge index | DERIVED | rebuild on demand; evict under pressure |
| Model weights | DERIVED | user-managed; evict least-recently-used under pressure |
| Budget reservations | EPHEMERAL | released on settlement or recovery |
| Temp files, partial downloads | EPHEMERAL | cleaned on startup |

### Compaction, not deletion

Compaction replaces a large payload with a small, structured summary that preserves the
answerable questions:

```
Before:  attempt.output_snapshot = <380KB of test runner stdout>
After:   attempt.output_snapshot = {
           "compacted_at": "...",
           "original_bytes": 389421,
           "original_sha256": "...",
           "summary": "142 tests, 3 failed: AuthTest.expiry, ...",
           "head": "<first 2KB>",
           "tail": "<last 2KB>"
         }
```

The hash is retained so that if the same content exists elsewhere — in an artifact the user
kept, in Git — it can still be located and verified. Head and tail are retained because they
carry most of the diagnostic value of a log.

Compaction is idempotent and runs on the `background` dispatcher (RFC-0007), in bounded batches
with cancellation checks, never blocking session work.

### Deduplication

`ContentNode.contentHash` is already SHA-256 and already indexed (RFC-0024). Deduplication moves
from Future Work into MVP: blob storage is content-addressed under `.aidos/blobs/<hash>`, and
identical content across nodes stores one copy with a reference count.

This is not an optimization for its own sake. An agent loop re-reads the same files repeatedly
across steps and Runs; without dedup, a single file read twenty times is stored twenty times.

### Storage pressure on mobile

The runtime tracks its own footprint and responds in escalating stages:

| Stage | Trigger | Action |
|---|---|---|
| 1 | project > soft limit (default 512MB) | Compact AGED content older than 7 days |
| 2 | project > hard limit (default 2GB), or device free space < 1GB | Evict DERIVED: knowledge index, then LRU model weights |
| 3 | device free space < 250MB | Stop starting new Runs; notify; offer archive/export |

Stage 3 stops rather than degrades because a Run that fails midway through writing is worse
than a Run that never started. The user is told which projects are largest and offered export.

Eviction of model weights is a real cost — re-downloading is expensive and may be impossible
offline. Weights are therefore evicted last, LRU, never automatically when offline, and never
without notification.

**Amendment 2026-08-14 (RFC-0103) — Stage 2's "then LRU model weights" is no longer an action Aidos
Agent can perform on MOBILE.** Model weights moved to **Aidos Engine**'s own storage, a separate
app Aidos Agent cannot delete files from. Two real conflicts, not just a relocation:

- **Aidos Agent has no mechanism to trigger weight eviction in Engine at all.** RFC-0103 names no
  such API — its own Storage screen for weights is explicitly "Manual only... Engine never deletes
  weights on its own to make room" (RFC-0103, "4 · Storage"). Stage 2's automatic LRU eviction
  under device-storage pressure has nothing on the other side of the app boundary to call.
- **Even if it did, RFC-0103's "manual only" policy for Engine's own weight deletion directly
  conflicts with this RFC's automatic-eviction-under-pressure design for that same data**,
  independent of which app performs it. This is a real, unresolved question — not just a wiring
  gap — for whoever reconciles the two: should Aidos Engine gain an automatic eviction path when a
  *different* app's storage pressure asks for it (weakening RFC-0103's "manual only" guarantee), or
  should this RFC's Stage 2 stop naming model weights as something it can evict on MOBILE and fall
  through to Stage 3's stop-and-notify behavior sooner instead?

This item was already `post-MVP` in this RFC's own scope (Stage 2 model eviction is not required
for the MVP milestone this RFC gates), so it is not blocking — but the design as currently written
will not build against Aidos Engine's architecture without settling the question above first, and
it should not be picked up as a routine implementation task without doing so.

### What is never deleted automatically

- Anything in Git. Aidos does not run `git gc`, does not prune refs it did not create, and
  does not delete user branches.
- Artifacts the user explicitly kept or promoted to resources.
- The audit log.
- Capability history.
- Any content whose hash is not present elsewhere and which is referenced by a PERMANENT record
  — such content is compacted only if a copy is verifiable in Git.

### User control

Retention is user-visible and user-adjustable per project: a window per AGED class, plus
"never compact this project" for work under audit obligations. Defaults are stated in the UI
rather than buried, because a system that silently discards the user's history has broken a
promise the vision made.

## Data Model

```sql
CREATE TABLE retention_policy (
    scope TEXT NOT NULL,              -- 'user' | 'project'
    scope_id TEXT,
    object_class TEXT NOT NULL,
    retention_days INTEGER,           -- NULL = never compact
    PRIMARY KEY (scope, scope_id, object_class)
);

CREATE TABLE compactions (
    id TEXT PRIMARY KEY,
    object_class TEXT NOT NULL,
    object_id TEXT NOT NULL,
    original_bytes INTEGER NOT NULL,
    retained_bytes INTEGER NOT NULL,
    original_sha256 TEXT NOT NULL,
    compacted_at TEXT NOT NULL
);

CREATE TABLE blob_refs (
    content_hash TEXT PRIMARY KEY,
    ref_count INTEGER NOT NULL,
    size_bytes INTEGER NOT NULL,
    last_accessed_at TEXT NOT NULL
);

CREATE INDEX idx_compactions_object ON compactions(object_class, object_id);
```

## Security

Compaction must not become an audit-evasion mechanism. Two rules:

1. Compaction never removes an audit log entry, a capability record, or the fact that an effect
   occurred — only the payload it operated on.
2. Every compaction is itself recorded in the `compactions` table with the original hash, so
   the audit trail states what was discarded and when.

Deletion of `SECRET`-labelled content (RFC-0024) is immediate and unconditional rather than
aged: secrets that leaked into a tool result are removed on detection, and the audit records
that a redaction occurred without recording the value.

## MVP

1. Retention classes and the default policy table above.
2. Compaction of Attempt snapshots, prompt packages, and event payloads, with head/tail/hash.
3. Content-addressed blob storage with reference counting.
4. Storage pressure stages 1 and 3 (stage 2 model eviction post-MVP).
5. `compactions` audit records.

Not in MVP: per-project retention UI, model weight eviction, verification against Git before
compaction.

## Future Work

Cold export: move AGED content to an external archive rather than compacting it, for users who
want full fidelity without local storage cost.

Content verification against Git before compaction, allowing more aggressive policies when a
verified copy exists in history.
