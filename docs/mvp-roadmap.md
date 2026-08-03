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

**Deliberately still in, despite looking cuttable:** MCP over stdio, desktop-only (D17). It is
the first real test of whether the tool abstraction can absorb tools the runtime did not write.
Finding out at Phase 2 is cheap; finding out after every built-in tool has been written against
the assumption that the runtime authored it is not.

---

## Acceptance state

As of 2026-08-03, 45 RFCs are **Accepted**. Accepted means implementation may begin against
them, not that they are frozen — see
[Accepted is not frozen](rfcs/README.md#accepted-is-not-frozen). The freeze list is RFC-0099's
"What Should Stabilise First", and it is unchanged.

### Still Draft, and off the MVP path

0012 Intent Graph · 0026 Model Memory · 0033 Shell · 0041 Export/Import · 0043 Plugin Packaging ·
0046 Identity · 0047 Project Templates · 0051 Desktop · 0060 Plugin SDK · 0100–0102 reviews.

Nothing in the MVP depends on these. They stay Draft because approving a design nobody is about
to build is how a corpus accumulates authority it has not earned.

### Still Draft, and **on** the MVP path

These three are needed and are not yet acceptable as written. Each must be revised and accepted
*before* the milestone that consumes it. This is tracked as real work, not as a caveat.

| RFC | Needed by | What is wrong with it |
|---|---|---|
| **0016 Instruction Engine** | M12 (Phase 2) | Written before D22 ("build less prompt machinery"). It proposes format plugins, conflict resolution, and a normalization layer for six instruction dialects. The MVP needs: find `AGENTS.md`/`CLAUDE.md`, concatenate, cap the budget. Cut it down to that and record what was cut as Future Work |
| **0031 MCP** | M14 (Phase 2) | Specifies stdio *and* streamable HTTP; D17 says stdio only, desktop only, for the MVP. Trust policy for MCP-sourced tools is a sentence where it needs to be a section — an MCP server is an untrusted subject (RFC-0027) that can also be a capability *requester* (RFC-0055 `user_interactive`), and that interaction is unspecified |
| **0015 Knowledge Engine** | M16 (Phase 3) | The blob-hash-keyed index model is settled and correct. Everything above it — ranking, chunking, the query interface, what happens when the index is stale — is not. This is the largest genuine design risk left in the MVP and it sits on the load-bearing gate |

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
| **M0.3** | `docs/decisions.md` | ✅ 24 decisions, none open |
| **M0.4** | Acceptance pass | ✅ 45 RFCs Accepted; remaining Draft set is deliberate and documented above |

**G0 met.** The parallel workstreams in RFC-0099 may now proceed against frozen contracts.

### Phase 1 — Execution kernel · no AI, no tools

The point of proving the kernel with no model in it: if it cannot survive eviction
deterministically, no amount of AI quality matters, and every later bug gets misattributed to
the model.

| | Deliverable | RFCs | Done-when |
|---|---|---|---|
| **M1** | Storage and migrations | 0040, 0039, 0054 | Fresh install creates `~/.aidos/user.db`, `~/.aidos/secrets/vault.db`, and `<project>/.aidos/state.db` from `schema/`. The migration runner applies a version and refuses a downgrade with a named error, not a crash. `check.py` still green |
| **M2** | Identity and scopes | 0054, 0010, 0011 | UUIDv7 IDs are monotonic within a process and unique across two concurrent runtimes. A project registered at user scope resolves from a path and from an ID. Opening a project whose directory has moved fails with `ProjectMoved`, not a null |
| **M3** | Capability manager | 0018, 0003 | Property test: no input to `RelPath.of` produces a path that escapes its root — including `..` in any segment, absolute forms, drive letters, NUL, and every encoding of those. Revocation by epoch invalidates outstanding handles within one step. `validate()` refuses when Run taint exceeds the grant's ceiling |
| **M4** | Audit log | 0003, 0037 | Every `validate` and every effect writes one row naming the subject, the capability actually exercised, and the outcome. An effect with no audit row is a test failure, enforced by the broker harness, not by review |
| **M5** | Execution graph tables and executor | 0019, 0009, 0006 | A Run of hard-coded Tasks executes to `COMPLETED`. `drive()` is re-entrant: calling it on a complete Run is a no-op. At most one *effectful* Task is `RUNNING` per Run (D14); `Read` tasks may overlap |
| **M6** | Recovery | 0009, 0029 | `recover()` classifies every interrupted attempt by `RecoveryClass`. `UNSAFE` is never retried — it is reported `INDETERMINATE` with what is known. Reservations are released. Budget and step ceilings terminate a runaway Run at the ceiling, every time |
| **M7** | Project lock and runtime instances | 0055, 0007 | Two runtimes on one project: the second fails to acquire and says so. A stale lock from a killed process is reclaimed, not waited on forever |
| **M8** | Crash-recovery suite | 0038 | **G1.** A Run is killed with `kill -9` at *every* checkpoint boundary and resumes correctly at every one. 100%, not "mostly" — this is the one metric with no acceptable degradation |

> **G1 blocks all AI work.** Do not start Phase 2 with M8 amber.

### Phase 2 — First vertical slice

| | Deliverable | RFCs | Done-when |
|---|---|---|---|
| **M9** | Runtime API, in-process transport, `MockRuntimeClient` | 0052, 0048 | Every `RuntimeClient` method is reachable in-process. The mock implements the same interface and is what frontend tests use. No method takes a client-side filesystem path |
| **M10** | CLI frontend | 0052 | Create a project, list sessions, send a message, watch the event stream, approve a pending request. Reconnecting with `sinceSequence` delivers the gap rather than a fresh stream with a hole in it |
| **M11** | Effect broker | 0030, 0029, 0028 | Every invocation passes through validation → capability resolution → budget reservation → preview → audit → taint, in that order. A tool registered without a `RecoveryClass` is rejected at registration. Unavailable tools are absent from `descriptorsFor`, never offered and then failed |
| **M12** | Filesystem tool | 0034 | Read, write, list, and search, all through `ResourceHandle`. Every `Mutate` returns a real `Preview.Diff`. Escape attempts are denied by the handle, not by a check inside the tool |
| **M13** | Git tool on JGit | 0032, 0053, D4 | Status, diff, add, commit, branch, log, and checkout on a real repository. `push` is `UNSAFE` and declares it. Reconciliation handles the user changing the working tree outside Aidos between two Aidos steps |
| **M14** | Secrets vault and one remote provider | 0035, 0021, 0023, 0042 | An API key round-trips through `vault.db` and never appears in a log, an event, an audit row, or a prompt. One provider adapter implements `ModelAdapter` and normalizes its tool-call format into the neutral envelope |
| **M15** | Prompt construction | 0025, 0016, D22 | Token budget derives from the selected model's context window. Assembly that cannot fit returns to routing once for a larger candidate — a bounded two-phase negotiation, not a loop. *Requires RFC-0016 revised and accepted first* |
| **M16** | Agent loop with trust and taint | 0008, 0027, D6, D7 | The full cycle runs: resolve model → assemble → checkpoint → invoke → validate schema → resolve capability → apply taint → execute → checkpoint. Taint is monotonic within a Run. A tainted Run is denied egress and escalates naming the specific untrusted content. The model never confirms its own success |
| **M17** | Injection suite | 0027, 0038 | A corpus of hostile repository content — README, source comments, commit messages, tool output, MCP responses — none of which escalates authority. New attacks are added to the corpus, not fixed in a special case |
| **M18** | MCP over stdio, desktop only | 0031, D17, D23 | An off-the-shelf MCP server's tools appear in the broker with `EffectKind` and `RecoveryClass` assigned, run under a capability, and taint the Run as `UNTRUSTED`. An MCP server cannot approve its own capability request (RFC-0055 `user_interactive`). *Requires RFC-0031 revised and accepted first* |
| **M19** | End-to-end | — | **G2.** Create project → task → model → tool → commit → artifact → audit, from the CLI, in one command sequence, with the audit trail reconstructing it afterwards |

### Phase 3 — Offline proof · the load-bearing phase

Scheduled before any UI, because a beautiful UI over a runtime that cannot work offline is not
this product. If G3 cannot be met, the correct response is to change the product — and this is
scheduled here so that answer arrives while it is still cheap to act on.

| | Deliverable | RFCs | Done-when |
|---|---|---|---|
| **M20** | Model runtime at user scope | 0022, 0054, 0045 | Weights are user-scope, not per-project. Loading is globally serialized through an admission queue — one loaded model can saturate a phone. Digest is verified on install |
| **M21** | One local LLM on a mid-range phone | 0022, 0045 | Cold start to first token under 10 seconds. It survives being backgrounded and reloaded. It runs only in a foreground service (D24); a background Run parks with `ForegroundRequired` rather than silently routing remote |
| **M22** | Local embeddings and the knowledge index | 0015, 0024, 0021 | Index identity is the Git blob hash, so switching branches invalidates nothing. Re-indexing after a branch switch touches only genuinely new blobs. Embeddings live outside the operational database (D21). *Requires RFC-0015 revised and accepted first* |
| **M23** | Routing policy with explicit degradation | 0020, 0049, 0023 | Routing is user-owned policy, not an engine heuristic: crossing the network boundary is never automatic unless the user said so. `UnavailableOffline` names the missing model kind and is not an error |
| **M24** | Treeless workers | 0053, 0049, 0007 | A worker builds commits directly against the object database on `refs/aidos/workers/<id>` with no `git worktree` and no second checkout — the phone does not have room for one. The worktree is the lock (D15) |
| **M25** | Retention and compaction | 0056, 0045 | Storage per active project stays under 512MB after 90 simulated days of use with default retention. Compaction is interruptible and resumes |
| **M26** | On-device measurement | 0045, 0038 | **G3.** On a real mid-range phone in airplane mode: open a real repository, ask a question about the code, get a useful answer, make an edit, commit. Inside Android's execution windows. Without exhausting storage. Measured, recorded, and published in the repository — not asserted |

> **G3 is the gate that matters.** Everything before it is infrastructure; everything after
> depends on it being true. A negative result here is a successful outcome for this milestone —
> it is the whole reason the milestone is scheduled before the UI.

### Phase 4 — Android application

| | Deliverable | RFCs | Done-when |
|---|---|---|---|
| **M27** | Foreground service and runtime hosting | 0050, 0044, 0009, D24 | The runtime is in-process behind the same `RuntimeClient` the CLI uses. Eviction mid-Run loses no committed step. The foreground notification says what is actually running |
| **M28** | Compose UI over the Runtime API | 0050, 0052 | Projects, sessions, Runs, and the event stream. Built against `MockRuntimeClient` first, so the seam cannot erode into shared mutable state |
| **M29** | Availability reporting | 0049 | Degraded and unavailable tools are shown at project open. Never discovered mid-Run, never offered and then failed |
| **M30** | Approval and preview flows | 0018, 0027, 0030 | Every mutation shows its `Preview` before it happens. An escalation names the untrusted source that caused it. Approval requires a `user_interactive` connection |
| **M31** | Diff and commit review | 0032, 0053 | Read a diff, stage, write a message, commit — comfortably, on a phone screen, with one hand, on a bus. This is the actual product |
| **M32** | Notifications | 0044 | Rate-limited. Never silently repeated. A parked Run that needs the user says so once |
| **M33** | Voice capture → local STT | 0022, 0050 | *Optional for G4.* Cut first if Phase 4 slips — it is the only item in this phase that is not on the critical path of the thesis sentence |
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
| Knowledge | M22 | RFC-0015 revision — otherwise nothing. The cleanest parallel stream |
| Frontends | M10, M28 | M9's mock only |
| Testing | M8, M17 | the thing under test |

**RFC revisions are unblocked work available right now** and each one removes a Phase 2/3
blocker: RFC-0016 (cut it down), RFC-0031 (narrow it and specify MCP trust), RFC-0015 (the real
design work).

## If it slips

Cut in this order, and stop when the thesis sentence is still true:

1. **M33 voice/STT** — not in the sentence.
2. **M18 MCP** — expensive to cut, because the information it buys arrives late instead. Cut it
   only if Phase 2 is otherwise at risk, and cut it as a *deferral to Phase 5*, not a deletion.
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
