# RFC-0015: Knowledge Engine

Status: Accepted 2026-08-04

## Abstract

The Knowledge Engine gives a session semantic access to a project's code and history: *what in
this repository is about retry logic*, *when did this concept first appear*, *what does this
module look like*. Aidos does not build it. It **consumes `gitsema-kotlin`** — a Kotlin
Multiplatform port of `jsilvanus/gitsema` — as an ordinary library dependency (D29).

This RFC is therefore not a design for an engine. It is a **consumption contract**: what Aidos
calls, what Aidos guarantees the library about where it may run and what it may touch, what the
library guarantees back, and what an honest answer looks like when the index is incomplete.

## Motivation

A session cannot be handed a repository. A 1M-line codebase does not fit in a context window,
and arbitrary samples of it are worse than nothing — they look like evidence. Something has to
decide which few thousand tokens of a project are relevant to the message the user just sent.

That is a retrieval problem with a well-understood shape, and one that a Git-native,
content-addressed index solves unusually well. It is also a problem Aidos would be foolish to
solve twice: `gitsema` already implements it, keyed on exactly the identity model Aidos
independently converged on, because Aidos adopted that model *from* gitsema.

**What changed, and why this document is short.** RFC-0015 was originally written as if Aidos
would build a knowledge engine — a provider SPI, an entry schema, an invalidation mechanism,
ranking heuristics. D29 settled that it is consuming one instead. Most of the previous 770 lines
described a system Aidos is not building, and describing it created the impression that someone
had committed to building it.

## Goals

1. Define the contract between Aidos and `gitsema-kotlin` precisely enough to implement against.
2. State what Aidos owns and what the library owns, so neither is surprised.
3. Make an incomplete index visible in every answer rather than silently confident.
4. Record the properties Aidos depends on, so a change upstream is a change Aidos *notices*.

## Non-goals

This RFC does not specify the library's internals, its schema, its algorithms, or its
performance characteristics. Those live in
[`docs/design/kotlin-port.md`](https://github.com/jsilvanus/gitsema/blob/main/docs/design/kotlin-port.md)
in the gitsema repository, which is authoritative for the port. Where this document restates
something from there, it does so because Aidos *relies* on it and would need to react if it
changed — not to duplicate ownership of it.

It does not define prompt assembly (RFC-0025), which is the consumer of query results.

It does not define a plugin or provider extension mechanism. There is none.

## Design

### The division of ownership

D29's split, stated as a table because it is the whole design:

| | Owner |
|---|---|
| The index schema, algorithms, ranking, storage format | **`gitsema-kotlin`** |
| Where the index lives on disk | **Aidos** |
| When indexing starts, yields, and stops | **Aidos** |
| The resource envelope — dispatcher, concurrency, cancellation | **Aidos** |
| The embedding model, and whether it can reach a network | **Aidos** |
| Presenting coverage and degradation to the user and the model | **Aidos** |

The mechanism that makes this real is dependency injection: the library's entry point takes its
git access, its embedding provider, and its three stores as constructor arguments. **Everything
Aidos must own is something Aidos passes in.** The split is not a convention both sides remember
to honour — it is the shape of the constructor.

**Aidos does not own the DDL, and `schema/` must never contain the library's tables.**
`schema/check.py` asserts that every table named in RFC DDL exists in the canonical schema; if
the index's tables were added there, CI would be policing a schema Aidos does not understand and
every upstream migration would become an Aidos migration. This RFC contains no DDL for that
reason.

### What Aidos calls

Three methods. The whole surface:

```kotlin
interface SemanticIndex {
    suspend fun index(ref: String, onProgress: (IndexProgress) -> Unit = {}): IndexResult
    suspend fun search(query: Query): SearchResult
    suspend fun status(): IndexStatus
}
```

A `SearchResult` carries the matches, an `IndexCoverage`, and a search-level `degraded` flag.
`IndexStatus` carries the same `IndexCoverage` plus the resume point and the embedding model.

A `Match` carries a blob hash, the paths that blob has been seen at, a line span, a score, and
its provenance (`VECTOR` / `FTS` / `HYBRID`) — never an Aidos domain type. The adapter maps it
into `ContextItem` (RFC-0025) on the way out.

Aidos reaches this through `KnowledgeContextProvider` (RFC-0025, `runtime/kernel/Knowledge.kt`),
which is the only interface the rest of the runtime sees. **That interface exists because
RFC-0025 needs a seam for prompt assembly, not because a second knowledge source is anticipated.**
There is exactly one implementation and no `KnowledgeProvider` SPI: a `query`/`is_current`/
`update`/`subscribe` seam maintained for hypothetical implementors is the speculative
extensibility D18 and D22 refused elsewhere. If a second source ever appears, the seam is cheap
to add against a real second case.

### What Aidos guarantees the library

1. **A background dispatcher**, passed explicitly. Every store and the Git layer take an
   `ioContext: CoroutineContext` at construction, so the host owns the dispatcher rather than the
   library assuming one — Aidos can keep this work off its own IO pool or confine it to a single
   thread. Indexing never runs on a foreground/UI dispatcher and never inside a Run's step
   (RFC-0009); it is a scheduled job (RFC-0044).
2. **Cancellation, and that cancellation is honoured.** Aidos cancels indexing on app
   backgrounding, foreground-service loss (D24), and project close. See the reciprocal guarantee
   below.
3. **An `EmbeddingProvider` that does not reach a network.** The library never dials out; it
   calls what it is handed. On MOBILE that is a local GGUF model through llama.cpp (D28). **The
   "no network" property is Aidos's to keep, not the library's to promise** — which is the right
   place for it, since Aidos is where egress policy lives (RFC-0042, RFC-0027).
4. **Storage under `.aidos/index/`**, never in `state.db` (D21, RFC-0054). Embedding writes would
   contend with the single writer (RFC-0007) and would inflate the file the user backs up with
   entirely rebuildable data.
5. **A bounded concurrency and batch size**, passed at construction, subject to the device's
   resource budget (RFC-0045).

### What the library guarantees back

1. **Blob-hash identity**, with the consequences below.
2. **Content-addressed idempotency.** A blob is embedded once per model. Re-running an
   interrupted index re-walks already-safe ground rather than re-embedding it, so interruption is
   cheap rather than merely safe.
3. **Cooperative cancellation.** `index()` calls `ensureActive()` at the top of both its
   commit-mapping and blob loops — once per commit, once per batch — rather than relying on store
   calls happening to redispatch. A cancelled run leaves the resume cursor untouched, so the next
   run resumes from the last known-good point.
4. **Search never blocks on indexing.** Before any vectors exist for the active model, `search()`
   returns FTS-only results marked `degraded`. It does not wait, and it does not return empty.
5. **Bounded ingestion memory.** Both the commit walk and the blob walk are consumed as `Flow`s
   in bounded windows, so peak memory is one window regardless of history length and the
   embedding pass backpressures the walk rather than the walk racing ahead. This was a documented
   gap when RFC-0015 was written — the indexer drained each walk into a list first — and it is
   closed.
6. **Bounded search memory.** Vectors are int8-quantized in a memory-mapped flat file, scored
   through a bounded top-K min-heap: **O(topK), not O(stored vectors)**. This matters more than it
   sounds — the original TypeScript implementation materialised the whole vector table per query,
   roughly 150 MB for a 50k-blob repository, which is incompatible with a loaded LLM competing for
   the same phone's memory.

### Index identity: address by content hash, not by path

**The unit of index identity is the Git object hash, not the file path.**

A path is a *name* that points to different content at different times, and to different content
on different branches. Indexing by path requires invalidation on every change and every checkout,
re-embeds identical content once per commit that touches it, and cannot answer questions about
history without a second index.

A blob hash is immutable and content-addressed. Index it once, and the entry is correct
permanently, on every branch, in every commit that has ever referenced it.

This is the model the rest of the architecture already converged on — content-addressed blobs
with reference counting (RFC-0056), object-database-first reads (RFC-0053), an indexed
`content_hash` on every ContentNode (RFC-0024), and the identity a diff hunk needs (D25).

Four consequences, in rising order of importance:

1. **Deduplication is automatic.** A file unchanged across 500 commits is embedded once. A file
   copied between directories is embedded once. Vendored dependencies shared between branches are
   embedded once.
2. **History is nearly free.** Searching across time is not a separate feature. Every blob ever
   committed is already indexed, and "what did this look like before the refactor?" is a lookup.
3. **Incremental indexing is a set difference.** Indexing a new commit means: list the blobs in
   its tree, subtract the blobs already indexed, embed the remainder. Usually a handful of files.
4. **Branch switching costs nothing.** This is the one that matters most, and it removes a problem
   RFC-0053 would otherwise have to solve. `git checkout` changes which blobs are *visible*; it
   changes no blob. There is nothing to invalidate.

On a phone, where re-indexing is expensive in both battery and time, that last point is the
difference between semantic search being usable and being something the user turns off.

### Addressing classes

Not all knowledge is blob-addressable, and pretending otherwise is how caches go stale silently.
This table describes **the library's model, and what Aidos would notice breaking** — it is not
Aidos's design and Aidos does not enforce it.

| Class | Key | Cacheable | Examples |
|---|---|---|---|
| **Blob-addressed** | blob SHA | **forever** | embeddings, text extraction, per-file facts |
| **Tree-addressed** | tree SHA | **forever** | project structure at a snapshot |
| **Commit-addressed** | commit SHA | **forever** | diffs, commit messages, authorship |
| **State-addressed** | working-tree fingerprint | **no — invalidate** | uncommitted edits, build metadata, test results |

The first three are immutable by construction and never require invalidation. Only the
state-addressed class does — and in the MVP, Aidos does not use it at all.

### The MVP indexes committed content only

Uncommitted work is not indexed (D29). Hash-on-save is elegant and re-embeds a file on every
keystroke, on a phone, for content that is superseded within minutes.

Uncommitted work reaches the model through the filesystem tool — which is how it reaches the
model anyway when the model is the one editing. Debounced hash-on-idle is the upgrade, not the
starting point.

The honest consequence: **immediately after the user edits a file, the index does not know about
that edit.** A query answers from committed content. This is a real limitation and it is stated
in the coverage report below rather than left for the user to infer.

### Every query reports coverage

**A query reports how much of the repository it actually searched.** Two counters: blobs indexed
over blobs known.

Without this, first open of a large repository answers *"there is no retry logic in this
codebase"* when the truth is *"I have not read most of it yet"* — and the answer is
indistinguishable from a confident, complete one. It also makes G3's measurement meaningless,
because an answer at minute two and an answer at minute forty look identical.

**The library provides it, on the `SearchResult`.** Aidos's adapter consumes coverage; it does
not compose it.

An earlier version of this RFC said the opposite — that the adapter would compose coverage by
calling `status()` alongside `search()`, which was the division the library's maintainer confirmed
at the time. It changed, and the shape it changed to is better than the one this RFC proposed.
The objection recorded here was that coverage is index-level state which does not vary per query,
so putting it on every `Match` would mean a metadata round trip per search for a number identical
across all results. That objection was right and is answered by putting it one level up: on the
**result**, once per search, rather than on each match or in a second call the adapter has to
remember to make. A promise the library keeps is worth more than the same promise kept by every
consumer separately.

`degraded` is a *different* claim and both are needed: it means "this specific model has no
vectors yet, so this is FTS-only", which coverage cannot express, while coverage means "12% of
this repository is indexed", which `degraded` cannot express. **Ten confident, non-degraded
matches from a 12%-indexed repository would otherwise look complete.** Both now arrive on the
same `SearchResult`, which is the right place for two facts a caller must weigh together.

Both reach the model as part of the context item's provenance (RFC-0025) and the user through
`IndexStatus` on the Runtime API (RFC-0052).

### What the index does and does not protect

**It does not redact secrets.** An earlier version of this RFC promised that secrets in code
would be detected and excluded. That promise is withdrawn (D29). Nothing funds a scanner, any
scanner would have false negatives, and stating the guarantee invites reliance on it.

The real properties, which are worth more because they are true:

- The index is **app-private storage**, under the same protection as `state.db`.
- The index **never egresses**. It is not synced (D16), not exported by default (RFC-0041), and
  not readable by an MCP server or any other tool subject.
- The index **holds nothing the repository does not already hold**. It is derived, and deleting
  it loses nothing but the time to rebuild.

**A secret committed to the repository is a secret in the repository.** The index does not make
that worse and does not pretend to make it better. The place to solve that is a pre-commit
control, not a retrieval index.

One thing the index *does* protect against is worth naming: because embedding runs against a
local model (D28), indexing a private repository does not transmit its contents anywhere. That is
a property of Aidos's provider choice — guarantee 3 above — not of the library.

### Availability and degradation

The knowledge engine is a **degradable** capability (RFC-0049). It is never a hard dependency of
the core loop:

| Condition | Behaviour |
|---|---|
| No index yet | Search returns FTS-only, marked `degraded`; coverage reports 0 embedded |
| Indexing in progress | Search answers from what exists; coverage reports the fraction |
| No embedding model available | FTS-only, permanently, reported through `AvailabilityReport` |
| Index corrupt or deleted | Rebuild from Git; nothing is lost |

A session whose knowledge query returns nothing is not a failed session. It reads files with the
filesystem tool, which is what it would do anyway.

### Structural graph: not on the device

The library's structural graph — call edges, import edges, symbol-level identity — requires
tree-sitter, a native dependency **D27 declines** for this use: a viable non-native alternative
arguably exists, and the blast radius of a crash is an incomplete graph rather than a lost Run,
so the presumption is against it.

Aidos therefore ships **no structural graph in the MVP**, and no on-device parsing. Co-change
analysis ("what changes together"), which needs no parsing at all because it derives from commit
provenance, is the part of that capability that remains available.

The port specification describes a desktop-built, phone-imported graph bundle as the path to
structural answers without on-device parsing. Aidos does not depend on it, and if it ever
arrives, graph queries must report *unavailable* rather than returning a silently empty result.

## Data Model

Aidos stores nothing for the knowledge engine. There is no `IndexEntry` table, no provider
registry, no cached-fact store. The index is the library's, at `.aidos/index/`, in a schema Aidos
does not define and does not migrate.

The only Aidos-side type is the adapter's output, `ContextItem` (RFC-0025,
`runtime/kernel/Knowledge.kt`), which carries the trust level that feeds the Run's taint
computation (RFC-0027). **Indexed project content is `PROJECT` trust, not `TRUSTED`** — it is
repository content, and a query result is repository content that has been ranked.

## Lifecycle

**First open of a project.** Indexing does not start automatically on a metered or battery-
constrained device. It is offered, then runs as a scheduled job (RFC-0044) under the resource
budget (RFC-0045). Search works immediately, degraded, from the first FTS rows written.

**Steady state.** Indexing runs after commits, incrementally — a set difference against what is
already indexed, usually a handful of blobs.

**Interruption.** Process death mid-index is routine on Android (D3), not exceptional. The resume
cursor is written only after a full pass completes, so an interrupted run leaves the previous
cursor in place and the next run re-walks conservatively without re-embedding anything.

**Model change.** Embeddings are keyed by `(blob hash, model)`, so a new model is additive rather
than destructive — the old vectors remain valid for the old model. Aidos pins the embedding model
(RFC-0022); changing it is a deliberate re-index, not a silent one.

**Deletion.** The index is disposable. `rm -rf .aidos/index/` loses only time.

## Security Considerations

Covered above under "What the index does and does not protect". In summary: app-private, never
egressed, holds nothing the repository does not, no secret redaction promised, and — because
embedding is local — indexing does not transmit a private repository anywhere.

Query results are `PROJECT` trust and participate in taint (RFC-0027) like any other project
content. A query result is not a special category of evidence.

## MVP Scope

The MVP implements:

1. The adapter from `KnowledgeContextProvider` (RFC-0025) onto `SemanticIndex`.
2. Storage at `.aidos/index/`, injected, outside `state.db`.
3. Indexing as a cancellable background job under the resource budget.
4. A local embedding provider (D28); no network path.
5. Coverage surfaced to both the model and the UI, read from `SearchResult` and `IndexStatus`.
6. FTS-first degraded search from the first rows written.

The MVP does not implement:

- Uncommitted-content indexing.
- Any structural graph, on-device parsing, or symbol-level identity.
- Multi-repo or cross-project search (D16 — the index is not synced).
- Ranking-weight tuning. The library's defaults ship as defaults; validating them needs a
  retrieval-evaluation harness and real device measurement, which is a G3 activity.

## Known dependency risks

Recorded because they are real and dated, not to be discovered at M22. Refreshed 2026-08-05
against `gitsema-kotlin` `main`; two earlier risks are retired and the one that matters moved
rather than vanished.

- **It compiles for Android and has never run on one.** `androidTarget()` is wired and building
  (`compileSdk` 34, `minSdk` 26, a `release` publication), with 50 Android unit tests alongside
  117 on JVM — but those are JVM-hosted unit tests of pure-Kotlin suites. **There are no
  instrumented tests**, and the library's own README is explicit that the gap between "compiles"
  and "works" is where its Android risk lives. Named hazards, all established statically:
  - JGit's `WindowCache` registers a JMX MBean on first packfile read through classes Android
    does not have, and catches only checked exceptions — not the resulting `NoClassDefFoundError`.
    A guard exists and **must be called before any repository is constructed**; that it is
    necessary is established, that it is sufficient is not.
  - `FS_POSIX` probes for a system `git` executable, which Android has none of, and
    `FileStoreAttributes` measures filesystem timestamp resolution. Both are known hazards and
    neither is addressed.
  - The SQLite driver passes an absolute path as the database *name*, relying on
    `Context.getDatabasePath` resolving it to that exact file — AOSP's documented behaviour,
    asserted rather than observed.
- **Nothing about resource behaviour is measured.** The memory-mapped vector store's page-cache
  behaviour under Android memory pressure, indexing throughput inside Android's execution
  windows, and whether the brute-force scan is fast enough before any ANN work — all deferred to
  a real device. **This is what G3 measures**, and it is the reason G3 is scheduled before the UI.
- **Not yet run against a real repository at scale.** Still true, but now testable without Aidos:
  the library ships a desktop CLI (`gitsema-cli`) that drives the same core. Running it against a
  large repository is the cheapest available de-risking of M22 and can happen at any time.
- **FTS5 uses a Porter/ASCII tokenizer**, which fits English source and comments better than it
  fits anything else. Flagged in the port specification as a matter for Aidos's adapter to
  consider; not addressed in the MVP.

**Retired 2026-08-05.** `androidTarget()` was "blocked on environment access"; it is wired. The
library "has no CI"; it now builds and tests both targets on every push to `main` and every pull
request. Pin a commit rather than a branch regardless — the version Aidos builds against should
be a decision, not whatever `main` happens to be.

## Future Work

**Uncommitted content**, via debounced hash-on-idle rather than hash-on-save.

**Structural answers** from a desktop-built, phone-imported graph bundle — available only for
repositories someone has pre-built, and reporting *unavailable* otherwise.

**Retrieval evaluation** (precision@k, recall@k, MRR) against a real corpus, which is what would
turn the ranking weights from inherited constants into a decision.

**Capability gaps are upstream work.** Aidos's contribution here is the adapter and the resource
discipline. A knowledge feature the library does not have is a `gitsema-kotlin` change, not an
Aidos one — which is the point of consuming a library rather than building an engine (D29).
