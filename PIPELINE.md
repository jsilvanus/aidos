# PIPELINE — Aidos MVP

The live tracking document for MVP implementation. An agent working through
[`docs/mvp-roadmap.md`](docs/mvp-roadmap.md) reads this first and updates it in the same commit
as the work. Everything not written down here is lost between links.

---

## Goal

Build the Aidos MVP: a person opens a real Git repository on a mid-range Android phone, in
airplane mode, asks a question about the code, gets a useful answer, makes an edit, reviews the
diff, and commits. That is RFC-0099 Phases 0–4, ending at gate G4. The work is
RFC-driven — `docs/rfcs/` is the design, `schema/` is the canonical DDL, `runtime/kernel/` is
the contract surface, and `docs/decisions.md` says why the architecture is this and not
something else. Implementation follows the RFCs; where implementation reveals a design problem,
the RFC is amended in a separate commit *before* the code that departs from it.

## Status

Link 3 · 2026-08-03 · Phase 0 complete, Phase 1 not started. Legacy RFC audit in progress.
Branch: `claude/aidos-architecture-review-miqn7p`

## Done

- [x] **M0.1** `schema/` — 54 tables in three files, `check.py` green in CI
- [x] **M0.2** `runtime/kernel/` — KMP common interfaces, compiling under `allWarningsAsErrors`, contract tests green
- [x] **M0.3** `docs/decisions.md` — 26 settled decisions, none open
- [x] **M0.4** Acceptance pass — corrected: 29 Accepted, 18 reverted to Draft pending a body audit
- [x] **G0 met**
- [x] **RFC-0016 revised and Accepted** — 657 lines → ~230. Cut normalization, categories, priorities, conflict resolution, provider SPI. Added instruction-set identity by blob hash and **adoption** (unseen instruction files do not reach the system turn)
- [x] **D25 settled** — diff review moves earlier, hunk card stack, structured hunks in the API
- [x] **D26 settled + RFC-0057 written** — glanceable and hands-free operation. The Run Summary is a *projection* of the Execution Graph, not a model call; glance and voice may approve only the benign class

## Next

**Legacy RFC audit — one document at a time, in this order.** Each is read end to end, checked
against `docs/decisions.md`, `schema/`, and `runtime/kernel/`, fixed, and re-accepted.

- [x] **RFC-0050 Android** — rewritten. Package `fi.italeino.aidos`; in-process runtime (D5); background execution split correctly between FGS and `WorkManager` (D24); app-private storage per D2/RFC-0054; inbox-first UI; commit review no longer optional; an editor, which it previously lacked entirely
- [ ] **RFC-0040 Storage** — next. Places project state at `~/.aidos/projects/<id>/storage.db`, contradicting D2 and `schema/`. M1 depends on it
- [ ] **RFC-0020 AI Engine / RFC-0022 Local Models** — neither mentions D24
- [ ] **RFC-0002, 0004, 0005, 0010, 0011, 0017, 0021, 0023, 0024, 0030, 0032, 0034, 0099** — audit
- [ ] **RFC-0000, 0001** — vision and principles; likely fine, verify and re-accept

Then:

- [ ] **RFC-0052** — add the structured-hunk diff shape now that D25 is settled (M9 consumes it)
- [ ] **RFC-0031 revision** — narrow to stdio/desktop-only per D17; specify MCP trust policy (an MCP server is an untrusted subject *and* a capability requester; that interaction is unspecified)
- [ ] **RFC-0015 revision** — the real design work: ranking, chunking, the query interface, staleness. Largest genuine design risk left in the MVP
- [ ] **M1** Storage and migrations — pick the SQLite binding for KMP, build the migration runner over `schema/`
- [ ] **M2** Identity and scopes
- [ ] **M3** Capability manager, with the path-escape property test

Full breakdown with done-when conditions: [`docs/mvp-roadmap.md`](docs/mvp-roadmap.md).

## Notes for the next link

**Phase 0 artifacts are real, not aspirational.** `schema/check.py` and the `runtime/` build
both run in CI (`.github/workflows/schema.yml`, `.github/workflows/runtime.yml`). If either goes
red, fix it before doing anything else — they are the only two things currently preventing the
corpus from drifting from the code, and that drift is what the third architecture review found
had already happened once.

**`schema/` governs.** Where an RFC's DDL and `schema/` disagree, the schema is right and the
RFC is the bug. Change both in the same commit. `check.py` asserts that every table named in RFC
DDL exists in the schema, so a new RFC table is a CI failure until it is in `schema/`.

**Accepted is not frozen**, and Accepted is a claim someone checked. The first acceptance pass
marked 45 RFCs Accepted on the strength of their headers; sampling four found body-level
contradictions in three, so 18 went back to Draft with `— body not audited`. Do not implement
against those without checking the decision they touch. Re-accept one only after reading it end
to end. A status line nobody verified is exactly how RFC-0102's "addressed" table came to be
wrong about four items — the same failure, one level up.

**Two RFCs on the MVP path are Draft on purpose** (0015, 0031) — genuinely unsettled, as opposed
to merely unaudited. That distinction is worth preserving in the status lines.

**The kernel has no implementations and that is deliberate.** `runtime/kernel/` is contracts
only. When Phase 1 starts, implementations go in a sibling module, not into `:kernel`. Keeping
the contract module implementation-free is what lets the frontend streams start against
`MockRuntimeClient` at G0.

**Blob-hash identity keeps paying.** It was introduced in RFC-0015 for the knowledge index (so
branch switching invalidates nothing) and turned out to solve two more problems for free: an
instruction set's identity is the hash of its `(filename, blob hash)` pairs, which makes change
detection exact with no watcher and gives a Run a precise answer to "what steered you" — and it
is the same identity a diff hunk needs (D25). If a subsystem needs to know whether project
content changed, reach for the blob hash before writing a cache invalidation scheme.

**RFC-0016's revision found a security hole, not just bloat.** Instruction files were going into
the system turn as trusted text. A cloned repository's `AGENTS.md` is attacker-controlled prose
aimed at the highest-authority position in the prompt, and the injection defences in RFC-0025
were guarding a different door. The fix is *adoption* — a set does not steer a model until a
human has seen it, tracked by hash. Worth remembering when revising 0031 and 0015: both also
carry content from outside the user's authorship into the model's context.

**The graph is why the glance surface is cheap.** RFC-0057's Run Summary is a SQL projection
over `runs`/`tasks`/`attempts` — instant, offline, no inference, checkable against the audit
trail. Asking a model to summarize its own Run would be D6, would cost ten seconds at a
two-second interaction, and would park with no foreground service (D24) in exactly the eyes-free
case that motivated it. When a new surface needs "what happened", reach for a query first.

**A mapping test is owed.** Once persistence lands at M1, add a test asserting every non-derived
kernel field has a schema column. It was noted when the kernel was written and not built,
because there was nothing to map to yet. It is the third leg of the CI that keeps design and
code together.

**Do not start Phase 2 with M8 amber.** The crash-recovery suite is the one metric with no
acceptable degradation. If a Run does not resume correctly from *every* `kill -9` checkpoint,
every AI-layer bug found afterwards will be misattributed to the model.

**Commit standards** are in `CLAUDE.md`: reference the RFC, explain the *why*, one logical change
per commit, tests pass before committing.

---

## Working across sessions

This plan is long. Use the `session-pipeline` skill (`.claude/skills/session-pipeline/`) to work
it across session limits: on wake, schedule the next wakeup **first**, then re-orient, then work
one coherent piece, then commit, push, and update this document.

Wakeup message to carry forward verbatim:

```
SESSION PIPELINE — link N.

FIRST ACTION: schedule the next wakeup.
  send_later(delay_minutes = 305, message = <this message, with N incremented>)
Do this before reading files, before git status, before anything.

Repo:    /home/user/aidos  (github.com/jsilvanus/aidos)
Branch:  claude/aidos-architecture-review-miqn7p
Plan:    PIPELINE.md  (breakdown in docs/mvp-roadmap.md)

Goal: Build the Aidos MVP — offline Git work on an Android phone. RFC-0099 Phases 0-4,
gate G4. Work is RFC-driven: docs/rfcs/ is the design, schema/ is canonical DDL,
runtime/kernel/ is the contract surface, docs/decisions.md says why. Implementation
follows the RFCs; if implementation reveals a design problem, amend the RFC in a
separate commit first.

Then: re-orient (git status, git log, read PIPELINE.md), take the item under "Next",
make real progress on it, commit, push, and update PIPELINE.md — including "Notes for
the next link" — in the same commit. End the turn.

Stop the chain — schedule nothing further — if the goal is met, the user says stop, or
you are blocked on something only the user can resolve. Say which, explicitly, in both
the final message and PIPELINE.md.
```
