# PIPELINE — building the Aidos MVP

**Read this first.** It is the working document: what the MVP is, how to build it, the rules that
are not negotiable, and what to do next. An agent picks up here, works one coherent piece, and
updates this file in the same commit as the work. Everything not written down here is lost
between sessions.

---

## Goal

A person opens a real Git repository on a mid-range Android phone, in airplane mode, asks a
question about the code, gets a useful answer, makes an edit, reviews the diff, and commits.

That sentence is the whole product. It is RFC-0099 Phases 0–4, ending at gate **G4**. Every
milestone either serves it or is cuttable.

## Status

**2026-08-04 · Phase 0 complete. Phase 1 not started. The architecture phase is over.**

| | |
|---|---|
| RFCs | **54 Accepted, 7 Draft** — every remaining Draft is a subsystem the MVP does not build |
| Decisions | **34 settled, none open** (`docs/decisions.md`) |
| Schema | **56 tables**, `schema/check.py` green with 7 rules, running in CI |
| Kernel | `runtime/kernel/` compiles under `allWarningsAsErrors`, contract tests green |
| Milestones | **38** across Phases 1–4; every RFC they name is Accepted |

**Next work: M1 — storage and migrations.** Nothing blocks it.

---

## How to work

The loop, once per milestone:

1. **Read the milestone** in [`docs/mvp-roadmap.md`](docs/mvp-roadmap.md) — its RFCs and its
   **done-when**. The done-when is the definition of finished; it is written to be *observable*
   rather than asserted.
2. **Read the RFCs it names**, and any decision (`D<n>`) it cites in `docs/decisions.md`.
3. **Implement.** Minimal — what the RFC says, no more.
4. **Test against the done-when.** If the done-when cannot be observed by a test, the milestone
   is not finished.
5. **Verify** (both must pass, every time):
   ```bash
   python3 schema/check.py          # canonical DDL: executes, FKs resolve, RFC↔schema agree
   cd runtime && gradle build       # kernel + implementations, allWarningsAsErrors
   ```
6. **Commit** per `CLAUDE.md`: reference the RFC, explain the *why*, one logical change.
7. **Update this file in the same commit** — Status, Next, and Notes.
8. **Push** to the working branch. Do **not** open a pull request unless asked.

### Rules that are not negotiable

- **`docs/decisions.md` is settled.** If a decision looks wrong, say so in your final message.
  Do not quietly implement something else.
- **`schema/` is canonical DDL.** Where an RFC's DDL and the schema disagree, **the schema is
  right and the RFC is the bug**. Fix both in the same commit. `check.py` runs in CI and must
  stay green.
- **A banner marking a document known-wrong is deleted by the commit that makes it right.** A
  banner that outlives its fix is worse than no banner.
- **`runtime/kernel/` is contracts only.** No implementations. They go in a sibling module —
  that is what lets frontend work start against `MockRuntimeClient`.
- **Amend the RFC before departing from it**, in its own commit, not alongside the code.
- **G1 blocks all AI work.** Do not start Phase 2 with M8 amber. Crash recovery is the one
  metric with no acceptable degradation: 100% of `kill -9` points resume correctly, not "mostly".

### Where everything lives

| Document | What it is | Authority |
|---|---|---|
| **`PIPELINE.md`** | this file — status, next, accumulated lessons | the working state |
| [`docs/mvp-roadmap.md`](docs/mvp-roadmap.md) | 38 milestones with RFCs and done-when conditions | the work breakdown |
| [`docs/decisions.md`](docs/decisions.md) | 34 decisions — *why* the architecture is this | **settled**; cite `D<n>` |
| [`docs/rfcs/`](docs/rfcs/) | 61 RFCs — *what* the system does | Accepted ⇒ implement against it |
| [`schema/`](schema/) | canonical DDL, 3 files, `check.py` | **governs**; RFC DDL defers to it |
| [`runtime/kernel/`](runtime/kernel/) | KMP contract surface, no implementations | frozen at G0 |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | the map, one paragraph per subsystem | orientation only |

**Accepted is not frozen, and Accepted is a claim someone checked.** The first acceptance pass
marked 45 RFCs Accepted on the strength of their headers; sampling four found body-level
contradictions in three, and 18 were reverted the same day. Every current Accepted status was
reached by reading the document end to end. Keep it that way.

---

## The plan

| Phase | Goal | Gate | Milestones |
|---|---|---|---|
| **0 · Contracts** | freeze the seams | **G0** ✅ | M0.1–M0.4 — complete 2026-08-03 |
| **1 · Execution kernel** | durable execution with no AI and no tools | **G1** | M1–M8 |
| **2 · First vertical slice** | the agent loop and its authority boundary, CLI only | **G2** | M9–M19 |
| **3 · Offline proof** | prove the thesis on real hardware, before any UI | **G3** | M20–M26 |
| **4 · Android application** | the app | **G4** | M27–M35 |

Gates are defined in RFC-0099 and carried by a specific milestone: **G1 at M8**, **G2 at M19**,
**G3 at M26**, **G4 at M35**.

**Phase 3 sits before the UI deliberately.** A beautiful UI over a runtime that cannot work
offline is not this product, and G3 is scheduled early so a negative answer arrives while it is
still cheap to act on. A negative result at G3 is a *successful* outcome for that milestone.

**If it slips**, cut in this order and stop when the thesis sentence is still true: M33 voice →
M18 MCP (as a deferral, not a deletion; and cut stdio before HTTP) → M34 F-Droid → M25 retention.
**Never cut** M8 (crash recovery), M17 (injection suite), or M26 (the measurement).

### Known external dependency

**M22 is blocked on `gitsema-kotlin` gaining `androidTarget()`.** The knowledge engine is a
consumed library (D29); its Tier 1 core is merged and tested on JVM, but the Android target is
blocked on environment access rather than scheduled work. Pin a commit rather than tracking a
branch — the library has no CI. Full risk list in RFC-0015, "Known dependency risks".

---

## Next

- [ ] **M1 — Storage and migrations** · RFCs 0040, 0039, 0054, 0036
      **Done when:** a fresh install creates `~/.aidos/user.db`, `~/.aidos/secrets/vault.db`, and
      `<project>/.aidos/state.db` from `schema/`. The migration runner applies a version; a
      database written by a *newer* runtime opens read-only with `storage.migration_required`
      rather than refusing (RFC-0017). Declared settings carry type, default, range, and scope
      class; `aidos.toml` parses with per-line error reporting and fails closed on an invalid
      value. `check.py` still green.

      **Start here:** pick the SQLite binding for KMP. That choice constrains M2–M8 and is the
      first real implementation decision of the project.

Then M2 (identity and scopes), M3 (capability manager, with the path-escape property test), and
on through [`docs/mvp-roadmap.md`](docs/mvp-roadmap.md).

**A mapping test is owed at M1**: every non-derived kernel field should have a schema column,
asserted by a test. It was noted when the kernel was written and deferred because there was
nothing to map to yet. It is the third leg of the CI that keeps design and code together.

---

## What has been settled

Phase 0 produced three artifacts — `schema/`, `runtime/kernel/`, `docs/decisions.md` — and then
an architecture pass read the whole corpus against them. The durable output:

- **34 decisions**, D1–D34, none open. The load-bearing ones for implementation: **D3**
  (step-machine execution — anything that must survive a step boundary is a column), **D6** (the
  model may propose and report, never confirm its own success), **D7** (taint attenuates
  authority), **D14/D15** (concurrency is across Runs; the worktree is the lock), **D21**
  (embeddings outside `state.db`), **D24** (local inference requires a foreground service),
  **D25** (structured diff hunks), **D26** (glance and voice may approve only the benign class).
- **Six RFCs rewritten** — 0016, 0050, 0040, 0022, 0021, 0005 — and six audited and accepted in a
  second pass: 0052, 0031, 0015, 0046, 0026, 0043, 0012, 0047, 0099.
- **Four new decisions from that second pass**: D31 (MCP tool descriptions are fenced prose,
  adopted per operation), D32 (no model-written summary anywhere), D33 (memory is session-scoped;
  project scope is a user promotion), D34 (five RFCs claimed MVP scope no milestone built).
  D17 amended: HTTP MCP ships on every profile.
- **`schema/check.py` gained two rules** during that pass, both from defects it had missed.

---

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

**0015 and 0031 are Draft with their decisions already made.** Both now carry a banner at the top
naming exactly what in them is superseded and by which decision. That is a deliberate state, not
an oversight: the decisions are in `docs/decisions.md` (D29, D30) where they are durable, and the
documents will be rewritten to match. Read the banner before reading the body — parts of both
bodies are known-wrong, and RFC-0031's security section is wrong in three specific places while
reading as though it were settled. When the rewrite lands, delete the banner in the same commit
that changes the status line; a banner that outlives its own fix is the same class of defect the
audit spent a day removing.

**The architecture phase is over. Resist reopening it.** Sixty-one RFCs, thirty decisions, a
canonical schema, and a contract surface are enough to build against. The next design question
that arises during implementation should be answered by amending one RFC in one commit and then
continuing — not by a new document, and not by a review. If a question genuinely cannot be
settled that way, it belongs to the user, and the honest move is to stop and say so rather than
to design around it.

**The kernel has no implementations and that is deliberate.** `runtime/kernel/` is contracts
only. When Phase 1 starts, implementations go in a sibling module, not into `:kernel`. Keeping
the contract module implementation-free is what lets the frontend streams start against
`MockRuntimeClient` at G0.

**The last model call in a deterministic path is the one to look for.** D26 made the Run Summary a
projection, D25 deferred model-summarized diffs, D22 refused adaptive compression — and a model
summarizer still survived in RFC-0026's `SUMMARY` kind, where it was also the taint-laundering
channel RFC-0027 had to patch with a `max()`. Three decisions had each removed a generation step
without anyone checking whether the same step existed elsewhere. When a decision says "compute it,
do not generate it", grep the corpus for the *other* places the same generation happens.

**Taint and adoption are two controls, and which one applies is a question of *when*.** D31's
answer generalizes: **taint governs content that arrives during a Run; adoption governs content
that is there before it starts.** Anything sitting at high authority in every prompt cannot be
handled by taint at all — taint present from step 0 never clears, and permanently degrades every
Run. That is true of instruction files (RFC-0016) and of tool catalogs (D31). When new content
enters the prompt, ask which side of the Run boundary it arrives on before reaching for a control.

D31 was also found by a user question rather than by the revision that should have caught it —
PIPELINE's own note said to look for outside-authored content entering the model's context when
revising 0031, and the revision looked at *results* and missed *descriptors*. When asking "is this
content trusted", enumerate every field of the thing that reaches the prompt. A tool has a name, a
schema, and a paragraph of prose; only two of those had a rule.

**"Localhost" is not one threat model across profiles.** The `http://` loopback exemption written
for HTTP MCP was reasonable on desktop and a credential-disclosure path on Android, where any app
holding `INTERNET` can bind or connect to a loopback port and the socket carries no peer identity.
RFC-0055 had already solved the same problem for the desktop runtime socket — `0600` Unix socket
plus a token — which is the tell: when a rule leans on *local means safe*, check what "local"
authenticates on each profile before writing the exemption. On MOBILE it authenticates nothing.

**A scope limit written as a platform fact outlives the platform fact.** D17 said MCP was
"desktop only" in the MVP because Android cannot spawn a subprocess — true of *stdio*, and it
silently became the rule for MCP as a whole, including the transport that works fine on a phone.
It read as a security posture for months and was really an observation over-applied. The tell was
that RFC-0049 and RFC-0050 had already modelled HTTP MCP as available everywhere and nobody
noticed the corpus disagreeing with the decision. When a decision limits scope, check whether the
limit follows from the reason given or is broader than it.

**Five RFCs claimed MVP scope that no milestone built — now D34.** A mechanical sweep of every `## MVP`
section against every Phase 1-4 milestone row finds fifteen unmatched; ten are explicable (meta,
superseded, or post-MVP by design) and **five are not: RFC-0004 (event bus), RFC-0005 (scheduler),
RFC-0012 (intent graph), RFC-0036 (settings), RFC-0047 (templates)** — three of them Accepted. The
RFCs' MVP sections and the roadmap's milestone set were written independently and never
reconciled, so each is internally consistent and they disagree with each other. RFC-0026 was the
first instance found; it was a pattern, not a coincidence, and D34 reconciles all five. **Ask of
any RFC claiming MVP scope: which milestone builds this?** If the answer is none, one of the two
documents is wrong — and it is worth a CI check, which does not exist yet.

**"Which RFCs does the MVP depend on" is not the same question as "which RFCs does a milestone
name".** Every RFC named by an MVP milestone was Accepted — that check passes and is not the one
that mattered. The one that mattered was: which *Draft* documents are cited by `schema/` and
`runtime/kernel/`, the two artifacts Phase 0 froze. RFC-0046 was governing `ActorRef` and four DDL
columns while unaudited, and auditing it found three schema defects. Two Draft RFCs are still
cited by canonical DDL — 0026 (`memory_entries`) and 0047 (`projects.project_type`). **Grep the
frozen artifacts for Draft-RFC citations before trusting a milestone table's RFC column**: that
column lists what a milestone *builds*, not what constrains it.

**Removing a concept means removing its column.** D30 deleted the MCP `TRUSTED` promotion, and
`mcp_servers.trust TEXT NOT NULL DEFAULT 'UNVERIFIED'` was still sitting in `schema/user.sql`
holding the two values that no longer mean anything. A dead column in canonical DDL is worse
than a stale paragraph: the paragraph is prose someone may doubt, the column is a fact an
implementor will faithfully populate. When a decision deletes a concept, grep `schema/` for it
before calling the decision applied.

**A shape decision is only made once it is made everywhere it is read.** D25 settled structured
hunks in August and RFC-0032 already said so in prose — but `Preview.Diff` in the kernel still
carried `unified: String`, and RFC-0050 says the approval card and the hunk card are *the same
component*. One of the two paths would have had to parse text, on the client, which is the exact
outcome D25 exists to prevent. When applying a decision about a data shape, grep for every type
that carries that data before declaring it applied; the RFC that names the decision is rarely the
only place it lands.

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

**Tool descriptions are two halves, and the second one has no other home.** gitsema splits them:
`guideTools.ts` (1,814 lines) says how to *call* each capability, `interpretations.ts` (695) says
how to *read* the result — thresholds, caveats, what a citation should look like — and a
`docsSync` test stops the two drifting from the generated skill file. Aidos had only the first
half; `ToolDescriptor.resultGuidance` is the second. It is runtime-authored and TRUSTED, emitted
with the *result* rather than the definition, and a tool never supplies its own — an MCP server
writing its own interpretation guidance would be an UNTRUSTED subject telling the model how to
weigh its own evidence.

**The knowledge engine comes from outside, and two of its three constraints are gone.** The
survey that produced this note found three things constraining the Aidos side. Where they stand
after RFC-0015's rewrite against `gitsema-kotlin` PR #1:

- **Vector search materialising ~150 MB — retired.** The port scores off a memory-mapped
  int8-quantized flat file through a bounded top-K min-heap: O(topK), not O(stored vectors), with
  a test asserting an 8 MB heap-delta ceiling. This was the single biggest risk on the Aidos side
  and it is simply no longer there.
- **The structural graph needing tree-sitter — still true, and declined.** D27's presumption
  holds; no on-device parsing ships. Co-change analysis, which needs no parsing, remains.
- **The analysis capabilities being the bulk of the product — still true.** "Knowledge engine"
  still does not mean "search". Aidos consumes the retrieval core (Tier 1); everything above it
  is upstream work Aidos may or may not ever ask for.

The general lesson worth keeping: a constraint recorded from a survey has a shelf life. Re-check
it against the dependency's current state before designing around it — this one had been quietly
false for a while.

**Reviewing a dependency's PR paid for itself.** RFC-0015's four open questions went to
`gitsema-kotlin` PR #1 as a review comment rather than being guessed at. Two were real defects and
were fixed upstream within the hour (`Indexer` had no cooperative cancellation, so a mid-index
cancel was dispatcher-dependent; `status().lastIndexedCommit` read an in-process var that
under-reports after Android process death — D3's own hazard, in a dependency). Two were design
questions answered definitively, so the RFC states facts instead of assumptions. **Read the
dependency, not just its README** — both defects were visible only in the source.

**`reversible` is not `RecoveryClass`, and the difference is load-bearing.** `RecoveryClass` asks
whether an effect can be re-executed after a crash; `reversible` asks whether the user can get
their work back. Branch switching found the conflation: discarding uncommitted changes is
in-project, untainted, and perfectly re-runnable, so it satisfied every clause of D26's benign
class and would have been approvable by one spoken word while cycling. `approvalTier()` in the
kernel now encodes the corrected rule, with tests. When adding an operation, answer both
questions separately.

**Approval keys on the subject, not the act.** A user editing a file and a user reverting a hunk
both go through the broker as ordinary mutations and both get audit rows — but neither asks for
approval, because the user is the authority an approval would consult. Only session-subject
mutations can need one. This came up twice in one day; it is the rule, not a special case.

**Verification, not modality, gates authority.** D26 first said voice could approve only the
benign class. The eyes-free loop overturned that: a user who asked what, where, why, and what-if-
I-refuse and heard structured answers has verified more than someone tapping a card they glanced
at. What survives as never-by-voice is the set that changes the *authority envelope* rather than
exercising it — egress, tainted Runs, new grants — because a structured readback cannot verify
those. Apply the same test to any future approval surface.

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

Phases 1–4 will not fit one session. Use the `session-pipeline` skill
(`.claude/skills/session-pipeline/`): on wake, **schedule the next wakeup first**, then
re-orient, then work one coherent piece, then commit, push, and update this document.

Wakeup message to carry forward verbatim, with `N` incremented each time:

```
SESSION PIPELINE — link N.

FIRST ACTION: schedule the next wakeup.
  send_later(delay_minutes = 305, message = <this message, with N incremented>)
Do this before reading files, before git status, before anything. If the tool is
unavailable, say so in your final message — the chain is broken and the user must restart it.

Repo:   /home/user/aidos  (github.com/jsilvanus/aidos)
Branch: claude/aidos-phase1-<suffix>, from main. Create it if it does not exist.
Plan:   PIPELINE.md — read it first. It has the goal, the working loop, the
        non-negotiable rules, and what is next. docs/mvp-roadmap.md has the milestones.

Then: re-orient (git status, git log -5, read PIPELINE.md), take the item under
"Next", make real progress on it, verify (python3 schema/check.py AND
cd runtime && gradle build), commit, push, and update PIPELINE.md — Status, Next, and
"Notes for the next link" — in the same commit. End the turn.

Do not open a pull request unless the user asks.

Stop the chain — schedule nothing further — if the milestone set is complete, the user
says stop, or you are blocked on something only the user can resolve. Say which,
explicitly, in both the final message and PIPELINE.md.
```

**What "real progress" means here.** One milestone is a good unit; half of one is acceptable if
it ends at a commit that builds and whose tests pass. What is not acceptable is ending a link
with uncommitted work, a red `check.py`, or a PIPELINE.md that does not describe the current
state — the next link starts from this file and nothing else.
