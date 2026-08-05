# RFC-0026: Model Memory

Status: Draft

## Abstract

This RFC distinguishes the four places information persists across model calls — run transcript,
session memory, project knowledge, and **provider-side retention** — and defines what may be
remembered, for how long, on whose authority, and what the user is told. It treats memory as a
privacy surface first and a capability second.

## Motivation

Memory is the feature most likely to become a privacy hazard in an AI runtime, because it is the
feature where the system decides on its own what to keep about you.

Four distinct things get called memory, and conflating them is how the hazard arises:

1. **Run transcript** — turns within one Run. Ephemeral by design (RFC-0008).
2. **Session memory** — what a session carries between Runs (RFC-0011).
3. **Project knowledge** — the indexed content of the project (RFC-0015).
4. **Provider-side retention** — what a remote provider keeps after the call returns.

The fourth is the one no amount of local design controls, and the one users assume does not
exist. A local "delete my memory" that leaves data in a provider's logs is a false promise, and
making it is worse than not offering the feature at all.

There is a second hazard specific to long-lived agents: **memory drifts from facts about the
work to inferences about the person.** "The auth module uses JWT" is a project fact. "The user
prefers terse responses and gets frustrated on Friday afternoons" is a profile, and nobody asked
for one.

## Goals

1. Define the four memory locations and what belongs in each.
2. Define what may be written to durable memory, and by whom.
3. Define expiry, review, and deletion.
4. Define how provider-side retention is recorded and surfaced.
5. Define memory's interaction with taint (RFC-0027) and egress (RFC-0024).

## Non-goals

This RFC does not define the session memory schema (RFC-0011) or context assembly (RFC-0025).
It does not define the knowledge index (RFC-0015).
It does not define training or fine-tuning.

## Design

### The four locations

| | Lifetime | Written by | User control | Leaves device |
|---|---|---|---|---|
| Run transcript | one Run, then compacted | runtime | implicit | only inside the prompt |
| Session memory | session lifetime | runtime, from Run outcomes | reviewable, deletable | in prompts |
| Project knowledge | project lifetime | indexer, derived | rebuildable, deletable | in prompts |
| Provider retention | **the provider's policy** | the provider | **none** | already gone |

The rows are ordered by descending user control, ending at none. Any feature described to the
user as "memory" must state which row it is.

### What may be written to durable memory

Session memory entries (RFC-0011) are `FACT`, `DECISION`, and `TASK_STATE`. Two rules govern
what may become one.

**There is no `SUMMARY` kind** (D32). A model-written compaction of a session was removed rather
than deferred: it is a model reporting on its own work (D6), it launders taint across the Run
boundary (RFC-0027), it parks without a foreground service (D24), and it is the adaptive-
compression machinery D22 already declined. Conversation history that does not fit is dropped
with an omission marker (RFC-0025); "what happened" is answered by the Run Summary, a projection
over the Execution Graph (RFC-0057). The three kinds that remain are **specific cited claims,
not free-form prose** — which is what makes them reviewable, invalidatable, and safe to carry.

**Rule 1 — memory is about the work, not the worker.** Permitted: facts about the project,
decisions and their reasons, task state. Not permitted: inferred preferences, working patterns,
emotional state, productivity observations, or any characterization of the user.

This is a hard boundary, not a default. A coding agent has no legitimate need for a user profile,
and building the capability creates an obligation to secure and explain something that should
not exist. Users who want the model to know their preferences state them in an instruction file
(RFC-0016) — explicit, editable, visible, and versioned.

**Rule 2 — durable memory cites its source.** Every entry carries `source_refs` pointing at the
Run, Attempt, or ContentNode that justifies it. A fact with no traceable origin cannot be
verified, cannot be invalidated when its source changes, and is indistinguishable from a
hallucination that has been promoted to a belief.

```kotlin
data class MemoryEntry(
    val id: String,
    val sessionId: SessionId,
    val kind: MemoryKind,               // FACT | DECISION | TASK_STATE
    val content: String,
    val sourceRefs: List<SourceRef>,    // Run, Attempt, or ContentNode — never empty
    val createdBy: ActorRef,            // who recorded it (RFC-0046)
    val confidence: Confidence,
    val trustLevel: TrustLevel,         // RFC-0027; max taint of its sources
    val createdAt: Instant,
    val expiresAt: Instant?,
    val supersededBy: String?
)

enum class Confidence { OBSERVED, INFERRED, USER_STATED }
```

**`createdBy` and `confidence` answer different questions and both are needed.** `confidence` says
how the claim was arrived at; `createdBy` says which actor recorded it. Without the second, a
`USER_STATED` entry is distinguishable from a session's inference only by trusting the first —
which is a field the session itself writes.

`confidence` matters at recall: `INFERRED` entries are presented to the model as inferences
("a previous run concluded…"), never as facts. An inference laundered into a fact by being
written down is the mechanism by which an agent becomes confidently wrong over weeks.

### Taint propagates into and out of memory

A memory entry carries the maximum trust level of its sources. A `FACT` derived from a Run that
read untrusted content is `UNTRUSTED`, and a Run that includes it inherits that taint (RFC-0027).

Without this, memory is a taint-laundering channel: read a hostile document, write it into
memory, and have it re-enter future Runs as trusted session state. Closing the channel costs one
field and one `max()`.

The largest version of that channel is closed structurally rather than by the field: **there is
no model-written `SUMMARY`** (D32). A `max()` at every write site is a rule someone can forget;
an absent summarizer is not.

### Expiry and review

- `TASK_STATE` expires when its Run reaches a terminal state.
- `FACT` carries a default expiry (90 days), refreshed on recall. A fact nobody has needed in
  three months is probably stale.
- `DECISION` does not expire. These are small, and they are the most valuable thing a long-lived
  session accumulates.

**Review is a first-class surface, not a debug screen.** The user can list, search, inspect the
source of, edit, and delete entries. Deleting an entry deletes it — no tombstone retains the
content.

### Provider-side retention

The runtime cannot control what a remote provider keeps. It can, and must, record and surface it.

Every Attempt against a remote model records the provider's stated policy at the time of the
call:

```kotlin
data class ProviderRetention(
    val policy: RetentionPolicy,    // ZERO | TRANSIENT | RETAINED | UNKNOWN
    val statedDurationDays: Int?,
    val trainingUse: TrainingUse,   // NONE | OPT_OUT_HONOURED | UNSPECIFIED
    val recordedAt: Instant
)
```

Three consequences:

1. **`UNKNOWN` is a valid and honest value.** A provider that states no policy is recorded as
   unknown rather than assumed benign.
2. **Retention is shown before egress, not after.** The approval prompt for sending content to a
   remote model states what that provider retains. That is the last moment the user can decide.
3. **"Delete my memory" says what it cannot do.** Local deletion is exact. Content already sent
   to a provider is beyond reach, and the UI says so — naming the providers and the date range
   rather than implying a clean erase.

This section is why this RFC exists separately from RFC-0011.

### What memory is not

Memory is not a substitute for the knowledge index. A session that "remembers" a file's contents
has copied data RFC-0015 already stores, content-addressed and current. **Memory holds
conclusions; the index holds content.**

Memory is also not cross-session. A session's memory is its own. Conclusions move between
sessions through content nodes and the intent graph, which are reviewable — not through an
ambient store accumulating unattributed beliefs.

## Data Model

`schema/project.sql` is canonical; this restates it so the RFC can be read on its own.

```sql
CREATE TABLE memory_entries (
    id               TEXT PRIMARY KEY,
    session_id       TEXT NOT NULL,
    project_id       TEXT NOT NULL,
    -- No SUMMARY kind: no model-written compaction of a session (D32).
    kind             TEXT NOT NULL
                     CHECK (kind IN ('FACT','DECISION','TASK_STATE')),
    content          TEXT NOT NULL,
    source_refs_json TEXT NOT NULL,                       -- never '[]'
    -- Who recorded it, distinct from what justifies it (RFC-0046).
    created_by_kind  TEXT NOT NULL,                       -- USER|SESSION|WORKER|RUNTIME
    created_by_id    TEXT NOT NULL,
    confidence       TEXT NOT NULL,
    trust_level      TEXT NOT NULL DEFAULT 'UNTRUSTED',
    created_at       TEXT NOT NULL,
    expires_at       TEXT,
    superseded_by    TEXT,
    FOREIGN KEY (session_id)    REFERENCES sessions(id),
    FOREIGN KEY (project_id)    REFERENCES projects(id),
    FOREIGN KEY (superseded_by) REFERENCES memory_entries(id)
);

CREATE INDEX idx_memory_active ON memory_entries(session_id, kind)
    WHERE superseded_by IS NULL;
```

`attempts.provider_retention_json` holds the per-Attempt retention record described above. It is
a column of `attempts` in `schema/project.sql`, not an `ALTER` — an earlier version of this RFC
wrote it as a migration step, which would have been a second, conflicting definition of a column
the canonical schema already declares inline.

## Security

1. Entries pass through the redactor (RFC-0035) before storage. A secret that appeared in a tool
   result must not survive as a remembered "fact".
2. `SECRET` and `SENSITIVE` content (RFC-0024) never enters memory.
3. Memory carries taint and cannot launder it.
4. No user profiling, per Rule 1. Enforced by a test corpus asserting what memory must never
   contain (RFC-0038), applied to every write path.
5. Memory is included in project export (RFC-0041) and must therefore be redaction-clean, since
   an export may be shared.

## MVP

1. `MemoryEntry` with mandatory `source_refs`, `confidence`, and `trust_level`.
2. `TASK_STATE` and `DECISION` kinds. There is no `SUMMARY` kind (D32).
3. Taint propagation into and out of memory.
4. Provider retention recorded per remote Attempt and shown in the egress approval prompt.
5. A memory review surface: list, inspect source, delete.

Not in MVP: `FACT` extraction, expiry sweeps, memory search, cross-provider retention reporting.

## Future Work

Automatic invalidation of `FACT` entries when their `source_refs` change — the content-addressed
index (RFC-0015) makes this detectable rather than guesswork.

A retention report: "in the last 90 days, content from this project went to these providers,
under these stated policies."

User-stated preferences as a first-class instruction source, so the legitimate half of "remember
how I like things" is served explicitly rather than by inference.
