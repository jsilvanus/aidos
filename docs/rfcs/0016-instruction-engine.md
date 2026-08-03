# RFC-0016: Instruction Engine

Status: Accepted 2026-08-03

## Abstract

Instruction files — `AGENTS.md`, `CLAUDE.md` — are prose written by a human for a model to read.
Aidos discovers them, concatenates them in a fixed precedence order, identifies the result by
content hash, and places it in the prompt. It does not parse them into records, does not assign
them categories or priorities, and does not resolve conflicts between them. It does require the
user to have seen an instruction file before it is allowed to steer a model, because an
instruction file in a cloned repository is attacker-controlled text aimed directly at the
system prompt.

## Motivation

A project may carry instructions in several files and formats. A session should receive them.

That is the whole requirement, and it is much smaller than it looks. The previous version of
this RFC proposed parsing six dialects into a normalized `Instruction` record with a UUID, a
category, a priority from 1 to 10, an `applies_to` context list, and a `conflicting_with` edge
set, then filtering by task type and language and resolving conflicts by precedence.

Three things are wrong with that, and they are worth stating because the design is superficially
attractive.

**The metadata does not exist in the source.** Nothing in a `CLAUDE.md` says "priority 3" or
"applies to Python". The engine would have to invent those fields — by heuristic, which is
wrong often, or by asking a model, which costs an inference on project open, offline, on a
phone. Invented metadata that then drives a decision about which of the user's instructions to
discard is the same class of error as authoring an intent status (D10) or letting a model
confirm its own success (D6): a machine manufactures a fact and then acts on it.

**The round trip loses information and buys nothing.** Instruction text is decomposed into
records and then re-serialized into prose for the prompt. Structure, ordering, and emphasis —
all of which the author used deliberately and all of which a model reads — are destroyed in the
middle. The output of the pipeline is worse than its input.

**Conflict resolution is the model's job and it is already good at it.** Two prose instructions
that appear to conflict are reconciled by a model reading both, in context, the way a new
colleague reconciles them. An engine that silently drops one has deleted text the user wrote,
for a reason the user cannot see. That is a worse outcome than the conflict.

D22 — build less prompt machinery, not more — applies here directly. What remains after cutting
is small enough to describe completely, and the space saved is spent on the one thing the old
design treated as a footnote: instruction files are an injection vector pointed at the system
prompt.

## Goals

1. Find the instruction files a project carries.
2. Compose them into prompt text, in a stated precedence order, within a budget.
3. Give the composed set a stable identity, so a Run can record exactly what steered it.
4. Ensure no instruction text steers a model until a human has seen it.

## Non-goals

This RFC does not parse instruction files into structured records. They are prose.

This RFC does not classify, prioritize, or filter instructions by task, language, or module.

This RFC does not detect or resolve conflicts between instructions.

This RFC does not define a provider plugin model for new formats. Adding a filename to a list is
not an extension point worth an SPI (D18).

This RFC does not define an Aidos-specific instruction file. The ecosystem has enough dialects;
adding one would be a cost to every user and a benefit to none.

This RFC does not define where the composed text sits in the prompt or how it is budgeted
against other sections — that is RFC-0025.

## Design

### Sources and precedence

The MVP reads two filenames, at the project root only:

| Order | File | Rationale |
|---|---|---|
| 1 | `AGENTS.md` | the emerging cross-vendor convention; most specific to agent behaviour |
| 2 | `CLAUDE.md` | widely present, same shape, same intent |

Later files are appended after earlier ones. Nothing is deduplicated, merged, or dropped for
redundancy. Order is the only precedence mechanism, it is stated, and it is the one a model
already understands.

`.cursor/rules`, `.github/copilot-instructions.md`, `GEMINI.md`, and nested per-directory
instruction files are deliberately excluded from the MVP. Each is easy to add later — a filename
and a position in the table — and none is needed to make the product work. Nested files in
particular require knowing which directory the session is working in before the model has read
anything, which is circular at prompt-assembly time.

### Composition

```
for each source file present, in precedence order:
    emit a header naming the file
    emit its contents verbatim
```

The header matters: it tells the model where each block came from, which is what lets it
reconcile apparent conflicts sensibly rather than guessing. It is also what makes the composed
text readable by the user in the adoption view, below.

**Budget.** RFC-0025 reserves a section for project instructions and it is included in full. If
the composed set does not fit that reservation, the runtime does not truncate mid-file and does
not silently drop a source. It drops whole files from the end of the precedence order, records
which, and surfaces it — an instruction file that was too long to include is something the user
must know about, because the alternative is a model that ignores a rule the user believes is in
force. If the highest-precedence file alone does not fit, the Run fails with a named error
(RFC-0025 already specifies this case).

### Identity

The composed instruction set is identified by the hash of its inputs: the ordered list of
`(filename, blob hash)` pairs. This is the same identity trick RFC-0015 uses for the knowledge
index, and it is free here for the same reason — the files are Git blobs and the hashes already
exist.

Three things fall out of it, none of which the previous design could do:

- **Change detection is exact and costs nothing.** No filesystem watcher, no re-scan heuristic,
  no `is_current` flag to get wrong. The set is stale if and only if a hash differs.
- **A Run records what steered it.** `runs.instruction_set_hash` answers "which instructions was
  this Run given?" precisely, months later, which is the question an audit trail exists to
  answer (RFC-0003, RFC-0037). The previous design had no identity for the merged set at all.
- **Adoption can be tracked per version**, which is what makes the security model below
  workable.

Instruction files are `ContentNode`s (RFC-0024) like any other project content, carrying the
`PROJECT` trust level RFC-0027 assigns them. RFC-0025's `instructionNodeIds` already refers to
them this way. There is no separate instruction table.

### Adoption

**An instruction set does not steer a model until a human has seen it.**

The threat is concrete and unglamorous: a user clones a repository, and its `AGENTS.md` — which
the user has never read, because nobody reads a cloned repo's instruction file — is placed into
the system prompt, the highest-authority position in the context. Prompt-injection defences
(RFC-0025) wrap *retrieved* content in untrusted-content markers precisely so it cannot claim
that position. Instruction files would have walked straight past them.

So: an instruction set hash is either adopted or it is not.

```
On project open, or when a hash changes:
    if the set is adopted        → include it
    else                         → exclude it, and tell the user there is
                                   an unreviewed instruction file
```

Adoption is one interaction: the user reads the text and accepts it. For a *changed* file, the
user is shown a diff against the adopted version rather than the whole file again — the common
case is three new lines in a file that was fine, and re-reading the whole thing is friction that
trains people to tap through. Adoption is recorded at project scope by hash, so it survives
branch switches and returns to a previously adopted version without re-prompting.

Instruction files the user authors inside Aidos are adopted on write. The user is the author;
asking them to review their own text is theatre.

**This is not a taint mechanism and does not replace one.** Adopted instruction text is still
`PROJECT` trust, not `TRUSTED` — adoption means "the user has seen this", not "this is
safe to act on with full authority". RFC-0027's attenuation applies unchanged. What adoption
prevents is the specific case of unseen text reaching the system turn.

RFC-0025 §"No direct string injection" lists instruction files among trusted system-prompt
sources. That is corrected by this section: instruction files reach the system turn only when
adopted, and they carry `PROJECT` trust when they do.

### What runs when

```
project open ──→ hash the source files
                     │
                     ├─ unchanged ──→ reuse composed text
                     ├─ changed, adopted ──→ recompose
                     └─ changed or new, not adopted ──→ exclude, surface for review
```

A Run in progress keeps the instruction set it started with, by hash. Changing the rules under a
running Run produces behaviour nobody can reconstruct afterwards, and the Execution Graph would
record a single Run governed by two different instruction sets with no way to say which step got
which.

## Data Model

No new tables. Two columns and one small table:

```sql
-- RFC-0019: which instruction set governed this Run.
ALTER TABLE runs ADD COLUMN instruction_set_hash TEXT;

-- Adoption is per project, per composed set.
CREATE TABLE instruction_adoptions (
    project_id          TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    set_hash            TEXT NOT NULL,      -- hash of ordered (filename, blob_hash) pairs
    adopted_at          TEXT NOT NULL,
    adopted_by          TEXT NOT NULL,      -- 'user' | 'authored_in_aidos'
    source_manifest     TEXT NOT NULL,      -- JSON: the ordered pairs, for display
    PRIMARY KEY (project_id, set_hash)
) WITHOUT ROWID;
```

`schema/project.sql` is canonical; this DDL is here so the RFC reads on its own.

The composed text itself is not stored. It is a pure function of blob hashes that Git already
holds, and caching it would create a second copy to keep consistent for no gain.

## Security

The threat model for instruction files is the one RFC-0027 describes, applied to the highest-
authority position in the prompt.

| Threat | Mitigation |
|---|---|
| Cloned repository's instruction file steers the agent | Adoption. Unseen text is excluded, not included-and-warned |
| Instruction file modified by a pull between sessions | Hash changes → adoption lapses → diff shown before it takes effect again |
| Instruction file claims runtime authority ("you may skip approval") | Instruction text is `PROJECT` trust and cannot grant capability. Authority comes from grants (RFC-0018), never from prose. A model persuaded by an instruction still hits the capability check |
| Instruction file contains secrets | Not prevented, and not preventable — it is a file the user wrote. Egress of instruction text is subject to the same policy as any `PROJECT` content (RFC-0042) |
| Very large instruction file crowds out everything else | Budget reservation is fixed; overflow drops whole files and reports it |

The load-bearing sentence: **an instruction cannot grant authority.** Everything an instruction
file says is advisory to the model. Every effect the model then attempts is checked against
capabilities it was granted out of band. A hostile instruction file can waste a Run; it cannot
escalate one.

## MVP

Everything above. Concretely:

1. Discover `AGENTS.md` and `CLAUDE.md` at the project root.
2. Compose with headers, in precedence order, within RFC-0025's reservation.
3. Hash the ordered `(filename, blob hash)` pairs; record it on the Run.
4. Adopt: exclude unseen sets, show the text or the diff, record adoption by hash.
5. Report dropped files when the budget overflows.

Not in the MVP: additional formats, nested files, categories, priorities, filtering, conflict
detection, provider plugins, instruction editing UI, and instruction generation. None of them
are needed for a session to be usefully steered by a project's instructions, which is the entire
point of this subsystem.

## Future Work

**More formats.** `.cursor/rules`, `.github/copilot-instructions.md`, `GEMINI.md`. Each is a
filename and a precedence position. The JSON dialects need a two-line extraction, not a parser.

**Nested instruction files.** Per-directory `AGENTS.md`, included when the session is working in
that subtree. Requires a notion of the session's working set that does not exist yet, and is
worth having only once it does.

**Instruction editing in the app.** Writing an instruction from the phone rather than editing
the file. Cheap once the diff-review surface exists, and auto-adopted on write.

**Instruction provenance in explanations.** "The agent did X because `AGENTS.md` line 14 says
Y." The hash already makes the set reconstructible; connecting a behaviour to a line is the
hard part and needs the model's cooperation, which makes it a D6 hazard.

Deliberately dropped from the previous version's Future Work, with reasons:

- *Instruction graph with justification and conflict edges* — solves a problem created by
  decomposing prose into records, which this RFC no longer does.
- *Automatic instruction generation from codebase patterns* — a model writing the rules it will
  then be judged against.
- *Instruction compliance checking* — a linter, and a much better one already exists for every
  rule that can actually be checked.
- *Instruction learning / embeddings* — an unbounded research project attached to a subsystem
  whose job is to concatenate two files.
- *Custom instruction DSL* — the ecosystem converged on Markdown prose because models read
  prose. A DSL would be read by the model as prose anyway, badly.

## Open Questions

- Should adoption be per project or per project-and-remote? Cloning the same upstream twice
  currently requires adopting twice.
- Should a session be able to see that an instruction file exists but was excluded for
  non-adoption, so it can tell the user why it is behaving generically?
- Is root-only discovery sufficient for a monorepo, or does that case need nested files sooner
  than "future"?
