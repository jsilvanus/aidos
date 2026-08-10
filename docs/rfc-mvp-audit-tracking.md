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

Link 1 · 2026-08-10 (in progress)

## Done

- [x] Read CLAUDE.md, PIPELINE.md (full, 1634 lines), docs/mvp-roadmap.md (full), confirmed
      PR #27 (`claude/fix-baseline-modules`) is still **open, not merged** — base is `main`@`c9173b5`,
      same commit this audit branch is cut from. So `:knowledge`/`:modelruntime` are still red on
      `main` as of this audit; PR #27 fixes them but hasn't landed.
- [x] Confirmed PRs #18–#26 (the 2026-08-09 review's "outstanding work" fixes, plus the
      AgentLoop↔executor bridge / RunExecutor / Scheduler / RuntimeCompositionRoot chain) are all
      **merged into `main`** (`git log --oneline --all | grep "Merge pull request #(18-26)"` — all
      four+ merge commits present, matching PIPELINE.md's own dated narrative). So the 2026-08-09
      review's findings were NOT left sitting — PIPELINE.md's own text shows extensive follow-up
      work across 2026-08-09/10, and the merge commits confirm it actually landed on `main`, not
      just in PIPELINE.md prose on an unmerged branch.
- [x] Read docs/decisions.md is NOT yet done — next.
- [ ] Read docs/rfcs/0099-roadmap.md, docs/rfcs/README.md — next.
- [ ] Dispatch parallel verification agents (in progress this link) — Agent A: Phase 0-1
      foundational RFCs/M1-M8 spot-check. Agent B: re-verify 2026-08-09 review's "outstanding work"
      resolution claims (RunCreator, AgentLoopTaskRunner, RunExecutor, Scheduler,
      RuntimeCompositionRoot, SchedulerMatcher, SessionSubscriptionStore, SqliteIntentStore,
      SqliteContentNodeStore, DegradationLadder) actually exist as claimed, with real tests.
- [ ] Write first draft of PIPELINE.md audit section covering findings so far.

## Next

- Continue RFC-by-RFC sweep for RFCs not covered by the 2026-08-09 review: 0000-0003, 0006-0011,
  0013-0014, 0017, 0019-0023, 0025-0035 (MCP/knowledge partially covered — check remainder),
  0037-0042, 0046, 0048-0057, 0060, 0099-0102.
- Cross-check every mvp-roadmap.md milestone (M1-M35) one by one against code — build a table.
- Draft the final honest MVP-readiness assessment (gap list vs. G4).

## Notes for the next link

(none yet — this is link 1, still in progress)
