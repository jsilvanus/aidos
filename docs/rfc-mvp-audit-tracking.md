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

Link 1 · 2026-08-10 · complete, pushed. PIPELINE.md now has "RFC/MVP Readiness Audit — 2026-08-10
(Part 1: Phase 0/1 + re-verification of the 2026-08-09 review)".

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

## Next

- **Part 2 (next link): Phase 2 (M9-M19)** — RFCs 0008, 0013-0016, 0021, 0023, 0025-0027,
  0030-0035, 0042, 0048, 0052. Same method: independent subagents grepping runtime/ + running
  tests, don't trust this file's own claims. Suggest 2-3 agents split by subsystem (e.g. API/CLI/
  broker; filesystem/git/vault/prompt; agent-loop/memory/injection/MCP).
- **Part 3 (link after that): Phase 3 (M20-M26)** — RFCs 0020-0024, 0044-0045, 0049, 0053, 0056.
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
