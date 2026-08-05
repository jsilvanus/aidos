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

Link 10 · 2026-08-04 · Phase 0 complete, Phase 1 not started. **Architecture work is done, and so
is the RFC backlog.** All three rewrites have landed — 0052, 0031, 0015 — and 0015's rewrite
closed the last item that was blocking a milestone. **54 Accepted, 7 Draft** — six more RFCs were
audited on top (0046, 0026, 0043, 0012, 0047, 0099), after checks showed Draft RFCs governing
frozen Phase-0 artifacts and five RFCs claiming MVP scope no milestone built (D34). **Every
remaining Draft is a subsystem the MVP does not build.**
`docs/decisions.md` has no open questions: D31 and D32 were found during this work and settled,
and D17 was amended. **Phase 1 (M1) is the next work, and it waits on the user's go-ahead.**
Branch: `claude/aidos-rfc-revisions-3ido05`

## Done

- [x] **M0.1** `schema/` — 54 tables in three files, `check.py` green in CI
- [x] **M0.2** `runtime/kernel/` — KMP common interfaces, compiling under `allWarningsAsErrors`, contract tests green
- [x] **M0.3** `docs/decisions.md` — 26 settled decisions, none open
- [x] **M0.4** Acceptance pass — corrected: 29 Accepted, 18 reverted to Draft pending a body audit
- [x] **G0 met**
- [x] **RFC-0016 revised and Accepted** — 657 lines → ~230. Cut normalization, categories, priorities, conflict resolution, provider SPI. Added instruction-set identity by blob hash and **adoption** (unseen instruction files do not reach the system turn)
- [x] **D25 settled** — diff review moves earlier, hunk card stack, structured hunks in the API
- [x] **D26 settled + RFC-0057 written** — glanceable and hands-free operation. The Run Summary is a *projection* of the Execution Graph, not a model call
- [x] **Branch switching specified** (RFC-0053) — enabled; uncommitted changes are discarded after a warning naming what is lost, with "commit first" as the primary action. No per-branch WIP. `EffectKind.Mutate` gains `reversible`, and D26's benign class now requires it
- [x] **Instruction adoption settled** — per project for the decision, per user for recognition (`known_instruction_sets`). No open questions remain anywhere in the corpus
- [x] **Ten of eleven open questions settled** — hunk revert is a user-subject edit through the broker; reviewed/unreviewed does not survive a rebase; sessions are told when instructions were excluded; root-only discovery reports what it is not reading; the glance shows three; session summaries compose from Run summaries; the gesture grammar is horizontal-peer / vertical-list / tap-deeper; voice approvals are audited by channel; tier 2 is not motion-gated; the editor stays project-scoped
- [x] **D26 amended + RFC-0057 extended** — the full eyes-free loop: spoken notification ducks the music, headset-button push-to-talk, a fixed question vocabulary answered by template, then a voice approval in three tiers. Home is inbox and projects as swiped panes
- [x] **Legacy RFC audit complete** — every document except RFC-0099 read end to end against `docs/decisions.md`, `schema/`, and `runtime/kernel/`. Six rewritten (0016, 0050, 0040, 0022, 0021, 0005), the rest patched or accepted as-is. **46 Accepted, 15 Draft.** The dominant finding was five documents still promising deterministic replay against D1; the second was RFC-0011 asserting sessions run sequentially, which contradicts D15 and would make worker fan-out impossible
- [x] **D29 settled** — the knowledge engine is a *consumed library*. `gitsema-kotlin` owns its schema; Aidos owns the location, the lifecycle, and the resource envelope. No provider SPI. MVP indexes committed content only. Queries report coverage. The secret-redaction promise is withdrawn
- [x] **D30 settled** — an MCP server's authority is fixed when it is enabled: it may never raise a capability request, the grant is by effect class at enable time, the `TRUSTED` promotion is removed, and nothing spawns on project open. MCP resources do not feed the knowledge engine; Aidos does not expose itself as an MCP server in v1
- [x] **D31 settled (user decision, 2026-08-04)** — an MCP server's *tool description* is third-party prose that lands in RFC-0025's **reserved** `toolDescriptors` section. Taint cannot govern it (descriptors enter at step 0, so every Run in an MCP project would begin tainted and approval never clears taint), so admission does: prose is **fenced** in the structural sandbox with attribution, and each operation is **adopted by hash** over `(name, description, inputSchema)` at enable time. Unadopted operations are absent from the catalog and never interrupt a Run. New table `mcp_operation_adoptions`
- [x] **D32 settled (user decision, 2026-08-04)** — **no model-written summary anywhere.** The `SUMMARY` memory kind is removed; conversation history that does not fit is dropped with an omission marker, never compacted by a model call. What crosses a Run boundary is cited (`FACT`/`DECISION`/`TASK_STATE`), projected (the Run Summary), or a marker (`runs.taint_level`, rendered `⚠`). This closes the taint-laundering channel *structurally* instead of with a `max()` at every write site
- [x] **D17 amended (user decision, 2026-08-04)** — streamable HTTP MCP is in the MVP **on every profile**, not stdio-on-desktop only. The old limit was a platform fact about Android over-applied to MCP as a whole, while the network is already a used path (M23 remote providers, Git fetch/push). RFC-0049 and RFC-0050 had already modelled HTTP MCP as available everywhere, so only the phasing documents moved
- [x] **RFC-0099 audited and Accepted** — the last document the legacy audit had excepted, and the only place all six gates are defined. Its Phase 0 closure paragraph reported "45 RFCs Accepted" — **a number that was already wrong when written** (eighteen were reverted the same day when sampling found body-level contradictions) — plus stale table and decision counts. Its eight Open Questions were business-strategy questions this RFC's own Non-goals exclude; six closed as out of scope, two answered. Its Risk section listed market adoption and funding with mitigations like "open-source (can't be killed)"; replaced with the four risks to the *plan's shape*, pointing at `mvp-roadmap.md` for the operational table. **RFC-0057 was missing from the RFC index entirely.** Phase blocks updated for D29, D34, D25, and M32c
- [x] **D34 settled (user decisions, 2026-08-04) — five RFCs claimed MVP scope no milestone built** — the RFCs' `## MVP` sections and the roadmap's milestone set were written independently and never reconciled. **0004** was bookkeeping (the event bus *is* built at M5/M9/M10, just uncited). **0036** was plumbing M14/M16/M18 all assume and none built → folded into M1 and M2. **0005** split on a real line: *waking from an event is part of the session model; waking on a clock is a feature* → event wake at M5, `causal_depth` ceiling + self-wake refusal at M6, timers post-MVP. **0012** → task list at new **M32c**, built last, but with derived status and the proposal gate, the two items that cannot be retrofitted. **0047** → types in at M2, templates out. 0012 and 0047 Accepted
- [x] **RFC-0043 audited and Accepted; 0012 and 0047 repaired** — 0043 permitted a plugin to raise a runtime capability request when interactive, which is what D30 forbids for MCP servers, and the argument is *stronger* for in-process WASM. Now an absolute prohibition, with the enable-time grant stated as effect classes to match D30. **RFC-0012 carried a second, conflicting data model** — an `IntentGraph { … version: Int  # Git commit count or sequential }` pseudo-structure whose version field is precisely the device-local sequence number **D16 forbids** for intent; deleted, and its seven Open Questions answered. **RFC-0047** now names the one column it owns (`projects.project_type`) and no longer reserves intent seeding, which would have made template instantiation depend on the subsystem the MVP defers furthest
- [x] **D33 settled (user decision, 2026-08-04) + RFC-0026 Accepted** — memory is **session-scoped; `FACT`/`DECISION` promote to project scope by the user, never by a session.** `TASK_STATE` is session-only always. The gate is not new machinery: intent proposals (D6) and instruction adoption (RFC-0016) already refuse this exact crossing the same way. Three CHECK constraints enforce it in the database rather than in a write path — no promotion without a user, no project-scoped `TASK_STATE`, and **no promotion of `UNTRUSTED` content**, because a promoted entry taints every future Run in the project and one hostile file read once would otherwise degrade every later session permanently. All four behaviours verified by insert tests. Memory gains milestones: **M16b** (write path + scope), **M14** (provider retention), **M30** (review surface, which is where promotion happens — so it stops being optional)
- [x] **RFC-0026 audit: DDL drift fixed across three copies** — `memory_entries` was written out in `schema/project.sql`, RFC-0011, *and* RFC-0026, and the two RFC copies had drifted from canonical (missing D32's `kind` CHECK and RFC-0046's `created_by_*`). RFC-0011 additionally sketched a **`session_memory_entries`** table that has never existed under that name, with columns that do not match the real one. **`schema/check.py` gained a check for exactly that format** — a fenced block whose first line is a bare table name — which is the same defect its `Table:` check catches in the other format. Verified both ways: green on the corrected corpus, fails when the defect is reinjected
- [x] **RFC-0046 audited and Accepted** — it was Draft while `runtime/kernel/Ids.kt`'s `ActorRef` and four columns of canonical DDL cited it, which no milestone had caught because no milestone *names* it. Four defects: `runs` had no `device_id` though the RFC reserves it; `memory_entries` had no attribution at all; `capabilities.issued_by TEXT` was the untyped identifier the RFC exists to eliminate; and the RFC put device identity in a JSON file while `schema/` has a `device_identity` table. Three fixed in `schema/`, the fourth in the RFC — the schema was right
- [x] **RFC-0015 rewritten and Accepted** (D29) — 777 lines → 362. The knowledge engine is a consumed library, and the consumption contract is now concrete because `gitsema-kotlin`'s constructor *is* the ownership split: git access, embedding provider, and all three stores are injected, so "Aidos owns the location, the lifecycle, and the resource envelope" holds by construction rather than by convention. Every query reports coverage, composed in Aidos's adapter. **The ~150 MB vector-materialisation constraint is retired** — the port scores through a memory-mapped int8 file with a bounded top-K heap, O(topK) not O(stored vectors)
- [x] **RFC-0052 carries the structured-hunk shape** (D25) — a `DiffQueries` domain returning `FileChange`/`FileDiff`/`DiffHunk` keyed by `HunkId(path, baseBlobHash, index)`, in the RFC and in `runtime/kernel/`. The same pass found `Preview.Diff(path, unified: String)` still holding a formatted string, which RFC-0050 says is the *same component* as a hunk card — so it now carries a `FileDiff` (RFC-0030 amended in the same commit)

## Next

**The three RFC rewrites are done.** What follows is Phase 1, which the user has asked not to
start without an explicit go-ahead.

- [x] **RFC-0052 — structured hunks.** Done. `DiffQueries` on `RuntimeClient`; `changes()` lists
      files with hunk counts and `hunks()` fetches one file, because a card stack shows one hunk
      at a time and a phone should not receive a large fetch's every line to display eleven of
      them. `unified()` is the fallback view. `stage()` is the expensive half and is the first
      thing to cut (D25). Mirrored into `runtime/kernel/Diff.kt`, and `Preview.Diff` converted to
      the same shape. **RFC-0052 stays Accepted; RFC-0030 amended alongside it.**

- [x] **RFC-0031 — apply D30.** Done and Accepted. The `TRUSTED` promotion is gone, replaced by a
      remembered per-`(server, project)` egress grant that is an ordinary capability row; the
      enable-time grant is a set of effect classes; a new "What a server may never do" section
      states the five prohibitions; lifecycle is lazy start and idle stop; the Abstract agrees
      with the MVP scope. Both Open Questions closed. `mcp_servers.trust` dropped from
      `schema/user.sql` in the same commit — it existed only to hold the removed promotion.
      **Then D17 amended** in a second commit at the user's direction: both transports ship, HTTP
      everywhere, with the egress/TLS/redirect/credential-path consequences written out.

- [x] **RFC-0015 — rewritten against D29 and the port spec, and Accepted.** 777 lines → 362.
      Written from `docs/design/kotlin-port.md` and from `gitsema-kotlin` PR #1, which implements
      it. Everything on the cut list is gone; the keep list survives, reframed as a description of
      the library's model rather than Aidos's design. The consumption contract is now concrete
      rather than aspirational, because the library's constructor *is* the D29 ownership split —
      everything Aidos must own is injected. Adds a **Known dependency risks** section: the
      library has no `androidTarget()` yet, has never run against a real repository at scale, and
      has no CI.

**Phase 1**, from [`docs/mvp-roadmap.md`](docs/mvp-roadmap.md) — **do not start without the
user's go-ahead**:

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
