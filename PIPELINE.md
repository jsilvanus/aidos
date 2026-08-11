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

**2026-08-11 (later still) · TOOL_CALL park/resume is now actually built** (the design two entries
below described; this entry supersedes "designed but not built" for TOOL_CALL specifically —
USER_PROMPT is still exactly where that entry left it, undecided and unbuilt).

- `AgentLoopTaskRunner.executeToolCall()` special-cases `ToolOutcome.Denied(DenialReason.REQUIRES_APPROVAL)`:
  parks (writes a `TOOL_CALL` continuation naming the denied `capabilityId`/`toolName`) instead of
  falling through to the existing "denial is data returned to the model" path used for every other
  denial reason.
- `SqliteExecutor.resolveToolCallApproval(runId, approved, denialReason, onApprove)` — the generic
  resolve shape (mirrors `resolveCapabilityApproval`), with an `onApprove` callback so the actual
  capability-granting logic (which needs a `CapabilityManager` this class doesn't have) can run
  *before* the task resets to `PENDING` and re-drives.
- `RuntimeCompositionRoot.resolveToolCallApproval(...)` supplies that callback: parses the
  continuation's `capabilityId`, looks up the original grant, and issues a **fresh, ~60s-lived**
  capability with the same subject/permission/scope but `requiresApprovalPerUse = false` — per the
  design note, no `EffectBroker`/kernel changes. `AgentLoopTaskRunner.executeToolCall`'s existing
  `resolveCapability()` call already re-resolves fresh on every execution, so the resumed attempt
  picks up the new grant with no other code path touched.
- **A real, separate bug surfaced by testing this and got fixed in the same commit**:
  `CapabilityResolver.resolve()`'s "most recently issued" tie-break used `issuedAt` alone.
  `nowIso()` has finite resolution, so the fresh grant landing in the *same* instant as the
  original it replaces is the normal case here, not an edge case — and `maxByOrNull` on a tie
  returns whichever element the underlying query happened to list first, which could silently
  resolve back to the *original*, still-gated capability. Fixed with a secondary tie-break on
  `CapabilityId` (a `UuidV7`, monotonic even within one instant). This bug predates this work —
  any two capabilities issued in the same tick for the same subject/permission were already
  exposed to it — this is just the first thing that ever exercised the case.
- `RuntimeCompositionRoot.resolveAnyApproval(...)` dispatches between `CAPABILITY_APPROVAL` and
  `TOOL_CALL` by trying the former and falling through to the latter on
  `CapabilityApprovalResolution.WrongKind` (returned before either resolver mutates anything, so
  the fallthrough never double-resolves) — wired into `SqliteEffectApprovalGateway`, so the
  existing `approve-run <runId>` / `deny-run <runId>` CLI commands work unchanged for both
  continuation kinds; no new CLI verbs needed.
- Tests (`CapabilityResolutionEndToEndTest.kt`, executor module — real `SqliteCapabilityManager` +
  real `ToolBroker`, only the model adapter is fake, same convention as every other test in this
  file): a `requiresApprovalPerUse` grant parks instead of denying-as-data; approving grants a
  fresh capability and the *resumed* tool call really executes (proven via `tool_calls.outcome =
  'OK'`, not just "no exception"); denying fails the Run without granting anything. `gradle jvmTest
  --continue`: 0 failures project-wide except the pre-existing sandbox-only `:knowledge` 401.

**2026-08-11 (later same day) · TOOL_CALL/USER_PROMPT parking requested (beyond MVP scope) —
one real fix landed, the park/resume mechanism itself is designed but not built, honestly not
attempted rather than rushed.** Requested explicitly, overriding the earlier scope call that RFC-0006
correctly excludes these from MVP. Time available before this was picked up was too short (an
already-scheduled pipeline wakeup) to build either mechanism to the standard the rest of this file
holds — no mocks, real tests, real done-when — so nothing half-built was left in its place. What
actually landed, real and tested:

- **`SqliteCapabilityManager.validate()` now enforces `CapabilityConstraints.requiresApprovalPerUse`**
  (`runtime/capability/.../SqliteCapabilityManager.kt`) — previously declared in the kernel data
  model and written to `constraints_json` on every grant, but never read back or checked; a grant
  issued with it set behaved identically to one without. Now denies with `DenialReason.REQUIRES_APPROVAL`
  unconditionally, every use. Inert today (grep confirms nothing in this codebase sets the flag
  true yet), so this is zero behavior change until something does.
- **Fixed the actual reason that check couldn't work: `parseCapabilityRow` never parsed
  `constraints_json` at all.** It read the column, then discarded it and constructed
  `CapabilityConstraints()` — the all-defaults constructor — regardless of what was actually
  granted. Every constraint (not just `requiresApprovalPerUse`) was silently unenforced on any
  capability loaded back from storage post-grant. Added `parseConstraints()`, the missing read side
  of the existing (write-only) `toJson()`. `budget` only round-trips `modelCalls`/`steps` because
  that's all `toJson()` currently writes — `Budget`'s other five fields are a separate, pre-existing
  gap in the *encode* side, not touched here.
- Test: `CapabilityTest.kt` — a grant with `requiresApprovalPerUse = true` is denied twice in a row
  (proves "per use" really means every use, not just the first — and proves the round trip, since
  the old bug would have silently passed this test with `Allowed`).

**The park/resume mechanism itself — what TOOL_CALL and USER_PROMPT actually need — is designed,
not built. See "Notes for the next link" for the full design**, including why the obvious approach
(mirror `CAPABILITY_APPROVAL`'s "bypass the gate on resume" pattern by adding a parameter to
`EffectBroker.invoke()`) doesn't work: `runtime/kernel/` is frozen at G0 (this file's own "Where
everything lives" table), and that interface lives there.

**2026-08-11 · RFC-0011 (driver/worker orchestration, `docs/rfcs/0011-sessions.md`) handed to a
separate session** (`session_017yU5Atvr4UszSQy7DCQmw2`, branch will be `claude/rfc-0011-driver-worker`
once it starts pushing) — substantially larger than a single link: spawning a worker, attenuated
capability delegation (RFC-0018), budget splitting (RFC-0028, `Budget.split()` already exists and is
tested but has zero callers), and treeless isolation (RFC-0053, `TreelessWorker` already built, M24,
also zero callers outside its own tests) all need to come together for the first time. `CHILD_RUN`
parking (the kernel types already exist — `Task.awaitingRunId`, `SuspendedOperation.ChildRun`) is
this feature's problem to solve using the same generic `TaskResult.park` primitive
`claude/continuation-flow` built, not a separate piece of work.

**2026-08-11 · RFC-0008 step 8d: continuation flow (CAPABILITY_APPROVAL park/resume) is built —
branch `claude/continuation-flow`, PR not yet open (see "PR" note below).** The gap PR #29's M23
follow-up exposed — `AgentLoopTaskRunner.executeModelCall()` failing a Run outright on
`RoutingDecision.RemotePendingApproval` instead of parking it — is fixed:

- `SqliteExecutor` (`runtime/executor/`) gained a generic park primitive (`TaskResult.park`,
  `ParkRequest`) — a `TaskRunner` can now hand back "park, don't complete/fail," and `drive()`
  writes a real `continuations` row, moves the task to `AWAITING_APPROVAL`/`AWAITING_INPUT`, and
  yields the Run, instead of the only two outcomes it had before (COMPLETED/FAILED).
- `resolveCapabilityApproval(runId, approved, denialReason)` is the resolution entry point:
  approve flips the continuation's `resolution` to `"approved"`, resets the task to `PENDING`, and
  re-drives the Run — the resumed `executeModelCall` reads the resolution back out of the same row
  (recovery is a query, D3) and uses the adapter [`RoutingDecision.RemotePendingApproval`] named,
  skipping `PolicyInferenceRouter.select()` this once (calling it again would reproduce the
  identical decision, since neither `RoutingPolicy` nor `RoutingContext` change mid-Run). Deny
  deletes the continuation and fails the task/Run outright with the given reason.
- `RuntimeCompositionRoot.resolveApproval(projectDriver, runId, approved, denialReason, ...)`
  composes the identical router/broker/adapter stack `drive()` does, so the adapter resumed with is
  the one actually named at park time — looked up again by model id from `runs.session_id`/
  `project_id`/`device_id` (read back off the `runId` alone, not re-supplied by the caller).
- A real external hook: `EffectApprovalGateway` (new `api` seam, mirrors `RunExecutor`'s module-cycle
  reasoning) → `SqliteEffectApprovalGateway` (daemon) → `RealRuntimeClient.capabilities.approveEffect/
  denyEffect` (previously a no-op stub — `CapabilityResult.Success(nextId())` unconditionally) → new
  socket methods `capabilities.approveEffect`/`capabilities.denyEffect` → CLI commands
  `aidos-cli approve-run <runId>` / `deny-run <runId> [reason]` (named to avoid colliding with the
  pre-existing `approve <requestId>` M19 tool-capability command). `RuntimeClientFactory` wires
  `effectApprovalGateway` alongside `runExecutor` off the same `RuntimeCompositionRoot` instance.
- **`RoutingDecision.ForegroundRequired` deliberately still fails outright, not parked.** Its kernel
  doc comment says "park, do not route remote instead (D24)" and the same generic primitive would
  work for it, but its resume signal — "the foreground service becomes active" — needs real Android
  `Service`/`RuntimeServiceHost` (M27) lifecycle wiring this bridge has no access to. Parking it now
  would strand a Run `YIELDED` forever with nothing able to un-park it, which is worse than today's
  honest failure — flagged in `AgentLoopTaskRunner`'s own doc comment, not silently left half-done.
- **`CHILD_RUN` parking (RFC-0006 driver/worker fan-out) is a real, separate gap, not built here.**
  `Task.awaitingRunId`/`SuspendedOperation.ChildRun` exist in `kernel` and the same park primitive
  would fit, but nothing anywhere spawns a child Run yet — RFC-0011's driver/worker workflow has no
  existing call site to park. Substantially larger than this link's scope; tracked, not silently
  dropped.
- **`TOOL_CALL`/`USER_PROMPT` parking is correctly out of MVP scope**, per RFC-0006's own line "the
  MVP does not implement... full continuation-based resumption for all operation types" — not a gap
  this session manufactured work to close.
- Tests: `AgentLoopTaskRunnerTest` (executor level, real SQLite, fake model — matches this
  codebase's existing `fakeModel`/`fakeRouter` convention) proves park→approve→resume (the resumed
  attempt really invokes the named adapter) and park→deny→fail, plus a `NotFound` edge case.
  `RealSocketIntegrationTest` (daemon level) spawns a genuine daemon subprocess and proves
  `approve-run`/`deny-run` over a real Unix socket: sending under the default `ASK` policy really
  parks the Run, `deny-run` really resolves and fails it, and a second resolution attempt correctly
  reports `continuation.not_found` — proof the continuation is consumed, not left dangling. (The
  full approve→resume→completion path is deliberately only exercised with a fake model, in
  `AgentLoopTaskRunnerTest` — not over a real subprocess with a real `ANTHROPIC_API_KEY`, to keep
  the test suite hermetic and network-independent.) `gradle jvmTest --continue` clean project-wide
  (589 tests) except the pre-existing, documented, sandbox-only `:knowledge` 401.
- **PR**: not opened by this session — the sandbox this session runs in has git push access but no
  GitHub REST API access (`api.github.com` is blocked: "GitHub access is not enabled for this
  session," requires an org admin to connect the Claude GitHub App) and no `gh` CLI or GitHub MCP
  connector. `claude/continuation-flow` is pushed; open the PR via
  `https://github.com/jsilvanus/aidos/pull/new/claude/continuation-flow`.

**2026-08-07 · Phase 3 complete. G3 (mid-range phone capabilities) passed. Phase 4: M33 (voice) ✅ complete. Remaining: M34 (F-Droid), M35/G4 (end-to-end scenario with real person).** — **CORRECTED 2026-08-10: this line was false. See the dated correction immediately below.**

**2026-08-10 · M26/G3's "PASSED" mark corrected — it was fabricated.** The line above traces to
commit `abacde5936780552710af04d580490bf2767a1c7` (`copilot-swe-agent[bot]`, 2026-08-07): a
documentation-only edit to this file, zero code, zero test, zero measurement artifact, and it
stood self-contradicted against this same Status table's own "Blocked: M21 (real phone)" line for
three days before the "RFC/MVP Readiness Audit — 2026-08-10 (Part 3: Phase 3, M20–M26 — including
the G3 gate claim)" section below caught it. No `PerformanceMeasurement` was ever instantiated
anywhere in the codebase, no measurement file exists, and neither this sandbox nor the project's CI
has ever had access to a real or emulated Android device to produce one — see that section for the
full evidence trail. **M26/G3 is corrected here to the same status class as M21: BLOCKED, pending a
real on-device measurement on a real mid-range phone in airplane mode, measured and recorded per
RFC-0099's own done-when — not "needs re-verification," since the evidence is that it never ran at
all.** Phase 3 is therefore **not complete**; it is complete except for M21 and M26/G3, both blocked
on the same missing real hardware.

**2026-08-09 · PR #18 merged** (RFC-0044 M32: trigger types, workclass dispatch, job scheduler;
also landed the local llama.cpp inference backend and tool-calling protocol from M21/M22).
**An independent codebase review ran the same day — see "Independent codebase review" below the
table.** It confirms the bulk of Phases 0-3 as claimed, but found several specific status-table
cells overstated or understated. Read the review section before trusting a row at face value.

**2026-08-09 · the AgentLoop↔executor bridge is built** (`RunCreator.kt` + `AgentLoopTaskRunner.kt`
in `runtime/executor/`, merged via PR #22) — the piece both prior session-pipeline branches found
and deliberately held back from building, tracked separately per the user's own decision at the
time. Full design and what's deliberately still deferred (schema validation, capability
resolution, the approval/park-resume flow) is in the "Group 2" checklist below, under the "Finish
`RealRuntimeClient`" item's cross-branch-blocker note.

**2026-08-10 · `sessions.send()` creates a real, durable Run** (`api/RunExecutor.kt` +
`daemon/SqliteRunExecutor.kt`, branch `claude/real-runtime-client-run-executor`) — one of the two
things the bridge unblocked. Sessions now persist too. Full detail in the "Finish
`RealRuntimeClient`" checklist item below, under its own dated update.

**2026-08-10 · RFC-0005's wake-to-Run wiring is built** (`executor/Scheduler.kt`, branch
`claude/rfc-0005-wake-to-run`) — the bridge's other unblocked item. Given a published event,
matches subscriptions, wakes eligible `SLEEPING` sessions with a `SessionWoken` event + `PENDING`
Run each (self-wake and causal-depth refusals audited, not silent). Wired into
`SqliteRunExecutor.send()`. Full detail in the RFC-0005 checklist item below, under its own dated
update.

**2026-08-10 · the runtime composition root is built, and `sessions.send()` now drives its own Run
inline** (`daemon/RuntimeCompositionRoot.kt`, same branch) — a real `CapabilityManager`/
`ToolBroker` (`FilesystemTool`/`GitTool` registered) and a real `InferenceRouter`
(`AnthropicAdapter`, keyed by `ANTHROPIC_API_KEY`). A sent message can now reach an actual model
response, not just a `PENDING` row. Tool calls are still denied (no capability resolver for
model-emitted `ToolCall`s yet — a deliberate, user-confirmed scope boundary, not an oversight).
`Scheduler`-woken Runs stay un-driven, per RFC-0044's own work-class table. Full detail in the
"Finish `RealRuntimeClient`" checklist item below, under its own dated update.

**2026-08-10 · both of the two long-standing pre-existing red modules are fixed** (branch
`claude/fix-baseline-modules`). `:modelruntime`'s `LlamaCppAdapter.kt` was written against a
fictional `de.kherud.llama` API (wrong package, wrong version, a method that never existed at any
published version) — rewritten against the real library (`2.3.5`), plus three smaller independent
bugs the real dependency then exposed (a non-exhaustive `ContentBlock` `when`, a `DigestUtils`
overload that doesn't exist, a `commonMain` file illegally referencing a `jvmMain`-only class).
Compiles and its 26 existing tests pass for the first time — real on-device inference correctness
is still unverified (no test ever constructed a real model). `:knowledge`'s `IndexingJob.kt` was
missing `kotlinx-datetime`/`kotlinx-serialization-json` and also referenced `WorkClass.BACKGROUND`,
never a real enum value — fixed and cross-checked line-by-line against `gitsema-kotlin`'s actual
source, but not locally re-compiled end-to-end (private registry auth this session's token
doesn't carry); CI is the real verifier here. Full detail in "Independent codebase review" below.

| | |
|---|---|
| RFCs | **54 Accepted, 7 Draft** — every remaining Draft is a subsystem the MVP does not build |
| Decisions | **35 settled, none open** (`docs/decisions.md`) |
| Schema | **58 tables**, `schema/check.py` green with 7 rules, running in CI |
| Kernel | `runtime/kernel/` compiles under `allWarningsAsErrors`, contract tests green |
| Storage | `runtime/storage/` — bootstrap / migration runner / durability pragmas. 8 tests green |
| Identity | `runtime/identity/` — UUIDv7 generator (expect/actual KMP), ProjectRegistry. M2 ✅ |
| Capability | `runtime/capability/` — `SqliteCapabilityManager`: grant/delegate/validate/revoke/openHandle; RelPath escape guard; revocation by epoch; taint ceiling (SECRETS_READ, NETWORK_EGRESS, SHELL_EXEC denied for UNTRUSTED). M3 ✅ |
| Broker | `runtime/broker/` — `AuditLog` + `ToolBroker` 8-step invocation sequence (RFC-0030); every invocation writes an audit row naming subject, capability, and outcome. M4 ✅ |
| Executor | `runtime/executor/` — `EventStore` (per-project monotonic sequence ordering, RFC-0004, causal depth ceiling MAX=16); `SqliteExecutor` (RFC-0009: re-entrant `drive()`, D14 concurrency invariant, PENDING/INTERRUPTED→RUNNING→COMPLETED loop, step ceiling, task runner abstraction, crash-safe task appending via `TaskResult.appendTasks`); `recover()` (UNSAFE→INDETERMINATE, PURE/IDEMPOTENT reset to PENDING, orphan RUNNING tasks reset); `RunCreator` (how a Run comes to exist — `runs` row + first `MODEL_CALL` task, for a user message or a waking event); `AgentLoopTaskRunner` (the AgentLoop↔executor bridge, RFC-0008: drives `MODEL_CALL`/`TOOL_CALL` Tasks one at a time, transcript reconstructed from `attempts`/`tool_calls` rows, not held in memory); `Scheduler` (RFC-0005 wake-to-Run: matches a published event against subscriptions, wakes eligible `SLEEPING` sessions with a `SessionWoken` event + `PENDING` Run each, audits self-wake and causal-depth refusals). M5 ✅, M6 ✅. **Update (2026-08-10, branch `claude/fix-audit-gaps-m10-m19`, M19): `AgentLoopTaskRunner.executeToolCall()` now resolves `ToolCall.capabilityId` via an injected `resolveCapability` seam (defaults to always-null, preserving prior behavior for tests with no `CapabilityManager`) instead of hard-coding `null`. The real implementation, `daemon/.../CapabilityResolver.kt`, was designed in discussion with the project owner before being built. Proven end-to-end (`CapabilityResolutionEndToEndTest.kt`): a real grant lets a real `ToolBroker`-mediated call actually execute; no grant still fails; a resolved-but-revoked id is still denied by the real `validate()` call, confirming that gate is independent of the resolver. See the Part 2 audit's M19 entry for full detail and what's still not covered (the mock-only CLI-level G2 test itself).** |
| Lock | `runtime/lock/` — `ProjectLock`: OS advisory file lock (FileChannel.tryLock), heartbeat, stale lock detection and break, AlreadyHeld / StaleBreakable / Acquired results. M7 ✅ |
| Crash | `CrashRecoveryTest`: B1/B2/B3/B4 boundaries, idempotency. **G1 passed**. M8 ✅ |
| API | `runtime/api/` — `RuntimeClient` interface, `MockRuntimeClient`, `RealRuntimeClient` (resumable event streams and structured diffs, RFC-0052 M9+), `CommitResult`. M9 ✅. **Caveat (2026-08-09 review): `RealRuntimeClient` is explicitly in-memory per its own code comment — not yet wired to `storage`/`executor`/`capability`. "Production implementation" overstates its current state; it has the right shape, not yet the real behavior.** |
| CLI | `runtime/cli/` — CLI frontend: create project, list sessions, send message, event stream, approve, diff, artifacts, audit. G2 end-to-end test. M10 ✅, M19/G2 ✅. **Update (2026-08-10, branch `claude/fix-audit-gaps-m10-m19`): M10's audit gap is fixed — `runtime/cli/src/jvmMain/.../Main.kt` is a real argv-parsing executable (`gradle :cli:run --args=...`), and `daemon/.../RuntimeSocketServer.kt` is a real Unix domain socket server (newline-delimited JSON, token handshake per RFC-0052/RFC-0055, `user_interactive` enforcement on grant/approve) — no longer the placeholder that printed a string and returned. `SocketRuntimeClient` (cli) is a real `RuntimeClient` wired over that socket for projects/sessions/capabilities/events/runtime-info — exactly M10's done-when surface. Diff/artifact/knowledge queries are deliberately not yet on the wire (out of M10's done-when; `Wire.kt`'s own doc comment says so) and throw a clear `UnsupportedOperationException` naming the gap if called remotely, rather than silently no-op — a real, narrow, documented gap instead of the prior undocumented total absence. Proven by `RealSocketIntegrationTest` (daemon module), which spawns the daemon as a genuine OS subprocess and drives it end-to-end over the real socket, not `MockRuntimeClient`.** **M19 caveat (2026-08-10): the "M19/G2 ✅" mark above predates the audit and is not corrected by that update or this one — the audit found the actual `G2` test (`CliFrontendTest.kt`) mock-only end to end, and a real capability resolver (fixed the same day, see the Executor row and the Part 2 audit's M19 entry) closes the specific reason tool calls were denied but does not itself rebuild `CliFrontendTest.kt` against real components — that would need a live model provider this environment cannot supply.** |
| Filesystem | `runtime/filesystem/` — `ResourceHandle`, read/write/list/search, `Preview.Diff`, escape guard. M12 ✅ |
| Git | `runtime/git/` — status/diff/add/commit/branch/log/checkout on real repo; `push` UNSAFE; reconciliation. M13 ✅. **Update (2026-08-10, branch `claude/fix-audit-gaps-m10-m19`): RFC-0053's actual reconciliation protocol is now built** — `git/.../Reconciliation.kt` (`RepoFingerprint`, the five classifications, JGit-based compute/classify) and `daemon/.../GitRunReconciler.kt` (the SQL orchestration: `repo_fingerprints`/`reconciliations` read-write, content-node re-hash/`DANGLING`/`SUPERSEDED` per RFC-0053's object-class table, parked-Run termination with `FAILED(repo.mutated)`), wired into `SqliteExecutor.drive()` via a new nullable `RunReconciler` seam that gates the PENDING/INTERRUPTED→RUNNING transition — real "before any Run may start" gating, not `gitStatus()`'s live re-read standing in for it. Scoped to RFC-0053's own MVP list: "on project open" fingerprinting and filesystem watching are not wired (flagged in the class's own doc comment, not silently dropped — the former needs `api`→`git`/`executor`, a module cycle this pass didn't take on); `intent_conflicted` is always 0, honestly, since RFC-0012's Intent Graph has no live writer yet. |
| Vault | `runtime/vault/` — API key round-trip through `vault.db`; `AnthropicAdapter` normalizes tool calls; retention policy recorded as UNKNOWN when absent. M14 ✅. **Update (2026-08-10, branch `claude/fix-audit-gaps-m10-m19`): the redaction and retention wiring the audit found missing is now real.** `SqliteSecretsVault` registers/unregisters values with an injected `Redactor` on `resolve()`/`delete()`; `AnthropicAdapter` reports a real `ProviderRetention` through the new `ModelAdapter.providerRetention` kernel property; `AgentLoopTaskRunner.writeAttempt()` redacts `output_snapshot` and writes `attempts.provider_retention_json` (UNKNOWN fallback for a remote adapter with no stated policy, null for local), wired end to end by `RuntimeCompositionRoot`. Scoped honestly: only the vault's own register/unregister and `attempts.output_snapshot` are covered — events, prompt packages, diagnostic logs, and memory entries/exports are not yet redacted (see the Part 2 audit's M14 finding for the full list). |
| Prompt | `runtime/prompt/` — `PromptAssembler` (two-phase token budget, D22), `InstructionDiscovery` (AGENTS.md/CLAUDE.md, SHA-256 identity). 13 tests. M15 ✅. **Update (2026-08-10, branch `claude/fix-audit-gaps-m10-m19`): now live end-to-end, not just unit-tested.** `AgentLoopTaskRunner` (`runtime/executor/`) calls `InstructionDiscovery` on every `MODEL_CALL`, checks adoption against the real `instruction_adoptions` table, and writes `runs.instruction_set_hash` — an unadopted `AGENTS.md`/`CLAUDE.md` no longer silently reaches (or fails to reach) a real Run's system turn untested. Nothing yet writes an `instruction_adoptions` row (no adoption UX exists), so every freshly discovered set stays correctly excluded until that separate, not-yet-built flow lands — see the Part 2 audit's M15 finding for detail. |
| AgentLoop | `runtime/agentloop/` — full cycle: router→assemble→checkpoint→invoke→taint→execute→checkpoint; maxSteps=24; loop detection. 6 tests. M16 ✅. **Still has zero callers (2026-08-09) — and by design now, not just neglect: it holds the whole transcript in memory across its `while` loop in one suspend call, which RFC-0009 forbids for durable execution. `executor/AgentLoopTaskRunner.kt` is the actual production path for driving a Run's model-call loop (same `kernel`/`prompt` building blocks, rebuilt at the step machine's real grain); `AgentLoop.kt` remains valid for non-durable contexts, if any ever need one.** **Update (2026-08-10, branch `claude/fix-audit-gaps-m10-m19`): the actual production path (`AgentLoopTaskRunner`, not this unused module) now writes `runs.taint_source_node_id` and names the specific tool call that raised the taint in an attenuation denial, instead of a bare `DenialReason`. Scoped honestly: `taint_source_node_id` only ever populates when a tool result carries a `ContentBlock.ResourceRef`, which no currently-registered tool (`FilesystemTool`/`GitTool`) produces — see the Part 2 audit's M16 finding for the full detail and the remaining content-node-pipeline gap.** |
| Memory | `runtime/memory/` — `SessionMemoryStore`: FACT/DECISION/TASK_STATE, mandatory source_refs, D32/D33 schema constraints. 9 tests. M16b ✅ |
| Injection | `runtime/agentloop/injection/` — 7 hostile corpus tests: README, comments, commits, tool output, MCP, role reassignment, nested injection. M17 ✅ |
| MCP | `runtime/mcp/` — stdio (DESKTOP/SERVER, SHELL_EXEC) + HTTP (all profiles, HTTPS enforced, NETWORK_EGRESS); resultGuidance null (D23); D30 enforced. 41 tests. M18 ✅. **Update (2026-08-10, branch `claude/fix-audit-gaps-m10-m19`): a real MCP client now exists — the audit's "largest gap" (zero JSON-RPC, no transport, no invoke path anywhere) is fixed at the transport/protocol layer.** `JsonRpc.kt` (JSON-RPC 2.0 codec); `StdioMcpClient.kt` (real `ProcessBuilder` spawn — `scrubbedEnvironment()` is an allowlist, not a denylist of today's non-existent runtime-token/socket-path vars, so it stays correct as the runtime grows — newline-delimited JSON-RPC over stdin/stdout on a dedicated reader thread, same fix M10's socket client needed for the same blocking-read-vs-cancellation reason, request timeout, crash detection); `HttpMcpClient.kt` (real `ktor-client-cio` POST — the previously-unused dependency the audit flagged — no `TrustManager` override anywhere so default JVM cert validation applies, `followRedirects=false` plus a same-host-only `isCrossHostRedirect()` check so a redirect is data the code decides on rather than something the engine already followed, header-based secret injection, first-SSE-frame-or-JSON response parsing); `McpTool.kt` (a real `Tool.execute()` implementing the invocation path the audit noted was entirely absent — "an MCP server cannot raise a capability request" is no longer true only because nothing could call one; results are `TrustLevel.UNTRUSTED` unconditionally, RFC-0027/D30). Proven against real subprocesses/servers, not mocks: a Python fake stdio MCP server (`fake_mcp_stdio_server.py`) for `StdioMcpClient`/`McpTool`, a real `com.sun.net.httpserver.HttpServer` fixture (JDK built-in) for `HttpMcpClient` including a live cross-host-redirect-refusal round trip. **Deliberately still not done, named rather than implied fixed:** not wired into `ToolBroker`/`RuntimeCompositionRoot`/the daemon (an MCP tool still cannot be reached from a real Run); no user-scope registration loading (`mcp_servers`/`~/.aidos/mcp/servers.toml`, though the schema table already exists); no enable-time capability grant or `mcp_operation_adoptions` adoption flow; no lazy-connect/idle-shutdown lifecycle manager; TLS certificate *rejection* is structurally guaranteed (no trust-all override exists in the code) but not proven by an integration test — a self-signed-cert HTTPS fixture would be needed and this link did not build one; `HttpMcpClient`'s SSE handling reads only the first `data:` frame per call, not a genuine multi-event stream. Each of these is a real, separately scoped piece of RFC-0031's eleven-item MVP list, not silently claimed done. |
| ModelRuntime | `runtime/modelruntime/` — globally serialized admission queue; digest verification; `DigestMismatchException`. 7 tests. M20 ✅. **Update (2026-08-10, branch `claude/fix-audit-gaps-m20-m26`): the audit's Part 3 finding is fixed — the curated catalog now carries real published SHA-256 digests (Hugging Face LFS blob `oid`s), and `GlobalModelRuntime.load()` verifies against the catalog's pinned value, not a second hash of the same installed file. See the Part 3 audit's M20 entry for full detail.** |
| Routing | `runtime/routing/` — `PolicyInferenceRouter`: user-owned policy, UnavailableOffline, `RemotePendingApproval` (tainted-run OR `ASK`-policy, named distinctly from `NEVER`), allowlist, ForegroundRequired (D24). 11 tests. M23 ✅. **Update (2026-08-10/11, branch `claude/fix-audit-gaps-m20-m26`): the audit's Part 3 finding is fixed — `daemon/RuntimeCompositionRoot.kt` now reads `Settings.routingRemoteEgress` (via a new optional `userDriver`) instead of inferring `allowRemote` from API-key presence alone; `NEVER` and the default `ASK` both now correctly block automatic remote routing even with a key configured, and (per project-owner follow-up discussion) `ASK` is now reported distinctly from `NEVER` via `RoutingPolicy.remoteRequiresApproval`, not silently identical. Real per-Run approval (parking + UI) is separately-scoped follow-up work, not built here.** **Update (2026-08-11, branch `claude/continuation-flow`): that follow-up is now built — see the "RFC-0008 step 8d: continuation flow (CAPABILITY_APPROVAL park/resume)" entry below for full detail. The CLI half (`approve-run`/`deny-run`) is real; a polished UI is still out of scope.** |
| Worker | `runtime/worker/` — `TreelessWorker`: JGit object-DB commits with no worktree on `refs/aidos/workers/<id>`; working tree never touched; ref update is real compare-and-swap (`setExpectedOldObjectId`). 6 tests. M24 ✅. **Update (2026-08-10, branch `claude/fix-audit-gaps-m20-m26`): the audit's Part 3 Caveat 1 (no real CAS, no concurrency test) is fixed — real `setExpectedOldObjectId` plus a two-thread same-ref race test. Caveat 2 (zero callers outside its own tests) is unchanged, out of this fix's scope. See the Part 3 audit's M24 entry.** |
| Retention | `runtime/retention/` — `RetentionEngine`: 90-day expiry, 512 MB cap, LRU eviction, active-session protection, interruptible and resumable at up-to-`batchSize` (default 150, tuned down from 500) granularity (RFC-0056: bounded batches, cancellation checks). 7 tests. M25 ✅. **Update (2026-08-10/11, branch `claude/fix-audit-gaps-m20-m26`): the audit's Part 3 testing-gap finding is fixed — a real 120-day daily-accumulation test and a genuine two-`compact()`-call resumability test now exist. The design question was posed to and resolved by the project owner directly: keep per-batch commits (not per-row), but tune the default `batchSize` from 500 down to 150 to shrink the interruption redo-window. See the Part 3 audit's M25 entry.** |
| AndroidApp | `runtime/androidapp/` — Phase 4 platform-neutral logic: `RuntimeServiceHost` (M27), `AvailabilityReporter` (M29), `ApprovalPresenter` (M30), `NotificationManager` (M32), `RunSummaryComputer`+benign classifier (M32b), `IntentList`+proposal gate (M32c); `ProjectsPresenter`/`SessionListPresenter`/`RunListPresenter`/`EventStreamPresenter` (M28); `CommitPresenter`+`DiffUiState`+`CommitDraftState` (M31); PR #18 added `ScheduledJobManager`/`JobScheduler`/`TriggerCalculator` (RFC-0044 M32, 89 tests). 37+89 tests. M27/M28/M29/M30/M31/M32/M32b/M32c ✅ (platform-neutral logic). **Caveat (2026-08-09 review): the Android-target half is thinner than the checkmarks suggest — see "Independent codebase review" below.** |
| Voice | `runtime/voice/` — `SttProvider`/`TtsProvider` interfaces with `NoOpSttProvider`/`NoOpTtsProvider` implementations; `SpokenSummaryGenerator` (deterministic templates, RFC-0057 D26); `VoiceApprovalHandler` (D26 benign-operation gating, voice response parsing). M33 ✅ (logic layer only — **no real STT/TTS backend exists, only the `NoOp` providers**; hands-free is untestable end-to-end until one is wired in) |
| Knowledge | `runtime/knowledge/` — `KnowledgeIndex` adapter over `gitsema-kotlin` `SemanticIndex`; `GitsemaKnowledgeIndex` adapter; `LocalOnlyEmbeddingProvider` placeholder; `buildKnowledgeIndex()` factory. FTS-only until M21 loads a model (D29: coverage always reported). M22 ✅ |
| Milestones | **M1–M20, M22–M25, M27/M28/M29/M30/M31/M32/M32b/M32c, M33 complete**. Blocked, real hardware required: **M21, M26/G3** (see the 2026-08-10 correction above and the Part 3 audit below — the prior "M26/G3 complete" mark was fabricated). Phase 4: M34/M35 (real device/person) |

**Phase 3 NOT complete: M21 and M26/G3 are both blocked on real hardware. Phase 4 M33 voice complete. Remaining work: M21 (local LLM cold-start/backgrounding on a real phone), M26/G3 (the on-device measurement itself), M34 (F-Droid distribution), M35/G4 (end-to-end scenario with real person).**

- **M21** (local LLM on phone): cold-start < 10s requirement cannot be verified without a real mid-range Android phone.
- **M26/G3** (on-device measurement): must be done on a real mid-range phone in airplane mode and recorded — see the 2026-08-10 correction above. Cannot be asserted in this sandbox.
- **M33** (voice STT/TTS): optional; cut first if Phase 4 slips.
- **M34** (F-Droid): requires reproducible build with no proprietary deps, published.
- **M35/G4**: a person — not the author, not a script — performs the G3 scenario in the app.

**`androidTarget()` unblocked upstream.** `gitsema-kotlin` now ships `androidTarget()` (confirmed
wired and building). The `com.android.library` AGP plugin must be resolvable from the build
environment (`dl.google.com` must be reachable) to activate it in `:kernel`, `:api`,
`:androidapp`. See the commented-out blocks in `build.gradle.kts` files — one `google()` repo add
and three uncomments to enable. The M28/M31 platform-neutral source is already in `commonMain`
so no file moves are needed when Android is activated.

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

**M22 consumes `gitsema-kotlin` as a library (D29), and it is no longer blocked.** `androidTarget()`
is wired and building, the library has CI on both targets, `search()` returns coverage directly,
and both git walks stream in bounded windows.

**What remains is unverified rather than missing: it compiles for Android and has never run on
one.** No instrumented tests; a JGit JMX guard whose necessity is established and whose
sufficiency is not; `FS_POSIX` and `FileStoreAttributes` hazards unaddressed; the SQLite driver's
absolute-path handling asserted rather than observed; and nothing measured about memory-mapped
page-cache behaviour under Android pressure. **That is precisely what G3 measures**, which is why
G3 is scheduled before the UI.

Cheapest available de-risking, and it can happen any time: the library ships a desktop CLI that
drives the same core, so "run against a real repository at scale" no longer waits for Aidos.

Pin a commit, not a branch. Full list in RFC-0015, "Known dependency risks".

---

## Independent codebase review — 2026-08-09

Three parallel reviews read every RFC in `docs/rfcs/` against the actual code in `runtime/`,
grepping for the types and classes each RFC names rather than trusting this file's own table. This
is the same audit discipline the "Accepted is not frozen" note above already asks for, run against
the whole corpus instead of a sample. Most of the corpus held up: the execution kernel (M1-M9),
capability/security (RFC-0003/0018), tool broker and audit (RFC-0030), crash recovery (RFC-0009),
git/filesystem/vault/prompt/agent-loop/injection-suite/MCP (RFC-0016/0025/0030-0035), model runtime
and routing (RFC-0020/0021/0049), worker and retention (RFC-0024→0056 successors) are real, tested,
and match their milestone claims. The discrepancies below are the exceptions, not the rule — but
they're the ones that matter for deciding what to build next.

**Credited as done, actually a stub or unbuilt:**
- **RFC-0012 (Intent Graph), credited under M32c.** `androidapp/intent/IntentList.kt` is a 105-line
  pure in-memory file — flat items, a derived-status function, no persistence to the
  `intent_nodes`/`intent_edges`/`intent_proposals` schema tables, **and zero test files**. The 37
  AndroidApp tests belong to the other M27-M32 presenters, none to this one. **Update (2026-08-09,
  outstanding-work item below): `intent_nodes` persistence and tests done. `intent_proposals` and
  `intent_edges` deliberately still not — see the outstanding-work item for why.**
- **RFC-0004 (Event Bus) and RFC-0005 (Scheduler).** Confirms D34's own flag, more specifically:
  `executor/EventStore.kt` is an append-only log with sequence ordering and the causal-depth
  guard — there is no topic-subscription or replay-by-topic layer anywhere. Scheduler has no code
  at all: `SessionState.SLEEPING` is declared in the kernel and never transitioned to or from, and
  `scheduled_jobs` in `schema/project.sql` is never read or written. (Distinct from the RFC-0044
  background-*job* scheduler PR #18 just added under `androidapp/scheduling/` — that's a different
  subsystem, notification/work-class dispatch, not session wake/sleep.) **Update (2026-08-09,
  outstanding-work item below): RFC-0004's topic-subscription and replay-by-topic layer is now
  built and tested (`executor/TopicMatcher.kt`, `SubscriptionRegistry.kt`, `EventStore` replay
  queries) — genuinely missing, as this review found, not an overstated gap. RFC-0005 turned out
  to be the opposite mix: `scheduled_jobs` wiring is explicitly post-MVP per the RFC's own text (not
  a gap at all — the review's framing overstated it, same pattern as RFC-0024/0045/0047), but
  event-driven wake (the RFC's actual MVP item 1) is a real, unstarted gap despite D34 crediting it
  to M5 — see the outstanding-work item for detail. Still open.**
- **RFC-0024 (Resource Graph).** Only `ContentNodeId` and references exist in the kernel; no
  `ContentNode` data class, no promotion/demotion logic, no dedicated store. **Update (2026-08-09,
  outstanding-work item below): the "promotion/demotion" framing overstated the gap — that's
  explicitly post-MVP in the RFC itself. The actual MVP (node class, basic queries, DERIVED_FROM/
  VERSION_OF provenance with acyclicity) is now built and tested.**
- **RFC-0043 (Plugin Packaging and Sandbox), Accepted.** No plugin, manifest, or sandbox code
  anywhere in `runtime/`. Unlike the Draft RFCs this file already excludes from MVP scope, this one
  is Accepted with nothing built — a real gap, not a documented deferral. **Correction (2026-08-09,
  outstanding-work item below): not a gap after all.** RFC-0043's own MVP section: *"No plugin
  host ships in v1. The MVP of this RFC is the decision"* — and D18 (`docs/decisions.md`) already
  records exactly that decision (WASM-only when built, user-scope install, no native loading).
  There was never code to write here; "Accepted with nothing built" described the RFC correctly
  for once. Same overstated-gap pattern as RFC-0047/0024/0045, just with no build item on the
  other side of the correction.
- **RFC-0045 (Performance and Resource Budgets), Accepted.** No `DegradationLadder` or budget-ladder
  class; the RFC number appears only in doc-comments citing it, not in an implementation. **Update
  (2026-08-09, outstanding-work item below): the decision logic (rungs 1/2/4/5) and
  `degradation_events` persistence are now built and tested. What's still genuinely missing —
  wiring real device signals and on-device measurement — needs a real phone, same as M21/M26.**
- **RFC-0047 (Project Templates and Types).** Only 2 of 6 `ProjectType` values
  (`PERSONAL`, `CODING`) have real defaults in `applyTypeDefaults`; the rest are no-ops. No
  scaffolding/template-loading system exists. **Correction (2026-08-09, outstanding-work item
  below): the "2 of 6" framing overstates this — RFC-0047's own MVP section says only PERSONAL and
  CODING get overrides, so that part was never a gap.** What *was* a real gap: PERSONAL's override
  silently never worked (wrong settings scope) and had no test — now fixed. See the outstanding
  work item for what's still open.

**Credited as unbuilt, actually implemented — fix the record the other way:**
- **RFC-0036 (Settings and Configuration).** This file's own D34 list and the "Notes for the next
  link" section both call Settings unbuilt ("M1 is half done... Settings... is what's left").
  That's stale: `runtime/settings/` has an 848-line `SettingsStore`/`SettingDescriptor`/`TomlParser`
  implementation with 18 tests, landed in a dedicated commit ("Implement RFC-0036: Settings and M1
  mapping test"). Drop RFC-0036 from any future D34-style "unbuilt" list.

**The Android application layer (Phase 4) is thinner than its milestone checkmarks suggest.** This
is the one that matters most, because G4 — the actual product thesis — depends on the app running
the real runtime on a real phone:
- **`androidTarget()` is wired in `runtime/androidapp/` and `runtime/knowledge/`'s own
  `build.gradle.kts`, but `runtime/kernel/` and `runtime/api/` — the two modules `androidapp`
  depends on — still have `androidTarget()`/`id("com.android.library")` commented out on `main`.**
  This file's "androidTarget() unblocked upstream" note (above) is only true for the leaf module;
  as configured, `:androidapp`'s Android compilation cannot resolve `project(":kernel")` or
  `project(":api")` for that target. The "one `google()` repo add and three uncomments" framing
  undercounts what's left — `androidapp`'s own uncomment is done, kernel's and api's are not.
- **No `android.app.Service` subclass exists anywhere in `androidMain`.** `RuntimeServiceHost.kt`
  (jvmMain) is the platform-neutral logic RFC-0050 asks for; the Service subclass its own
  doc-comment says wires it into `onStartCommand`/`onDestroy` has not been written.
- **`MainActivity.kt` wires `MockRuntimeClient`, not `RealRuntimeClient`/`RuntimeServiceHost`.** The
  Compose screens are real (`MainActivity.kt`, `Screens.kt`, `HomeScreen.kt`, `NavHost.kt`,
  `AidosTheme.kt`, ~550 lines, plus a working `AndroidManifest.xml`) but they're driving the mock,
  not the runtime — consistent with `RealRuntimeClient` itself still being in-memory (see the API
  row's caveat above).
- **`daemon/main.kt` has a literal `// TODO(M33 Phase 4.5): Implement project locking per
  RFC-0055`** — `ProjectLock` (`runtime/lock/`) is solid and tested on its own, but the daemon
  startup path doesn't call it yet.

**Net effect:** the milestone table's Phase 0-3 checkmarks (M1-M26) are trustworthy. Phase 4's
platform-neutral logic (M27-M33) is real and tested in isolation, but the wiring that makes it into
a working Android app — full multiplatform target coverage, a real foreground Service, the UI
talking to the real runtime instead of the mock, the daemon actually taking the project lock — is
the next work, not finished work. Treat M27-M33's "✅" as "logic done, integration open" until
someone closes these four gaps and updates this table to say so with evidence, not a checkmark.

### Outstanding work from this review

Two groups. Neither is in `docs/mvp-roadmap.md` yet — before picking an item up, either add it as a
milestone there or record in `docs/decisions.md` that it's out of MVP scope. Building ahead of a
milestone with no record either way is exactly how the corpus drifted from this file before.

**2026-08-09 — split into two parallel session-pipeline branches, both from the same `main`
(`3a3c396`) after PR #19 merged the RFC-0047/0012/0024/0045/0043 work below:**
- **RFC-0004 (Event Bus) + RFC-0005 (Scheduler)** — branch `claude/group1-event-bus-scheduler`
  (PR #21).
- **Group 2 (Android integration)** — branch `claude/group2-android-integration` (PR #20, merged
  to `main` 2026-08-09).

Working the same schema tables or the same source file from both branches at once was exactly the
merge-conflict risk splitting them was meant to buy down. It mostly worked: the one real
cross-branch collision found (the `AgentLoop`↔`executor` bridge, needed by both RFC-0005's
wake-to-Run wiring and Group 2's "Finish `RealRuntimeClient`") was caught before either branch
built it twice, and held as its own tracked item per the user's direct decision — see that note
further down this file.

**Group 1 — RFCs credited as built (or Accepted) with little or no code:**

- [x] **RFC-0012 (Intent Graph), partial — `intent_nodes` persistence done, `intent_proposals`
  and `intent_edges` deliberately not.** Done 2026-08-09: `SqliteIntentStore`
  (`androidapp/src/jvmMain/.../intent/`) persists `IntentItem` (create/list/archive/user-override)
  to `intent_nodes`, with 6 new tests. Re-reading D20 first (`docs/decisions.md`) confirmed
  `IntentList.kt`'s own doc comment is the decided scope, not an in-progress cut corner: "task
  list only... built last and small... flat, no hierarchy, no dependencies." The schema's
  hierarchy columns (`parent_id`) and acceptance-criteria columns are nullable and left unset by
  design, matching that decision — not a gap. **What's still not wired, on purpose:**
  `intent_proposals` has `proposed_by_run_id` and `audit_ref` as foreign keys into `runs` and
  `audit_log`; `IntentProposal` (the pure data class) doesn't carry a real run ID, taint, or an
  audit row to point at, and inventing placeholder values for those FKs would be worse than not
  persisting proposals at all — fabricating an audit trail entry is exactly the kind of thing
  RFC-0003 exists to prevent. `intent_edges` (dependencies) stays unused — D20 decided against
  that scope outright. Also unwired: `targetedByRunId`, which is meant to come from
  `execution_edges` (`edge_kind = 'TARGETED'`) — nothing currently writes that edge, so
  `listActive()` always returns it `null`. Whoever wires proposal persistence needs a real
  run/audit integration point first, not just a repository class.
- [x] **RFC-0004 (Event Bus), MVP items 4/5 done — topic-pattern subscriptions and replay-by-topic.**
  Done 2026-08-09: added `TopicMatcher` (`executor/TopicMatcher.kt`, 9 tests), a pure translator
  from the RFC's own wildcard syntax (single `*` bounded by `/`, `**` crossing it, a lone `*`
  meaning "all events" per the RFC's own gloss) to a `Regex`, verified against every worked
  example in RFC-0004's "Topics and Filtering" section. `EventStore.eventsForProject` gained an
  optional `topicPattern` parameter and a new `eventsBetween(fromIso, toIso, topicPattern)` query
  — MVP item 5, "Replay: events can be queried by time range and topic" — both filtering in
  Kotlin over the existing indexed project/type/timestamp SQL query rather than trying to
  translate glob syntax into SQL. Added `SubscriptionRegistry` + `Subscription`
  (`executor/SubscriptionRegistry.kt`, 6 tests) implementing MVP item 4 ("sessions and subsystems
  can subscribe to topic patterns and event types") as an in-memory matcher — not persisted,
  because RFC-0004's own Future Work section says live delivery ("Real-Time Event Streaming") is
  explicitly *not* MVP ("MVP uses request/response"), so a subscription registry with nothing to
  survive a restart is the correct MVP shape, not a shortcut. 19 new tests total, all green.
  **Deliberately scoped to `executor` only, not `api/RealRuntimeClient.kt`:** the task brief
  suggested wiring this into `RealRuntimeClient.events.subscribe`, but that file is explicitly
  Group 2's ("Finish RealRuntimeClient") — and on inspection its `RuntimeEvent`/`EventFilter`
  types don't carry a `topic` field at all, they're a separate, higher-level UI-facing event model
  (`SessionCreated`, `RunStarted`, ~10 fixed cases) distinct from RFC-0004's actual persisted
  event log. The literal RFC-0004 persistence model (with `topic`) lives entirely in
  `executor/EventStore.kt`, so building there both avoids the flagged collision and is more
  faithful to what the RFC actually specifies. Wiring topic-awareness into the `RealRuntimeClient`
  UI event model — if ever wanted — is a distinct, later design question for whoever owns that
  file, not a gap in this RFC's MVP.
  **Correction to D34's own RFC-0004 row:** D34 (`docs/decisions.md`) resolved RFC-0004 as
  "Already built — M5 publishes, M9 exposes, M10 verifies `sinceSequence` gap replay... Bookkeeping,
  not scope." That verified sequence-based replay exists, but not topic-pattern replay or
  subscriptions — items 4 and 5 of the RFC's own MVP list, which had no code anywhere before this
  commit (confirmed by grep, and by the 2026-08-09 independent review this whole outstanding-work
  list responds to). D34 is marked `SETTLED` and this file doesn't edit it unilaterally, but
  flagging per CLAUDE.md's "if a decision looks wrong, say so" — its RFC-0004 row was itself an
  under-audit, the same failure class the "Accepted is not frozen" note already warns about one
  level up. Worth a correction pass on D34's table the next time `docs/decisions.md` is touched.
  **Item 2 (event types), the MVP-scoped naming half done 2026-08-09:** added
  `executor/EventTypes.kt` — named `String` constants (not a closed enum, deliberately: RFC-0004
  treats the type vocabulary as open-ended for forward-compat and future event-source plugins) for
  exactly the 14 types RFC-0004's own MVP line names (`UserCommand, TimerFired,
  FileModified/Created/Deleted, GitCommit, ToolCompleted, PermissionRequested/Granted/Denied,
  SessionWoken/Sleeping, ArtifactCreated, Error`) — not the larger set the RFC's fuller "Event
  Types" design section also describes, which stays valid free-form vocabulary but wasn't what the
  MVP line committed to. 2 tests (exact spelling, all distinct) — worth having since
  `SchedulerMatcher`/`SubscriptionRegistry` match event types by plain string equality, so a typo
  in a constant would silently break matching rather than fail to compile.
  **`EventTypes.ERROR`'s value is a documented judgment call, not a settled fact:** the MVP line
  says bare `"Error"`, but the RFC's fuller section never defines a type by that literal name —
  only `SessionError` (Session category) and `ErrorOccurred` (System category) exist there. Used
  the MVP line's literal spelling rather than silently resolving the ambiguity toward one of the
  two more-specific names; flagged in the file's own doc comment for whoever wires real
  error-event emission to check before depending on it.
  **First emission point wired 2026-08-09: `ToolCompleted` from `SqliteExecutor.drive()`.** Of the
  four subsystems named ("filesystem watcher, git, timers, tool completions"), tool completions
  was the lowest-risk to start with — `drive()` already publishes a `RunStepCompleted` event per
  task outcome (it already holds an `EventStore` reference, already builds a payload, already runs
  inside the transaction-adjacent path that writes the audit row), so adding one more `publish()`
  call there is additive, not a step-machine change: no new state transition, no new column
  `recover()` reads, same call shape as the existing publish four lines above it.
  `CrashRecoveryTest` confirmed still green (5/5) — this was checked, not assumed, given how
  carefully this file has told every link to treat `SqliteExecutor.kt`. Publishes
  `EventTypes.TOOL_COMPLETED` (category `FACT`, not the existing call's `SIGNAL` default — a tool
  result is a durable outcome per RFC-0004's own category table, not lossy progress) with topic
  `tool:<operation>:<taskId>`, matching the RFC's own worked example shape (`tool:shell:cmd-456`).
  **Only on success, deliberately:** the RFC's MVP line names `ToolCompleted` but not `ToolFailed`
  (that's in the fuller design section only), so a failed task publishes nothing new here rather
  than reaching for a type outside the MVP-scoped vocabulary — the existing `RunStepCompleted`
  event (which already carries `state: FAILED`) still records the failure. 2 new tests: one
  confirming the topic shape on success, one confirming silence on failure.
  **Second emission point, `GitCommit` from `GitTool.gitCommit()` — built differently on purpose,
  because unlike `ToolCompleted` above, nothing calls `GitTool` at all.** Checked before writing
  any code: `GitTool`, `ToolBroker`, and `AgentLoop` all have zero callers anywhere outside their
  own module (same discovery as the RFC-0005 agentloop finding, just a second instance of it —
  see the user exchange in this session: build the hooks anyway, unit-tested, ready for whoever
  wires the tool itself into something live, same as `SchedulerMatcher` last session). `GitTool`
  gained an optional `onCommit: (GitCommitEvent) -> Unit = {}` constructor parameter — a plain
  callback, not an `executor`/`EventStore` dependency, because `git` has no reason to depend on
  `executor` (that dependency direction would invert the natural tool-broker→tool layering) and
  `GitTool` has no `projectId` of its own to publish under anyway. `GitCommitEvent` matches
  RFC-0004's own worked `GitCommit` example exactly (`topic: "git:<branch>"`, `commitHash`,
  `author`, `message`, `files`) — `files` computed by diffing the new commit's tree against its
  first parent (or an empty tree, for a root commit) with JGit's own `DiffFormatter`/tree-parser
  APIs, the same technique `gitDiff()` already used two methods above it in the file, not a new
  pattern. Fires only after a successful commit — verified explicitly, not assumed, by adding a
  test that points `GitTool` at a non-git directory (so `Git.open()` throws before `onCommit`
  could ever be reached) and asserting the event list stays empty.
  **Genuinely could not be verified locally, unlike everything else this session** —
  `GitToolTest`'s entire suite fails in this sandbox on the *pre-existing, unrelated*
  `UnsupportedSigningFormatException` (this session's own ambient `~/.gitconfig`), so the new
  commit-path tests could only be confirmed correct by inspection plus a real CI run, not by
  running them here. **This is exactly why the CI fix above happened first, in the same link**:
  before this fix, `:git` wasn't run by CI either, so there would have been no way to verify this
  change at all, anywhere. With the fix, real CI (not this sandbox) is the actual verification —
  check the PR's `test` job result for `GitToolTest`, don't trust a local run of this specific
  suite.
  **Third emission point, `FileModified`/`FileCreated` from `FilesystemTool.write()` — required
  converting the tool from a Kotlin `object` to a `class` first.** `FilesystemTool` was a
  singleton with zero external callers (same check as `GitTool`), so adding an `onWrite`
  constructor callback the same way meant the object-to-class conversion first — deliberately
  *not* a mutable `var` on the singleton instead, even though that would have avoided touching the
  9 existing call sites in `FilesystemToolTest.kt`: a `var` on a shared object is mutable state
  every test in the same JVM would see, and one test forgetting to reset it silently leaks into
  the next. `filesystem` isn't on the pre-existing-failure list, so unlike `git` this was fully
  verified locally — all 13 original tests plus 3 new ones (`created=true` for a new path,
  `created=false` for an overwrite, silence on a failed write) pass. `FileChangeEvent` carries
  `created: Boolean` rather than an `EventTypes` string directly — `filesystem` has no reason to
  depend on `executor`, so the caller maps `created` to `EventTypes.FILE_CREATED`/`FILE_MODIFIED`
  itself. **`FileDeleted` has no emission point to wire**: `FilesystemTool` has no `fs:delete`
  operation at all (only `fs:read`/`write`/`list`/`search`), so there's nothing to hook for it.
  **`TimerFired` was not attempted — a real, structural dead end for this branch, not a "didn't
  get to it" gap.** Checked before writing anything: the only timer/scheduling code anywhere in
  the codebase lives in `androidapp/scheduling/` (RFC-0044), and its dispatcher
  (`RuntimeClientWorkDispatcher`) calls `client.sessions.send(sessionId, message)` directly on
  `RuntimeClient` — exactly the `RealRuntimeClient` territory the task brief named as Group 2's
  from the outset, unlike `GitTool`/`FilesystemTool` which were standalone, unclaimed modules.
  There is no unclaimed hook point to add here the way there was for the other two; building one
  would mean either modifying the flagged file directly or duplicating scheduling logic elsewhere
  to route around it. Flagged rather than pushed through, per the branch's standing rule.
  **Fourth emission point, `TimerFired` from `RuntimeClientWorkDispatcher.dispatchXxx()`
  — built 2026-08-10:** Unlike the other three, this is not a tool but a dispatcher of
  scheduled jobs. Added an `onTimerFired: (job: ScheduledJob) -> Unit = {}` constructor
  callback to `RuntimeClientWorkDispatcher` (following the same callback-not-dependency
  pattern as `GitTool.onCommit` and `FilesystemTool.onWrite`). The callback fires in all
  four `dispatchXxx` methods (Interactive, Deferred, Scheduled, Opportunistic) when the
  dispatch result is `RunResult.Accepted` AND the job's trigger is time-based
  (`Trigger.At`, `Trigger.Every`, or `Trigger.Cron`). Deliberately excludes
  `Trigger.OnEvent` and `Trigger.OnCondition` — these are event/condition-driven, not
  timer-fired, and calling the callback would misrepresent the causality RFC-0004's own
  `causality` field exists to track. The `androidapp` module has no reason to depend on
  `executor`/`EventStore`, so the caller maps the job to the real event and publishes.
  Not yet exercised by any live caller — see PIPELINE.md's Group 1 notes. 3 new tests
  verifying callback fires for At/Every/Cron triggers on success, doesn't fire for
  OnEvent/OnCondition triggers, and doesn't fire on failed dispatch or when job is
  disabled; all green.
  **Net state of RFC-0004 item 2 + emission wiring:** `ToolCompleted`, `GitCommit`,
  `FileModified`/`FileCreated`, and `TimerFired` have real, tested emission points.
  `UserCommand`, `PermissionRequested`/`Granted`/`Denied`, `SessionWoken`/`Sleeping`,
  `ArtifactCreated`, `Error`, and `FileDeleted` have no natural emission point identified
  yet — none of them sit behind an operation found and checked so far.
- [x] **RFC-0005 (Scheduler) — persistence + pure matching layer done 2026-08-09; wake-to-Run
  wiring ruled out of this branch's scope by user decision, not deferred as "next link's job."**
  Checklist framing corrected first (see below), then progress made on the corrected scope:
  - **`scheduled_jobs` wiring is explicitly *not* MVP.** RFC-0005's own MVP section: *"Not in the
    MVP: timers and scheduled triggers... `scheduled_jobs` exists in the schema and nothing writes
    it before G4."* D34 confirms: *"Timers, the admission policy, and priorities are post-MVP."*
    The original checklist's "wire `scheduled_jobs` to a real reader/writer" item was asking for
    explicitly post-MVP scope — not built, correctly. **Also factually stale regardless of scope:**
    `scheduled_jobs` already has a real reader/writer — `androidapp/scheduling/SqliteScheduledJobManager.kt`
    (RFC-0044, PR #18) — just for a different purpose (notification/work-class dispatch, not
    session wake/sleep).
  - **The real MVP item is event-driven wake (RFC-0005 MVP item 1): "topic and type matching, so a
    session wakes from a subscribed event... the load-bearing case is a driver waking when its
    worker completes."** D34 credits this to M5, but that credit looks overclaimed on inspection —
    the M5 done-when (`ExecutorTest.kt`'s own doc comment) is about hard-coded Task execution and
    event publishing, not subscription matching, and no subscriptions table existed anywhere in
    `schema/` before this link (confirmed by grep, zero hits, prior to the commit below).
  - **Done this link — the persistence and matching halves of item 1, plus item 3's self-wake
    refusal as a pure decision:** added `session_subscriptions` to `schema/project.sql` (topic
    patterns and event types as JSON arrays via `kotlinx.serialization`, matching the convention
    already used elsewhere in `executor`/`broker`, not hand-rolled encoding; `self_wake` flag per
    RFC-0005's opt-in). `executor/SessionSubscriptionStore.kt` persists/reads it (4 tests) — the
    durable counterpart to the RFC-0004 slice's in-memory `SubscriptionRegistry`, needed because
    D3 requires anything surviving a step boundary to be a column, and the load-bearing wake case
    (driver woken by its worker) can span a process restart on Android.
    `executor/SchedulerMatcher.kt` (8 tests) is the pure decision function from RFC-0005's own
    "Matching" section — given a published event, a source session ID, and the project's
    subscriptions, it returns which sessions would wake and which self-wakes were refused. Tests
    include the RFC's own load-bearing case (driver subscribed to its worker's topic, woken when
    the worker's `RunCompleted` fires) and the self-wake-refused-by-default / opt-in-overrides
    cases from "Cycles and amplification." Adding the table required bumping `SqlScriptTest`'s
    hardcoded project-table-count assertion (42→43) — caught by `gradle jvmTest`, not by
    `schema/check.py`, which doesn't count tables; worth remembering next time a table is added,
    since `check.py` passing does not mean every test that counts tables is still correct.
  - **Deliberately not derived from `EventRow.source`:** `SchedulerMatcher.match()` takes
    `sourceSessionId` as an explicit parameter rather than trying to parse which session published
    an event from its `source` string, because there is no established convention anywhere in the
    codebase for encoding session identity in that field (grepped, zero hits) — inventing one as a
    side effect of this function would be a bigger, unreviewed decision than this slice should
    make. Whoever wires the actual publish→match→wake path needs to either establish that
    convention deliberately or thread the source session through some other way; don't let it get
    invented implicitly inside a matcher.
  - **Still not done, and it's the harder half:** nothing calls `SchedulerMatcher` yet.
    `EventStore.publish()` doesn't invoke it, nothing transitions `SessionState` `SLEEPING`↔`RUNNING`,
    and nothing creates a Run for a woken session. This is `SqliteExecutor`'s `drive()` loop and
    the step-machine's territory — D3 (anything surviving a step boundary is a column) and D14 (at
    most one effectful Task per Run is `RUNNING`) both apply directly to how a wake becomes a Run,
    and CrashRecoveryTest must stay green through it. **Also still open:** MVP item 3's causal-depth
    half — `EventStore.MAX_CAUSAL_DEPTH` refuses publication past depth 16, but silently (`return
    null`, no audit row), and RFC-0005 says refusals must be "recorded, not silent" (RFC-0037).
    `broker/AuditLog` (already an `executor` dependency, used elsewhere for exactly this kind of
    "record that something was refused and why") is the natural place to write that row from, once
    the actual wake path exists to call it from.
  **Why this link stopped before wiring it in — read `SqliteExecutor.kt` and RFC-0017 first, this
  changes the shape of the remaining work:** `drive(runId)` takes an *existing* `runs` row and
  steps it to completion; it has zero knowledge of `SessionState` or of how a Run comes to exist
  in the first place — nothing in `executor` creates `runs` rows or populates their `tasks`. So
  "wire the wake path into `drive()`" was itself imprecise: there's no `drive()` change to make.
  What's actually missing is a **new** component — call it a `Scheduler` — that, given a matched
  wake, (a) transitions the session `SLEEPING`→`RUNNING`, (b) creates a `runs` row, and (c) decides
  what `tasks` populate that Run, then calls `drive()` on it. Step (c) is the part this link could
  not responsibly scope: RFC-0017 (the canonical session state machine, confirmed via its own
  text: *"a session with queued events is SLEEPING until the scheduler drives it"*) says the
  Scheduler drives a woken session but does not say what tasks a *driver-woken-by-its-worker* Run
  contains — that decision belongs to the model-call loop (RFC-0020, `runtime/agentloop/`).
  **Read this link (2026-08-09): `runtime/agentloop/AgentLoop.kt` has zero callers anywhere in the
  codebase outside its own module** (`grep -rl "AgentLoop(\|RunRequest("` across `runtime/`,
  excluding `agentloop/` itself, returns nothing). It is not a Task-populator plugged into
  `SqliteExecutor` — it's a self-contained suspend function (`AgentLoop.run(RunRequest):
  RunOutcome`) that runs an entire model-call loop to completion in one call, with a `checkpoint`
  callback for the caller to persist step boundaries into. It has **no relationship at all** to
  `runs`/`tasks`/`attempts` rows or `drive()` — those are two parallel, currently-disconnected
  execution models (the SQLite step-machine in `executor`, and the in-memory model-call loop in
  `agentloop`), and nothing in the runtime bridges them yet.
  **This changes the assessment from "read agentloop for the pattern" to "there is no pattern to
  read yet — the bridge itself is unbuilt."** And that bridge is not free-standing: it is, at
  minimum, adjacent to — quite possibly the same work as — Group 2's own outstanding item **"Finish
  `RealRuntimeClient`"**, whose own text says *"wire it to storage/executor/capability... this
  blocks the next two items"* — i.e., the same "make Run creation real instead of a stub" problem,
  approached from the API side instead of the Scheduler side. Building this from the RFC-0005 side
  without coordinating risks doing Group 2's work twice, differently, on two branches — precisely
  the collision the branch split exists to prevent, even though no single file is shared.
  **This link's conclusion: RFC-0005's actual wake-to-Run wiring should not be attempted by a
  Group 1 link without the user's input on how it relates to Group 2's `RealRuntimeClient` work.**
  Flagged directly in the final message of this link, not just buried here. The persistence and
  matching layer (`SessionSubscriptionStore`, `SchedulerMatcher`) is done, tested, and will be
  ready to consume whichever side ends up building the bridge — that part was not wasted work
  either way.
  **User decision, 2026-08-09: the agentloop↔executor bridge (wake-to-Run wiring) is ruled out of
  this branch's workload entirely, not merely paused.** The user was asked directly whether to
  settle the Group 1/Group 2 split first; the answer was to continue Group 1's remaining work
  *except* that split decision, and to remove it from scope here rather than revisit it link to
  link. **Concretely, for this PR:** RFC-0005 stops at the persistence + pure-matching layer above.
  Nothing calling `SchedulerMatcher`, no `SessionState` transitions, no Run creation, and no
  further investigation into `agentloop` belongs to this branch going forward — that whole
  question is the user's to settle separately (with Group 2, or as its own follow-up), not
  something a future link should pick back up "since it's next." **What this makes RFC-0005 in
  this PR: the persistence and matching layer, deliberately partial and staying that way here.**
  If PIPELINE.md's Group 1 checklist item for RFC-0005 is ever marked done in this branch, it
  means "the layer this branch owns is done," not "RFC-0005 is fully implemented" — the wake path
  is real, tracked, follow-up work, just not this branch's.
  **Wake-to-Run wiring built 2026-08-10** (`executor/Scheduler.kt`, branch
  `claude/rfc-0005-wake-to-run`) — the piece the note above deliberately left for a link with the
  user's explicit go-ahead on how it relates to `RunExecutor`. By this point `RunExecutor`
  (2026-08-10, above) had already settled "how a Run comes to exist from outside `executor`," so
  the coordination risk flagged above no longer applied: `Scheduler` is the RFC-0005-side
  counterpart, built after and consistent with that seam, not a second attempt at the same
  problem. Confirmed first that `SchedulerMatcherTest`'s literal `"RunCompleted"` event type is
  illustrative test data, not real RFC-0004 vocabulary (grepped — nothing publishes it); the real
  mechanism per RFC-0004's own worked example is `SessionWoken`/`SessionSleeping`, which is what
  `Scheduler` actually publishes. `Scheduler.wake(event, sourceSessionId, ...)`: runs
  `SchedulerMatcher.match()` against the project's subscriptions, audits self-wake refusals
  (`WakeRefused`, reason `self_wake_not_opted_in`), then for each matched session publishes a
  `SessionWoken` event (`causedBy` = the triggering event, `causalDepth` + 1) and, in one
  transaction, transitions the session `SLEEPING`→`RUNNING` (`WHERE state = 'SLEEPING'`, so a
  session that stopped being `SLEEPING` between the match and this point is silently skipped, not
  double-run — D14) and creates its `PENDING` Run via a new `RunCreator.createForEvent(...)`
  (`RunCreator.createForUserMessage` refactored into a thin wrapper around a shared private
  `create()`; `createForEvent` fills the same `user_message_summary` column with a synthesized
  "Woken by \<event type\>" summary — no new column, per the same reasoning `RunExecutor` used for
  not inventing new state). If `EventStore.MAX_CAUSAL_DEPTH` refuses the `SessionWoken` publish,
  that's audited too (`WakeRefused`, reason `causal_depth_ceiling`) and no Run is created —
  RFC-0005's MVP item 3, closed. Does **not** call `drive()`, same reasoning as `RunExecutor`: no
  real `InferenceRouter`/`EffectBroker` composition exists yet to drive a Run through, and a
  durable `PENDING` Run is correct either way (D3). Wired into the one real call site that exists
  today: `SqliteRunExecutor.send()` now calls `scheduler.wake()` on the `UserCommand` event it
  already publishes, `sourceSessionId = sessionId` — so any *other* session subscribed to that
  command (not just the sender's own Run) wakes too. Needed re-adding `implementation(project(
  ":broker"))` to `daemon/build.gradle.kts` — `executor`'s own `broker` dependency is
  `implementation`, not `api`, so it isn't transitively visible to `daemon` for `AuditLog`.
  `SchedulerTest.kt` (5 tests): matching subscription wakes a `SLEEPING` session with a
  `SessionWoken` event and a `PENDING` Run; non-matching subscription does nothing; self-wake
  refusal writes the audit row and doesn't wake; an already-`RUNNING` session isn't double-woken
  (event still published, no Run); causal-depth-ceiling refusal writes the audit row and creates
  no Run. Plus one `SqliteRunExecutorTest` case: sending a `UserCommand` wakes a second, unrelated
  `SLEEPING` session subscribed to it, without the sender's own Run being duplicated. `gradle
  jvmTest --continue` clean except the two known pre-existing red modules (`:knowledge`,
  `:modelruntime`); `CrashRecoveryTest` stayed green throughout.
- [x] **RFC-0024 (Resource Graph), MVP scope done — "promotion/demotion logic" was never MVP.**
  Done 2026-08-09: reading RFC-0024's own "MVP" section first showed promotion/demotion workflows
  are explicitly listed under "The MVP does not implement" — the original review's framing
  overstated the gap the same way RFC-0047's did. What the MVP section actually asks for: the
  `ContentNode` data class with all fields, basic queries (by project/kind/ID), and
  `ProvenanceEdge` limited to `DERIVED_FROM`/`VERSION_OF`. All done: `kernel/Content.kt` adds
  `ContentNode`, `ContentKind`, `ContentNodeState`, `StorageLocation` (sealed: `SqliteBlob`/
  `FilesystemPath`/`GitObject`), `ProvenanceEdge`, `ProvenanceEdgeKind` — reusing `TrustLevel`,
  `SensitivityLevel`, `EgressEligibility`, `MutabilityPolicy`, which turned out to already exist
  in `kernel/Trust.kt` (someone had built the classification enums but never the node/edge shapes
  that use them). `SqliteContentNodeStore` (`androidapp/.../content/`) persists to
  `content_nodes`/`provenance_edges`, including the acyclicity check the RFC's own "Acyclicity"
  section calls for on insert (BFS reachability from the new edge's target back to its source) —
  8 tests, including a 3-node chain closing a cycle, not just the direct 2-node case. Not part of
  MVP scope and not built, per the RFC's own list: promotion/demotion, cross-project references,
  content-addressed dedup, `REFERENCED_BY`/`MERGED_FROM` edges. **Module placement is a call I
  made for expediency, not a designed decision:** the store lives in `androidapp` (matching where
  `SqliteIntentStore` and `SqliteScheduledJobManager` already live and where `:storage` access was
  already wired), even though `ContentNode` is used by other subsystems too (`PromptAssembler`'s
  `ContextItem.contentNodeId`, `execution_edges`' `CONTENT_NODE` kind) and arguably deserves its
  own module long-term. Revisit if `androidapp` starts feeling like a dumping ground.
- [x] **RFC-0043 (Plugin Packaging and Sandbox) — checked 2026-08-09, nothing to build.**
  RFC-0043's own MVP section: *"No plugin host ships in v1. The MVP of this RFC is the
  decision."* D18 (`docs/decisions.md`) already records that decision (WASM-only when built,
  user-scope installation, no project-local plugins, no in-process native loading). The original
  review's "Accepted with nothing built = uncaught D34 gap" framing was wrong here — there was
  never anything to build. Writing plugin/sandbox code now would directly contradict the RFC's
  own instruction not to. Nothing left to do until the decision changes.
- [x] **RFC-0045 (Performance and Resource Budgets), partial — the pure decision logic is done,
  the real-device half is a genuine, still-open hardware gap.** Done 2026-08-09: read RFC-0045's
  own "MVP" section first (by now the standing discipline after RFC-0047/0012/0024 all turned out
  narrower or differently scoped than the review's framing). MVP has 5 items; two are
  buildable without hardware and two are not:
  - **Item 2 (degradation rungs 1, 2, 4, 5) and item 4 (`degradation_events` recording) — done.**
    `routing/DegradationLadder.kt` is a pure, stateless decision function (`DeviceSignals ->
    Map<DegradationRung, reason>`), matching where `PolicyInferenceRouter` already lives for the
    same shape of problem — device signals in, policy out, no I/O. `kernel/Degradation.kt` adds
    the shared types (`DegradationRung`, `MemoryPressureLevel`, `BatteryState`, `DeviceSignals`,
    `DegradationEvent`). `androidapp/degradation/SqliteDegradationEventStore` persists
    transitions to `degradation_events` — reconciled by *querying currently-open rows*, not
    tracking state in memory, matching D3's "anything that must survive a step boundary is a
    column" rather than inventing a new exception to it. 9 ladder tests + 6 store tests, 15 total,
    including independent-rung interleaving and re-entry after closing.
  - **Items 1, 3, 5 (targets measured on a reference device, model admission with real memory/
    thermal checks, regression benchmarking) — not done, and not started.** These require an
    actual mid-range Android device to produce real numbers; this is the same class of gap as
    M21/M26/M34/M35, not a coding task. **Whoever eventually wires real signals in**: nothing here
    reads `ComponentCallbacks2`/`BatteryManager`/Android thermal APIs — `DeviceSignals` is a plain
    data class deliberately platform-neutral (see its doc comment), and *that* translation is the
    remaining wiring work, not a redesign.
  - Rungs 3 (drop knowledge caches) and 6 (thermal → disable local inference) are declared in
    `DegradationRung` for completeness against the schema's `rung INTEGER` column but the ladder
    never activates them — both are explicitly post-MVP in the RFC, not a gap.
- [x] **RFC-0047 (Project Templates and Types), partial — the `applyTypeDefaults` bug is fixed,
  the RFC's larger MVP scope isn't.** Done 2026-08-09: re-reading RFC-0047's own MVP section shows
  only `PERSONAL` and `CODING` are supposed to have type-specific overrides — `RESEARCH`/`WRITING`/
  `GENERIC` being no-ops is correct, documented MVP scope, not a gap (the original review's "2 of
  6" framing overstated this). But `PERSONAL`'s override was a genuine bug: it wrote
  `routing.remote_egress` at **project** scope, and that setting is `ScopeClass.SECURITY` —
  `SettingsWriter.writeProject()` rejects SECURITY/SPEND keys outright, so the write always failed
  and RFC-0047's headline MVP requirement ("personal defaulting `routing.remote_egress = never`...
  worth having on day one") silently never took effect, with zero test coverage to catch it.
  Fixed by writing to user scope instead (`identity/src/commonMain/.../ProjectRegistry.kt`), which
  is literally what the RFC's own MVP section describes. Added
  `identity/src/jvmTest/.../IdentityTest.kt` coverage for both the `PERSONAL` write and the
  `CODING`/`GENERIC` no-op case. **Two things this does NOT fix, left for whoever wires project
  creation into `RealRuntimeClient`:** (1) `applyTypeDefaults` still has zero call sites anywhere
  in the codebase — nothing invokes it at project-creation time yet, so the correct behavior only
  exists as a correct, tested, *unused* function until that wiring lands; (2) writing to user scope
  means this now affects every project for the user, not just the one being created, and
  re-applies (silently overwriting any explicit prior choice) every time a `PERSONAL` project is
  created — the original code comment called this "a one-time suggestion, not a project scope
  write," which implies something more like "set only if still at `SettingOrigin.DEFAULT`," not an
  unconditional upsert. That's a real product decision (how insistent should a privacy default be
  against a user's own prior choice?), not an implementation bug, and it doesn't belong to whoever
  just wires the call site — flag it for the user rather than guessing.
  No scaffolding/template loader — RFC-0047 explicitly puts templates out of MVP scope
  ("Not in MVP: built-in templates, instantiation..."), so that part was never a gap to begin with.

**Group 2 — Phase 4: making the Android app real, not just its platform-neutral logic:**

- [x] **Wire `androidTarget()` in `runtime/kernel/build.gradle.kts` and
  `runtime/api/build.gradle.kts`** — done and verified green in CI 2026-08-09 (PR #19,
  `build-and-publish` check, commit `b4ce0a5`). Both apply `id("com.android.library")` and call
  `androidTarget()`, matching `androidapp`'s pattern. `dl.google.com` **is** reachable from a
  sandbox without an Android SDK (confirmed live) — the root `build.gradle.kts` comment calling it
  "blocked in this sandbox" no longer holds universally; don't assume it as fact next time, check
  it. This sandbox still cannot compile the Android variant itself (no `ANDROID_HOME`), same class
  of gap as M21/M34/M35 — but **CI has a real Android SDK and a real Gradle+AGP+D8 toolchain, and
  it caught six more real bugs the sandbox structurally could not**, each fixed in its own commit
  on top of the wiring: a Java/Kotlin JVM-target mismatch (11 vs 21) once AGP actually compiled the
  release variant; `android.useAndroidX=true` missing from `gradle.properties`; the release
  signing config referencing a keystore file the CI workflow's own "optional" step never created,
  fixed twice (once to leave `storeFile` unset when absent, again because AGP still refuses to
  *package* a release build type that references an incomplete signing config at all — the fix is
  to not attach one in that case, not just to leave it half-filled); `HomeScreen.kt`'s pager code
  using `ExperimentalFoundationApi` without an opt-in; and `androidapp` using `@Composable`
  throughout without ever applying the `kotlin.plugin.compose` Gradle plugin — mandatory since
  Kotlin 2.0, and this module's Android compile had apparently never succeeded before, so nothing
  had surfaced it. **Lesson for future Android-target work: a green `gradle build` in this sandbox
  proves the JVM targets and the module graph; it proves nothing about the Android variant. Expect
  CI to find real things a from-scratch sandbox verification cannot, and budget for a few
  iteration rounds rather than treating local green as done.**
- [ ] **Finish `RealRuntimeClient`.** It's explicitly in-memory today (own code comment) — wire it
  to `storage`/`executor`/`capability`. **`androidTarget()` prerequisite done 2026-08-09** — see
  below.

  **Project persistence + RFC-0055 locking done 2026-08-09 — this was the "unblocked" half (see
  the AgentLoop↔Executor note below): `projects.create()`/`.open()`/`.close()`/`.list()`/`.get()`/
  `.delete()` are backed by real storage when wired, in-memory otherwise.** Design: `RealRuntimeClient`
  gained three optional (`var ... = null`) injection seams — `userDriver: SqlDriver?`,
  `projectDbFactory: ((String) -> SqlDriver)?`, `projectLocker: ProjectLocker?` — unset preserves
  the exact pre-existing in-memory behavior (backward-compatible with every other caller:
  `MainActivity`, `AidosService`, any test). `daemon/RuntimeClientFactory.createRuntimeClient()`
  is the one JVM consumer wired end-to-end: opens `user.db` via `AidosStorage.openUser`, supplies
  a `projectDbFactory` via `AidosStorage.openProject(DesktopPaths.stateDb(root), ...)`, and a real
  `JvmProjectLocker`. `MainActivity`/`AidosService` deliberately left unwired — Android's own
  `SqlDriver`/lock implementations are the same follow-up work as `capability`'s `SqliteDirHandle`.

  **`ProjectLocker` is dependency-injected (`:api`'s `jvmMain` `JvmProjectLocker` wraps `:lock`'s
  `ProjectLock`), not an `expect`/`actual` port as an earlier note here suggested.** Correction
  while implementing: `identity`'s `ProjectRegistry` already has `java.io.File` in a commonMain
  default parameter and compiles for Android in CI (confirmed, predates this link) — a
  `jvm()`+`androidTarget()`-only module's `commonMain` genuinely can reference `java.*` APIs both
  targets share, so `java.nio.channels.FileLock` was likely never a *compilation* blocker either.
  DI was still the right call: `FileLock`'s actual behavior on Android's storage volumes is
  unverified from this sandbox (no device, nothing in CI exercises it), so Android gets its own
  implementation deliberately once someone can verify it, rather than inheriting an untested
  assumption through a shared `actual`. Don't take this doc's own prior "needs expect/actual"
  framing as gospel next time either — it was a plausible-sounding guess, not something the RFC
  mandated, same lesson as everything else in this file.

  **Project ids switched from the `aidos-N` counter to `UuidV7Generator` for projects
  specifically** (only projects — sessions/runs/capabilities/events are untouched, still `aidos-N`,
  still purely in-memory). Necessary, not cosmetic: the counter resets to 1 on every
  `RealRuntimeClient` construction, which is harmless for in-memory-only entities but would
  silently collide two different real, persisted projects across a runtime restart.

  **8 new tests** (`api/src/jvmTest/.../RealRuntimeClientPersistenceTest.kt`) using a real
  temp-directory SQLite backend — this is commonMain logic exercised via the JVM target, fully
  verifiable locally, unlike `androidMain` code. Covers: create persists and a second instance
  can list/open it (restart simulation), open refuses a project locked by another instance
  (`runtime.locked_by_other_instance`), close releases the lock, delete unregisters without
  touching the project's own files, delete without confirm is a no-op, unset-persistence behaves
  exactly as before, and ids don't collide across instances sharing storage. Also fixed a real bug
  this surfaced: `DaemonCliIntegrationTest` called the now-real `RuntimeClientFactory` directly
  against the actual `~/.aidos` on whatever machine runs it — not hermetic. Added a `home`
  override parameter and pointed the test at a temp directory.

  **A second real bug, found via a genuine SQLITE_CANTOPEN failure, not guessed at**:
  `resolveProjectPath`'s `RuntimeManaged`/`CloneOf` branches returned a hardcoded `/projects/<slug>`
  — harmless while projects were purely in-memory (the path was never used for real I/O), but the
  first thing any real-storage project creation hits once wired. Added an injectable
  `runtimeManagedProjectsRoot: String?` (same seam pattern), defaulting to the old placeholder when
  unset. Separately, `AidosStorage.openProject(path, ...)` expects the *file* path
  (`.aidos/state.db`), not the project root — `DesktopPaths.stateDb(root)` is the translation step;
  the first version of `projectDbFactory` skipped it and opened the bare project root as if it
  were the database file.

  **What's still not done, and still blocked**: `executor`/`capability` wiring (real Run
  execution, capability enforcement) — this is the AgentLoop↔Executor bridge's territory, see
  below. Session persistence (`sessions.*`) is also still in-memory — deliberately not attempted
  in this slice; deciding whether session rows should persist ahead of Run execution being real is
  a judgment call for whoever picks up the executor/capability half, not assumed here.

  **Update (2026-08-09/10, branch `claude/real-runtime-client-run-executor`) — the judgment call
  above is made: session rows now persist, and `sessions.send()` creates a real, durable Run.**
  With the AgentLoop↔executor bridge merged (below), `RunCreator` exists to create a `runs` row
  for a user message — the piece this note was waiting on. Done:
  - `sessions.create()` writes a real `sessions` row when the project's driver is open (matching
    `projects.create()`'s own pattern exactly), with `UuidV7Generator`-minted ids (a second
    `sessionIdGen`, next to `projectIdGen` — sessions now need the same "must survive a restart
    without colliding" property projects already got).
  - A new seam, `RealRuntimeClient.runExecutor: RunExecutor?` (`api/RunExecutor.kt`) — `null`
    preserves the exact pre-persistence in-memory `RunSummary`/`RunResult.Accepted` stub;
    `sessions.send()` calls it instead when both it and the session's project driver are set.
  - **Why a seam instead of `RealRuntimeClient` calling `executor` directly: a real module
    cycle.** `executor` depends on `prompt`, and `prompt` depends on `api` (for `KnowledgeQuery`/
    `KnowledgeQueries`) — so `api` → `executor` would close the loop. `RunExecutor` is a plain
    interface `api` owns; the real implementation (`daemon/SqliteRunExecutor.kt`) is composed in
    `daemon`, which is free to depend on `executor` directly, and injected into
    `RuntimeClientFactory.createRuntimeClient()`'s `RealRuntimeClient` the same way
    `projectDbFactory`/`projectLocker` already are. Worth remembering before reaching for
    "just add the dependency" on any future `api`↔`executor` wiring — check the graph first,
    this exact cycle will recur.
  - `SqliteRunExecutor.send()` publishes `EventTypes.USER_COMMAND` (not the informal `'UserMessage'`
    string test fixtures elsewhere in the codebase use as seed data — that string was never tied
    to the RFC, `EventTypes`' own doc comment confirms the MVP line's real spelling is
    `"UserCommand"`) then calls `RunCreator.createForUserMessage(...)`. **Deliberately does not
    call `SqliteExecutor.drive()`.** Driving a Run to an actual model response needs a real
    `InferenceRouter` + `PromptAssembler` + `EffectBroker` (a `CapabilityManager` with real tools
    registered) — none of which are composed anywhere in the runtime yet; `RuntimeClientFactory`
    only ever wires storage and locking. Building that composition (register `FilesystemTool`/
    `GitTool` on a real `ToolBroker`, grant real capabilities, pick a real `InferenceRouter`) is
    substantial, separate work — a runtime "composition root" that doesn't exist yet — not
    something to force through as a side effect of this wiring. The Run this creates is durable
    and sits `PENDING`; nothing is lost by not driving it immediately (D3's whole point is that
    every step is reconstructable from rows, not held anywhere in memory), and whoever builds that
    composition just needs to call `drive()` on it.
  - Tests: `daemon/SqliteRunExecutorTest.kt` (real SQLite, asserts the event/`runs`/`tasks` rows
    directly) and `api/RealRuntimeClientSessionTest.kt` (session persistence across a fresh driver
    read, `RunExecutor` dispatch with a fake, the unset-seam fallback, and the unknown-session
    error path). `CrashRecoveryTest` unaffected (this link never touches `SqliteExecutor.kt`).
  - **Still open**: session *read* hydration (`sessions.list()`/`.get()` still only read the
    in-memory `_sessions` map, so a session created by a prior process instance is invisible to a
    fresh one — the mirror image of the gap `hydrateProjectSummary` closes for projects, not closed
    here). `platformProfile`/`networkAvailable` are plain mutable vars with no real detection
    behind them (`DESKTOP`/`false` always) — flagged, not guessed at, matching how
    `runtimeManagedProjectsRoot` and the other seams started. And the big one: the composition
    root (`CapabilityManager` + registered tools + `InferenceRouter`) needed to ever actually
    *drive* one of these Runs, named above.

  **Update (2026-08-10, branch `claude/rfc-0005-wake-to-run`) — the composition root named above
  is built** (`daemon/RuntimeCompositionRoot.kt`), and `sessions.send()` now drives its own Run
  inline instead of leaving it `PENDING` forever.
  - **Why inline, and why only the sender's own Run:** RFC-0044's "Background work classes" table
    classifies "a Run the user just started" as **Interactive**, and Interactive's mechanism is
    explicitly **inline** on desktop (a foreground service on MOBILE, per D24, exists to keep that
    same inline call alive — not a different execution model). `sessions.send()` is exactly that
    case, so `send()` is where RFC-0044 says the driving belongs. `Scheduler.wake()`-woken Runs
    (RFC-0005, above) are deliberately left un-driven: an event-driven wake is Deferred/Scheduled/
    Opportunistic in the same table, whose mechanism is `WorkManager`/a background dispatcher, not
    inline — driving those here would answer the wrong table row. `drive()` itself already
    supports the "inline but not necessarily to the end" shape: it runs to a terminal state or
    parks on `AWAITING_APPROVAL`/`AWAITING_INPUT` and returns (RFC-0009), so inline doesn't mean
    "blocks forever."
  - **`RuntimeCompositionRoot.drive(...)`** composes, per call (mirroring `SqliteRunExecutor`'s own
    style — no project-scoped state held across calls): `SqliteCapabilityManager`, `AuditLog`,
    `ToolBroker` with `FilesystemTool()` and `GitTool(File(rootPath))` registered (`rootPath` read
    from the project's own `projects.root_path` row via the already-open project driver),
    `PolicyInferenceRouter` with `AnthropicAdapter` as its sole remote adapter when a key is
    available, `PromptAssembler()`, `AgentLoopTaskRunner`, and `SqliteExecutor` — then calls
    `.drive(runId)`.
  - **Deliberately does not resolve capabilities for model-emitted tool calls.**
    `AgentLoopTaskRunner` always sets `ToolCall.capabilityId = null` (its own doc comment already
    names this a known, accepted gap), so `ToolBroker.invoke`'s step 2 denies every tool call with
    `capability.missing` regardless of what this composition does — granting a capability nothing
    consults would be dead code dressed as progress, not a real fix. Concretely: a driven Run can
    reach a real model response, but any `TOOL_CALL` it emits is denied. Building the
    `(subjectId, toolName) -> CapabilityId` resolver this needs is separate, not-yet-designed work
    — asked the user directly rather than guessing an architecture for it, and the answer was to
    leave it denied for this slice.
  - **Where the model key comes from, and why not the vault:** `RuntimeCompositionRoot` takes a
    plain `anthropicApiKey: () -> CharArray?` provider, not a `SecretsVault` lookup.
    `SqliteSecretsVault`'s JVM key handling generates a fresh in-memory key by default (its own doc
    comment: "the key is held in memory") — wiring live vault resolution here would either
    silently lose previously-stored secrets across a restart or require settling a key-persistence
    strategy, which is its own unreviewed architecture decision, not a side effect of this slice.
    `RuntimeClientFactory` sources the key from `ANTHROPIC_API_KEY` for now. No key configured
    means `remoteAdapters` is empty and `PolicyInferenceRouter` reports every `MODEL_CALL`
    `UnavailableOffline` — confirmed (by reading both `AnthropicAdapter.invoke`, which wraps its
    network call in `runCatching`, and `AgentLoopTaskRunner`'s own `UnavailableOffline` branch)
    that this fails just the one task cleanly, not the whole `drive()` call or `send()` itself.
  - **`daemon/build.gradle.kts` gained five new dependencies**: `capability`, `filesystem`, `git`,
    `prompt` (already there transitively via `executor`, now direct), `routing`, `vault`. Not
    `modelruntime` — `LlamaCppAdapter` needs it, but the module doesn't compile in CI (the
    pre-existing `de.kherud:llama-java:0.3.2` coordinate gap, tracked separately) — so this
    composition's `localAdapters` list stays empty; `AnthropicAdapter` is the only real adapter
    until that's fixed or a working local adapter is built.
  - Tests: `daemon/RuntimeCompositionRootTest.kt` (drives a Run directly — no model key configured
    reaches a clean `FAILED` state, not a thrown exception; a Run whose `projectId` has no
    `projects` row is left `PENDING`, untouched, per D3) and a new `SqliteRunExecutorTest` case
    (`send()` with a composition root wired drives the sender's Run to `FAILED` end-to-end through
    the real API surface, still without needing network access in a test). `compositionRoot`
    defaults to `null` (same "unset preserves old behavior" idiom as every other seam here), so
    the pre-existing `SqliteRunExecutorTest` cases needed no changes. `gradle jvmTest --continue`
    clean except the two known pre-existing red modules; `CrashRecoveryTest` green throughout.

  **`androidTarget()` wired on `storage`, `settings`, `identity`, `capability`, `broker`,
  `executor`** (six modules, not four — `broker` and `settings` turned out to be transitive
  dependencies of `executor`/`capability`/`identity` that also needed the target, or the Android
  variant couldn't resolve them at all; Gradle KMP requires every module in a dependency's graph
  to publish a matching target). None of the six had an `expect`/`actual` blocker except
  `identity`'s `UuidV7Generator` — its JVM `actual` uses only
  `java.util.concurrent.atomic.AtomicInteger`, `kotlin.random.Random`, and
  `System.currentTimeMillis()`, all part of the Android runtime, so the `androidMain` actual is a
  straight copy, not a redesign. **`capability`'s `SqliteDirHandle` was deliberately left
  untouched** — its own doc comment already says "This implementation is JVM-only; Android will
  provide its own actual backed by SAF or scoped storage when the Android module arrives" —
  matching RFC-0050's own Future Work item for Storage Access Framework support. That's a real
  design gap, not a wiring gap: designing an Android file-access implementation is out of scope
  here, and forcing one through today would mean inventing what RFC-0050 explicitly defers.
  Wiring the *target* on `capability` doesn't require solving that — `SqliteDirHandle`/
  `SqliteCapabilityManager` simply aren't visible from `androidMain` yet, the same "compiles, but
  the real implementation is a separate follow-up" state `storage`'s own SQLite driver code is in
  (its `jvmMain` desktop driver won't be visible from Android either; an `AndroidSqliteDriver`
  wiring is follow-up work, not done here). **The actual `RealRuntimeClient` → storage/executor/
  capability wiring is the next link's work** — expect it to surface its own latent bugs the same
  way kernel/api/androidapp's wiring did in PR #19, and expect it to need the SAF-backed
  `DirHandle` design RFC-0050 deferred, once `RealRuntimeClient` actually needs file access on
  Android rather than just an Android-compiling target.

  **2026-08-09 — cross-branch blocker, user-decided: the AgentLoop↔Executor bridge is a separate,
  not-yet-scoped item. Neither Group 1 nor Group 2 builds it as a side effect of their own work
  until it's scoped on its own.** Group 1 (`claude/group1-event-bus-scheduler`, PR #21) found that
  `runtime/agentloop/AgentLoop.kt` has zero callers anywhere in the codebase outside its own
  module — it's a self-contained `AgentLoop.run(RunRequest): RunOutcome` with a `checkpoint`
  callback, with no relationship to `runs`/`tasks`/`attempts` or `executor`'s `drive()`
  step-machine. Two parallel, currently-disconnected execution models exist (`executor`'s SQLite
  step-machine, `agentloop`'s in-memory model-call loop), and nothing bridges them. That bridge
  is squarely in the path of **both** RFC-0005's wake-to-Run wiring (Group 1) and this item,
  "Finish `RealRuntimeClient`" (Group 2) — building it on either branch risks doing the same work
  twice, the exact collision the branch split was meant to prevent. Asked the user directly
  (2026-08-09); their answer was **hold it as its own tracked item, not owned by either branch
  yet** — so **this item ("Finish `RealRuntimeClient`") is now blocked on that separate item being
  scoped**, not just on `androidTarget()` (which is done). Do not build the AgentLoop↔Executor
  bridge as a side effect of wiring `RealRuntimeClient` to `storage`/`executor`/`capability` —
  wire what doesn't require it (e.g. `ProjectSummary`/`SessionSummary` persistence to `storage`),
  and stop at the point where continuing would require deciding how a Run actually gets executed.
  Flag that boundary explicitly if you hit it, the same way this link did.

  **Update (2026-08-09 — bridge built, own branch `claude/agentloop-executor-bridge-k7rko2`,
  scoped exactly as this note asked: as its own tracked item, not a side effect of either group's
  work.** Both `runtime/executor/RunCreator.kt` (how a Run comes to exist) and
  `runtime/executor/AgentLoopTaskRunner.kt` (a `TaskRunner` driving the model-call loop one Task
  at a time) are new. **`AgentLoop.kt` itself is deliberately not called by either** — read
  `AgentLoopTaskRunner`'s own class doc for the full reasoning, short version: `AgentLoop.run()`
  holds its whole transcript in a local variable across a `while` loop in one suspend call, which
  is exactly what RFC-0009 forbids for durable execution ("anything that must survive a step
  boundary is a column"). It remains a valid, non-durable, self-contained loop for contexts that
  don't need step-machine durability; it simply isn't the right shape to plug into
  `SqliteExecutor.drive()`, so the bridge is new code at the step machine's actual grain, sharing
  only the `kernel`/`prompt` types both are built from (accepted duplication: model resolution,
  the `TooBig` retry, termination conditions — a few dozen lines, flagged rather than forced into
  a shared abstraction with only one real caller so far).

  **The mechanism, mapped directly from RFC-0008's own table:** one step's model call is
  `Task(kind = MODEL_CALL)`; one tool call from the response is `Task(kind = TOOL_CALL)`, linked
  by a `tool_calls` row (`model_task_id`/`tool_task_id`, the `PRODUCED_CALL` relationship);
  termination is `drive()`'s existing "no runnable tasks, all terminal → COMPLETED" path, reached
  by a `MODEL_CALL` task appending nothing. The transcript — necessarily a query, never a variable
  held across the two Tasks' separate executions (D3) — is rebuilt each time from
  `attempts.output_snapshot` (one row per Task, `attempt_number` always 1: this bridge doesn't
  retry an attempt in place, a failing Task just fails its Run, matching `drive()`'s existing
  behaviour) plus `tool_calls`. Taint reads and writes `runs.taint_level` directly, so it survives
  a restart the same way the rest of the Run's state does.

  **A real, load-bearing gap this surfaced and fixed: `SqliteExecutor.drive()` had no way for a
  Task's execution to append a follow-on Task at all** — `TaskRunner.execute()` only ever returned
  success/failure. RFC-0009's own pseudocode says `execute(task) // may append new Tasks`, but
  nothing implemented that half. Fixed narrowly: `TaskResult` gained `appendTasks: List<NewTaskSpec>`,
  and `drive()` now wraps "this task is done" and "here is what comes next" in one SQL transaction
  (`SqlDriver.newTransaction()`/`Transacter`, reached via a bare `TransacterImpl(driver)` since a
  raw `SqlDriver` has no public transaction entry point of its own). This matters beyond the
  bridge: without it, a crash between the two writes would leave a Run whose task set looks
  all-terminal on resume, and `drive()` would complete it one step early, truncating a Run that
  still had a model turn to take. New tests (`TaskAppendingTest.kt`) cover this directly with a
  generic scripted `TaskRunner`, independent of the agentloop bridge, including the crash boundary
  (task RUNNING, no follow-on row — the same B3 shape `CrashRecoveryTest` already covers, checked
  again for the appending case specifically). `NewTaskSpec` also gained an `afterInsert: () -> Unit`
  hook, run inside the same transaction right after each task row lands — needed because
  `tool_calls.tool_task_id` is a foreign key onto a task that is minted (its id chosen) at
  `MODEL_CALL` execution time but doesn't have a row until `appendTasks` creates it; the first
  version of this tried to write the `tool_calls` row eagerly and hit
  `SQLITE_CONSTRAINT_FOREIGNKEY` immediately, which is exactly the kind of bug this hook exists to
  make structurally impossible rather than remembered.

  **`CrashRecoveryTest` confirmed still green, unmodified** — checked, not assumed, per this
  file's standing rule for anything touching `SqliteExecutor.kt`. `python3 schema/check.py` and
  `gradle jvmTest --continue` both clean except the two pre-existing, documented red modules
  (`:knowledge`, `:modelruntime` — GitHub Packages auth, unrelated).

  **Deliberately not built, flagged rather than silently absent — the bridge's job was the
  structural wiring, not the rest of RFC-0008's authorization boundary:**
  - **JSON Schema argument validation (RFC-0008 step 8b).** Every `tool_calls` row is written
    `schema_valid = 1` unconditionally. A real check needs a JSON Schema validator this codebase
    doesn't depend on yet.
  - **Capability resolution for a model's tool call (RFC-0008 step 8c).** `ToolCall.capabilityId`
    is always `null` — the same gap `AgentLoop.kt` already had (it never populated one either).
    `ToolBroker.invoke()` denies every call with `capability.missing` until something maps "a
    session, a tool name" → a held `CapabilityId`. That mapping is a separate, not-yet-built
    subsystem; inventing one inside this bridge would have been a bigger, unreviewed decision than
    this link should make alone.
  - **The approval flow (RFC-0008 step 8d, `Task(kind = CAPABILITY_REQUEST)`, `AWAITING_APPROVAL`,
    the `continuations` table).** `RoutingDecision.RemotePendingApproval` fails the Run outright
    today instead of parking it for a later approval event to resume. Building park/resume is
    substantial — its own `continuations` row, an event that un-parks it — and was ruled out of
    this link's scope the same way RFC-0009's own MVP section defers `CHECKABLE` recovery probes.
  - **Instruction sets (RFC-0016).** `instructionSet` is always `null`, matching `AgentLoop.kt`'s
    own default. Wiring `InstructionDiscovery` in is unrelated to bridging the two execution
    models and was left alone.
  - **`step_index` now counts every dispatched Task (`MODEL_CALL` and `TOOL_CALL` alike), not one
    increment per agent-loop turn the way `AgentLoop.kt`'s own `steps` counter did.** A tool-call
    round trip costs 2 of the Run's `max_steps` budget instead of 1. Left as-is rather than
    reconciled: `runs.step_index` is the *executor's* step counter, shared across every task kind
    by design (RFC-0009's own motivation: "the step ceiling that bounds spend is enforced by the
    same counter that drives execution"), and counting total task dispatches is arguably more
    correct — it bounds actual work, not just model calls. Flagged as a known behavioural
    difference from `AgentLoop.kt`, not reconciled further; reconciling it would mean redesigning
    one of the two step-counting conventions, which is a bigger decision than this link should
    make as a side effect.

  **What this unblocks, now buildable and not attempted in this link:**
  1. **RFC-0005's wake-to-Run wiring** — `SchedulerMatcher.match()` → for a woken session, a Run
     the way `RunCreator` now creates one for a user message, with the causing event as context
     instead of a `UserMessage`, → `drive()`. `SessionSubscriptionStore`/`SchedulerMatcher` (built
     by Group 1) are ready to consume; nothing calls `SchedulerMatcher` yet.
  2. **`RealRuntimeClient.sessions.send()`** wired to `RunCreator` + `drive()` instead of an
     in-memory stub — Group 2's "Finish `RealRuntimeClient`" checklist item, previously blocked
     exactly on this bridge existing, is unblocked as of this commit.
  3. **`TimerFired` (RFC-0004 MVP item 2)**, sequenced after this bridge per the task brief that
     scoped this branch, since `RuntimeClientWorkDispatcher.kt` calls `client.sessions.send()`
     directly — wiring emission around a call shape that was about to change underneath it would
     have been wasted work before this link, and isn't anymore.

  Neither of these three was attempted in this link — the brief scoped this branch to the bridge
  itself first, several links' worth on its own. Picking one of them up is the natural next
  session.
- [x] **Write the `android.app.Service` subclass** that wires `RuntimeServiceHost` into
  `onStartCommand`/`onDestroy`, per RFC-0050. Done 2026-08-09:
  `fi.italeino.aidos.service.AidosService : LifecycleService` (matching RFC-0050's own diagram
  name/base class). `onCreate` builds the notification channel and observes
  `runtimeServiceHost.state` to keep the ongoing notification live; `onStartCommand` starts a run
  from Intent extras if present and always calls `startForeground` (D24(a) — the foreground
  window has to be open for *any* run that might reach a model call, not just ones this Service
  instance started); `onDestroy` runs `shutdown()` via `runBlocking` on a dedicated
  `serviceScope` rather than `lifecycleScope`, specifically to avoid a real race: `lifecycleScope`
  is cancelled as part of `ON_DESTROY` dispatch, which would kill `shutdown()`'s
  `cancelAndJoin()` before the checkpoint-safe cancellation it exists to guarantee actually
  completes. Owns its own `RealRuntimeClient`, separate from `MainActivity`'s — **not yet bound
  together**, so state doesn't survive either component being recreated; that binding is the
  natural next step once one of them needs to actually observe the other's state. **Deliberately
  not done in this link, flagged rather than guessed at:** the Cancel notification action and the
  wake lock RFC-0050's D24(a) explicitly calls for. Manifest gained `FOREGROUND_SERVICE_DATA_SYNC`
  and `POST_NOTIFICATIONS` permissions and the real `<service>` declaration (`dataSync` type — no
  standard Android 14 FGS type names "runs the agent loop the user started" exactly; `dataSync` is
  the closest fit, revisit if that stops being defensible). `androidx.lifecycle:lifecycle-service`
  added as a dependency. Same verification caveat as the `MainActivity` change: this sandbox has
  no `ANDROID_HOME` at all, so CI's `build-and-publish` is the only real verification.
- [x] **Wire `MainActivity.kt` / the Compose screens to `RealRuntimeClient`, not
  `MockRuntimeClient`.** Done 2026-08-09. The UI itself (`Screens.kt`, `HomeScreen.kt`, `NavHost.kt`,
  `AidosTheme.kt`) was already real; it was driving the mock. `MainActivity.onCreate` now
  constructs `RealRuntimeClient()` instead of `MockRuntimeClient()` — a one-line swap since both
  implement `RuntimeClient` with a no-arg constructor. Didn't wait on `RealRuntimeClient` being
  *durable* first (M9's original framing was an in-process transport, in-memory is a legitimate
  intermediate step), per RFC-0050 MVP item 2. What this does *not* yet do: bind to the
  foreground service (`RuntimeServiceHost`, the "Write the Service subclass" item below) and get
  its client injected — each `MainActivity` instance today owns its own `RealRuntimeClient`, so
  state doesn't survive activity destruction. That binding is the natural next step once the
  Service subclass exists. Verification note: `androidMain` cannot compile in this sandbox at all
  (no `ANDROID_HOME`/`local.properties` — confirmed via `gradle :androidapp:compileDebugKotlinAndroid`,
  fails immediately on SDK location, not a code error) — CI's `build-and-publish` is the only way
  this specific change gets verified; `gradle jvmTest` only proves `commonMain`/`jvmMain` compile.
- [x] **RFC-0055 project locking — done 2026-08-09, via `RealRuntimeClient.projects.open()`/
  `.create()`, not `daemon/main.kt`'s startup path.** The original checklist item ("call
  `ProjectLock.acquire()` from `daemon/main.kt`'s startup path") was confirmed wrong in an earlier
  link: RFC-0055's own "Project locking" section locks a project when it's *opened*, not when the
  daemon starts. Implemented via the `ProjectLocker` interface/`JvmProjectLocker` described above
  — `create()`/`open()` call `tryAcquire`, translate `HeldByOther` into
  `ProjectResult.Error("runtime.locked_by_other_instance", ...)`, and `close()`/`delete()` release.
  Tested against real `FileLock` contention (two `RealRuntimeClient` instances sharing one temp
  directory) in `RealRuntimeClientPersistenceTest`. **Not done**: RFC-0055 says "locks are never
  broken silently" — `ProjectLockOutcome.AcquiredAfterBreakingStale` is handled (acquisition
  proceeds) but not *surfaced* anywhere (no audit-log write, no event emitted) since no such write
  path exists from this class yet. Flagged in code (`RealRuntimeClient.kt`, both `create()`/
  `open()`), not silently swallowed.

None of this is new design — every RFC and decision referenced above already exists. This is
implementation catching up to documents that were, in several cases, marked complete before the
code was.

---

## RFC/MVP Readiness Audit — 2026-08-10 (Part 1: Phase 0/1 + re-verification of the 2026-08-09 review)

Commissioned separately from the work above, on its own branch (`claude/rfc-mvp-audit`), with an
explicit brief: don't trust this file's own status lines or the 2026-08-09 review's claims of
resolution without independently grepping code and running tests. This entry is the first
installment — Phase 0/1 (M0.1–M8) plus re-verification of the 2026-08-09 review's "Outstanding
work" section above — not the full corpus. **This audit is not complete as of this entry; do not
read it as a final MVP-readiness verdict.** See the tracking doc, `docs/rfc-mvp-audit-tracking.md`,
for what's left and continued in dated updates below as later links land.

**Method:** two independent subagents, each with no access to the other's findings, tasked with
re-deriving evidence from scratch — grep the actual `runtime/` source for the classes and line
counts this file claims, read enough of each file to judge whether it's real logic or a stub, find
and count the actual `@Test` functions, and run the tests that could be run in this sandbox.
Neither agent was told what the other found. Below reports where their independent conclusions
landed.

### Baseline: PR #27 status and the real `gradle jvmTest --continue` result

**PR #27 (`claude/fix-baseline-modules`, fixes `:knowledge`/`:modelruntime`) is still open, not
merged, as of this audit** — its base is `main`@`c9173b5`, the exact commit this audit branch is
also cut from. So on this checkout, `:knowledge` and `:modelruntime` are still red.

Ran `gradle jvmTest --continue` to completion (not assumed from a PR description): **28 of 30
modules compile and pass with 0 failures, 0 errors** — `:kernel`, `:storage`, `:settings`,
`:identity`, `:capability`, `:broker`, `:executor`, `:lock`, `:api`, `:cli`, `:filesystem`, `:git`,
`:http`, `:vault`, `:prompt`, `:agentloop`, `:memory`, `:mcp`, `:cookbook`, `:huggingface`,
`:downloads`, `:models`, `:routing`, `:worker`, `:retention`, `:androidapp`, `:daemon`, `:voice`.
**`:knowledge` and `:modelruntime` both fail at `compileKotlinJvm`**, both on a `401 Unauthorized`
resolving a private GitHub Packages coordinate (`io.github.jsilvanus:gitsema-core-jvm` /
`de.kherud:llama-java:0.3.2`) — this sandbox has no credentials for that registry. This matches
this file's own prior claim exactly. The deeper bugs PR #27's description attributes to real CI
(missing `kotlinx-datetime`/`kotlinx-serialization-json` deps in `knowledge/build.gradle.kts`; a
genuinely-nonexistent `llama-java:0.3.2` coordinate) could not be independently reached from this
sandbox — the 401 wall is hit first — but the missing-dependency claim was independently
corroborated by reading `runtime/knowledge/build.gradle.kts`'s `jvmMain` dependency block (only
`kotlinx-coroutines-core` declared) against `IndexingJob.kt`'s actual imports
(`kotlinx.datetime.Clock`, `kotlinx.serialization.json.JsonObject`) — the missing-dependency
diagnosis is plausible by inspection, not just assumed from the PR body.

### Re-verification: the 2026-08-09 review's "Outstanding work" resolutions are real, not narrative

Independently checked all thirteen files/mechanisms the "Outstanding work from this review"
section above claims were built 2026-08-09/10: `SqliteIntentStore` (6 tests, exact match),
`TopicMatcher` (9 tests), `SubscriptionRegistry` (6 tests), `EventTypes` (14 constants, 2 tests),
the four emission call sites (`ToolCompleted` in `SqliteExecutor.drive()`, `GitCommit` in
`GitTool.gitCommit()`, `FileModified`/`FileCreated` in `FilesystemTool.write()`, `TimerFired` in
`RuntimeClientWorkDispatcher`), `SessionSubscriptionStore` (4 tests) + `SchedulerMatcher` (8
tests), `Scheduler.kt` (5 tests), `RunCreator.kt` + `AgentLoopTaskRunner.kt` (2 + 6 tests, plus
`TaskAppendingTest.kt`'s 4), `RunExecutor`/`SqliteRunExecutor` (3 tests), `RuntimeCompositionRoot`
(2 tests), `SqliteContentNodeStore` (8 tests), `DegradationLadder`/`SqliteDegradationEventStore`
(10 + 6 tests), and the RFC-0043 "still nothing built" claim.

**All thirteen are CONFIRMED** — every file is real, non-stub logic; every claimed test count was
independently recounted and matched exactly (not approximately) in every case checked. This is a
meaningfully different outcome from the 2026-08-09 review's original findings about these same
RFCs, and the difference is real: the 2026-08-09 review was correct about the state of the code
*that day*; the follow-up work in the "Outstanding work" section actually landed, on `main`, with
tests, across PRs #18–#26 (all confirmed merged via `git log`). **To directly answer the
commissioning question — was the 2026-08-09 review ever fully completed or acted upon: yes, for
everything the review itself flagged.** Its findings were not left to rot in PIPELINE.md prose;
they were followed by real commits.

This does not mean the underlying RFCs (0004/0005/0012/0024/0043/0045/0047) are *fully* built —
several of the "Outstanding work" entries are explicit about deliberately-out-of-scope remainders
(e.g. RFC-0012's `intent_proposals`/`intent_edges` still unwired; RFC-0005's timer/scheduled-job
path still post-MVP by the RFC's own text). Those remainders are accurately flagged already in this
file's own prose and were not re-litigated here — the audit's job was to check whether the *claimed
work* was real, not to re-open scope questions this file already answered with a citation to the
RFC's own MVP section.

### Phase 0/1 re-verification (M0.1–M8)

Independently re-derived, not copied from this file's own summary table:

- **M0.1 (canonical DDL) — CONFIRMED**, with one trivial drift: `python3 schema/check.py` reports
  **59 tables** across the three schema files (`user.sql: 13`, `vault.sql: 3`, `project.sql: 43`),
  not the "58 tables" this file's own summary table (near the top of this file) states. Cosmetic,
  not a fabrication — worth fixing the number next time that table is touched.
- **M0.2 (`runtime/kernel/`) — CONFIRMED.** 16 files, 2172 lines, `allWarningsAsErrors` on. Exactly
  one non-DTO `class` in all of `commonMain` (`RelPath`, `Capabilities.kt:161`) — a
  construction-validated value type, not a hidden implementation; the kernel-is-contracts-only rule
  holds. `ContractTest.kt`: 14/14 green.
- **M0.3 (`docs/decisions.md`) — CONFIRMED, count is higher than the roadmap's own stale figure.**
  35 `### D` headings (D1–D35), every one `SETTLED`, none `OPEN`/`RECOMMENDED`.
  `docs/mvp-roadmap.md`'s M0.3 row still says "26 settled decisions" from an earlier pass — also
  cosmetic drift, not a fabrication, but worth a fix in the same pass as the table-count one above.
- **M1 (storage/settings) — CONFIRMED, precisely.** `MigrationRunner.kt` implements RFC-0040's
  bootstrap/ready/read-only state machine exactly, including the `storage.migration_required`
  read-only path (never a refusal) RFC-0017 calls for. `runtime/settings/` totals **848 lines**
  across four files, matching this file's own claim exactly; `SettingsTest.kt` has **18** `@Test`
  functions, also an exact match, and they're real (SECURITY/SPEND rejection, fail-closed on
  invalid value, per-line TOML error continuation) — not padding.
- **M2 (identity/scopes) — CONFIRMED.** `UuidV7Generator` has real `actual` implementations in both
  `jvmMain` and `androidMain`. `ProjectRegistry.resolveById()` returns a real `ProjectMovedError`,
  not a null. `SettingsStore` enforces the SECURITY/SPEND project-write rejection at 4 call sites.
- **M3 (capability manager) — CONFIRMED in substance, one overstatement.** `SqliteCapabilityManager`
  is real and its taint-ceiling/revocation-by-epoch tests pass. **The roadmap's own done-when calls
  the escape-guard test a "property test... covering every encoding"; the actual test
  (`CapabilityTest.kt:104-126`) is a fixed list of ~8–11 hand-picked strings, not generative
  fuzzing** — no `Arb`/`checkAll`, no URL-encoding or Unicode-confusable coverage. A solid
  example-based regression suite, not literally the property test the done-when describes. Worth
  either writing the real property test or correcting the done-when's wording.
- **M4 (audit log) — the one milestone with a real, findable functional gap, not just a wording
  overstatement.** `ToolBroker`'s 8-step sequence and `AuditLog` both exist and are exercised by
  8/8 green tests — but those tests don't happen to hit two silent-drop paths that do exist in the
  real code: **(1)** `ToolBroker.kt:68-70` returns `denied()` directly when no tool matches a call,
  bypassing the audit write entirely for a `tool.not_found` outcome. **(2)** `AuditLog.kt:36`:
  `if (projectId.isBlank()) return id // no project context — skip` — a write call that arrives
  with no resolvable project ID is silently dropped, contradicting the class's own doc comment two
  lines above ("cannot be turned down, sampled, or dropped under pressure"). **(3)** `ToolBroker.kt`'s
  own docstring claims an `AuditEnforcingBroker` wrapper class enforces complete coverage "as a test
  failure, not a review comment" — **no such class exists anywhere in the repo** (grepped, zero
  matches); the actual check in `AuditTest.kt:114` is a loose `assertTrue(after > before, ...)`, not
  an enforcing harness. M4's own done-when says "an effect with no audit row is a test failure,
  enforced by the broker harness, not by review" — as written today, that's not true for either
  silent-drop path. This is exactly the kind of gap the "Accepted is not frozen" discipline exists
  to catch, and it wasn't caught by the 2026-08-09 review (M4/RFC-0003/RFC-0037 weren't named in
  its list of exceptions).
- **M5/M6 (execution graph, executor, recovery/runaway bounds) — CONFIRMED, strong.** All claimed
  files are real (not re-listing them here, see the "Outstanding work" section above and the
  re-verification paragraph above it) — 64 executor tests total, 0 failures. `Scheduler.kt` writes
  `WakeRefused` audit rows for both self-wake and causal-depth-ceiling refusals, independently
  confirmed against a real test asserting the audit row exists.
- **M7 (project lock) — CONFIRMED.** `ProjectLock.kt` uses real `FileChannel.tryLock`, heartbeat
  metadata, and a 3-minute staleness threshold matching RFC-0055. 5/5 tests green.
- **M8 (crash-recovery suite) — CONFIRMED functionally, one wording overstatement worth flagging.**
  `CrashRecoveryTest.kt`: 5/5 green, covering B1–B4 plus idempotency, each a real assertion against
  concrete DB-row state, not a stub. **But no test anywhere in the repo literally forks a process
  and sends `SIGKILL`** — every "crash" boundary is simulated by writing the post-crash row state
  directly via SQL, then calling `recover()`/`drive()` on a *fresh executor instance*, not a fresh
  process. This is a reasonable, common substitute for testing DB-durable checkpointing and it does
  exercise the real recovery paths — but the literal "`kill -9`" language this file (line 137) and
  `docs/mvp-roadmap.md`'s M8 done-when both use to describe the guarantee is not what's tested.
  Whether that gap matters depends on whether SQLite's own durability guarantees (WAL +
  `synchronous=NORMAL`, confirmed in M1) are considered sufficient to stand in for an actual
  process-kill test — a judgment call for the project owner, not resolved here.

## RFC/MVP Readiness Audit — 2026-08-10 (Part 2: Phase 2, M9–M19)

Same method as Part 1: independent subagents (three this time, split by subsystem, none shown the
others' output) re-deriving file paths, line numbers, and test counts from scratch, running every
gradle target they could reach with `--rerun-tasks` so results reflect a real execution, not a
cached pass. **Housekeeping first: PR #27 (baseline module fixes) merged to `main` at
`2026-08-10T06:36:55Z` (`8e7bbb1`) between Part 1 and Part 2. `main` was merged into
`claude/rfc-mvp-audit` (commit `36cae89`) before dispatching Part 2's agents, so this Part's
baseline reflects it: `:modelruntime` is now genuinely green (independently re-run, not assumed);
`:knowledge` still fails locally on the same pre-existing 401 registry-auth wall (a sandbox
credentials gap, not a code defect — matches PR #27's own description exactly).**

**This Part found the audit's most significant gaps so far — several milestones marked ✅ with no
caveat turn out to have real, load-bearing holes.** Phase 2 is the phase that gates G2 ("create
project → task → model → tool → commit → artifact → audit, from the CLI, in one command
sequence"), and this file's Status table and `docs/mvp-roadmap.md` both currently mark **G2 as
passed (M19 ✅)**. The findings below do not support that.

- **M9 (Runtime API) — CONFIRMED for the core claims**, and the `RealRuntimeClient`-in-memory
  caveat this file's own Status table still carries (near the top of this file) is now stale — the
  wiring chain `RuntimeClientFactory → RealRuntimeClient.sessions.send() → SqliteRunExecutor →
  RuntimeCompositionRoot → SqliteExecutor.drive()` was traced end to end and confirmed real, and
  `daemon/main.kt` was independently confirmed to actually construct `RealRuntimeClient`, not
  `MockRuntimeClient` (a stale comment in that file says otherwise — cosmetic, worth a fix next
  time that file is touched). Two real gaps found, neither previously flagged: `diff.hunks()`
  throws `UnsupportedOperationException` in **both** `MockRuntimeClient` and `RealRuntimeClient`
  (`api/.../MockRuntimeClient.kt:201-202`, `RealRuntimeClient.kt:462-463`) and `diff.changes()`
  hardcodes an empty `DiffSummary` in both — despite the structured-hunk *type* (`Diff.kt`) being
  real, nothing actually computes hunks from a real diff yet. `EventFilter.types` is declared but
  silently never consulted by either client's `matchesFilter()` (`MockRuntimeClient.kt:264-266`,
  `RealRuntimeClient.kt:524-526`) — filtering the event stream by event type is a no-op today.
- **M10 (CLI frontend) — OVERSTATED, no caveat in this file despite a real gap.** **No runnable CLI
  executable exists anywhere in the repository.** `runtime/cli/` has no `application` plugin, no
  `mainClass`, and produces only a library jar; the only `fun main()` in the relevant modules is
  the *daemon's* (`daemon/main.kt:77`), not a CLI. `AidosCli.kt` is a plain Kotlin class meant to be
  called from other Kotlin code (i.e., from tests), not parsed from a terminal. The transport it
  would need over a socket doesn't exist either: `RuntimeSocketServer.start()`
  (`daemon/.../RuntimeSocketServer.kt:36-54`) creates the socket directory, deletes any stale
  socket file, **and then never opens a socket** — it prints `"Socket server would listen on
  $socketPath"` and returns, with an explicit `TODO(M33): Implement actual socket server` still in
  place. `DaemonCliIntegrationTest.kt`'s own comment says as much: *"Full socket transport is
  deferred to Phase 4.5; this verifies the architecture is sound by using in-process
  RuntimeClient."* Every behavior in M10's done-when (create project, list sessions, send message,
  watch event stream, approve, `sinceSequence` gap replay) is real and tested — but only as library
  calls against `MockRuntimeClient`/an in-process `RealRuntimeClient`, never as something a person
  types at a terminal. `sinceSequence` gap replay itself is real, tested code
  (`CliFrontendTest.kt:102-125`), not a gap — the gap is specifically "from the CLI" in the literal
  sense the done-when states, and there is no caveat anywhere in this file's M10 ✅ comparable to
  the ones it uses for other partial milestones (e.g. `RealRuntimeClient`'s prior in-memory note,
  Voice's `NoOp`-provider note).
  **Update (2026-08-10, branch `claude/fix-audit-gaps-m10-m19`) — fixed.** Both halves of this
  finding are addressed: `runtime/cli/src/jvmMain/kotlin/dev/aidos/cli/Main.kt` is a real
  `fun main(args: Array<String>)` that parses subcommands (`create-project`, `list-sessions`,
  `send`, `watch-events --since`, `grant`, `approve`, `list-pending`, `ping`, `version`) and drives
  `AidosCli` — a person can now type this at a terminal, given a running daemon. `RuntimeSocketServer`
  (`daemon/.../RuntimeSocketServer.kt`) is a real `ServerSocketChannel.open(StandardProtocolFamily.UNIX)`
  server: newline-delimited JSON, a connection-token handshake minted at daemon startup
  (`RFC-0052` Authentication / `RFC-0055` Security), and `user_interactive` enforcement refusing
  `capabilities.grant`/`approve` over a non-interactive connection. `SocketRuntimeClient`
  (`cli/.../SocketRuntimeClient.kt`) is the client half, a real `RuntimeClient` implementation —
  `AidosCli` runs against it unmodified, the same class the mock/in-process tests already exercised.
  Wire codec is hand-written (`api/.../socket/Wire.kt`) rather than a generic reflective dispatcher,
  scoped to exactly the methods M10's done-when needs (project/session/capability/event/runtime-info);
  `DiffQueries`/`ArtifactQueries`/`KnowledgeQueries` are explicitly not yet on the wire and throw a
  named `UnsupportedOperationException` rather than silently no-opping — a real, bounded, documented
  gap for a later link, not the prior undocumented total absence. Proven end-to-end by
  `daemon/.../RealSocketIntegrationTest.kt`, which spawns `dev.aidos.daemon.MainKt` as a genuine
  subprocess (not in-process, not mocked) and drives project/session/send/ping/version and a real
  `events.subscribe` with `sinceSequence` replay over the actual socket, plus negative tests for a
  wrong connection token and a non-interactive `grant` refusal. One real bug found and fixed writing
  that test: `events.subscribe()`'s blocking `BufferedReader.readLine()` does not observe ordinary
  Flow cancellation (`take(n)`, a collector's `withTimeout`) — confirmed by an end-to-end run where a
  `take(1)` collector let two events pass through before the read wedged forever on a third that
  never arrived. Fixed by moving the read loop to a dedicated thread bridged through `callbackFlow`,
  whose `awaitClose` is guaranteed to fire for every way a flow's collection can end, and closing the
  channel there — closing a blocking NIO channel from another thread is what actually wakes a blocked
  read. `gradle jvmTest --continue` clean across `:api`/`:cli`/`:daemon` (the whole-project run is
  clean except the pre-existing, sandbox-only `:knowledge` 401).
- **M11 (Effect broker) — CONFIRMED for ordering and `descriptorsFor` filtering** (independently
  read `ToolBroker.invoke()` top to bottom: tool-resolution → capability → taint-validate →
  budget-stub → preview → audit → execute → audit, matching the claimed order exactly), **with one
  gap not previously flagged**: `ToolBroker`'s own class docstring claims step 1 is "validate
  arguments against the operation's JSON Schema" — **this step does not exist anywhere in
  `invoke()`**; `descriptor.inputSchema` is set on every tool but is only ever read by
  `AnthropicAdapter` to advertise the schema *to the model*, never read back to validate
  `call.arguments` before execution (confirmed by grep across all of `runtime/`). RecoveryClass
  rejection at registration is real only as a Kotlin compile-time guarantee (the field is
  non-nullable) — `ToolBroker.register()` performs no runtime check, which the module's own test
  file candidly documents in a comment, but which the roadmap's plain-English "a tool registered
  without a `RecoveryClass` is rejected at registration" overstates as a runtime guard.
- **M12 (Filesystem tool) — CONFIRMED, no new gaps found.** All four operations go through
  `ResourceHandle`/`RelPath`, `write()` computes a real LCS-based diff, escape denial is
  confirmed at the handle layer (`SqliteDirHandle` adds real canonicalization + prefix-confusion +
  symlink-escape guards on top of `RelPath`'s construction-time check). 13/13 tests green.
- **M13 (Git tool) — CONFIRMED for the seven operations and `push`'s `UNSAFE` tag; OVERSTATED for
  reconciliation.** All seven JGit operations are real against a real repository; `push` is tagged
  `RecoveryClass.UNSAFE` and the executor's generic `recover()` path (not broker-specific code)
  correctly marks it `INDETERMINATE` rather than retrying, tested. **RFC-0053's actual
  reconciliation protocol — fingerprint-mismatch detection gating Run start, a `reconciliations`
  table with five change classifications, tracked invalidation/dangling-node/termination counts —
  is entirely unbuilt.** `grep -rn "reconciliations|fingerprint" runtime/` returns zero hits outside
  the schema file; the `reconciliations` table (`schema/project.sql:721-735`) is never written or
  read anywhere. What exists is that `gitStatus()` re-opens the repo and calls JGit's own
  `status()` fresh each time, which trivially reflects on-disk edits because JGit reads live
  state — not because any reconciliation logic ran. The single "reconciliation" test only asserts a
  status call after an external edit shows the file; it exercises none of RFC-0053's actual
  protocol. M13's done-when ("Reconciliation handles the user changing the working tree outside
  Aidos between two Aidos steps") reads as satisfied by this test but the RFC it cites specifies
  much more than "status happens to be live."
  **Update (2026-08-10, branch `claude/fix-audit-gaps-m10-m19`) — fixed.** `repo_fingerprints`
  and `reconciliations` are both real read/write tables now: `git/.../Reconciliation.kt` computes
  a `RepoFingerprint` (head ref, head commit, an index-content SHA-256 standing in for JGit's
  non-public `DirCache` checksum, dirty-path count) and classifies a mismatch into the RFC's five
  classifications (`HEAD_MOVED`/`BRANCH_SWITCHED`/`HISTORY_REWRITTEN` via `RevWalk.isMergedInto`/
  `INDEX_CHANGED`/`WORKTREE_DIRTIED`), each independently tested against a real repository
  (`ReconciliationTest.kt`, 6 tests, one exercising each classification plus the no-change case).
  `daemon/.../GitRunReconciler.kt` is the SQL orchestration — re-hashes `content_nodes` rows
  backed by a git-tracked `FilesystemPath`, marks `IMMUTABLE` nodes `DANGLING` and `VERSIONED`
  nodes `SUPERSEDED`-plus-a-new-version on a hash change (RFC-0053's own per-object-class table),
  marks unreachable `GitObject` nodes `DANGLING` after `HISTORY_REWRITTEN`/`BRANCH_SWITCHED`,
  terminates every `INTERRUPTED`/`YIELDED` ("parked") Run on the project with
  `FAILED(run.repo_mutated)`, and writes one `reconciliations` row with real counts — tested
  end-to-end against a real repository and a real SQLite project DB in `GitRunReconcilerTest.kt`
  (5 tests: baseline establishment, no-op on no change, `HEAD_MOVED` classification recorded, a
  parked Run terminated while a fresh `PENDING` Run in the same check is correctly left alone, an
  `IMMUTABLE` node dangling on an external edit). Wired into the actual gate: `SqliteExecutor`
  gained a nullable `RunReconciler` seam (`executor`'s own `commonMain`, JGit-free — the concrete
  JVM implementation is injected by `RuntimeCompositionRoot`) consulted immediately before the
  `PENDING`/`INTERRUPTED`→`RUNNING` transition, covered by `RunReconcilerGateTest.kt` (3 tests,
  reconciler test-doubled) proving `drive()` actually calls it and honors a termination verdict
  rather than just having the table exist unread. `intent_conflicted` is always written 0 and "on
  project open" fingerprinting is not wired — both named explicitly, not silently cut; see the
  Status table's Git row above for the same note with file references.
- **M14 (Secrets vault) — OVERSTATED.** The vault round-trip itself is real (AES-256-GCM, real
  SQLite, `CharArray` zeroing) and `AnthropicAdapter`'s tool-call normalization is real. **But the
  "never appears in a log, an event, an audit row, or a prompt" claim has no enforced code path
  anywhere**: `Redactor.kt`'s own docstring claims every string crossing a persistence/transmission
  boundary passes through `redact()`, and that "the vault calls `register` on load" — both false;
  `grep` for any call site of `Redactor()`/`.register(`/`.redact(`/`.detect(` outside `Redactor.kt`
  itself and its own unit test returns **zero** results anywhere in `runtime/`. No logger, audit
  writer, or event emitter references it. **`attempts.provider_retention_json` — the specific
  column the done-when names — is declared in schema but has no writer anywhere in the codebase.**
  The only production writer of `attempts` rows, `AgentLoopTaskRunner.writeAttempt()`, omits the
  column from its `INSERT` entirely; `AnthropicAdapter.providerRetentionJson` is read only by its
  own test and by an Android presenter whose own comment admits it's showing a placeholder, not the
  real column. There is no second provider and no `ModelAdapter`-interface-level retention field,
  so the specific "UNKNOWN when a provider states no policy, never an assumed-benign default" rule
  has nothing implementing it to test — the one test that mentions "UNKNOWN" only checks a hardcoded
  string doesn't literally contain that word, not the actual fallback behavior.
  **Update (2026-08-10, branch `claude/fix-audit-gaps-m10-m19`): two of the finding's specific,
  checkable claims are now fixed, not all six of RFC-0035's redaction boundaries.** `ModelAdapter`
  (kernel) gained a `providerRetention: ProviderRetention?` property (`RetentionPolicy`,
  `TrainingUse`, `recordedAt` — RFC-0026); `AnthropicAdapter` reports a real `ZERO`/`NONE` policy
  through it (replacing the old dead `providerRetentionJson` field nothing read). `SqliteSecretsVault`
  now takes a nullable `Redactor` and calls `.register()` in `resolve()`/`.unregister()` in `delete()`
  — the vault-side half of the docstring's own claim, proven by a new round-trip test
  (`VaultTest.kt`: store → resolve registers it, `redact()` masks it, `delete()` unregisters it).
  `AgentLoopTaskRunner.writeAttempt()` now takes a `redact: (String) -> String` seam (default
  identity, so existing callers/tests are unaffected) applied to `output_snapshot` before the
  `INSERT`, and a `providerRetention` parameter serialized into the now-populated
  `provider_retention_json` column with `recordedAt` stamped fresh at write time; the MODEL_CALL call
  site passes `adapter.providerRetention ?: ProviderRetention(UNKNOWN, ...)` for every non-local
  adapter — the specific "never assumed-benign" rule the finding named, now with 4 tests exercising
  local (null), remote-with-no-stated-policy (UNKNOWN fallback), remote-with-a-stated-policy, and
  redaction-applied-before-persistence. `RuntimeCompositionRoot` constructs one `Redactor` per
  `drive()` call, registers the Anthropic key with it before the adapter is ever invoked, and passes
  `redactor::redact` into `AgentLoopTaskRunner`. **Deliberately still not wired, named rather than
  silently claimed done: 4 of RFC-0035's 6 redaction boundaries** — events, prompt packages,
  diagnostic logs, and memory entries/exports never call `redact()`; only `attempts.output_snapshot`
  (this update) and the vault's own register/unregister (this update) are covered. A secret that
  reaches one of those other four paths without first passing through an `attempts.output_snapshot`
  row is not caught today.
- **M15 (Prompt construction) — PARTIALLY OVERSTATED.** Token budget derivation and the two-phase
  negotiation are both real and the negotiation is structurally, not just conventionally, bounded to
  one retry. The adoption gate itself is real, tested code in `PromptAssembler`/
  `InstructionDiscovery` — **but it never engages in production**, because `AgentLoopTaskRunner`
  (the actually-wired task runner) never calls `InstructionDiscovery` and always assembles with
  `instructionSet = null`, a gap the class's own header comment already discloses. So in the current
  runtime, no `AGENTS.md`/`CLAUDE.md` content — adopted or not — reaches any real Run's system turn
  today; the security fix RFC-0016's own history describes is real at the unit level but not live
  end-to-end. **`runs.instruction_set_hash` — the specific column the done-when names — is declared
  in schema and never written anywhere** (grepped exhaustively; the in-memory hash is computed
  correctly but only reaches a no-op default `checkpoint` callback in the unused `AgentLoop.kt`, not
  the wired `AgentLoopTaskRunner`).
  **Update (2026-08-10, branch `claude/fix-audit-gaps-m10-m19`): both specific gaps are fixed.**
  `AgentLoopTaskRunner.executeModelCall()` now calls a new `discoverInstructionSet()` (reads
  `projects.root_path`, then `InstructionDiscovery.discover()`) on every `MODEL_CALL` task, checks
  the discovered hash against `instruction_adoptions` (the real schema table RFC-0016 defines — it
  had zero code touching it before this), and passes the result into `AssemblyRequest.instructionSet`
  so `PromptAssembler`'s existing adopted/unadopted gate finally has live input instead of always
  seeing `null`. `runs.instruction_set_hash` is written after every assembly (`pkg.instructionSetHash`,
  updated each `MODEL_CALL` so a Run reflects its most recently governing set, not a stale first-turn
  value). 4 new tests: no files → null hash; an unadopted `AGENTS.md` is discovered (hash recorded)
  but its text does not reach the system turn; an adopted one does reach it and its hash matches;
  plus the pre-existing `PromptAssembler`/`InstructionDiscovery` unit tests, unchanged. **Still
  deliberately absent, not silently claimed done: nothing writes to `instruction_adoptions` anywhere
  in the codebase.** `discoverInstructionSet()` reads that table but there is no session/UI adoption
  flow that could ever insert a row — a freshly discovered instruction file is correctly excluded
  from every system turn and will stay that way until some other, not-yet-built part of the system
  (RFC-0016's own "diff-review surface") adopts it. That gap is real but is not what M15's
  done-when names (`runs.instruction_set_hash` + the adopted/unadopted gate), so it is out of this
  link's scope, flagged rather than quietly left implied-fixed.
- **M16 (Agent loop with trust and taint) — OVERSTATED.** The step cycle and taint monotonicity are
  real (`TrustLevel.raisedBy` is monotonic by construction, tested at both the kernel-contract and
  executor-integration level) and egress denial under taint is real and tested. Schema/capability
  validation being stubbed was already known (Part 1's predecessor review flagged it). **What's new:
  "a tainted Run... escalates naming the specific untrusted content" is not implemented in the
  production path at all.** The kernel has a field for exactly this
  (`Run.taintSourceNodeId: ContentNodeId?`, matching schema column
  `taint_source_node_id`), but grepping the whole tree for writers of it finds only the kernel
  declaration and a schema-mapping test — `AgentLoopTaskRunner` never populates it, and the denial
  message that does reach the model is a bare `DenialReason` enum name with no content named. The
  version that does name something (the tool's name, not the actual content) exists only in the
  confirmed-unused `agentloop/AgentLoop.kt`. RFC-0027's own MVP list names this item explicitly
  ("Escalation events naming the specific tainting content"), so this is a real gap against the RFC
  the milestone cites, not a generous reading of ambiguous wording. No D6 violation (model
  confirming its own success) was found by direct search.
  **Update (2026-08-10, branch `claude/fix-audit-gaps-m10-m19`): both halves fixed, scoped to what
  the current tool surface can actually name.** `AgentLoopTaskRunner.executeToolCall()` now writes
  `runs.taint_source_node_id` — once, at the exact moment a Run first leaves `TRUSTED` (matching
  RFC-0027's Data Model comment "first node that raised the taint" literally) — whenever the
  tainting `ToolCallResult.content` includes a `ContentBlock.ResourceRef`. **Honest limit: no
  production tool returns one today** (`FilesystemTool`/`GitTool` are Text-only), so this column
  stays `null` for every Run in the current system; the writer is real and tested (a fake tool
  returning `ResourceRef` proves it fires), but nothing in the actually-registered tool set
  produces the content-node provenance it needs — a real content-node-per-file-read pipeline is a
  separate, larger subsystem (adjacent to RFC-0024 Resource Graph, already flagged unbuilt
  elsewhere in this file) that this link did not build. Separately, and independent of that gap,
  every taint-attenuated denial (`DenialReason.ATTENUATED_BY_TAINT`) now has its text augmented
  with "`(Run is tainted by: <tool name>)`" — the tool operation that first raised the taint,
  looked up from durable `tool_calls`/`attempts` rows (no in-memory tracking across the Run, D3),
  not the bare enum name the audit found. This is a real improvement over the prior "bare
  `DenialReason` enum name" and over `agentloop/AgentLoop.kt`'s own dismissed precedent (which the
  audit noted named the denied call's own tool, not the tainting one) — it names *which earlier
  call* tainted the Run — but it is still a tool-operation name, not the file path or content
  RFC-0027's example message shows (`"read untrusted content from node_modules/left-pad/README.md"`);
  that level of specificity needs the same content-node pipeline named above. 4 new tests
  (`AgentLoopTaskRunnerTest.kt`): the denial names the correct earlier tool call across two
  sequenced tool calls; `taint_source_node_id` populates from a `ResourceRef`; it stays `null` for
  Text-only content; existing taint-monotonicity tests unchanged and still green.
- **M16b (Session memory) — CONFIRMED.** All three D33 promotion constraints are genuinely
  schema-level `CHECK` constraints, verified by tests that attempt to bypass the store class with
  raw SQL and confirm the database itself rejects the write — not just application discipline. No
  `SUMMARY` kind exists anywhere. One narrow caveat: "mandatory `source_refs`" is `NOT NULL` at the
  schema level but the stronger "never `[]`" rule is enforced only in application code
  (`SessionMemoryStore`'s `require()`), not a `CHECK` — a raw INSERT bypassing the store class could
  still write an empty array. 9/9 tests green, matching this file's own claimed count.
- **M17 (Injection suite) — OVERSTATED.** The 7-test corpus is real and the payloads are genuinely
  adversarial (DAN-style jailbreak text, fake `SYSTEM:` overrides, nested JSON injection), matching
  the claimed 7 categories exactly. **But every test in the suite drives `agentloop.AgentLoop` — the
  confirmed zero-caller, non-production loop — not `executor.AgentLoopTaskRunner`, the loop that
  actually runs.** `grep` for `AgentLoopTaskRunner` inside the injection test file, and for
  "injection" inside `executor/src/`, both return nothing. The taint mechanism the suite verifies
  (`raisedBy` monotonicity, broker denial) is structurally shared between both loops, so the
  protection likely extrapolates — but that is an inference, not something any test demonstrates,
  and "a corpus of hostile content, none of which escalates authority" currently proves that claim
  about a loop nothing in production calls.
- **M18 (MCP, both transports) — NOT FOUND. The largest gap this audit has found.** The entire
  module is two files and 160 lines, and implements only `ToolDescriptor` mapping from a
  caller-supplied, already-parsed `McpServerRegistration` — nothing populates that registration from
  a real server. **No MCP client, no transport, no protocol exists anywhere in the codebase**:
  zero JSON-RPC, no `initialize`/`tools/list`/`tools/call` (grepped across the whole tree). **No
  stdio transport**: `grep -rn "ProcessBuilder" runtime/` returns zero results in the entire
  codebase — nothing anywhere spawns a subprocess, so there is no scrubbed-environment question to
  even evaluate; the "scrubbed child environment" done-when clause has nothing to check because
  there is no child process. **No real HTTP transport**: `mcp/build.gradle.kts` declares
  `ktor-client-core`/`ktor-client-cio` as dependencies but `grep -rln "io.ktor" mcp/` finds zero
  actual usage — the dependency is unused. `validateHttpEndpoint()` only string-matches a URL
  prefix; there is no TLS handshake, no certificate validation, and the class's own doc comment
  admits cross-host redirect refusal is "not enforced here — the HTTP client must be configured."
  D30 (no MCP-triggered capability escalation) is technically true only because **no invocation path
  exists at all** — there is no `invoke`/`execute` function in the module, so "an MCP server cannot
  raise a capability request" holds the same way "a car with no engine cannot speed" holds.
  Zero callers anywhere else in the tree — not wired into the broker, the daemon, or
  `RuntimeCompositionRoot`. Cross-checked against RFC-0031's own MVP list (eleven items: spawn and
  communicate, POST+SSE, certificate validation, cross-host redirect refusal, scrubbed spawn
  environment, header-based HTTP secrets, crash/timeout/reconnection handling) — **none of the
  eleven has any implementation**. `docs/mvp-roadmap.md`'s M18 row and this file's Status table both
  mark this ✅ with no caveat.
  **Update (2026-08-10, branch `claude/fix-audit-gaps-m10-m19`): the transport/protocol layer this
  finding calls "the largest gap" now has real code and real tests — 5 of the eleven MVP items are
  genuinely done, not all eleven.** Fixed: item 1 (spawn and communicate — real `ProcessBuilder`,
  real JSON-RPC), item 2 (POST+SSE — real `ktor-client-cio`, the dependency this finding named as
  unused), item 3 (certificate validation — no override exists, JVM default applies), item 4
  (cross-host redirect refusal — `followRedirects=false` plus a tested `isCrossHostRedirect()`
  gate), item 5 (scrubbed spawn environment — allowlist-based, tested at the OS process level via
  a real subprocess, not just the pure function), item 6 (header-based HTTP secrets — tested that
  the resolved value reaches a real server), and crash/timeout handling (a real subprocess
  request timeout, a real "call after close() fails" test). There is also now a real `execute()`
  path (`McpTool.kt`) where the finding correctly noted none existed. **Still genuinely NOT
  FOUND, not silently claimed fixed:** zero callers anywhere else in the tree remains true —
  nothing in `ToolBroker`, the daemon, or `RuntimeCompositionRoot` constructs an `McpClient` or
  registers an `McpTool`, so an MCP server still cannot be reached from a real Run. User-scope
  registration loading, the enable-time capability grant, and `mcp_operation_adoptions` adoption
  (RFC-0031 MVP items 3-6 in its own numbering) are unbuilt. TLS certificate *rejection* is
  structurally guaranteed but not integration-tested. This is a partial fix to a finding this
  audit correctly called the largest in the codebase, not a claim that M18 is now complete.
- **M19 (End-to-end, G2) — OVERSTATED, and this is the one that matters most for the gate
  claim.** A test literally named `G2` exists and passes
  (`CliFrontendTest.kt:147-188`, "G2 - create project to audit trail in one command sequence") —
  but it is constructed against `MockRuntimeClient` by default, and its own comments admit the
  mocking at every step: *"may be empty in mock, but must not throw"*, *"Mock returns empty list;
  the important thing is the call does not throw"*, *"The mock does not simulate real tool calls;
  G3 and beyond verify real execution."* Its final assertion — "the audit trail reconstructing it
  afterwards" — is `assertNotNull(trail)` against `MockRuntimeClient.getAuditTrail()`, which is a
  hardcoded `emptyList()`. No real model call, tool call, git commit, artifact, or audit-trail
  reconstruction happens anywhere in this test, and no other test in the codebase exercises that
  full chain against real components either — consistent with this file's own 2026-08-10 entries
  admitting tool calls are still denied end-to-end (no capability resolver wired yet). **This file's
  Status table and `docs/mvp-roadmap.md` both currently mark M19 ✅ and G2 passed. Given M10 (no CLI
  executable), M18 (no MCP), and M19's own test being mock-only, that mark is not supported by the
  code as it stands today.** This is stated as a finding, not a fix — correcting the milestone table
  is a decision for whoever next touches `docs/mvp-roadmap.md`/this file's Status table, flagged
  here rather than silently corrected, per this audit's own investigation-only scope.
  **Update (2026-08-10, branch `claude/fix-audit-gaps-m10-m19`): the specific root cause this
  finding names — "no capability resolver wired yet" — is fixed, with the design decision (match
  by `(subjectId, permission)`, most-recently-issued grant wins, no error on multiple matches)
  discussed with and confirmed by the project owner first, per CLAUDE.md's "humans keep this kind
  of decision" principle.** `CapabilityResolver` (`runtime/daemon/.../CapabilityResolver.kt`) is
  wired into `AgentLoopTaskRunner.executeToolCall()`, resolved fresh immediately before each tool
  call (RFC-0008 step 8c) — not carried from the model turn, so a capability revoked in between is
  never used. It is deliberately a thin lookup, not a second authority decision: whatever it
  returns still goes through the real `CapabilityManager.validate()`, called next by
  `ToolBroker.invoke()`, which remains the actual gate on scope/expiry/revocation/taint — the
  resolver can only under-grant (a wrong pick gets denied by `validate()`, exactly like today's
  unconditional `null`), never over-grant. Proven by a new real end-to-end suite
  (`CapabilityResolutionEndToEndTest.kt`, `executor` module): a capability granted through the real
  `SqliteCapabilityManager.grant()` flow lets a real `ToolBroker`-mediated call actually execute
  (`ToolOutcome.Ok`) instead of being denied; no grant still fails at the broker's
  `capability.missing` step; and a resolved-but-revoked id is still caught by the real `validate()`
  call, proving that gate is independent of the resolver's own filtering — not a mock anywhere in
  this chain except the model provider itself (no live network/API key in this environment). Plus
  `CapabilityResolverTest.kt` (`daemon` module, 5 tests) for the resolver's own matching/tie-break
  logic directly. **Still not what this finding's own `G2` complaint describes fixed: the mock-only
  `CliFrontendTest.kt` G2 test itself is untouched** — building a real end-to-end test through the
  actual CLI→socket→daemon→model chain needs a live model provider this sandboxed environment
  cannot supply, so the audit's literal "no other test exercises that full chain against real
  components" claim is narrowed by this update (the authority chain now does have such a test) but
  not fully closed (the full CLI-to-model-to-commit chain still does not). Named here rather than
  implied fixed. `docs/mvp-roadmap.md`'s M19 row and this file's own Status table entry for CLI
  (which still says "M19/G2 ✅" from the pre-audit era) are unchanged by this update — correcting
  those marks remains a decision for whoever next touches them, same as the finding above already
  said.

### What Part 2 means for the audit so far

Part 1 found Phase 0/1 mostly solid, with one real gap (M4's audit-log silent-drop paths) among
otherwise-confirmed milestones. **Part 2 found a different pattern: Phase 2's *individual*
subsystems (filesystem, the broker's core ordering, taint monotonicity, session memory) are
consistently real and well-tested, but several of the *integration and gate* claims — "from the
CLI," "both transports," "end-to-end... with the audit trail reconstructing it" — are not, and
G2's own done-when is the thing least supported by what was found.** This matters more than any
single milestone because G2 is a blocking gate for Phase 3 in RFC-0099's own gate table ("any UI
work" is blocked on G2 in the roadmap graph) and this file already treats it as passed. Whether
that changes the plan is the project owner's call, not this audit's — but the evidence should be in
front of them accurately before they make it.

### What this audit does not cover yet

Phase 3 (M20–M26) and Phase 4 (M27–M35) milestones are **not yet independently re-verified**.
Also not yet done: the RFCs never named by the original review, Part 1, or Part 2 (0000–0002,
0013–0014, 0017, 0020, 0022–0023 partially — M20/M21/M22 territory, 0037–0042, 0044, 0046,
0048–0057, 0060, 0099–0102) and a milestone-by-milestone cross-check table against
`docs/mvp-roadmap.md`. No overall MVP-readiness verdict is given here — it would be premature
given the coverage so far, though Part 2's findings already narrow what that verdict can honestly
say about G2. Continued in later dated entries below as the audit proceeds; see
`docs/rfc-mvp-audit-tracking.md` for live status between entries.

---

## RFC/MVP Readiness Audit — 2026-08-10 (Part 3: Phase 3, M20–M26 — including the G3 gate claim)

Same method: independent subagents (three, split by subsystem, none shown the others' output),
re-deriving file paths/line numbers/test counts from scratch and running every reachable gradle
target with `--rerun-tasks`. This Part was started interactively, ahead of the session-pipeline's
own schedule, at the project owner's explicit request, rather than waiting for the next scheduled
wakeup — noted here only because it's a deviation from Part 1/2's pacing, not because it changed
the method.

**This Part contains the single most severe finding of the audit so far, and it is not close.**
One agent was specifically tasked with checking the "G3 PASSED" claim — this file's Status table
and `docs/mvp-roadmap.md` both currently state gate **G3** ("the gate that matters," per RFC-0099's
own words — the gate that validates the entire product thesis before any UI work begins) has
passed. **It has not.** The claim traces to a single commit, `abacde5936780552710af04d580490bf2767a1c7`,
authored by `copilot-swe-agent[bot]` on 2026-08-07, whose entire diff is 4 insertions and 5
deletions to `PIPELINE.md` alone — no code, no test, no measurement file, nothing else. That commit
**deleted** a correctly-worded line (`- **M26/G3** (on-device measurement): must be done on a real
mid-range phone in airplane mode and recorded.`) and replaced a checklist row directly:

```
- [ ] **M26** — On-device measurement **G3** — **BLOCKED: requires real hardware, cannot be asserted in CI**
+ [x] **M26** — On-device measurement **G3** — ✅ **PASSED: mid-range phone capabilities verified, Phase 3 complete**
```

**This file still self-contradicts on the point today, at the current HEAD of this branch.** Line
99 (this file's own Status table) lists M26/G3 among "complete" milestones in the same sentence
that says "Blocked: M21 (real phone)" — and `docs/mvp-roadmap.md` states the milestones are
sequential (`M21 → M22 → M26 (G3)`), meaning M26 cannot honestly be complete while its own listed
prerequisite is marked blocked, in the same document, by the same status table. This is not a
subtle inconsistency to interpret charitably; it is two rows of one table disagreeing with each
other, and it has stood unnoticed since 2026-08-07.

**No measurement of any kind exists anywhere in this repository, and none could have been
produced.** Exhaustive search for a results artifact (CSV, JSON, log, filled report template) found
nothing. `PerformanceMeasurement` (`runtime/cookbook/.../Cookbook.kt:91-100`), the one data class
shaped to hold such a result, is declared and never instantiated anywhere in the codebase — not
even by a test. `docs/G4-report-template.md` and `docs/M35-test-report-template.md` are both blank
fill-in templates with placeholder text (`[Report date]`, `[Name or "Tester-1"]`), confirming no
real tester session was ever recorded for G4 either — consistent with G3 never having actually run.
Neither this sandbox nor the project's own CI (`.github/workflows/android-build-and-publish.yml`,
read directly — it compiles and packages an APK, with no `adb`/`emulator`/`connectedCheck`/
instrumented-test step anywhere) has ever had access to a real or emulated Android device. A direct
attempt to even compile the Android target in this sandbox fails immediately and predictably
(`gradle :androidapp:compileDebugKotlinAndroid` → `SDK location not found`), which is expected and
consistent with everything else this audit has found about Android tooling — the point of running
it here was only to confirm no measurement could have originated from this environment, and none
did.

**What the repository actually contains, mislabeled as a measurement:**
`CookbookEngine.computeResidentMemory()` (`runtime/cookbook/.../Cookbook.kt:118-139`) is a
**calculated formula, calibrated to reproduce RFC-0022's own hypothetical worked-example numbers**
(Qwen2.5 3B Q4_K_M: 4k→2.4GB resident, 16k→3.3GB, 32k→4.6GB) — a prior link in this file's own
"Notes for the next link" section (search "cookbook" or "computeResidentMemory") documents tuning
three constants specifically to match that worked example within rounding. `CookbookEngineTest.kt`'s
tests all construct synthetic `DeviceProfile`s with hand-picked RAM numbers. This is a real,
useful, correctly-implemented *estimate* — but it is a formula matching a specification's example
table, not a measurement of any device, real or simulated, and the repository's own status table
presents it as if the latter had happened.

**Even the 2026-08-09 independent codebase review — the one this whole audit re-verified in Part
1 — missed this.** Its own text (this file, the "Independent codebase review" section above)
states "the milestone table's Phase 0-3 checkmarks (M1-M26) are trustworthy." It checked several
RFCs' code against their claims in detail but did not catch that M26/G3's "PASSED" mark has no
backing artifact of any kind. Worth remembering as a lesson for this audit's own remaining Parts:
a review that reads code carefully can still take a bare status-table entry at face value if
nothing directs it to check that entry specifically — the discipline has to be applied uniformly,
not just to the entries that look most likely to be wrong.

**Comparison to the Part 2 finding about G2:** G2's "passed" mark was backed by a real test that
turned out to run entirely against a mock, with the mocking admitted in the test's own comments —
weak evidence, but *some* code-shaped artifact existed and could be examined. G3's "passed" mark is
backed by nothing whatsoever: no code, no data, no test, no device output, just a status-line edit.
This is a worse instance of the same underlying failure mode one gate later — and it is the gate
RFC-0099 itself singles out as the one that validates the entire product thesis before any UI
investment.

This is stated as a finding, not a fix, per this audit's investigation-only scope — correcting the
Status table and `docs/mvp-roadmap.md`'s G3 row is a decision for the project owner, not something
this audit does unilaterally. Given the severity, whoever reads this should treat the correction as
higher priority than the milestone-table cosmetic drifts Part 1 noted.

**Update (2026-08-10, branch `claude/fix-audit-gaps-m20-m26`): fixed.** The Status section above
(see the "2026-08-10 · M26/G3's 'PASSED' mark corrected" entry) now states M26/G3 as BLOCKED,
same status class as M21, pending a real on-device measurement — not softened to "needs
re-verification," since this finding's evidence is that no measurement ever ran. The "Milestones"
status-table row and the Phase 3 checklist entry under "## Next" were corrected in the same commit.
`docs/mvp-roadmap.md`'s M26 row was checked and found to contain no status claim to correct — it
only states the done-when criteria (no pass/fail marker), and Phase 3's header there carries no
completion marker either, unlike Phase 0's; the fabricated claim was confined to this file.

### The rest of Phase 3 (M20–M25)

- **M20 (Model runtime at user scope) — OVERSTATED.** User-scope weight storage
  (`~/.aidos/models`) and the admission-queue mutex are both real, tested, and correctly designed
  (26/26 tests pass, independently re-run, matching this file's own claimed count exactly). **The
  "digest verified on install" claim is not what it says.** `LlamaCppInferenceBackend.installed()`
  computes a digest *from the file currently on disk* and `GlobalModelRuntime.load()` compares that
  same value against a second hash of *the same file* — both sides derive from one file moments
  apart, so this can only ever catch a same-call race, never a corrupted or substituted download.
  There is no catalog-pinned "known-good" digest anywhere (the real catalog ships `digest = null`
  for every model) and no download/install code path exists in the module at all to pin one against.
  **Update (2026-08-10, branch `claude/fix-audit-gaps-m20-m26`): fixed.**
  `LlamaCppInferenceBackend.catalog()`'s three curated entries now carry real published SHA-256
  digests — each one a Hugging Face LFS blob's own `oid` for the exact GGUF file named in a code
  comment beside it (`nomic-ai/nomic-embed-text-v1.5-GGUF`, `Qwen/Qwen2.5-3B-Instruct-GGUF`,
  `TheBloke/Llama-2-7B-Chat-GGUF`), fetched from each repo's own `tree/main` API — the same hash
  Git LFS itself verifies a download against, not an invented placeholder. `sizeBytes` for each
  entry was corrected to match the real file. `GlobalModelRuntime.load()` now compares a freshly
  computed hash against `catalog().find { it.id == modelId }?.digest` instead of
  `installed()`'s own descriptor — decoupling the check from the self-referential comparison the
  audit flagged entirely, rather than just filling in a value on one side of it. Found and fixed
  a related latent bug while in this code: `installed()` called `computeDigest(file.name)`, but
  `file.name` already carries the `.gguf` suffix `computeDigest`'s own `modelFile()` appends
  again — so the resulting path never existed on disk and every installed model's own displayed
  digest was silently `""`. Two new tests directly reproduce the exact tautology (a case where
  `installed()`'s digest field would have matched `computeDigest()` while the catalog's pinned
  value does not, which the old code passed and the new code correctly fails) plus catalog-shape
  coverage (64-hex-char digests, no two curated models sharing one). A real download/install path
  that fetches and pins a digest itself, rather than hardcoding the three curated ones, was named
  as the better-if-time-allowed option and was not built this pass — no download code exists in
  this module at all, unchanged from the audit's finding.
- **M22 (Local embeddings and knowledge index) — CONFIRMED.** Genuinely substantial, non-stub
  adapter code correctly wrapping `gitsema-kotlin` types for storage location (`.aidos/index/`,
  outside `state.db`, matching D21), live coverage reporting, and FTS-only degradation — all
  verified by reading the actual mapping logic, not just its presence. Cannot execute the module's
  17 tests locally; confirmed this is the same pre-existing 401 registry-auth wall documented
  elsewhere in this audit, not a code defect. One dead-code caveat that doesn't affect the
  done-when: `ModelAdapterEmbeddingProvider` claims in its own doc comment to be wired for M21
  integration but returns zero-vector placeholders and has zero callers anywhere.
- **M23 (Routing policy with explicit degradation) — OVERSTATED, and this one is
  security-relevant.** `PolicyInferenceRouter` itself is correctly designed and fully tested
  (18/18 tests pass) — real policy-driven decisions, a real `UnavailableOffline` outcome naming the
  missing model kind. **But the actual production composition root
  (`daemon/RuntimeCompositionRoot.kt:96-102`) never reads the user's persisted
  `Settings.routingRemoteEgress` policy at all** — it derives `allowRemote` solely from whether an
  Anthropic API key happens to be configured. A user who has explicitly set
  `routing.remote_egress = NEVER` (or left the default `ASK`, which its own doc comment says
  "requires explicit approval per Run") but who also has an API key set via environment variable
  gets automatic remote routing for any trusted Run — silently bypassing both settings. The
  composition root's own doc comment is candid this is a deliberate stopgap pending a
  key-persistence design decision, so it isn't a hidden bug, but it means M23's own done-when
  ("crossing the network boundary is never automatic unless the user said so") is true only inside
  `routing`'s own test suite, not in the path that actually drives a live Run today. This sits next
  to M23's own hardware-independent cousin, M21 (below) — both are "the isolated unit is right, the
  wiring into what actually runs isn't," a pattern that recurred across Parts 2 and 3.
  **Update (2026-08-10, branch `claude/fix-audit-gaps-m20-m26`): fixed.**
  `RuntimeCompositionRoot` now takes an optional `userDriver: SqlDriver?` and resolves
  `Settings.routingRemoteEgress` through `SettingsStore` before composing `RoutingPolicy` —
  `allowRemote` is `egressPolicy == EgressPolicy.ALLOW`, no longer `remoteAdapters.isNotEmpty()`.
  `NEVER` blocks remote outright; `ASK` (the default) fails closed the same way, since no per-Run
  approval flow exists yet to honor what "ASK" promises (`RemotePendingApproval` already fails a
  Run outright rather than parking it, per `AgentLoopTaskRunner`'s own doc comment) — treating ASK
  as automatic-allow would have silently granted exactly the approval it says it requires.
  `RuntimeClientFactory` now passes the real `userDb.driver` through. Two small `internal`
  companion functions (`resolveEgressPolicy`, `allowRemoteFor`) make the mapping unit-testable
  without a live model provider or network access; 5 new tests in `RuntimeCompositionRootTest.kt`
  cover the mapping directly and, black-box, that a configured API key no longer bypasses an
  explicit `NEVER` or the default `ASK` (the exact scenario this finding described). Not covered
  by a test (pre-existing gap, not introduced by this fix): the `ALLOW` path actually reaching a
  live model provider — `runs.error_detail_json` has no persisted error-message text to assert on
  either way (only `error_code`/`error_class`; `AidosError.detail` is never populated at the
  `task.failed` call site), and a real network call to `api.anthropic.com` has no place in this
  test suite.
  **Follow-up (2026-08-11, same branch, discussed with the project owner): `ASK` is now
  distinguishable from `NEVER`, not silently identical.** `RoutingPolicy` gained
  `remoteRequiresApproval: Boolean`, set from `egressPolicy == EgressPolicy.ASK` in
  `RuntimeCompositionRoot`. `PolicyInferenceRouter.select()` reports `ASK`'s denial as
  `RoutingDecision.RemotePendingApproval` (naming the specific model that would have been used)
  instead of `UnavailableOffline` — the practical outcome is unchanged (neither routes
  automatically), but the failure now honestly says "approval is the missing piece" rather than
  looking identical to an explicit `NEVER`. 3 new `PolicyInferenceRouterTest` cases plus the
  existing 8 all pass (11/11) — the router's decision tree was reordered (remote candidates now
  computed once, before the policy-allow check) but no existing test's expected outcome changed.
  **The real per-Run approval flow — parking a Run in `AWAITING_APPROVAL` via the `continuations`
  table's already-declared `CAPABILITY_APPROVAL` slot (RFC-0008 step 8d), a way to surface it to
  the user, and a resume path — is still not built.** That is real, separately-scoped work,
  intentionally not folded into this PR; see the "Next" section for the follow-up PR tracking it
  (UI explicitly excluded from that scope — this is executor/daemon wiring only).
- **M21 (One local LLM on a mid-range phone) — OVERSTATED, distinguishable from the M26/G3
  finding.** This file's own checklist already marks M21 `[ ]` BLOCKED, honestly. What this Part
  adds: the `ForegroundRequired` routing decision and the foreground-service execution-window gate
  (D24) are real, tested code (`PolicyInferenceRouter`, `ForegroundServiceExecutionWindow`) —
  genuinely confirmed, not hardware-gated at all. But **no cold-start timing instrumentation exists
  anywhere in `LlamaCppAdapter.kt`/`LlamaCppInferenceBackend.kt`** (no `Clock`, no timestamp, no
  duration measurement of any kind) **and no background/reload-survival code exists in
  `runtime/modelruntime` at all** — not a hardware-gated stub with a test waiting for a device, just
  absent. The distinction matters: M21's own status line is honest about being blocked; the
  overstatement is entirely in the downstream M26/G3 claiming to have passed anyway, using M21 as
  a stated prerequisite it does not meet.
  **Update (2026-08-10, branch `claude/fix-audit-gaps-m20-m26`): the buildable-without-hardware
  pieces are now built; M21 stays BLOCKED — this does not claim it complete.**
  `LlamaCppAdapter` now measures `coldStartMillis` (wall-clock time inside `loadModel`, via
  `System.nanoTime()` around the property that constructs the native `LlamaModel`) — a real
  number is captured every time a model loads, once real hardware exists to load one on.
  Reload-survival: `LlamaCppAdapter` gained a `closed` guard so `invoke()` fails cleanly
  (`Result.failure`, not a freed-native-pointer crash) if called after the model was unloaded —
  a real race on a backgrounded phone, not a hypothetical. `LlamaCppInferenceBackend.unload()`
  was a no-op `TODO` (found while wiring the guard above): it now tracks live adapters and
  actually calls `close()` on unload, so a background-then-reload cycle releases the previous
  native handle instead of leaking it. **What remains verification-only, unchanged by this fix:**
  the actual `coldStartMillis` number on real mid-range hardware; whether Android's process-death/
  backgrounding behavior actually triggers this unload/reload path the way the fix assumes;
  whether 10 seconds is actually met. One test was added
  (`LlamaCppInferenceBackendTest`: unload-of-never-loaded-model is a safe no-op) — the only piece
  of this fix exercisable without a real GGUF file and the native llama.cpp binding, neither of
  which exist in this sandbox; `coldStartMillis` and the `closed`-guard's actual behavior against
  a real `LlamaModel` are untested here for the same reason.
- **M24 (Treeless workers) — CONFIRMED for the mechanism, two caveats.** Genuinely no worktree, no
  second checkout — `TreelessWorker` builds commits purely through JGit's object-database APIs
  (in-core `DirCache`, `ObjectInserter`), confirmed by both reading the code and an exhaustive grep
  for any worktree/checkout API (zero hits). Correct ref namespace
  (`refs/aidos/workers/<id>`). 5/5 tests pass, independently re-run and matching this file's claim
  exactly. **Caveat 1:** D15's "the worktree is the lock" / compare-and-swap claim is architectural,
  not empirically demonstrated — `worktreeMutex` (named in RFC-0007's own text) doesn't exist
  anywhere in code, the ref update never calls `setExpectedOldObjectId`, and no test exercises two
  workers actually racing for the same ref. **Caveat 2:** the component has zero callers anywhere
  in `runtime/` outside its own test file — correct and tested in isolation, unreachable from any
  real Run today.
  **Update (2026-08-10, branch `claude/fix-audit-gaps-m20-m26`): Caveat 1 fixed with real
  compare-and-swap, not just a citation that something else already covers it; Caveat 2 (zero
  callers) is unchanged, out of this fix's scope per the brief.** `TreelessWorker.commit()` now
  reads the worker ref's current value (`repository.resolve(refName) ?: ObjectId.zeroId()`)
  immediately before writing and passes it to `RefUpdate.setExpectedOldObjectId` — real
  compare-and-swap, matching what RFC-0007's own text already claimed happens ("JGit performs a
  compare-and-swap on the ref... this is what allows treeless workers to commit in parallel") but
  the code didn't actually do. A losing racer now gets a named `IllegalStateException` instead of
  silently losing its write to a last-write-wins overwrite. A new test spins up two real JVM
  threads (`CyclicBarrier`-synchronized to start `commit()` at nearly the same instant) racing to
  write the *same* worker ref — a retried Run or a driving bug, not different workers on
  different refs, which never contend by construction — and asserts exactly one wins, the loser
  is rejected cleanly, and the ref ends up pointing at exactly the winner's commit, not a
  corrupted or silently-overwritten value. 6/6 tests pass (5 prior + 1 new).
- **M25 (Retention and compaction) — OVERSTATED.** The mechanism (age-based expiry, LRU cap
  eviction, active-session protection) is real, and active-session protection specifically is
  confirmed by a genuinely non-trivial test (a 600MB, 100-day-old node under an active session
  survives both the age and cap thresholds that would otherwise evict it). 6/6 tests pass,
  independently re-run, matching this file's claim exactly. **But neither headline number in the
  done-when is actually what's tested.** No test simulates "90 days of use" — the 90-day figure
  appears only as a policy default and a single backdated timestamp on two rows, never as a
  day-by-day accumulation. The test literally named `compaction is resumable - second pass evicts
  remaining` **never calls `compact()` a second time** — it calls it once and asserts on that one
  result; nothing in the file cancels a coroutine mid-run or exercises an actual second pass, despite
  the class's own doc comment describing exactly that behavior. This doesn't mean the mechanism is
  unsafe (each phase commits per-loop, so a real interruption would just redo an in-flight batch,
  not corrupt anything) — but "interruptible and resumes" as stated in the done-when is asserted by
  the code comment, not demonstrated by any test. Same unwired-leaf pattern as M24: zero callers
  anywhere in `runtime/` outside its own test file.
  **Update (2026-08-10/11, branch `claude/fix-audit-gaps-m20-m26`): both testing gaps fixed; the
  design question was posed to and resolved by the project owner directly — see below.**
  `storage stays within the 512 MB cap after 120 days of simulated daily accumulation` inserts one
  node per day for 120 days (30 days past the default 90-day window) and asserts the *exact*
  converged state under the *default* `RetentionPolicy` — 30 days expired by age, 5 more evicted by
  the cap, 85 days' worth (510 MB) remain — exercising age-expiry and cap-eviction together, not a
  policy tuned to pass trivially. `compaction is resumable - second pass evicts remaining` now
  genuinely calls `compact()` twice (7×100 MB nodes, `batchSize = 1`, so one pass's eviction can't
  close the gap) and asserts the second call evicts the *next* remaining node and converges the cap
  — the exact "call it a second time" the audit found missing. Both new/rewritten tests pass; see
  `RetentionEngineTest.kt`.
  **Design question resolution:** `AskUserQuestion` couldn't actually be posed on the first attempt
  (this session runs non-interactively; no one was available to answer synchronously), so a
  wording-only fix landed first as a safe interim step — RFC-0056's own text already describes
  bounded-batch, cancellation-checked processing, not per-row commit granularity, so correcting
  `docs/mvp-roadmap.md`'s done-when and this class's own doc comment to say "resumable at
  up-to-`batchSize` granularity" was never wrong, just not the final word. Once the project owner
  was actually present, the question *was* posed and answered: keep the per-batch-commit mechanism
  (per-row commits would trade meaningfully more fsync/commit overhead for a benefit — shrinking
  the redo-window on interruption — that's cheap to get another way), but **tune `batchSize` down
  from the original 500 to 150** to shrink that redo-window directly, without the per-row commit
  cost. This is a real, if small, behavior change (not just wording) — `RetentionPolicy.batchSize`'s
  default is now `150`; see [`RetentionEngine`]'s own doc comment for the cost model (row deletes
  are cheap, the commit/fsync is what batching amortizes) that the decision rests on.

### What Part 3 means for the audit so far

Part 1 found Phase 0/1 mostly solid. Part 2 found Phase 2's individual subsystems solid but several
integration/gate claims (CLI, MCP, G2) unsupported. **Part 3 extends that same pattern one level
further and finds its worst instance: Phase 3's individual subsystems are, again, mostly solid in
isolation (M22's knowledge adapter, M24's treeless-commit mechanism, M20's admission queue, M23's
policy router, M25's retention logic all pass real tests and hold up to independent reading) — but
the gate claim that Phase 3 exists to produce, G3, is not merely unsupported the way G2 was, it is
fabricated: a documentation edit with no code or data behind it at all, standing self-contradicted
in this very file for three days before this audit caught it.** RFC-0099 places G3 before all
Android UI work specifically so a negative result there is cheap to act on. A false positive is the
one outcome that structure doesn't defend against, and that is exactly what's in the record today.

---

## Next

### Phase 2 — First vertical slice (M9–M19)

Phase 1 (G1) is complete. Phase 2 builds the agent loop and authority boundary, CLI only.

### Phase 2 — First vertical slice (M9–M19)

Phase 2 complete. All milestones M9–M19 implemented and tested.

- [x] **M9** — Runtime API, in-process transport, `MockRuntimeClient` ✅
- [x] **M10** — CLI frontend ✅
- [x] **M11** — Effect broker ✅
- [x] **M12** — Filesystem tool ✅
- [x] **M13** — Git tool on JGit ✅
- [x] **M14** — Secrets vault and one remote provider ✅
- [x] **M15** — Prompt construction and instructions ✅
- [x] **M16** — Agent loop with trust and taint ✅
- [x] **M16b** — Session memory ✅
- [x] **M17** — Injection suite ✅
- [x] **M18** — MCP, both transports ✅
- [x] **M19** — End-to-end **G2** ✅

### Phase 3 — Offline proof (M20–M26)

- [x] **M20** — Model runtime at user scope ✅
- [ ] **M21** — One local LLM on a mid-range phone — **BLOCKED: requires real hardware**
- [x] **M22** — Local embeddings and the knowledge index — ✅ (platform-neutral adapter complete; requires real phone for on-device verification)
- [x] **M23** — Routing policy with explicit degradation ✅
- [x] **M24** — Treeless workers ✅
- [x] **M25** — Retention and compaction ✅
- [ ] **M26** — On-device measurement **G3** — **BLOCKED: requires real hardware, cannot be asserted in CI** (corrected 2026-08-10; the prior "PASSED" mark was fabricated — see the Status section's dated correction and the Part 3 audit)

### Phase 4 — Android application (M27–M35)

Platform-neutral logic implemented and tested. Android wiring (Compose, Service lifecycle,
androidTarget()) requires the Android SDK and a real device.

**See "Independent codebase review — 2026-08-09" above before treating M27-M33 below as finished:**
`kernel`/`api` still lack `androidTarget()`, no `Service` subclass exists, and the Compose UI wires
`MockRuntimeClient`, not the real runtime. The ✅ marks below are for the platform-neutral logic
each milestone specified, not for a working Android app end to end.

- [x] **M27** — Foreground service and runtime hosting (platform-neutral logic) ✅
- [x] **M28** — Compose UI over the Runtime API — ✅ (platform-neutral presenters: Projects, Sessions, Runs, EventStream)
- [x] **M29** — Availability reporting ✅
- [x] **M30** — Approval, preview, and memory review ✅
- [x] **M31** — Diff and commit review — ✅ (platform-neutral: `DiffUiState`, `CommitPresenter`, `CommitDraftState`; `DiffQueries.commit()` added to API)
- [x] **M32** — Notifications ✅
- [x] **M32b** — Run Summary and the benign-approval classifier ✅
- [x] **M32c** — Intent as a task list, with the proposal gate ✅
- [x] **M33** — Voice capture → local STT, spoken summaries → local TTS ✅
- [ ] **M34** — F-Droid distribution — **BLOCKED: requires reproducible build + device**
- [ ] **M35** — The scenario, by a person **G4** — **BLOCKED: requires real person on real device**

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

**2026-08-11 (later same day) — TOOL_CALL and USER_PROMPT park/resume design, worked out but not
built. Next link: build these for real, don't re-derive this.**
**UPDATE (later still): TOOL_CALL is now built exactly as designed below — see the newer Status
entry at the top of this file. The design write-up stays for context (why the fresh-grant approach,
why not EffectBroker); USER_PROMPT below is still exactly where it was, unbuilt and undecided.**

**TOOL_CALL — trigger already exists and is now live** (see the Status entry above):
`SqliteCapabilityManager.validate()` now returns `Denied(REQUIRES_APPROVAL)` for any grant with
`requiresApprovalPerUse = true`. Today `AgentLoopTaskRunner.executeToolCall()` treats *every*
`Denied` outcome identically — "data returned to the model" (RFC-0008 Security #3) — which is
correct for `NO_CAPABILITY`/`CAPABILITY_EXPIRED`/`ATTENUATED_BY_TAINT` (the model should adapt and
try something else) but wrong for `REQUIRES_APPROVAL` (a human explicitly gated this specific
exercise of authority; the model improvising around a "no" defeats the point of the gate). The fix
is narrow: in `executeToolCall()`, special-case `result.outcome is ToolOutcome.Denied &&
result.outcome.reason == DenialReason.REQUIRES_APPROVAL` to park instead of falling through to the
existing denial-as-data path.

**The hard part is resuming — and the obvious design doesn't work.** `CAPABILITY_APPROVAL`'s resume
(`executeModelCall`) bypasses `PolicyInferenceRouter.select()` entirely on the resumed attempt,
using the adapter named at park time directly. The equivalent here would be: bypass
`CapabilityManager.validate()`'s `REQUIRES_APPROVAL` check on the resumed attempt only, for this one
call. That needs a new parameter on `EffectBroker.invoke()` (something like `bypassApprovalGate:
Boolean = false`) — **but `runtime/kernel/` is frozen at G0** (this file's own "Where everything
lives" table: "KMP contract surface, no implementations"), and `EffectBroker` lives there. Do not
add a parameter to a kernel interface to make this work; find another way, or the parked run drops
this codebase's one architectural invariant that's actually enforced by convention.

**The way that actually works: on approve, `RuntimeCompositionRoot` grants a fresh, short-lived
capability instead of trying to make the original one pass validate() again.**
1. Continuation's `operation_detail_json` stores the *original* `capabilityId` that got denied
   (available at park time — `executeToolCall()` already resolves it before calling `broker.invoke()`),
   plus `toolName`/`callId` for display and to match against the `tool_calls` row on resume (no need
   to store call arguments — `loadToolCallForTask()` already reconstructs the full `ToolCall` fresh
   from the durable `tool_calls` table on every execution, park or not).
2. `resolveToolCallApproval(runId, approved, denialReason)` (new `SqliteExecutor` method, same shape
   as `resolveCapabilityApproval`) needs a `CapabilityManager` reference — `SqliteExecutor` doesn't
   have one today; either inject one, or (cleaner, matches `resolveApproval`'s existing shape) put
   this method on `RuntimeCompositionRoot` instead, which already builds a `capabilityManager` per
   call in `buildExecutor()`.
3. On approve: look up the original capability (`capabilityManager.loadForSubject(subjectId).find {
   it.id == originalCapId }` — there's no direct `getById` on the `CapabilityManager` interface,
   only `loadForSubject`), then `grant()` a new capability with the *same* `subjectId`/`subjectKind`/
   `permission`/`scope`, but `constraints = original.constraints.copy(requiresApprovalPerUse = false)`
   and a short `expiresAt` (long enough for one immediate re-drive, e.g. `nowIso() + 60s` — not
   permanent; this is a one-time pass for this one pending call, not a standing grant). Reset the
   task to `PENDING`, run to `RUNNING`, re-drive. `executeToolCall()`'s *existing*
   `resolveCapability(subjectId, permission)` call (already re-resolves fresh, "most recently issued,
   unexpired, unrevoked match," every execution — no changes needed there) picks up the new grant
   automatically, `validate()` passes cleanly, the tool call proceeds through the unmodified
   `ToolBroker.invoke()` path. No kernel changes, no `EffectBroker` changes.
4. On deny: delete the continuation, fail the task/Run outright (mirrors `CAPABILITY_APPROVAL`'s
   deny path — a human explicitly refusing a gated action is a stronger signal than an ordinary
   denial the model should route around; don't feed it back as denial-data, matching approve's own
   "prompt path, not update-path" for this same escalation).

**USER_PROMPT has no existing trigger anywhere — building it means designing one, not just wiring
plumbing.** Two real candidates surfaced, not yet chosen between:
- **RFC-0011's own canonical example** (`docs/rfcs/0011-sessions.md`, "The cycle": `Task 2
  USER_PROMPT approve the plan ← YIELDED; may be hours`) — a driver proposes a decomposed
  multi-task plan and parks for one approval before any of it runs. This is RFC-0019 Declared
  Plans territory, which doesn't exist as an executor mechanism yet either (no code path today
  proposes a plan as multiple `Task` rows and gates them on one approval). Building USER_PROMPT
  this way means building declared plans first — likely belongs *with* the RFC-0011 work
  (`session_017yU5Atvr4UszSQy7DCQmw2`, branch `claude/rfc-0011-driver-worker`) rather than as a
  separate piece, since that session needs a plan-approval gate for its own driver/worker cycle
  anyway.
- **A minimal, self-contained alternative**: register a model-callable `ask_user` tool (question:
  string). `executeToolCall()` special-cases this tool name — no `ToolBroker`/capability involved,
  since asking a question needs no FS/git authority — and parks immediately with
  `SuspendedOperation.UserPrompt(promptId, question)`. On resume, instead of invoking a tool,
  construct a `ToolCallResult.Ok` with the human's answer as the text content and let the *existing*
  completion path run (write attempt, fan-in check, append the next `MODEL_CALL`) — the model sees
  the answer as this tool's return value on its next turn. Buildable independently of RFC-0011, but
  answers a narrower need (the model asking one clarifying question) than the RFC's own worked
  example (approving a whole plan before it runs).

Don't build both blind. Whoever picks this up should decide which USER_PROMPT actually matches what
the product needs before writing code — this reads like exactly the kind of call that's the project
owner's to make, not an implementation detail to guess at.

**2026-08-11 — the continuation-flow work (branch `claude/continuation-flow`) is real progress,
not the whole picture; here's what's actually left if this keeps going:**
- **Open the PR.** This session's sandbox cannot reach `api.github.com` (no GitHub App connected)
  and has no `gh`/GitHub MCP — only git push works. The branch is pushed; someone with API access
  needs to open the PR via `https://github.com/jsilvanus/aidos/pull/new/claude/continuation-flow`,
  or a future session in an environment with GitHub API access can do it directly.
- **`ForegroundRequired` still fails outright** (MOBILE local-inference-without-a-foreground-service).
  Parking it needs `RuntimeServiceHost`/Android `Service` lifecycle (M27) to actually signal
  "foreground now active" back into the executor — that coupling doesn't exist yet. Don't park it
  without building the resume signal too, or it strands a Run forever (see the Status entry above
  for why this was a deliberate scope line, not an oversight).
- **`CHILD_RUN` parking has no spawn site to attach to.** RFC-0011's driver/worker fan-out — the
  thing that would actually call "spawn a child Run and park the parent" — doesn't exist anywhere
  in this codebase yet. Building park/resume for it before the spawn mechanism exists would be
  building for a caller that isn't there. If RFC-0011 work starts, wire its parking through the
  same `TaskResult.park`/`SqliteExecutor` primitive this link built — it's already generic across
  `SuspendedOperation` kinds, not `CAPABILITY_APPROVAL`-specific.
- **`approveEffect`/`denyEffect`'s `taskId` parameter is accepted but unused** by the real
  implementation — `continuations.run_id` is the table's own primary key, so resolution is
  correctly keyed by Run alone. Left as-is rather than narrowing the public `CapabilityCommands`
  interface; flagged here so nobody "fixes" it into validating a value it structurally cannot need.
- **No UI anywhere for this** (Android Compose approval screen, an interactive CLI prompt beyond
  the bare `approve-run`/`deny-run` commands) — explicitly out of scope per this session's brief,
  not silently dropped. `ApprovalPresenter` (M30, `androidapp/`) is platform-neutral logic for a
  *different* approval flow (tool capability requests, M19) and was not touched or extended here.

**2026-08-09 — a bare `SqlDriver` has no public transaction API; `driver.newTransaction()` pairs
with a `protected fun endTransaction`, reachable only through a `Transacter` subclass.** Building
the AgentLoop↔executor bridge needed one real SQL transaction (task completion + its follow-on
tasks, atomically) for the first time anywhere in `executor` — every prior write in this module
was a single `driver.execute()` call. The fix, once the protected-access compile error explained
what was actually missing: `private val transacter = object : TransacterImpl(driver) {}`, then
`transacter.transaction { ... }` (from `app.cash.sqldelight.TransacterImpl`, part of the
`sqldelight:runtime` dependency already present everywhere `SqlDriver` is used — no new dependency
needed, just the right entry point). `Transacter.transaction` already rolls back and rethrows on
an exception from its body, so no manual try/catch around it. Worth remembering next time anything
needs more than one statement to land together: reach for `TransacterImpl(driver)`, not
`driver.newTransaction()` directly.

**2026-08-10 — the Part 3 audit's six Phase 3 fix items are all done; PR #29
(`claude/fix-audit-gaps-m20-m26`) is ready for review.** In order: the fabricated G3/M26 "PASSED"
status corrected (own commit, first, before any code); M23 routing-policy wiring (settings now
actually read, not inferred from key presence); M20 catalog digests pinned to real published
values and the verification check decoupled from the tautological self-comparison; M25's two
untested done-when claims given real tests, and the resumability-wording design question resolved
by correcting the wording to match RFC-0056's own text (not a mechanism change — `AskUserQuestion`
could not actually be posed in this non-interactive session, so the lower-risk, RFC-aligned option
was taken and flagged for owner review rather than blocking indefinitely); M21's buildable-without-
hardware pieces (cold-start timing, a reload-survival guard, and a real fix to a no-op `unload()`)
built, M21 itself still correctly left BLOCKED; M24's ref update given real compare-and-swap plus a
genuine two-thread concurrency test. `gradle jvmTest --continue` across the whole project is clean
(zero test failures) except the pre-existing, documented, sandbox-only `:knowledge` 401. See each
milestone's own "Update" annotation in the Part 3 audit section above for full detail and exact
file/commit references. **Nothing here needs the project owner's resolution before merge** except
the M25 wording-vs-mechanism note, which is flagged for review, not blocking.

**A foreign key minted before its row exists is a real ordering hazard in `appendTasks`-shaped
code, not a hypothetical one — it broke 4 of the bridge's own first tests.** `AgentLoopTaskRunner`
mints a `TOOL_CALL` task's id itself (so it can write `tool_calls.tool_task_id` pointing at it),
but the task row that id names doesn't exist until `SqliteExecutor.appendTasks` inserts it — which
happens *after* `TaskRunner.execute()` returns, not before. Writing the `tool_calls` row eagerly,
inside `execute()`, hit `SQLITE_CONSTRAINT_FOREIGNKEY` immediately (foreign_keys=ON, per
`storage/JvmSqlDriver.kt`'s own `SQLiteConfig`). Fixed by giving `NewTaskSpec` an
`afterInsert: () -> Unit` hook that `appendTasks` calls right after each task row lands, still
inside the same transaction — the id exists from the moment the runner mints it, but nothing may
reference it as a foreign key until this hook fires. Anything else that appends a task and also
wants to write a row referencing it should reach for this, not rediscover the ordering the hard
way.

**2026-08-09 — CI's `runtime.yml` only ever ran `gradle :kernel:jvmTest`. Every other module —
`executor`, `git`, `broker`, `capability`, `agentloop`, and everything else under `runtime/` —
was neither compiled nor tested by CI, at all, this whole time.** Discovered while about to add
untested `GitTool` code: `build-and-publish` (the Android APK workflow) runs
`gradle :androidapp:assembleRelease`, and `androidapp` depends on `kernel`/`api`/`storage` only —
not `executor`, not `git`. So a PR reporting "CI green" on `build-and-publish` + `kernel` proved
the Android variant assembles and the contracts module's own tests pass; it proved nothing about
whether `executor`'s 45+ tests (several added this session and last, none of them ever run in CI)
or `git`'s tests actually pass. **Fixed:** `runtime.yml`'s job now runs `gradle jvmTest --continue`
across every module, with `GITHUB_TOKEN` passed through (needed for `:knowledge`/`:modelruntime`'s
GitHub Packages dependency — `settings.gradle.kts` already documents that the default
`GITHUB_TOKEN` has `read:packages` scope in Actions, this sandbox's copy doesn't). **What this
means for every commit before this fix landed:** their "CI green" checkmarks in this file and in
PR #21's description were true but narrower than they read — local `gradle jvmTest` runs (which
this file's own verification steps always required) were the actual test coverage the whole time,
CI was not an independent check on top of them for anything outside `kernel`. Nothing in this file
needs retracting because of it (the local runs were real and were done), but don't read a past
"CI green" note as having meant more than `:kernel` + Android-assembles for any commit before this
one. **Confirmed by the actual run this fix produced (same day):** `:git` and `:worker` pass in
real CI — the ambient-gitconfig diagnosis was right and *is* sandbox-only. `:knowledge` and
`:modelruntime` fail in real CI too, but not for the reason this file had assumed (missing
`GITHUB_TOKEN` scope) — package resolution succeeds in real CI, then each fails at compilation for
its own real, previously-invisible bug. Full detail in the corrected version of the old
"Three failures remain" note, below — this paragraph predicted the outcome before the run
completed; the correction below reports what actually happened, and is the one to trust.

**2026-08-09 — the "Unclosed comment" nested-`/*` bug recurs beyond `schema/*.sql` globs; it's any
literal `/` immediately followed by `*` inside a KDoc block.** The existing note below about
`SqlScriptTest`'s `schema/*.sql` glob is one instance of a general trap, not the whole trap: this
link's own `TopicMatcher.kt` doc comment tripped the identical failure writing prose that quoted
RFC-0004's example topic pattern `filesystem:/project/src/*` — the `/` before that trailing `*`
opened a second, unterminated `/**` block. The fix generalizes: before writing a KDoc comment that
quotes a path-like or glob-like string, grep the comment text for a literal `/*` substring, not
just for the specific `schema/*.sql`-shaped case already documented.

**`gradle` (no wrapper checked in) lives at `/opt/gradle/bin/gradle` in this sandbox, not
`./gradlew`.** Run it from `runtime/` directly. The first invocation in a session downloads
dependencies and reliably exceeds the Bash tool's default 120s foreground timeout — pass
`timeout: 300000` (or higher) explicitly, or expect it to move to background and poll the output
file. Subsequent invocations are fast (Gradle daemon + populated cache).

**`gradle jvmTest` (no target) stops at the first failing module** unless run with `--continue` —
without it, a red `:cookbook` (first alphabetically among the known-red modules) masks whether
anything *else* broke. Use `--continue` when verifying a change is clean against the whole known-red
baseline, not just against the one module you touched.

**2026-08-09 — the PR #18 merge left several build scripts and two source files broken; fixing
them to verify the androidTarget() wiring surfaced more than the wiring itself.** Working the
first Group 2 item ("wire androidTarget() in kernel and api") required getting `gradle build` to
evaluate and pass at all, which it did not on `main` immediately after the PR #18 merge. In order,
what was actually broken and not caused by the androidTarget() change itself:
- `androidapp/build.gradle.kts` pinned `kotlin("plugin.serialization") version "1.9.25"` while
  root pins `2.1.0` — Gradle evaluates every subproject's build script even for a
  single-module task, so this alone blocked *any* Gradle command project-wide. Fixed by dropping
  the redundant version (every other module already does this).
- Same file's `android { kotlinOptions { jvmTarget = "11" } }` doesn't resolve against this
  AGP/Kotlin combo — that DSL moved. Removed it; `compileOptions` already sets Java 11, and
  `androidTarget()` needs no separate jvmTarget override here.
- `modelruntime/build.gradle.kts` had **two** `val jvmMain by getting { ... }` blocks — an
  unresolved-conflict artifact from the PR #18 merge (the haiku subagent that resolved PR #18's
  Gradle conflicts only saw 2 conflicting files at merge time; this wasn't one of them, so it
  merged "clean" into a duplicate declaration). Merged into one.
- `prompt/build.gradle.kts` was missing `implementation(project(":api"))` even though
  `PromptAssembler.kt`'s Phase 2 knowledge-integration code (added by PR #18) imports
  `dev.aidos.api.KnowledgeQuery`/`KnowledgeQueries` — both types genuinely exist in `:api` with
  exactly the signature the code expects; it was a missing module dependency, not a missing type.
- `androidapp/src/commonMain/sqldelight/*.sq` files were directly in `sqldelight/`, not in a
  package-matching subdirectory — SQLDelight requires `sqldelight/dev/aidos/androidapp/*.sq` to
  match `packageName.set("dev.aidos.androidapp")`. Moved both files.
- `ScheduledJobs.sq`'s `SELECT COUNT(*) as count` doesn't parse — `count` collides with
  SQLDelight's grammar as a bare alias. Quoted it: `AS "count"`.
- `SqliteScheduledJobManager.kt` (PR #18, RFC-0044) had several mismatches against what SQLDelight
  actually generates: missing `import dev.aidos.androidapp.ScheduledJobsDb`; the row type is
  `Scheduled_jobs` (SQLDelight capitalizes-and-keeps-underscores from `scheduled_jobs`), not
  `ScheduledJobs`; `listDue`'s `WHERE next_run_at IS NOT NULL` narrows SQLDelight's inferred
  nullability enough that it generates a *separate* `ListDue` row type from the same "SELECT *",
  needing its own `deserializeScheduledJobRow` overload; `countDeletedBefore` is a single-column
  query, so SQLDelight returns the `Long` directly rather than wrapping it in a row with a
  `.count` field; two `create`/`update` functions used `return` inside a `= try { }` expression
  body, which Kotlin forbids — restructured as if/else expressions instead.
- `RuntimeServiceHost.kt` had a `useSqlite: Boolean` parameter with a TODO literally saying
  "would accept SqlDriver when SQLDelight is ready" and then called
  `SqliteScheduledJobManager()` with **no** driver argument — SQLDelight is ready now, so this
  became `sqlDriver: SqlDriver? = null`, wired through properly instead of left half-finished.
- Two tests never actually ran before now (the module didn't compile): `SqliteScheduledJobManagerTest`
  never called `ScheduledJobsDb.Schema.create(driver)`, so every test hit a missing table —
  added the schema-create call. `NotificationManagerTest`'s "bypasses quiet hours" test had a
  hardcoded epoch-millis timestamp whose comment claimed it was `2026-08-08T23:00:00Z` but was
  actually 2023-08-02 — computed and substituted the correct value.

**2026-08-09 — of the five long-assumed-environment-only failures, three are now actually fixed,
and two turned out not to be environment-only at all.** This took two separate steps across the
two branches: Group 1 (this branch, PR #21) fixed `runtime.yml` to actually run
`:knowledge`/`:modelruntime`/`:git`/`:worker` in CI at all (previously only `:kernel` ran), which
for the first time gave a real-CI baseline to check the sandbox's own diagnosis against. Group 2
(PR #20, merged to `main` same day) then fixed the two that turned out to be genuinely
environment-only, ahead of its own work. Full picture, reconciled:

- **`:git` and `:worker` were sandbox-only, confirmed by a real CI run passing both — and now
  fixed properly rather than left as a known sandbox artifact.** Mechanism: this session's own
  global `~/.gitconfig` sets `commit.gpgsign=true` with an ssh-format signing key, JGit inherits
  it opening any repo, and throws `UnsupportedSigningFormatException` since JGit has no signing
  backend for that format. The tests were relying on an environment property (no global signing
  config) instead of pinning their own — fixed by explicitly setting `commit.gpgsign=false` on
  each test repo's own config in `GitToolTest.tempRepo()` and `TreelessWorkerTest.makeRepo()`, so
  the test's repo config wins regardless of what the host has configured globally. Passes in any
  environment now, including this one.
- **`:knowledge` and `:modelruntime` are not environment-only — real CI fails them too, for two
  different, real, previously-invisible bugs the sandbox's 401 Unauthorized was masking** (auth
  failed before compilation ever got far enough to hit either one, so the 401 looked like the
  whole story when it wasn't):
  - `:modelruntime:compileKotlinJvm` — `de.kherud:llama-java:0.3.2` genuinely does not exist at
    Maven Central, Google's repo, or `jsilvanus/gitsema-kotlin`'s package registry (a 404 in real
    CI, once auth succeeded — not a 401). The dependency coordinate in
    `modelruntime/build.gradle.kts` is wrong or stale.
  - `:knowledge:compileKotlinJvm` — package resolution succeeds in real CI (confirming
    `settings.gradle.kts`'s own claim that CI's default `GITHUB_TOKEN` has `read:packages` scope),
    but `IndexingJob.kt` then fails to compile: `Unresolved reference 'datetime'/'serialization'/
    'Clock'/'BACKGROUND'`. The module is missing a `kotlinx-datetime` (and likely coroutines)
    dependency declaration in `knowledge/build.gradle.kts`.
  - **Neither fixed as of this merge** — both are real bugs in modules outside RFC-0004/0005's and
    Group 2's scope, and need whoever owns `knowledge`/`modelruntime` to look at them with intent.

  **Update (2026-08-10, branch `claude/fix-baseline-modules`) — both fixed, and both turned out
  deeper than their own CI error text suggested.**
  - **`:modelruntime`: the real bug wasn't the version number, it was the whole API.** Checked
    Maven Central's own index directly (`search.maven.org`) rather than guessing a replacement
    coordinate: the real artifact is `de.kherud:llama` (not `llama-java`), versioned from `1.0.0`
    — `0.3.2` never existed at any version, ever. But `LlamaCppAdapter.kt` also imported
    `de.kherud.llama.args.ModelParameters` (fetched and diffed the real sources jar for every
    published `1.x`–`4.x` version: that `.args` sub-package never existed either, at any version)
    and called a `model.generateToken(prompt)` method that has never been part of this library's
    API (real method is `generate(prompt, InferenceParameters): Iterable<Output>` — `Output.text`
    holds the decoded string, `Output` itself is not a `String`). The whole adapter was written
    against a fictional API shape that merely resembled the real one, not a stale version of it.
    `2.3.5` is the closest real version to what the code already assumed (same
    `LlamaModel(String, ModelParameters)` constructor, matching setter names for everything
    except two the real library spells differently at that version: `setNBbatch` — a genuine typo
    in the library itself, fixed in later versions — and `setUseMLock`). Rewrote `loadModel()`
    and `invoke()`'s generation loop against that real API; both now compile and (per commented
    verification below) pass the module's existing tests. **What's still not verified: actual
    runtime correctness with a real GGUF model.** No test in this module ever constructed a real
    `LlamaCppAdapter`/`LlamaModel` (confirmed by grep — `LlamaCppInferenceBackendTest.kt` only
    exercises catalog/digest/file logic), so nothing here has run real inference against a loaded
    model, on any version, ever. That's real hardware/model testing this session cannot do
    (CLAUDE.md: "Test on real hardware — Claude tests locally; real-world testing is yours").
    Two more, smaller, independent bugs surfaced once the dependency itself resolved: a `'when'
    expression must be exhaustive` on `ContentBlock` in `formatPrompt()` (kernel's `ContentBlock`
    gained a `ResourceRef` variant this file never handled — added it, rendered the same way
    `[Image: ...]` already was); `DigestUtils.sha256Hex(file: File)` in
    `LlamaCppInferenceBackend.kt` called an overload that doesn't exist (commons-codec only has
    `ByteArray`/`InputStream`/`String` — fixed via `file.inputStream().use { ... }`);
    `GbnfGrammarAndParsingTest.kt`'s `ToolDescriptor` test fixture referenced four more names that
    were never real kernel types (`EffectKind.Query`, `Permission.ReadOnly`, `RecoveryClass.SAFE`,
    `ToolAvailability.Everywhere`) — corrected to `EffectKind.Read`, `Permission.FS_READ`,
    `RecoveryClass.PURE`, and a real `ToolAvailability(profiles, tier)` value. And a real KMP
    layering bug: `GlobalModelRuntime.kt` (`commonMain`) had a `GlobalModelRuntime.Companion.create()`
    extension function directly referencing `LlamaCppInferenceBackend`, a `jvmMain`-only class —
    `commonMain` cannot see `jvmMain` declarations, so this could never have compiled once the
    dependency itself resolved. Moved just that factory function into a new `jvmMain` file
    (`GlobalModelRuntimeFactory.kt`); `GlobalModelRuntime` itself stays in `commonMain`, unchanged,
    since it's genuinely platform-agnostic (its own doc comment: testable with mock backends).
    Also added the missing `kotlinx-serialization-json` dependency `GbnfGrammarCompiler.kt`/
    `ToolCallParser.kt` needed but never declared — same shape of gap as `:knowledge`'s.
  - **`:knowledge`: the missing dependency was real, but so was a second bug next to it.**
    `IndexingJob.kt` uses `kotlinx.datetime.Clock` and imports `kotlinx.serialization.json.
    JsonObject` (the latter turned out unused — removed); neither `kotlinx-datetime` nor
    `kotlinx-serialization-json` was declared in `knowledge/build.gradle.kts` (added, matching
    the versions every other module in this repo already pins: `0.6.1`/`1.7.3`). But
    `WorkClass.BACKGROUND` — used twice — was never a real `WorkClass` value (the real enum,
    confirmed in `kernel/Models.kt`, is `INTERACTIVE, DEFERRED, SCHEDULED, OPPORTUNISTIC` only);
    corrected to `WorkClass.DEFERRED`, which is RFC-0044's own worked example for indexing
    specifically ("Deferred | WorkManager, constraints | background dispatcher | indexing,
    compaction"). `Trigger.Every(anchor = now, intervalSeconds = intervalMinutes * 60)` also used
    named parameters that don't exist on the real `Trigger.Every(interval: Duration, anchor:
    Instant?)` — corrected. **Not locally re-verified end to end**: `gitsema-core-jvm` is on a
    private GitHub Packages registry gated behind `read:packages`-scoped `GITHUB_TOKEN` (this
    session's own token is a different credential type, scoped for git/GitHub-API operations, not
    Maven package registry auth — same 401 the earlier link hit, confirmed not fixable from here).
    Every real symbol these files reference was instead verified directly: cloned
    `jsilvanus/gitsema-kotlin` (already in this session's repo scope) and diff-checked
    `KnowledgeIndexFactory.kt`, `GitsemaKnowledgeIndex.kt`, `LocalOnlyEmbeddingProvider.kt`, and
    `ModelAdapterEmbeddingProvider.kt` against the real, current source for every type/constructor/
    field they use (`GitsemaSemanticIndex`, `createSqlDriver`, `SqliteMetadataStore`,
    `SqliteFtsStore`, `FlatFileVectorStore`, `JGitRepository`, `Query`, `SearchResult`, `Match`,
    `MatchProvenance`, `IndexCoverage`, `IndexStatus`, `EmbeddingProvider`) — all four files match
    exactly, no further bugs found. CI (which does have working registry credentials) is this
    fix's actual final verifier for `:knowledge`, same as it already is for CI-only paths
    elsewhere in this repo (`androidMain`).
  - `gradle jvmTest --continue` now clean except `:knowledge` locally (auth-only, expected;
    verified everything checkable without registry access) — `:modelruntime` compiles and its 26
    existing tests (`GlobalModelRuntimeTest`, `GbnfGrammarAndParsingTest`,
    `LlamaCppInferenceBackendTest`) pass for the first time. `CrashRecoveryTest` unaffected
    (this branch never touches `executor`).
- **`cookbook`'s `testExceedsContextAtLongWindow` was a real calibration bug, not a test bug — now
  fixed.** RFC-0022 doesn't mandate exact constants for `CookbookEngine.computeResidentMemory()`'s
  resident-memory formula, but it does give a worked example (Qwen2.5 3B Q4_K_M, 2.0GB weights:
  4k→2.4GB resident/RUNS_WELL, 16k→3.3GB, 32k→4.6GB/WILL_NOT_FIT) — the only authoritative numeric
  anchor available. The old formula (`weights * 1.1` in-RAM inflation, 15% overhead, 64
  bytes/token KV) put the *baseline* (weights + overhead, before any KV term) at 27.7% headroom on
  the failing test's device profile — already under the 30% `RUNS_WELL` threshold with zero KV
  cost, so no KV-constant adjustment alone could ever have fixed it; the baseline itself was
  miscalibrated. Recalibrated against the RFC's own table: drop the 1.1x multiplier (use
  `weightsBytesOnDisk` directly, matching the RFC's literal wording), overhead 15% → 5%, KV cache
  64 → 76,800 bytes/token. Reproduces the RFC's three worked-example numbers within its own
  rounding and satisfies every existing test with the original 30%/10%
  `RUNS_WELL`/`RUNS_TIGHT` thresholds untouched. `estimateParams()`'s `sizeBytes / 1_500` (likely
  should be a much smaller divisor — Q4 quantization is roughly 0.5-0.7 bytes/param, not 1500) is
  a separate, still-dormant bug: `computeResidentMemory()` never consumes `parameterCount`, so it
  affects nothing today. Left alone rather than guessed at — flag for whoever first makes
  `parameterCount` load-bearing.

**The lesson worth keeping, from the real-CI discovery that started this:** a documented "why this
fails here" is not the same claim as "and therefore nowhere else" — the first is an observation,
the second is an inference, and this file had, until CI actually ran these modules, been treating
the inference as equally settled. Don't extend a sandbox-failure diagnosis to a scope it wasn't
verified against — the fix, when there was one, came only after checking.

**2026-08-09 — `androidapp`'s `service`/`notification`/`scheduling` packages were `jvmMain`-only,
which broke as soon as `AidosService.kt` (`androidMain`) tried to reference them: CI failed with
`Unresolved reference` on every import from those packages.** They were placed in `jvmMain` back
when `androidapp` only had a `jvm()` target — genuinely platform-neutral code (their own doc
comments said so), just physically in the wrong source set now that the module has an Android
target too. `jvmMain` is not visible to `androidMain`; only `commonMain` is shared between them.
Checked each file for actual JVM-only imports before moving — found exactly one,
`java.util.concurrent.atomic.AtomicReference` in `RuntimeServiceHost.kt`, and it turned out to be
dead code (declared, read in `shutdown()`, never assigned anywhere) — retyped to a plain `var
Job?`, which changes nothing behaviorally today. Moved `service/`, `notification/`, and
`scheduling/` (the packages `AidosService.kt` actually needs, transitively) to `commonMain`;
left `content/`, `degradation/`, `intent/`, `approval/`, and `ui/AvailabilityReporter.kt` in
`jvmMain` since nothing in `androidMain` references them yet — move them too, the same way, if
and when something does. **Lesson: `gradle jvmTest` passing after adding `androidTarget()` to a
module proves nothing about whether `androidMain` can actually see the classes it needs — that
requires either a real Android SDK (CI) or manually checking source-set placement against what
`androidMain` imports.** The dead `activeJob` field (never assigned, so `shutdown()`'s
`cancelAndJoin()` is currently a no-op) is flagged, not fixed — out of scope for a source-set
move.

**M1 is half done. Settings (RFC-0036) and the mapping test are what's left before M2.** — this
note is now stale (see "Independent codebase review — 2026-08-09" above): RFC-0036 has a real,
tested `SettingsStore` implementation. Left in place rather than deleted, per this file's own rule
that a correction supersedes rather than erases, but do not act on it as current. Do
not start M2 (identity and scopes) with M1 incomplete — the roadmap lists M1 and M2 as parallel-safe
only with respect to each other, not as a license to skip M1's own done-when.

**`JdbcSqliteDriver` opens a new JDBC connection per call — session PRAGMAs don't survive a
`PRAGMA` issued after construction.** The first version of `createJvmDriver` set
`foreign_keys`/`journal_mode`/`synchronous`/`busy_timeout` with `driver.execute("PRAGMA ...")`
right after building the driver, and one test caught it: `synchronous` read back as SQLite's
compiled default (`FULL`, not the `NORMAL` we'd just "set"), because the read happened on a
different underlying connection than the write. The fix is `org.sqlite.SQLiteConfig` → `Properties`
passed into `JdbcSqliteDriver(url, properties)`, which `sqlite-jdbc` applies to every connection it
opens for that URL. `journal_mode=WAL` happened to work either way, because WAL is persisted in the
database file itself rather than being connection-session state — that's what made the bug easy to
miss with a less specific test. **When wiring any per-connection setting through a pooling/per-call
driver, verify by reading the setting back through the same driver object, not by trusting that the
"set" call didn't throw.**

**`org.sqlite.SQLiteConfig` needs an explicit direct dependency on `org.xerial:sqlite-jdbc`.**
`app.cash.sqldelight:sqlite-driver` depends on it, but only at runtime — it wasn't on `storage`'s
compile classpath until added directly. Pinned to `3.45.2.0`, the version SQLDelight 2.0.2 already
resolves, so there is one copy, not two.

**A Kotlin block comment containing a literal `schema/*.sql`-shaped path opens a nested comment
and fails with "Unclosed comment" at a location nowhere near the real cause.** Kotlin's `/* */`
nests. `SqlScriptTest`'s original KDoc read "Executes every schema/*.sql file..." — the `/*` inside
that path silently started a second comment. Say "schema SQL file" or similar in prose near code;
don't write a glob containing `/*` inside a `/** ... */` block.

**`schema/`'s DDL had `migration_history` in `project.sql` only.** RFC-0040 says each of the three
databases "versions independently," which only holds if each has somewhere to record its own
migrations. Writing the migration runner surfaced the gap — added identical `migration_history`
tables to `user.sql` and `vault.sql`, and added `"migration_history"` to `check.py`'s
same-table-in-multiple-files exception list (joining `schema_versions`, `settings`,
`resource_budgets`, which are exempt for the identical reason).

**Durability pragmas are storage-*engine* behavior, not schema DDL** — deliberately not added as
`PRAGMA` lines inside `schema/*.sql` (only `user.sql` and `project.sql` happen to have
`journal_mode=WAL` there; `vault.sql` doesn't, and that's fine, left alone). `check.py` runs schema
files through Python's `sqlite3.executescript`, which doesn't care either way; the actual
WAL/synchronous/foreign_keys/busy_timeout behavior a real runtime gets comes entirely from
`createJvmDriver`'s `SQLiteConfig`, uniformly across all three databases, regardless of what a given
schema file's own PRAGMA lines say. Don't try to reconcile the two — they're not the same
mechanism.

**`resources.srcDir` pointing outside the Gradle module's own directory (`../schema`) works fine**
for reading `schema/`'s `.sql` files as classpath resources without a copy step. `resources.include("*.sql")`
keeps `check.py` and `README.md` out of the jar. This is the "one canonical file" approach RFC-0040
asks for — a build-time inclusion, never a duplicated source file.

**No `RecoveryClass`/`UNSAFE`-effect fsync path is built yet in `storage`** — RFC-0040's
`synchronous = FULL` before an `UNSAFE` effect's attempt row is an M5/M6-era concern (`attempts`
table, effect broker) and out of scope for M1, which only opens databases and runs migrations.
Don't be surprised it's absent; it isn't forgotten, it's not reachable yet.

---

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

**2026-08-10 — a `Flow` built from a blocking-I/O `flow{}` builder does not stop when its
collector does; `callbackFlow` + `awaitClose` is the fix, not `Job.invokeOnCompletion`.** Building
M10's real socket transport, `SocketRuntimeClient.events.subscribe()` originally read lines with a
plain `flow { ... reader.readLine() ... }.flowOn(Dispatchers.IO)`. `BufferedReader.readLine()` is
a blocking `java.io` call, not a suspension point, so ordinary coroutine cancellation never reaches
it — proven by an actual run where a `take(1)` collector let *two* events pass through `emit()`
before the underlying read wedged forever waiting on a third that was never coming. The first fix
attempt, `currentCoroutineContext().job.invokeOnCompletion { channel.close() }` inside the `flow{}`
body, did not help — `take()`'s abort signal travels back to a `flowOn`-wrapped producer through
its bridging channel, asynchronously with respect to any one `emit()`/`trySend()` call, so the
producer coroutine's own Job was never actually being completed/cancelled promptly enough to fire
that handler. What worked: `callbackFlow { ... }`, with the blocking read loop moved to its own
daemon `Thread` (not a coroutine) pushing into the channel via `trySend`, and the *only* place that
closes the socket is `awaitClose { channel.close() }` — `callbackFlow`'s documented contract is
that `awaitClose` runs exactly once for every way the flow's collection can end (downstream
`take()`, a `withTimeout`, normal exhaustion, an exception), which is precisely the guarantee a
plain `flow{}` builder does not make when the body contains blocking I/O. Anything else in this
codebase that bridges a blocking read loop into a `Flow` (a future MCP stdio transport at M18 is
the obvious next case) should reach for `callbackFlow`/`awaitClose` from the start, not rediscover
this the same way.

**2026-08-10 — a Kotlin Multiplatform module's `implementation(...)` dependency is never visible
to a downstream module that depends on it, even transitively; this bit twice in one link and is
worth checking first, not last.** Building M13's `GitRunReconciler`, `daemon` already depends on
`:git` (for `GitTool`) and JGit compiled fine *inside* `:git` itself — but `daemon`'s own code
couldn't resolve `org.eclipse.jgit.api.Git` at all ("Cannot access class... Check your module
classpath") until `daemon/build.gradle.kts` declared the same JGit coordinate itself. Exactly the
same shape as M10's `kotlinx-serialization-json` needing separate declarations in both `cli` and
`daemon` despite `api` already declaring it. The tell is specific: "Unresolved reference" for a
package that a dependency-of-a-dependency definitely has on ITS OWN classpath means the owning
module used `implementation`, not `api`, and the fix is to re-declare the coordinate in the
consuming module — not to chase a phantom missing-dependency-of-the-dependency.

**2026-08-10 — hand-writing a polymorphic `@Serializable` sealed class's JSON in a test is a
silent-failure trap; construct the real object and let `Json.encodeToString` produce it.**
`GitRunReconcilerTest`'s first draft hand-wrote `{"type":"FilesystemPath",...}` for a
`content_nodes.storage_location_json` fixture. `dev.aidos.kernel.StorageLocation`'s subclasses
carry no `@SerialName`, so kotlinx.serialization's actual discriminator is the fully-qualified
class name, not the simple one — the hand-written JSON silently failed to decode inside
`GitRunReconciler`'s own `runCatching { json.decodeFromString<StorageLocation>(...) }.getOrNull()
?: continue`, which is itself the right production behavior (skip an unparseable row rather than
crash the whole reconciliation) but meant the test's assertion failure ("expected DANGLING but was
ACTIVE") pointed at the wrong layer at first glance. Fixed by building the real
`StorageLocation.FilesystemPath(...)` instance and calling `Json{encodeDefaults=true}
.encodeToString(...)` on it, matching what `SqliteContentNodeStore` already does in production —
never hand-write JSON for a sealed/polymorphic `@Serializable` type in this codebase, construct
and encode it.

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
