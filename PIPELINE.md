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

Link 0 · 2026-08-03 · Phase 0 complete, Phase 1 not started.
Branch: `claude/aidos-architecture-review-miqn7p`

## Done

- [x] **M0.1** `schema/` — 53 tables in three files, `check.py` green in CI
- [x] **M0.2** `runtime/kernel/` — KMP common interfaces, compiling under `allWarningsAsErrors`, contract tests green
- [x] **M0.3** `docs/decisions.md` — 24 decisions, none open
- [x] **M0.4** Acceptance pass — 45 RFCs Accepted; the remaining Draft set is deliberate
- [x] **G0 met**

## Next

**The single most useful next thing: RFC-0016 revision.**

It is unblocked, it is small, it removes an M15 blocker, and it is the kind of work that gets
skipped until it is urgent. Cut the Instruction Engine down to what D22 says the MVP needs —
find `AGENTS.md`/`CLAUDE.md`, concatenate, cap the budget — move format plugins, conflict
resolution, and dialect normalization to Future Work, and mark it Accepted.

Then, in order:

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

**Accepted is not frozen.** 45 RFCs are Accepted, which means work may begin — not that the text
cannot change. The freeze list is RFC-0099's "What Should Stabilise First" and it is much
shorter. Amending an Accepted RFC is an ordinary diff; amending a frozen contract is a version
bump and a migration.

**Three RFCs on the MVP path are still Draft on purpose** (0015, 0016, 0031). Each must be
revised and Accepted before the milestone that consumes it. Do not implement against them as
written — 0016 in particular describes a system D22 explicitly decided not to build.

**The kernel has no implementations and that is deliberate.** `runtime/kernel/` is contracts
only. When Phase 1 starts, implementations go in a sibling module, not into `:kernel`. Keeping
the contract module implementation-free is what lets the frontend streams start against
`MockRuntimeClient` at G0.

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
