# Aidos MVP Roadmap

The ordered work breakdown for the MVP. [RFC-0099](rfcs/0099-roadmap.md) states the phases and
why they are in that order; this document is what gets built, in what sequence, and how each
piece is known to be finished. `PIPELINE.md` at the repository root tracks live progress against
it.

---

## What the MVP is

**A person opens a real Git repository on a mid-range Android phone, in airplane mode, asks a
question about the code, gets a useful answer, makes an edit, reviews the diff, and commits.**

That is the whole product thesis. Everything in this roadmap either serves that sentence or is
cut.

The MVP is **RFC-0099 Phases 0 through 4**, ending at gate **G4**. Phase 5 (desktop GUI) and
Phase 6 (paired execution) are post-MVP, even though Phase 6 is the most exciting thing in the
plan — it is only expressible because the MVP built platform profiles, the Runtime API, and
checkpointed execution, and shipping it first would mean shipping none of them properly.

### What the MVP is not

| Not in the MVP | Why |
|---|---|
| Desktop GUI | The CLI covers the desktop need during the MVP; the GUI is Phase 5 |
| Paired remote execution | Phase 6. Depends on the entire MVP being true first |
| Plugin host / WASM | D18. The extension boundary is not stable and will not be for a year |
| Intent Graph as a UI surface | It is a leaf in the dependency graph — build it late and small |
| Multi-device sync | D16. Never full sync; a Git-backed subset arrives at v1.x |
| Real-time collaboration | Single-user is a design assumption, not a limitation |
| Shell tool | DESKTOP-tier only (RFC-0033). The MOBILE profile must work without it |
| Vision, OCR, translation | The `ModelKind` enum reserves them. Nothing in the thesis needs them |
| A plugin marketplace | See above, twice over |
| Pre-built index bundles | The phone indexes for itself, or the gate is not testing the thesis. Post-MVP — see RFC-0099 Later |
| Structural knowledge graph | Needs tree-sitter, a native dependency D27 declines. Co-change analysis, which needs none, is in scope |

**Deliberately still in, despite looking cuttable:** MCP, both transports — stdio on desktop,
streamable HTTP on every profile (D17, amended). It is the first real test of whether the tool
abstraction can absorb tools the runtime did not write. Finding out at Phase 2 is cheap; finding
out after every built-in tool has been written against the assumption that the runtime authored
it is not. HTTP is in because the network is already a used path (M23 routes to remote providers;
Git fetch and push egress on every profile), and because deferring it would validate the tool
abstraction on exactly one transport — the one whose threat model is least like a plugin's.

---

## Acceptance state

As of 2026-08-04, 53 RFCs are **Accepted** and 8 are Draft — see [the status split](rfcs/README.md#status). The legacy body audit is complete, and the three revisions that were
blocking milestones (0016, 0031, 0015) have all landed. Accepted means implementation may begin
against them, not that they are frozen — see
[Accepted is not frozen](rfcs/README.md#accepted-is-not-frozen). The freeze list is RFC-0099's
"What Should Stabilise First", and it is unchanged.

### Still Draft, and off the MVP path

0033 Shell · 0041 Export/Import ·
0047 Project Templates · 0051 Desktop · 0060 Plugin SDK · 0100–0102 reviews.

**0046 Identity** was on this list and should not have been: no milestone names it, but the
kernel's `ActorRef` and four columns of canonical DDL cite it. Audited and accepted 2026-08-04.

Nothing in the MVP depends on these. They stay Draft because approving a design nobody is about
to build is how a corpus accumulates authority it has not earned.

### Was Draft and **on** the MVP path — all three now closed

These were needed and were not acceptable as written; each had to be revised and accepted
*before* the milestone that consumed it. **All three are done, so no milestone is blocked on an
RFC revision.** Kept here as a record of what the revisions found, which was more than bloat.

RFC-0016 was the third and was closed on 2026-08-03: cut from 657 lines to ~230 per D22, and the
revision turned up a security hole rather than just bloat — instruction files were reaching the
system turn as trusted text, which means a cloned repository's `AGENTS.md` had the highest-
authority position in the prompt. Worth expecting something similar in the other two: all three
carry text the user did not author into the model's context.

| RFC | Needed by | What is wrong with it |
|---|---|---|
| **0031 MCP** | *done* | Revised and Accepted 2026-08-04. D30 applied: authority fixed at enable time, no `TRUSTED` promotion, lazy start, and a "what a server may never do" section that settles the untrusted-subject-as-capability-requester interaction (it may not). D17 amended in the same pass: both transports ship, HTTP on every profile |
| **0015 Knowledge Engine** | *done* | Revised and Accepted 2026-08-04, 777 lines → 362. D29 applied: the engine is a *consumed library* (`gitsema-kotlin`), not an Aidos subsystem — no provider SPI, committed content only, every query reports coverage, secret redaction withdrawn. What was "the largest genuine design risk left in the MVP" is now a dependency risk instead, and a narrower one: see the RFC's "Known dependency risks" |

---

## Milestones

Each milestone names its RFCs, its deliverable, and its **done-when** — a condition that can be
observed, not asserted. "Done-when" conditions that a person has to take on faith are how the
last three architecture reviews found four items marked "addressed" that were not.

### Phase 0 — Contracts · **COMPLETE** 2026-08-03

| | Deliverable | Done-when |
|---|---|---|
| **M0.1** | `schema/` — canonical DDL | ✅ `check.py` green in CI: executes, FKs resolve, no table defined twice, every table named in RFC DDL exists |
| **M0.2** | `runtime/kernel/` — KMP interfaces, no implementations | ✅ compiles under `allWarningsAsErrors`; contract tests green |
| **M0.3** | `docs/decisions.md` | ✅ 26 settled decisions, none open |
| **M0.4** | Acceptance pass | ✅ 29 RFCs Accepted, 18 awaiting a body audit; remaining Draft set is deliberate and documented above |

**G0 met.** The parallel workstreams in RFC-0099 may now proceed against frozen contracts.

### Phase 1 — Execution kernel · no AI, no tools

The point of proving the kernel with no model in it: if it cannot survive eviction
deterministically, no amount of AI quality matters, and every later bug gets misattributed to
the model.

| | Deliverable | RFCs | Done-when |
|---|---|---|---|
| **M1** | Storage and migrations | 0040, 0039, 0054, 0036 | Declared settings carry type, default, range, and scope class; `aidos.toml` parses with per-line error reporting and fails closed on an invalid value. Fresh install creates `~/.aidos/user.db`, `~/.aidos/secrets/vault.db`, and `<project>/.aidos/state.db` from `schema/`. The migration runner applies a version; a database written by a *newer* runtime opens read-only with `storage.migration_required` rather than refusing (RFC-0017). `check.py` still green |
| **M2** | Identity and scopes | 0054, 0010, 0011, 0036, 0047 | Settings resolve nearest-first across user and project scope and report their origin; a project attempting a `SECURITY` or `SPEND` setting fails with a visible error and an audit row, so a repository cannot turn off its own egress controls. `projects.project_type` selects defaults — `personal` defaults `routing.remote_egress = never`, `coding` defaults `trust.untrusted_paths` — and types set defaults, never constraints. UUIDv7 IDs are monotonic within a process and unique across two concurrent runtimes. A project registered at user scope resolves from a path and from an ID. Opening a project whose directory has moved fails with `ProjectMoved`, not a null |
| **M3** | Capability manager | 0018, 0003 | Property test: no input to `RelPath.of` produces a path that escapes its root — including `..` in any segment, absolute forms, drive letters, NUL, and every encoding of those. Revocation by epoch invalidates outstanding handles within one step. `validate()` refuses when Run taint exceeds the grant's ceiling |
| **M4** | Audit log | 0003, 0037 | Every `validate` and every effect writes one row naming the subject, the capability actually exercised, and the outcome. An effect with no audit row is a test failure, enforced by the broker harness, not by review |
| **M5** | Execution graph tables and executor | 0019, 0009, 0006, 0004, 0005 | Events publish with a per-project `sequence` that is the ordering key rather than the timestamp, and carry their causality. A session wakes from a subscribed event — including a driver waking when its worker completes, which the driver/worker model requires (RFC-0011, D15). A Run of hard-coded Tasks executes to `COMPLETED`. `drive()` is re-entrant: calling it on a complete Run is a no-op. At most one *effectful* Task is `RUNNING` per Run (D14); `Read` tasks may overlap |
| **M6** | Recovery and runaway bounds | 0009, 0029, 0005, 0028 | **Wake amplification is bounded**: `events.causal_depth` has a ceiling, a session cannot wake itself, and both refusals are recorded rather than silent — the same class of guard as the step and budget ceilings below, and the one that stops an event loop feeding itself. `recover()` classifies every interrupted attempt by `RecoveryClass`. `UNSAFE` is never retried — it is reported `INDETERMINATE` with what is known. Reservations are released. Budget and step ceilings terminate a runaway Run at the ceiling, every time |
| **M7** | Project lock and runtime instances | 0055, 0007 | Two runtimes on one project: the second fails to acquire and says so. A stale lock from a killed process is reclaimed, not waited on forever |
| **M8** | Crash-recovery suite | 0038 | **G1.** A Run is killed with `kill -9` at *every* checkpoint boundary and resumes correctly at every one. 100%, not "mostly" — this is the one metric with no acceptable degradation |

> **G1 blocks all AI work.** Do not start Phase 2 with M8 amber.

### Phase 2 — First vertical slice

| | Deliverable | RFCs | Done-when |
|---|---|---|---|
| **M9** | Runtime API, in-process transport, `MockRuntimeClient` | 0052, 0048, 0004, D25 | Every `RuntimeClient` method is reachable in-process. The mock implements the same interface and is what frontend tests use. No method takes a client-side filesystem path. **Diffs are returned as structured hunks with stable identity, not as a formatted string** (D25, settled; the shape is specified in RFC-0052 and mirrored in `runtime/kernel/Diff.kt`) — and the reason D25 is a Phase 2 decision rather than a Phase 4 one |
| **M10** | CLI frontend | 0052, 0004 | Create a project, list sessions, send a message, watch the event stream, approve a pending request. Reconnecting with `sinceSequence` delivers the gap rather than a fresh stream with a hole in it |
| **M11** | Effect broker | 0030, 0029, 0028 | Every invocation passes through validation → capability resolution → budget reservation → preview → audit → taint, in that order. A tool registered without a `RecoveryClass` is rejected at registration. Unavailable tools are absent from `descriptorsFor`, never offered and then failed |
| **M12** | Filesystem tool | 0034 | Read, write, list, and search, all through `ResourceHandle`. Every `Mutate` returns a real `Preview.Diff`. Escape attempts are denied by the handle, not by a check inside the tool |
| **M13** | Git tool on JGit | 0032, 0053, D4 | Status, diff, add, commit, branch, log, and checkout on a real repository. `push` is `UNSAFE` and declares it. Reconciliation handles the user changing the working tree outside Aidos between two Aidos steps |
| **M14** | Secrets vault and one remote provider | 0035, 0021, 0023, 0042, 0026 | An API key round-trips through `vault.db` and never appears in a log, an event, an audit row, or a prompt. One provider adapter implements `ModelAdapter` and normalizes its tool-call format into the neutral envelope. Every remote Attempt records the provider's stated retention in `attempts.provider_retention_json`; a provider that states no policy records `UNKNOWN`, never an assumed-benign default |
| **M15** | Prompt construction and instructions | 0025, 0016, D22 | Token budget derives from the selected model's context window. Assembly that cannot fit returns to routing once for a larger candidate — a bounded two-phase negotiation, not a loop. An unadopted instruction file does not reach the system turn; `runs.instruction_set_hash` records which set governed the Run |
| **M16** | Agent loop with trust and taint | 0008, 0027, D6, D7 | The full cycle runs: resolve model → assemble → checkpoint → invoke → validate schema → resolve capability → apply taint → execute → checkpoint. Taint is monotonic within a Run. A tainted Run is denied egress and escalates naming the specific untrusted content. The model never confirms its own success |
| **M16b** | Session memory | 0026, 0011, 0046, D32, D33 | `FACT`/`DECISION`/`TASK_STATE` entries write with mandatory `source_refs`, `created_by`, and a `trust_level` that is the max taint of their sources. Nothing summarizes a session into memory (D32). Entries are session-scoped; the three D33 promotion constraints are enforced by the schema, so a write path cannot create a project-scoped entry without a user, a project-scoped `TASK_STATE`, or a promoted `UNTRUSTED` entry — verified by insert tests, not by review |
| **M17** | Injection suite | 0027, 0038 | A corpus of hostile repository content — README, source comments, commit messages, tool output, MCP responses — none of which escalates authority. New attacks are added to the corpus, not fixed in a special case |
| **M18** | MCP, both transports | 0031, D17, D23 | An off-the-shelf MCP server's tools appear in the broker with `EffectKind` and `RecoveryClass` assigned, run under a capability, and taint the Run as `UNTRUSTED`. An MCP server cannot raise a capability request at all (D30). **stdio** on desktop with a scrubbed child environment; **streamable HTTP** on every profile — HTTPS enforced, certificates validated, cross-host redirects refused, every call `Egress`, and a tainted Run approving each one with the taint source named. Nothing spawns or connects on project open |
| **M19** | End-to-end | — | **G2.** Create project → task → model → tool → commit → artifact → audit, from the CLI, in one command sequence, with the audit trail reconstructing it afterwards |

### Phase 3 — Offline proof · the load-bearing phase

Scheduled before any UI, because a beautiful UI over a runtime that cannot work offline is not
this product. If G3 cannot be met, the correct response is to change the product — and this is
scheduled here so that answer arrives while it is still cheap to act on.

| | Deliverable | RFCs | Done-when |
|---|---|---|---|
| **M20** | Model runtime at user scope | 0022, 0054, 0045 | Weights are user-scope, not per-project. Loading is globally serialized through an admission queue — one loaded model can saturate a phone. Digest is verified on install |
| **M21** | One local LLM on a mid-range phone | 0022, 0045 | Cold start to first token under 10 seconds. It survives being backgrounded and reloaded. It runs only in a foreground service (D24); a background Run parks with `ForegroundRequired` rather than silently routing remote |
| **M22** | Local embeddings and the knowledge index | 0015, 0024, 0021 | `gitsema-kotlin` is consumed as a library, constructed with Aidos-supplied storage (`.aidos/index/`, outside `state.db` per D21), a local-only `EmbeddingProvider`, and a bounded concurrency. Index identity is the Git blob hash, so switching branches invalidates nothing. Indexing is a cancellable background job that survives process death without re-embedding. **Every query reports coverage** — blobs indexed over blobs known — composed in Aidos's adapter. Search degrades to FTS-only rather than blocking. Blocked on `androidTarget()` landing upstream |
| **M23** | Routing policy with explicit degradation | 0020, 0049, 0023 | Routing is user-owned policy, not an engine heuristic: crossing the network boundary is never automatic unless the user said so. `UnavailableOffline` names the missing model kind and is not an error |
| **M24** | Treeless workers | 0053, 0049, 0007 | A worker builds commits directly against the object database on `refs/aidos/workers/<id>` with no `git worktree` and no second checkout — the phone does not have room for one. The worktree is the lock (D15) |
| **M25** | Retention and compaction | 0056, 0045 | Storage per active project stays under 512MB after 90 simulated days of use with default retention. Compaction is interruptible and resumes |
| **M26** | On-device measurement | 0045, 0038 | **G3.** On a real mid-range phone in airplane mode, **with no pre-built index bundle**: open a real repository, ask a question about the code, get a useful answer, make an edit, commit. Inside Android's execution windows. Without exhausting storage. Measured, recorded, and published in the repository — not asserted |

> **G3 is the gate that matters.** Everything before it is infrastructure; everything after
> depends on it being true. A negative result here is a successful outcome for this milestone —
> it is the whole reason the milestone is scheduled before the UI.

### Phase 4 — Android application

| | Deliverable | RFCs | Done-when |
|---|---|---|---|
| **M27** | Foreground service and runtime hosting | 0050, 0044, 0009, D24 | The runtime is in-process behind the same `RuntimeClient` the CLI uses. Eviction mid-Run loses no committed step. The foreground notification says what is actually running |
| **M28** | Compose UI over the Runtime API | 0050, 0052 | Projects, sessions, Runs, and the event stream. Built against `MockRuntimeClient` first, so the seam cannot erode into shared mutable state |
| **M29** | Availability reporting | 0049 | Degraded and unavailable tools are shown at project open. Never discovered mid-Run, never offered and then failed |
| **M30** | Approval, preview, and memory review | 0018, 0027, 0030, 0026 | Every mutation shows its `Preview` before it happens. An escalation names the untrusted source that caused it. Approval requires a `user_interactive` connection. The **memory review surface** lists entries with their source and confidence, and is where a `FACT` or `DECISION` is **promoted to project scope** — the only path by which one exists (D33). The egress approval prompt states what the target provider retains (RFC-0026) |
| **M31** | Diff and commit review | 0032, 0053, D25 | Read a diff, stage, write a message, commit — comfortably, on a phone screen, with one hand, on a bus. This is the actual product |
| **M32** | Notifications | 0044 | Rate-limited. Never silently repeated. A parked Run that needs the user says so once |
| **M32b** | Run Summary and the benign-approval classifier | 0057, 0019, D26 | One page, no scrolling, computed from Execution Graph rows with no model call. Pending approvals, errors, egress, out-of-project mutation, and `INDETERMINATE` outcomes never collapse. A `RUNNING` Run reads "so far". **Not cuttable** — the classifier is a security boundary the approval card needs regardless |
| **M32c** | Intent as a task list, with the proposal gate | 0012, 0019, D6, D10, D20 | **Built last, and small** (D20). Flat goals with title, description, and priority — no hierarchy, no dependencies, no acyclicity checker, because a task list has no cycles. **Status is derived**, never stored, so a reverted or partially-failed Run cannot leave a status field lying; a user override is a timestamped claim shown alongside the derived value. **`TARGETED` edges** from Runs, so the list knows what is being worked on. **The proposal gate**: a session may only propose, a user resolves, and there is no `SESSION` variant by construction. Both derived status and the gate are non-deferrable — retrofitting derivation means migrating data that was never trustworthy, and a system that ships with sessions writing intent directly cannot later be told to stop, because by then nobody can tell which parts the user wanted |
| **M33** | Voice capture → local STT, spoken summaries → local TTS | 0022, 0050, 0057 | *Optional for G4.* Cut first if Phase 4 slips. Voice answers only benign approvals, off by default; spoken approval prompts contain no file content or model output |
| **M34** | F-Droid distribution | 0050 | Reproducible build, no proprietary dependencies, published |
| **M35** | The scenario, by a person | — | **G4.** A person — not the author, not a script — performs the G3 scenario in the app and reports it as comfortable. MVP complete |

---

## Ordering and parallelism

Sequential on the critical path:

```
M1 → M2 → M3 → M5 → M6 → M8 (G1) → M11 → M16 → M19 (G2) → M21 → M22 → M26 (G3) → M31 → M35 (G4)
```

Genuinely parallel once G0 landed, against frozen contracts:

| Stream | Milestones | Blocked by |
|---|---|---|
| Storage | M1, M2 | nothing |
| Security | M3, M4, M7 | M1 |
| Tools | M12, M13 | M11 |
| AI providers | M14 | M9 |
| Knowledge | M22 | `androidTarget()` landing in `gitsema-kotlin` — otherwise nothing. The cleanest parallel stream |
| Frontends | M10, M28 | M9's mock only |
| Testing | M8, M17 | the thing under test |

**RFC revisions are unblocked work available right now** and each one removes a Phase 2/3
blocker. All three are now done — 0016, then 0031, then 0015 — so nothing in Phase 1 or Phase 2
waits on an RFC revision. What Knowledge waits on now is upstream: `androidTarget()` in
`gitsema-kotlin`.

## If it slips

Cut in this order, and stop when the thesis sentence is still true:

1. **M33 voice/STT** — not in the sentence.
2. **M18 MCP** — expensive to cut, because the information it buys arrives late instead. Cut it
   only if Phase 2 is otherwise at risk, and cut it as a *deferral to Phase 5*, not a deletion.
   The transports can be cut separately: dropping **HTTP** keeps the tool-abstraction finding and
   loses MCP on MOBILE; dropping **stdio** keeps MCP on every profile and loses the subprocess
   lifecycle. Prefer cutting stdio — HTTP is the transport that reaches the phone, and the
   scrubbed-environment work it skips is already needed for the shell tool.
3. **M34 F-Droid** — sideload the APK for G4 and publish after.
4. **M25 retention** to a fixed cap with a manual purge — the honest version is real work and a
   90-day storage problem is not a launch blocker.

Never cut: M8 (crash recovery), M17 (injection suite), M26 (the measurement). Those three are
the ones that tell you whether the rest is real.

## Risks specific to the MVP

| Risk | Signal it is happening | Response |
|---|---|---|
| The local model is not good enough to answer a useful question about real code | M21 lands but M26 produces answers the author would not act on | Change the product before the UI. This is exactly what G3 is scheduled to reveal |
| The knowledge index does not fit or does not stay fresh on a phone | M22 storage or re-index time grows superlinearly with repository size | Narrow the scope: index the working set, not the history. Decide at M22, not at M26 |
| Android execution windows make Runs unfinishable | Parked Runs accumulate; M27 shows steps repeatedly evicted mid-effect | D24 already constrains this. If it still fails, the unit of work is too large — make steps smaller, not windows longer |
| JGit is too slow on real repositories | M13 or M22 measurably unusable on a repository the author actually uses | D4's accepted ceiling has been exceeded. Reopen D4 — this is the decision most likely to need revisiting |
| The RFC corpus drifts from the code again | An RFC and `schema/` or `runtime/kernel/` disagree and CI does not notice | Extend `check.py`. The mapping test for kernel fields against schema columns is already noted as owed once persistence lands at M1 |

## How this gets worked on

`PIPELINE.md` is the live tracking document. It is maintained by whoever — or whatever — is
working, and updated in the same commit as the work. The `session-pipeline` skill
(`.claude/skills/session-pipeline/`) is how an agent works through it across session limits.

Every milestone closes the same way: the code, the test that demonstrates the done-when
condition, the RFC diff if implementation revealed a design problem, and a line in `PIPELINE.md`
saying what the next link needs to know.
