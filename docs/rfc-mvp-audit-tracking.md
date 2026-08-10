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

Link 2 · 2026-08-10 · complete, pushed. PIPELINE.md now also has "Part 2: Phase 2, M9–M19" with
significant findings — see below. G2 (currently marked passed) is not well supported by the code.

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

## Next

- **Part 3 (next link): Phase 3 (M20-M26)** — RFCs 0020-0024, 0044-0045, 0049, 0053, 0056.
  Note M21/M26 are hardware-gated (no real phone in this sandbox) — the audit's job there is to
  confirm what's genuinely blocked-on-hardware vs. what's actually missing code, not to fake a
  device measurement.
- **Part 4: Phase 4 (M27-M35)** — RFCs 0044, 0050-0051, 0057, 0060. The 2026-08-09 review already
  found the Android wiring thinner than milestone checkmarks suggest (androidTarget() on
  kernel/api, Service subclass, MainActivity→RealRuntimeClient, daemon locking) — PIPELINE.md's
  later dated entries (2026-08-09/10) claim several of these were subsequently fixed. Re-verify
  those specific claims the same way Part 1 did for the RFC-0004/0005/etc. claims — do not assume
  they're accurate just because they're detailed.
- **Part 5: RFCs never named by any milestone or the original review** — 0000-0002 (vision/
  principles/runtime, mostly prose — light-touch check they're not contradicted by code), 0017
  (state model), 0028-0029 (cost/quota, error taxonomy — partially covered by M6, check the rest),
  0037-0041 (observability, testing strategy, serialization, storage — partially covered by M1,
  check remainder — export/import is Draft/post-MVP, confirm nothing built contradicts that), 0046
  (identity/actors — confirm ActorRef usage), 0054-0055 (scope/instances — partially covered by
  M1/M2/M7, check remainder), 0060 (plugin SDK — Draft, confirm nothing built).
- **Part 6 (final): milestone-by-milestone cross-check table** (M1-M35 in one table: CONFIRMED /
  PARTIAL / MISSING / HARDWARE-BLOCKED) and the honest final MVP-readiness assessment against G4.
  Do this LAST, once every phase has independent evidence — not before.

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
