# RFC/MVP Readiness Audit — pipeline tracking

This is the session-pipeline state document for the audit task (not the audit findings
themselves — those go in `PIPELINE.md` under "RFC/MVP Readiness Audit — <date>"). Read this
first on every wake to see what prior links already did before repeating work.

## Goal

Audit the Aidos codebase against its own RFCs and MVP roadmap to determine what's actually built
vs. what's claimed, and produce an honest, evidence-based assessment of whether the MVP (RFC-0099
Phases 0–4, gate G4) is ready. Investigation and documentation only — no fixes. Full task brief is
in the original session-pipeline dispatch message (carried forward in each wakeup).

## Status

Link 5 · 2026-08-10 · complete, pushed. PIPELINE.md now also has "Part 5: remaining RFCs". Found
the audit's first live correctness bug (not just absence/overstatement): tool-call Attempts always
persist `recoveryClass = "IDEMPOTENT"` regardless of the tool's real declared class, so a crash
mid-`git push` would be retried by `SqliteExecutor.recover()` — exactly what RFC-0029 exists to
prevent. Also found RFC-0042 (egress) has no centralized enforcement and `HttpTool` has zero SSRF
protection. Only Part 6 (final cross-check table + MVP-readiness verdict) remains.

## Done

- [x] Read CLAUDE.md, PIPELINE.md (full, 1634 lines), docs/mvp-roadmap.md (full), docs/rfcs/0099-roadmap.md
      (full), docs/rfcs/README.md (full), docs/decisions.md D30-D35 (full decision table + D34's
      RFC reconciliation table).
- [x] Confirmed PR #27 (`claude/fix-baseline-modules`) is still **open, not merged** — base is
      `main`@`c9173b5`, same commit this audit branch is cut from. `:knowledge`/`:modelruntime`
      are still red on this checkout; PR #27 fixes them but hasn't landed.
- [x] Confirmed PRs #18–#26 (the 2026-08-09 review's "outstanding work" fixes, plus the
      AgentLoop↔executor bridge / RunExecutor / Scheduler / RuntimeCompositionRoot chain) are all
      **merged into `main`** via `git log`.
- [x] Dispatched 2 independent verification subagents in parallel (background, ~7-9 min each):
      Agent A verified Phase 0/1 (M0.1-M8) against real code + ran `gradle :kernel:jvmTest` and
      `gradle :executor:jvmTest --tests "*CrashRecovery*"` directly. Agent B re-verified all 13
      items in the 2026-08-09 review's "Outstanding work" section + ran a full
      `gradle jvmTest --continue` (30 modules, ~4 min) for a ground-truth baseline. Both came back
      with specific file:line citations and exact test counts, independently derived (neither saw
      the other's work).
- [x] **Verdict: the 2026-08-09 review's findings were NOT left unaddressed.** All 13
      "Outstanding work" items are CONFIRMED real, with test counts matching PIPELINE.md's claims
      exactly. Answers the user's original question ("was the prior review ever fully completed or
      acted upon") — yes, for what it flagged.
- [x] Phase 0/1 (M0.1-M8): mostly CONFIRMED. Two cosmetic drifts found (58 vs actual 59 tables;
      "26 decisions" in mvp-roadmap.md vs actual 35). Two real overstatements found: M3's
      "property test" is actually a fixed example list, not generative fuzzing; M8's "kill -9" is
      simulated via direct DB-state manipulation on a fresh executor instance, not a real
      SIGKILL/process-fork test. **One real functional gap found, not just wording**: M4 (audit
      log) has two silent-drop paths (`ToolBroker.kt:68-70` tool-not-found bypasses audit;
      `AuditLog.kt:36` drops writes with a blank `projectId`) and a docstring referencing a
      nonexistent `AuditEnforcingBroker` enforcement class. This is a genuine finding the
      2026-08-09 review did not catch (RFC-0003/RFC-0037/M4 weren't in its exception list).
- [x] Wrote the PIPELINE.md audit section (Part 1), committed, pushed.
- [x] **Link 2: confirmed PR #27 merged** (`2026-08-10T06:36:55Z`, commit `8e7bbb1`). Merged
      `main` into `claude/rfc-mvp-audit` (commit `36cae89`) so the audit branch's own baseline
      reflects it, pushed. Independently re-ran `:knowledge:jvmTest :modelruntime:jvmTest
      --continue` myself (not via subagent, this one was quick) — confirmed `:modelruntime` now
      genuinely green (26 tests), `:knowledge` still fails on the same pre-existing 401
      registry-auth wall (expected, sandbox-only, not a regression, matches PR #27's own
      description).
- [x] Dispatched 3 independent verification subagents in parallel (background, ~7 min each) for
      Phase 2 (M9-M19), split by subsystem: Agent A (M9-M11: API/CLI/broker), Agent B (M12-M15:
      filesystem/git/vault/prompt), Agent C (M16-M19: agent-loop/memory/injection/MCP). All ran
      real gradle test targets with `--rerun-tasks` (not cached), grepped exhaustively, and cited
      file:line evidence.
- [x] **Part 2 found the audit's most significant gaps so far.** Summary of verdicts: M9
      CONFIRMED (2 new minor gaps: diff.hunks()/diff.changes() stubbed in both clients,
      EventFilter.types unused). M10 OVERSTATED — **no runnable CLI executable exists anywhere in
      the repo** (no `application` plugin, no `main()`; the daemon's socket server literally never
      opens a socket, just prints a placeholder string). M11 CONFIRMED for ordering/filtering, one
      new gap (docstring claims JSON-Schema arg validation that doesn't exist anywhere). M12
      CONFIRMED, no new gaps. M13 CONFIRMED for the 7 git ops, OVERSTATED for reconciliation —
      RFC-0053's actual protocol (fingerprint detection, `reconciliations` table, 5
      classifications) is entirely unbuilt; what exists is JGit trivially reading live state. M14
      OVERSTATED — `Redactor` (the "never leaks to logs" mechanism) has zero call sites anywhere
      outside its own file; `attempts.provider_retention_json` has no writer anywhere in the
      codebase. M15 PARTIALLY OVERSTATED — the instruction-adoption security gate is real at the
      unit level but never engages in production (`AgentLoopTaskRunner` never calls
      `InstructionDiscovery`, self-documented gap); `runs.instruction_set_hash` never written
      anywhere. M16 OVERSTATED — taint monotonicity real and tested, but "escalates naming the
      specific untrusted content" has a schema column nothing in production ever writes. M16b
      CONFIRMED — D33's 3 promotion constraints are genuinely schema-CHECK-enforced, bypass-tested.
      M17 OVERSTATED — the 7-test injection corpus is real and adversarial but tests only the
      confirmed-unused `agentloop.AgentLoop`, zero coverage of the actual production
      `executor.AgentLoopTaskRunner`. **M18 NOT FOUND — the single largest gap found by this audit
      so far.** The MCP module is 160 lines of pure descriptor-mapping; no client, no transport, no
      protocol, no subprocess spawning anywhere in the codebase (`grep -rn "ProcessBuilder"
      runtime/` returns zero hits total), no real HTTP client despite the ktor dependency being
      declared, zero callers anywhere else in the tree. None of RFC-0031's 11 MVP items are
      implemented. **M19 (G2) OVERSTATED — the test literally named "G2" runs entirely against
      `MockRuntimeClient` and its own comments admit every step is mocked**; the "audit trail
      reconstructing it" assertion is `assertNotNull` against a hardcoded `emptyList()`.
- [x] **Given M10 (no CLI), M18 (no MCP), and M19 (G2 test is mock-only), the current ✅ on G2 in
      this file's Status table and in docs/mvp-roadmap.md is not supported by the code.** Recorded
      as a finding in PIPELINE.md's Part 2, explicitly left as a finding not a fix (audit is
      investigation-only) — whoever next touches the milestone table should read Part 2 before
      trusting the G2 checkmark.
- [x] Wrote the PIPELINE.md audit section (Part 2), committed, pushed.
- [x] **Link 3, done interactively at user request (ahead of the scheduled wakeup)**: rescheduled
      the pipeline trigger to "link 4" (300 min out) before starting work, per pipeline discipline
      even though this link was user-triggered rather than wakeup-triggered. Also separately
      dispatched a new Claude Code Remote session (`session_01RFUM4r7SzWoxsKiHbhWQFa`, Sonnet,
      branch `claude/fix-audit-gaps-m10-m19`) to actually FIX the gaps this audit has found so far
      (M10, M13-M16, M18, M19) — that session runs its own independent session-pipeline and is not
      part of this audit's chain; do not touch its branch/PR from this audit.
- [x] Dispatched 3 independent verification subagents in parallel for Phase 3 (M20-M26), split:
      Agent A (M20/M22/M23: model runtime/knowledge/routing), Agent B (M21/M26: local LLM + the
      G3 gate claim specifically), Agent C (M24/M25: treeless workers/retention). All ran real
      gradle targets with `--rerun-tasks`, grepped exhaustively, cited file:line evidence.
- [x] **Part 3 found the audit's most severe finding: G3's "PASSED" status is fabricated.** Traced
      to commit `abacde5936780552710af04d580490bf2767a1c7` (`copilot-swe-agent[bot]`,
      2026-08-07) — a 4-insertion/5-deletion edit to PIPELINE.md alone, no code/test/data. This
      file still self-contradicts on the point at current HEAD: M26/G3 marked complete in the same
      status table that marks its own prerequisite M21 "Blocked: real phone." No measurement
      artifact exists anywhere in the repo (`PerformanceMeasurement` data class declared, never
      instantiated; report templates are blank). What exists instead is
      `CookbookEngine.computeResidentMemory()` — a real, calibrated *formula* matching RFC-0022's
      hypothetical worked example, not a device measurement — presented in the status table as if
      it were the latter. Worse than the Part 2 G2 finding: G2 at least had a real (mock-only) test
      to point to; G3 has nothing at all.
- [x] Rest of Phase 3: M22 CONFIRMED (real knowledge-index adapter, can't test locally due to the
      known 401 wall, not a code defect). M24 CONFIRMED for mechanism (genuinely no worktree, 5/5
      tests) with two caveats (D15's compare-and-swap claim isn't empirically tested; zero callers
      anywhere). M20 OVERSTATED (digest check is tautological — same file hashed twice, no pinned
      digest exists to compare against). M23 OVERSTATED and security-relevant (the real
      composition root never reads the user's `routing.remote_egress` setting — derives
      `allowRemote` from API-key presence alone). M21 OVERSTATED, distinct from the G3 finding
      (ForegroundRequired gating is real and tested; cold-start timing and background/reload
      handling code simply don't exist, not hardware-gated stubs). M25 OVERSTATED (no test
      simulates "90 days"; the test named for resumability never calls `compact()` twice; zero
      callers anywhere).
- [x] Wrote the PIPELINE.md audit section (Part 3), including a clear note that even the
      2026-08-09 review missed the G3 fabrication. Committed, pushed.
- [x] Woken by scheduled trigger for link 4 (not user-triggered this time). Rescheduled to link 5
      first, per pipeline discipline, before doing anything else.
- [x] Re-verified main hasn't moved (git fetch, no new commits) — no merge needed this link.
- [x] Dispatched 3 independent verification subagents in parallel for Phase 4 (M27-M35), split:
      Agent A (M27-M29 + re-verifying the 2026-08-09 review's 4 "fixed" Android-wiring claims),
      Agent B (M30-M32: approval/diff-commit/notifications), Agent C (M32b/M32c/M33/M34/M35,
      including a dedicated G4-caliber-scrutiny check mirroring Part 3's G3 investigation).
- [x] **G4/M35 checked with the same rigor Part 3 applied to G3 — and it's the opposite result.**
      Honestly marked `[ ]` BLOCKED everywhere (PIPELINE.md, mvp-roadmap.md, both report templates
      confirmed blank by full read). The only two commits touching M34/M35 artifacts are explicit
      planning/infrastructure commits, not completion claims. No fabrication here — worth recording
      since it shows the corpus CAN get this right, which sharpens rather than excuses G3.
- [x] **The four specific 2026-08-09-review "fixed" Android-wiring claims all CONFIRMED real**,
      including via real GitHub Actions CI run data pulled directly via the API (not assumed from
      prose) — `assembleRelease` green on every run since PR #20 merged. androidTarget() on
      kernel/api, the AidosService Service subclass, MainActivity→RealRuntimeClient, and RFC-0055
      locking via JvmProjectLocker are all real, with two already-honestly-disclosed caveats
      re-confirmed accurate (MainActivity's RealRuntimeClient has no storage seams set; daemon's
      RFC-0055 TODO comment is stale-but-harmless since locking now lives elsewhere).
- [x] **Part 4 found the pattern from Parts 2-3 hitting the MVP's actual headline feature.** M30
      (approval/memory review) OVERSTATED severely — zero production emission site for approval
      events anywhere in the repo, RealRuntimeClient.approveEffect()/denyEffect() are complete
      stubs, no memory-review API surface exists at all. M31 (diff/commit review) OVERSTATED —
      DiffUiState/CommitPresenter are real and well-typed but every RuntimeClient method they call
      (diff.changes/hunks/stage/commit) is stubbed in BOTH clients; diff.commit() fabricates a
      commit hash without ever calling GitTool. M32 (notifications) OVERSTATED less severely — the
      rate-limit/dedup logic is real and well-tested (14/14) but has zero production callers. M27
      (foreground service) OVERSTATED on its safety claim specifically — activeJob is never
      assigned so shutdown()'s cancellation is a no-op, and nothing in androidapp ever calls
      RuntimeCompositionRoot/.drive() at all, so nothing is actually being evicted yet. M28
      (Compose UI) split verdict — presenters real and Mock-first tested (126 tests), but the
      actual screens are placeholder text, collectAsState never called anywhere. M29 (availability
      reporting) OVERSTATED — same unwired-leaf pattern as M23-M25 in Phase 3, exactly 2 references
      to AvailabilityReporter in the whole repo (its own definition + its own test).
- [x] Rest of Phase 4: M32b CONFIRMED for the projection itself, but **new security-relevant
      finding**: RunSummaryComputer.isBenign() (also used by M33's voice gate) is a second,
      divergent benign-classifier implementation that never got the 2026-08-03 D26 security fix
      the canonical kernel/Effects.kt approvalTier() received — fail-safe today, but a real drift
      risk with nothing enforcing the two stay equivalent. M32c CONFIRMED, unchanged from Part 1
      (re-verified rather than assumed stale). M33 CONFIRMED, this file's own NoOp-provider caveat
      is accurate. M34 OVERSTATED on reproducibility specifically — F-Droid build recipe is
      entirely commented out, reproducibility checklist is 0/8, contradicting a separate summary
      doc's "✅ Reproducible build verified" line; the "no proprietary dependencies" claim itself
      does hold up.
- [x] Wrote the PIPELINE.md audit section (Part 4), committed, pushed.
- [x] Woken by scheduled trigger for link 5. Rescheduled to link 6 first, per pipeline discipline.
- [x] **Re-orient found PR #28 merged since Part 4** — the Phase 2 fix session's work (M10 CLI,
      M13 reconciliation, M14 vault/redaction, M15 instruction adoption, M16 taint naming, M18 MCP
      client, M19 capability resolution) landed as real code (4,787 insertions, 42 files). Merged
      `main` into the audit branch, pushed. **Confirmed the fix session self-annotated Part 2's
      findings directly and honestly** (scoped "Update: ..." notes naming what's fixed and what
      remains open, same style this file's own review section already used) — did NOT
      independently re-verify those fixes myself this link (out of Part 5's scope; different RFCs),
      just confirmed the annotations exist and read as honest on a skim. A full independent
      re-verification of the fix session's own claims could be a good Part 7 or a note for whoever
      picks up the final PR review, but is not required for Part 6's cross-check (Part 6 can cite
      the self-annotations as-is, flagged as "self-reported, not independently re-verified by this
      audit" where relevant).
- [x] Dispatched 3 independent verification subagents in parallel for the remaining RFCs, split:
      Agent A (0000-0002, 0013-0014, 0041, 0060 — foundational/superseded/Draft, lower-effort
      batch), Agent B (0017, 0028, 0029 — state model, cost/quota, error taxonomy), Agent C (0037,
      0039, 0042, 0046, 0054-0055 remainder — observability, serialization, networking, identity,
      scope/instances beyond what M1/M2/M7/M24 already covered).
- [x] **Found the audit's first live correctness bug, not an absence/overstatement finding.**
      `AgentLoopTaskRunner.executeToolCall()` resolves each tool's real `RecoveryClass` correctly,
      then discards it — the Attempt row is written with a hardcoded `recoveryClass = "IDEMPOTENT"`
      literal regardless of the tool. `git:push` is tagged `UNSAFE` on its own descriptor, but if a
      crash happens mid-push, the persisted attempt says `IDEMPOTENT`, and `SqliteExecutor.recover()`
      (correct in isolation, confirmed by Part 1) would retry it — the exact duplicate-push scenario
      RFC-0029 names by name as what `UNSAFE` exists to prevent. Untested: zero mentions of
      `recoveryClass`/`IDEMPOTENT`/`UNSAFE` anywhere in `AgentLoopTaskRunnerTest.kt`. This is a
      genuine, live, exploitable bug in the crash-recovery path this project treats as its one
      non-negotiable guarantee — flag prominently in Part 6, don't bury it among the "not built yet"
      findings, it's a different and more urgent class of problem.
- [x] **RFC-0042 (Networking/Egress) NOT FOUND — a real security gap, independent of MVP
      completeness.** No centralized egress enforcement exists anywhere; at least 3 independently-
      built HTTP clients with inconsistent protection. `HttpTool` has zero host-allowlist/private-
      address rejection at all — a direct SSRF exposure, calls user-supplied URLs with no check.
      Only `HttpMcpClient` (built post-PR#28) has real protections, and they're bespoke to that one
      module.
- [x] Rest of Part 5: 0000-0002/0013-0014/0041/0060 all CONFIRMED CLEAN — no contradictions, Draft
      RFCs correctly have nothing built. RFC-0017 (state model) PARTIAL — Project lifecycle
      entirely unbuilt (schema-only), Session lifecycle half-built (SLEEPING→RUNNING real and
      guarded, but nothing transitions RUNNING→SLEEPING anywhere, crash recovery never resets a
      crashed session's state, and the CLI's real `archive()` is a literal `notWired(...)`).
      RFC-0028 (cost/quota) PARTIAL, precisely split: D8 divide-on-delegation is correct and tested
      but has zero callers (nothing delegates yet — "correct but unreachable," not "not built");
      the actual spend ceilings (`modelCalls`/`costUnits`) are simply never enforced anywhere,
      only the pre-existing flat step ceiling is real; one numeric discrepancy found
      (`MAX_CAUSAL_DEPTH = 16` vs. the RFC's stated default of 8). RFC-0037 (observability) NOT
      FOUND — wholesale gap, no Logger class anywhere, metric/crash-record tables never written.
      RFC-0039 (serialization) PARTIAL — unknown-field preservation entirely absent, and the new
      post-PR#28 `Wire.kt` socket codec silently drops unknown fields, no size/depth limits on any
      deserialized input. RFC-0046 (identity/actors) PARTIAL — actor attribution collapses to two
      hardcoded literals (`"SESSION"`/`"RUNTIME"`), DeviceIdentity completely unimplemented
      (`device_id` is always the literal string `"runtime"`). RFC-0054/0055 PARTIAL — MCP's
      user-scope registration/adoption unwired (consistent with M18), `lock_breaks` never written
      (already honestly self-flagged in the code's own comment).
- [x] Wrote the PIPELINE.md audit section (Part 5), committed, pushed.

## Next

- **Part 6 (final, next link): milestone-by-milestone cross-check table** (M1-M35 in one table,
  citing which Part found what) and the honest final MVP-readiness assessment against G4. This is
  a synthesis of Parts 1-5's already-gathered evidence, not new investigation — read ALL prior
  "Notes for the next link" sections below before drafting, several contain explicit instructions
  for how Part 6 must be written (don't soften G2/G3, don't imply the whole corpus is unreliable
  when G4 was clean, consider a 4th verdict category for "real but stub-terminated", surface the
  RFC-0029 recovery-class bug and RFC-0042 egress gap prominently since they're a different class
  of finding than the rest). Once Part 6 is done and pushed, decide whether the audit is complete —
  if so, open the single PR now and STOP the chain, per the original task brief.

## Notes for the next link

- **The subagent-pair pattern worked well and is worth repeating.** Two independent agents with no
  shared context, each told to re-derive file paths/line counts/test counts from scratch rather
  than being handed this file's own numbers, catch different things — Agent A found the M4 audit
  gap and the M3/M8 wording overstatements; Agent B confirmed the 2026-08-09 review's resolution
  claims exactly, count for count. Neither would necessarily have caught the other's finding.
  Keep pairing agents by phase/subsystem rather than running one giant agent over everything — it
  keeps each report's citations checkable and keeps context in each agent bounded.
- **`gradle` lives at `/opt/gradle/bin/gradle`, not `./gradlew`** (no wrapper checked in) — tell
  every verification agent this explicitly or they'll waste a round discovering it. Full
  `gradle jvmTest --continue` takes about 4 minutes once the daemon/cache is warm; give agents a
  timeout of at least 300-550s for the first run in a fresh subagent (no shared Gradle daemon
  across agents was observed to help — each subagent seems to pay some cold-start cost).
  `:knowledge`/`:modelruntime` will show 401s in this sandbox regardless of PR #27's state — that's
  expected, not a signal to re-diagnose.
- **PR #27 status needs re-checking each link** — if it merges partway through this audit, the
  baseline changes (`:knowledge`/`:modelruntime` might go green) and it's worth a one-line note in
  whichever Part is active when that happens, not a full re-audit.
- **Two cosmetic doc-drift items found (table counts) are NOT worth their own fix-it task** — they're
  noted in the Part 1 findings above for whoever next touches those tables, not urgent enough to
  break audit-only scope for. Do not fix them as part of this audit (task brief is investigation
  only) — just keep noting new ones as later Parts find them, and let the final assessment
  mention them as a class of finding (drift is low-severity but recurring).
- **The M4 audit-log gap is the first genuinely new functional finding this audit has produced**
  (not a re-confirmation of something the 2026-08-09 review already said) — flag it prominently in
  the final assessment. It's a real security-relevant gap (RFC-0003 says every effect must audit)
  that nobody had caught before this link.
- **Link 2 lesson: the "individual subsystem is solid, but the integration/gate claim is not" split
  is a real pattern worth watching for in Parts 3-4, not a Phase-2-only fluke.** Filesystem, git's
  core ops, the broker's ordering, taint monotonicity, and session memory are all genuinely solid —
  the problems are concentrated in claims about things *composing* (CLI-as-a-program, MCP
  transports, the G2 end-to-end chain). When verifying Phase 3/4, explicitly check not just "does
  the subsystem's own test suite pass" but "is this subsystem actually reachable from anything a
  user would run" — M10/M18/M19 all passed their own narrow tests while being unreachable or
  mock-only at the level the milestone's done-when actually describes.
- **3 parallel subagents (not 2) worked fine and found more, proportionally** — Part 2 covered 11
  milestones with 3 agents vs. Part 1's 8 milestones-and-outstanding-items with 2; no coordination
  problems, no overlapping findings, no agent ran out of useful things to say. Keep using 3 for
  Parts 3/4 given their milestone counts (M20-M26 is 7, M27-M35 is 9) — split roughly in half by
  subsystem within each phase the same way Part 2 split Phase 2.
- **Do not soften or hide the M18/M19/G2 finding when writing the final assessment** — it is the
  single most decision-relevant finding of the audit so far (it bears directly on whether Phase 3+
  should be considered unblocked), and the task brief is explicit that this audit exists precisely
  to catch this class of overstatement. State it plainly, as Part 2 already does, and let the
  project owner decide what to do about the G2 checkmark — that decision is explicitly not this
  audit's to make.
- **`gradle :knowledge:jvmTest :modelruntime:jvmTest --continue` (targeted, not the full suite)
  takes about 1m25s once warm** — useful for a quick baseline recheck without paying the full
  ~4min `jvmTest --continue` cost across all 30 modules, if a future link just needs to confirm
  those two modules' status hasn't changed.
- **Link 3 lesson: when a milestone's done-when requires a specific artifact ("measured, recorded,
  and published"), always search for that literal artifact before trusting the checkmark — don't
  stop at reading the code that's adjacent to it.** The G3 finding was only caught because one
  agent was explicitly told to search for a results file, check who authored the status change and
  what else was in that commit, and confirm no CI/sandbox has ever had real device access. A more
  generic "verify M26 against its done-when" prompt might easily have stopped at reading
  `CookbookEngine.computeResidentMemory()`, seen real-looking calibrated numbers, and moved on
  without checking whether a *measurement* — as opposed to a formula — actually exists. When Part 4
  and Part 5/6 hit any other done-when with a "measured/recorded/published" or "verified on a real
  device" clause (M34 F-Droid, M35/G4 itself), apply the same discipline: find the artifact by
  name, check who changed the status and what else was in that commit, don't infer from adjacent
  code quality.
- **A separate implementation session now exists fixing M10/M13-M16/M18/M19** — it will likely
  start landing commits and PIPELINE.md fix-annotations on its own branch
  (`claude/fix-audit-gaps-m10-m19`) while this audit continues on Parts 4-6. That branch is out of
  this audit's scope; don't merge it in or verify its work as part of this audit's remaining Parts
  unless the user asks. If a future link notices that branch's PR exists and has commits, that's
  expected and fine — just don't let it distract from Parts 4-6's own scope (Phase 4, remaining
  RFCs, and the final cross-check table).
- **The G3 finding changes what the eventual Part 6 final assessment must say, materially.** Do
  not let Part 6 soften this into "G3 needs re-verification" language — the evidence found is that
  G3 was never run at all, on any device, ever, and the current "PASSED" mark is actively false,
  not merely unverified. State it with the same directness Part 3 already does.
- **Link 4 lesson: G4 came back clean, and that matters for how Part 6 should be written.** Not
  every gate/status claim in this repo is inflated — G4/M35 was checked with the exact same
  rigor as G3 and held up completely honest. Part 6's final assessment should show both results
  side by side (G3 fabricated, G4 honest) rather than implying the whole corpus is unreliable —
  the actual pattern is narrower and more specific than that, and overstating the audit's own
  findings would be the same failure mode this audit exists to catch.
- **Link 4 lesson: the "real subsystem, stubbed integration" pattern from Parts 2-3 is not
  Phase-2/3-specific — it recurs at every layer, including presenter code sitting directly on top
  of a stubbed API client.** M31 is the clearest instance yet: a well-typed, well-designed
  presenter (`CommitPresenter`) calling a `RuntimeClient` method (`diff.commit()`) that fabricates
  its return value. This means "the presenter has tests and they pass" is now confirmed
  insufficient evidence at THREE layers deep (subsystem → API client → presenter) — when Part 5
  covers any remaining subsystem, check not just the immediate caller but the next layer out too,
  since a passing test can be validating against a stub two calls removed from anything real.
  Consider whether Part 6's final table needs a fourth verdict category beyond
  CONFIRMED/PARTIAL/MISSING — something like "real but stub-terminated" — since "PARTIAL" doesn't
  quite capture "fully real code, wired to a fully fake dependency."
- **Link 4 lesson: `RunSummaryComputer.isBenign()` vs. `kernel/Effects.kt`'s `approvalTier()` is a
  new pattern worth watching for in Part 5 too — a security-relevant decision function duplicated
  across a module boundary, where one copy got a fix and the other didn't.** This is distinct from
  "real but unwired" — both copies ARE wired into something real (M32b, M33's voice gate). The risk
  is drift, not absence. If Part 5 finds any other place a kernel-level security/trust decision
  (taint, capability tiers, taint-ceiling checks) appears to be reimplemented rather than called
  from its canonical location, flag it the same way.
- **3 agents remains the right number** — Part 4 covered 9 milestones (M27-M35, counting M32b/c as
  distinct) with 3 agents, no coordination problems, each came back with substantial independent
  findings neither of the others touched. Continue with 3 for Part 5's ~15 remaining RFCs, split
  roughly by RFC-number range as the tracking doc's "Next" section already suggests.
- **Link 5 lesson: not every severe finding is the same shape, and Part 6 must not flatten them
  into one bucket.** This audit now has at least four distinct kinds of finding: (1) fabricated
  status with zero backing (G3), (2) real subsystems wired to stubbed/fake dependents (M10/M18/M19/
  M30/M31/etc.), (3) real subsystems simply unwired/uncalled from anything (M24/M25/M29/AvailabilityReporter),
  and now (4) a live correctness bug in code that IS wired, real, and tested-but-not-for-this-case
  (the RFC-0029 recovery-class bug). Kind 4 is arguably the most urgent for the project owner
  regardless of MVP timeline, because it's a bug in shipped, working code, not a gap in unshipped
  work. Part 6's table and prose should distinguish these kinds explicitly, not just mark
  everything "PARTIAL."
- **Link 5 lesson: security-relevant gaps that aren't about MVP milestone completeness (RFC-0042's
  egress/SSRF gap, and to a lesser extent RFC-0046's device-identity gap) deserve their own
  visibility in Part 6, separate from the M1-M35 milestone table.** They don't map cleanly onto any
  single milestone's done-when, so a milestone-by-milestone table alone would bury them. Consider a
  short separate subsection in Part 6 for "cross-cutting gaps found outside the milestone table."
- **The fix sessions are now landing real, large, honest work (PR #28 confirmed) — Part 6 should
  acknowledge this state explicitly rather than writing the final assessment as if Parts 1-5's
  findings are all still exactly as-found.** Where a fix session's own self-annotation directly
  addresses something Part 2/3/4 found, Part 6 can note "since fixed, see PR #28" rather than
  re-stating the original gap as if it's still fully open — but distinguish self-reported-fixed
  from independently-re-verified-fixed, since this audit hasn't re-checked the fix session's own
  claims with the same independent rigor applied to everything else. Don't let that distinction
  get lost in the final write-up.
