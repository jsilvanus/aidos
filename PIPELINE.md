# PIPELINE — building the Aidos MVP

**Read this first.** It is the roadmap: what the MVP is, how to build it, the rules that are not
negotiable, and what is still open. An agent picks up here, works one coherent piece, and updates
this file in the same commit as the work.

This file looks forward only. What the project has already learned — the traps, the corrected
assumptions, the rules that came out of real mistakes — lives in [`lessons.md`](lessons.md).
**Read it before your first commit.** It is short, and most of it was expensive.

The narrative history this file used to carry (dated status entries, the 2026-08-09 codebase
review, the six-part 2026-08-10 readiness audit, and the accumulated "notes for the next link") was
removed on 2026-08-19 once its durable content had been lifted into `lessons.md` and its open items
into "What is next" below. It remains in git history if a finding needs its original context.

---

## Goal

A person opens a real Git repository on a mid-range Android phone, in airplane mode, asks a
question about the code, gets a useful answer, makes an edit, reviews the diff, and commits.

That sentence is the whole product. It is RFC-0099 Phases 0–4, ending at gate **G4**. Every
milestone either serves it or is cuttable.

---

## The plan

| Phase | Goal | Gate | State |
|---|---|---|---|
| **0 · Contracts** | freeze the seams | **G0** | ✅ complete |
| **1 · Execution kernel** | durable execution, no AI and no tools | **G1** | ✅ complete |
| **2 · First vertical slice** | the agent loop and its authority boundary, CLI only | **G2** | ✅ M9–M19 built |
| **3 · Offline proof** | prove the thesis on real hardware, before any UI | **G3** | ⛔ blocked on hardware |
| **4 · Android application** | the app | **G4** | platform-neutral logic built; blocked on hardware |

Gates are carried by a milestone: **G1 at M8**, **G2 at M19**, **G3 at M26**, **G4 at M35**.

**Phase 3 sits before the UI deliberately.** A beautiful UI over a runtime that cannot work offline
is not this product, and G3 is scheduled early so a negative answer arrives while it is still cheap
to act on. A negative result at G3 is a *successful* outcome for that milestone.

**If it slips**, cut in this order and stop when the thesis sentence is still true: M33 voice →
M18 MCP (as a deferral, not a deletion; cut stdio before HTTP) → M34 F-Droid → M25 retention.
**Never cut** M8 (crash recovery), M17 (injection suite), or M26 (the measurement).

**A ✅ above means the platform-neutral logic each milestone specified was built and tested.** It
does not mean the phone app works end to end; nothing in Phases 3–4 has run on a device. The open
items below say which claims are still unverified rather than leaving it to the checkmarks.

---

## What is next

Ordered by what blocks the product, not by milestone number.

### 1. Correctness and security gaps that are open today

These are live defects in code that is wired into the production path, not missing features.
Nothing below is waiting on hardware or on a decision.

- **`AgentLoopTaskRunner` discards each tool's real `RecoveryClass`.** It resolves the declared
  class from the `ToolDescriptor` and then writes the attempt row with a hardcoded literal
  (`recoveryClass = "IDEMPOTENT"` in `executeToolCall`, `"PURE"` in the model-call path). Crash
  recovery reads that column to decide whether an effect may be re-executed, so an `UNSAFE` tool
  is currently recorded as safely re-runnable. This is exactly the class of bug RFC-0009's
  guarantee exists to prevent, and it undercuts M8, which must never be amber.
- **RFC-0042 (Networking and Egress) has no enforcement anywhere.** There is no centralized egress
  chokepoint: no host allowlist, no private/loopback-address rejection, no `egress_records` written
  by any path. `CapabilityScope.Network` exists in the kernel with the RFC's field shape and is
  never consulted. At least three HTTP clients were built independently with inconsistent
  protection — `HttpTool`, a general-purpose tool exposed to the model, calls whatever URL it is
  given, which is a live SSRF exposure against the exact target the RFC names by example
  (`http://169.254.169.254/`). `HttpMcpClient` has real protections, but bespoke to itself.
- **RFC-0046: actor attribution collapses to hardcoded literals.** `ActorRef` and its schema
  columns are real and correctly designed, but nearly every audit-writing call site hardcodes
  `actorKind = "SESSION"` regardless of who acted, and `device_id` is always the literal
  `"runtime"`. `DeviceIdentity` has no implementation. This degrades forensic precision rather than
  an authority boundary, so it ranks below the two above — but the audit trail is the product's
  accountability claim.

### 2. MCP (RFC-0031) — the SDK migration landed; the wiring did not

The client is real and speaks the protocol through the official Kotlin MCP SDK. What remains:

- **Nothing calls it.** MCP is still not wired into `ToolBroker`/`RuntimeCompositionRoot`, so an
  MCP server cannot be reached from a live Run. This is the item that makes the rest observable.
- **No enable-time flow.** The user must be shown each server's catalog — every operation's name
  and description — and choose which, if any, to adopt. The store and the persisted adoption
  records exist; the surface that drives them does not.
- **No lifecycle manager**: lazy connect exists per client, but nothing releases an idle server.
- **Adoption-hash migration** for rows stored before descriptors were persisted: recompute from the
  persisted `input_schema_json` rather than mass un-adoption.
- **A security regression suite.** Named test by test rather than as a gesture: an unregistered
  server cannot be contacted; an unenabled server cannot provide executable tools; revoked
  capabilities stop calls; MCP metadata cannot grant or widen permissions; an instruction-shaped
  description stays fenced descriptor prose and never becomes a system turn; secrets never reach
  audit records or error messages; two servers sharing a display name remain distinct subjects.
- **An external interoperability suite.** One stdio server and one Streamable HTTP server, at least
  one of them a non-Kotlin implementation. Everything green today runs against fixtures we wrote
  ourselves, which is a weaker claim than it looks — see `lessons.md` §6.
- **`JsonRpc.kt` has no production consumer** now that the SDK owns the protocol; only fixtures and
  tests use it. Move it to `jvmTest` or delete it.
- **Upstream:** the SDK's `ToolSchema` models five keys with no catch-all, so unmodeled top-level
  JSON Schema keywords are dropped. RFC-0031's "Protocol layer" amendment records why that is
  tolerable. A passthrough fix should still be filed upstream.

### 3. RFC-0011 driver/worker orchestration — designed, not built

The design was worked out and is the plan; start from it rather than re-deriving it.

- **Spawning**: a `COMPOSITE` task creates a worker session (`role = WORKER`, `parent_session_id` =
  the driver's), delegates a caller-selected subset of the driver's capabilities via
  `CapabilityManager.delegate()` with `Budget.split(ways)`, creates a child Run via
  `RunCreator.createForUserMessage` (the brief as the child's first user message), and returns
  `TaskResult.park(SuspendedOperation.ChildRun(...), AWAITING_INPUT)` — reusing the existing
  generic park primitive, not a new mechanism.
- **Driving the child and resuming the parent**: there is no background scheduler; every Run today
  is driven synchronously by whoever created it. Write the parent's continuation row *first*, then
  recursively `drive(childRunId)` on the same executor instance. Add a
  `resumeAwaitingParent(childRunId)` hook at every point `drive()` reaches a terminal state: find
  the `continuations` row by `correlation_id`/`suspended_operation = 'CHILD_RUN'`, record the
  outcome on the parent's task, set the parent back to `RUNNING`, re-drive. The ordering is what
  makes it correct when the child finishes synchronously inside that same call.
- **`DEPENDS_ON` / `SKIPPED`**: `pendingTasksFor` today returns the lowest-ordinal `PENDING` task
  with no dependency awareness. Scan in ordinal order and consult `execution_edges`: a
  `FAILED`/`SKIPPED` dependency marks this task `SKIPPED` and the scan continues (it cascades); a
  non-terminal dependency means keep scanning, since a later sibling may be runnable; all-completed
  or no edges means runnable. A task with zero edges stays vacuously runnable, so this is additive.
- **What a `COMPOSITE` task needs before it can spawn** — a brief, which capabilities to delegate,
  how many ways to split the budget — fits no `tasks` column. Plan is a small
  `worker_spawn_requests` table populated via `NewTaskSpec.afterInsert`, the way `tool_calls` rows
  are written today. **Not yet in `schema/`.**
- **Treeless isolation**: `TreelessWorker` is real and has zero callers. Plan is a `WorkerCommitter`
  seam in `executor` mirroring `RunReconciler` (interface in commonMain, JGit implementation
  composed in `daemon`), called from `resumeAwaitingParent`, writing to
  `refs/aidos/workers/<workerSessionId>`. Scoped down deliberately even in the original plan: the
  filesystem and git tools are real-working-tree tools with no treeless-aware variant, so a first
  cut commits an outcome summary, not real code diffs — and should say so.
- **`Permission.WORKER_CREATE`** exists in the kernel and is granted and checked nowhere.
  `WorkerSpawner` must check the driver session holds it before spawning.

### 4. `RealRuntimeClient` is still in-memory — sessions/runs now hydrate; Android isn't wired

Project persistence and locking are wired through optional injection seams, and `daemon`'s factory
is the one consumer wired end to end. **2026-08-25:** `sessions.list()`/`get()` now hydrate from the
project's own `sessions`/`runs` tables when a driver is open (mirroring `hydrateProjectSummary`),
and `sessions.send()` persists a real `PENDING` `runs`/`tasks` row even when no `RunExecutor` is
wired, instead of the old `_runs`-map-only stub. Covered by `RealRuntimeClientSessionTest`.

**What's still open, and it's the part that actually reaches the Android app:** `androidapp`'s
`MainActivity` still constructs a bare `RealRuntimeClient()` with nothing injected — no
`userDriver`, `projectDbFactory`, or `projectLocker` — so on-device it still runs exactly like the
old in-memory mock; the fix above only takes effect once something wires those seams. Doing that
needs an Android `SqlDriver` (`app.cash.sqldelight:android-driver`, not the JVM `sqlite-driver`
`storage`'s `jvmMain` uses today — `storage` declares `androidTarget()` but has no `androidMain`
source set yet) and an Android-appropriate path scheme (`Context.filesDir`, not `DesktopPaths`'
`System.getProperty("user.home")`), i.e. an Android equivalent of `daemon`'s
`RuntimeClientFactory`. `ProjectLocker` is deliberately left out of that follow-up: its own doc
comment already flags Android's implementation as unverified/deferred (real-device `FileLock`
behavior, same status as capability's `SqliteDirHandle`), so don't invent one blind.

**Why this is untracked rather than just built:** neither this sandbox nor CI's `test-agent` job
(`gradle jvmTest`) can compile `androidMain` — there's no Android SDK in either place today (lesson
in `lessons.md`: "`gradle jvmTest` passing... proves nothing about whether `androidMain` can see
what it imports"). Writing the SqlDriver/factory/`MainActivity` wiring blind, with no way to
compile-check it, is the wrong tradeoff until there's a real Android build available to verify
against — flagged here rather than guessed at.

### 5. A mapping test is owed

A test asserting every non-derived kernel field has a schema column. Noted when the kernel was
written, deferred because there was nothing to map to yet. It is the third leg of the CI that keeps
design and code together, alongside `schema/check.py` and the module test suites.

### 6. Blocked on real hardware — not on code

State these as blocked rather than letting a checkmark imply otherwise. G3 once carried a PASSED
mark that no device had earned.

- **M21** — one local LLM on a mid-range phone.
- **M22** — the knowledge index is complete and platform-neutral; on-device behavior is unverified.
- **M26 · G3** — the on-device measurement. Cannot be asserted in CI, by design.
- **M34** — F-Droid distribution (needs a reproducible build and a device).
- **M35 · G4** — the scenario, performed by a person.

Also unverified rather than missing: `:modelruntime`'s adapter compiles and its tests pass, but no
test has ever constructed a real model and run inference. That is real-hardware work.

### Known external dependency

**M22 consumes `gitsema-kotlin` as a library (D29), and it is not blocked.** `androidTarget()` is
wired and building, the library has CI on both targets, `search()` returns coverage directly, and
both git walks stream in bounded windows.

**What remains is unverified rather than missing: it compiles for Android and has never run on
one.** No instrumented tests; a JGit JMX guard whose necessity is established and whose sufficiency
is not; `FS_POSIX` and `FileStoreAttributes` hazards unaddressed; the SQLite driver's absolute-path
handling asserted rather than observed; nothing measured about memory-mapped page-cache behaviour
under Android pressure. **That is precisely what G3 measures.**

Cheapest available de-risking, and it can happen any time: the library ships a desktop CLI driving
the same core, so "run against a real repository at scale" does not wait for Aidos. Pin a commit,
not a branch. Full list in RFC-0015, "Known dependency risks".

---

## How to work

The loop, once per milestone:

1. **Read the milestone** in [`docs/mvp-roadmap.md`](docs/mvp-roadmap.md) — its RFCs and its
   **done-when**. The done-when is the definition of finished; it is written to be *observable*
   rather than asserted.
2. **Read the RFCs it names**, and any decision (`D<n>`) it cites in `docs/decisions.md`.
3. **Check the code before trusting any status line**, here or in an RFC. See `lessons.md` §1.
4. **Implement.** Minimal — what the RFC says, no more.
5. **Test against the done-when.** If the done-when cannot be observed by a test, the milestone is
   not finished.
6. **Verify** (both, every time, after the last edit):
   ```bash
   python3 schema/check.py                  # canonical DDL: executes, FKs resolve, RFC↔schema agree
   cd agent && gradle jvmTest --continue     # what CI runs; --continue enumerates every failure
   ```
   The Engine and SDK build the same way from `engine/` and `sdk/`.
7. **Commit** per `CLAUDE.md`: reference the RFC, explain the *why*, one logical change. Stage by
   path and read `git diff --stat` before committing.
8. **Update this file in the same commit** if what is next changed; add to `lessons.md` if the work
   taught something transferable.
9. **Push** to the working branch. Do **not** open a pull request unless asked.

### Rules that are not negotiable

- **`docs/decisions.md` is settled.** If a decision looks wrong, say so in your final message. Do
  not quietly implement something else.
- **`schema/` is canonical DDL.** Where an RFC's DDL and the schema disagree, **the schema is right
  and the RFC is the bug**. Fix both in the same commit. `check.py` runs in CI and must stay green.
- **A banner marking a document known-wrong is deleted by the commit that makes it right.**
- **`kernel/` is contracts only.** No implementations; they go in a sibling module. It is frozen at
  G0. Do not add a parameter to a kernel interface to make a feature work — find another way.
- **Amend the RFC before departing from it**, in its own commit, not alongside the code. RFC
  amendments are marked sections inside the RFC, not new files beside it.
- **G1 blocks all AI work.** Crash recovery is the one metric with no acceptable degradation: 100%
  of `kill -9` points resume correctly, not "mostly". The `RecoveryClass` defect above is a live
  claim against this rule.

### Where everything lives

| Document | What it is | Authority |
|---|---|---|
| **`PIPELINE.md`** | this file — the roadmap and what is open | the working state |
| [`lessons.md`](lessons.md) | what we learned the hard way | read before your first commit |
| [`docs/mvp-roadmap.md`](docs/mvp-roadmap.md) | 38 milestones with RFCs and done-when conditions | the work breakdown |
| [`docs/decisions.md`](docs/decisions.md) | settled decisions — *why* the architecture is this | **settled**; cite `D<n>` |
| [`docs/rfcs/`](docs/rfcs/) | 67 RFCs — *what* the system does | Accepted ⇒ implement against it |
| [`schema/`](schema/) | canonical DDL, 3 files, 59 tables, `check.py` | **governs**; RFC DDL defers to it |
| [`kernel/`](kernel/) | KMP contract surface, no implementations | frozen at G0 |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | the map, one paragraph per subsystem | orientation only |
| [`CLAUDE.md`](CLAUDE.md) | how Claude works in this repo | process |

`kernel/` is a repo-root module, not `agent/kernel` — both the `agent/` and `engine/` Gradle roots
include it by path so the two share one definition instead of two drifting copies.

---

## Working across sessions

Phases 3–4 will not fit one session. Use the `session-pipeline` skill
(`.claude/skills/session-pipeline/`): on wake, **schedule the next wakeup first**, then re-orient,
then work one coherent piece, then commit, push, and update this document.

Wakeup message to carry forward verbatim, with `N` incremented each time:

```
SESSION PIPELINE — link N.

FIRST ACTION: schedule the next wakeup.
  send_later(delay_minutes = 305, message = <this message, with N incremented>)
Do this before reading files, before git status, before anything. If the tool is
unavailable, say so in your final message — the chain is broken and the user must restart it.

Repo:   /home/user/aidos  (github.com/jsilvanus/aidos)
Branch: claude/aidos-<topic>-<suffix>, from main. Create it if it does not exist.
Plan:   PIPELINE.md — read it first, then lessons.md. PIPELINE has the goal, the working
        loop, the non-negotiable rules, and what is open. docs/mvp-roadmap.md has the
        milestones.

Then: re-orient (git status, git log -5, read PIPELINE.md), take an item from "What is
next", make real progress on it, verify (python3 schema/check.py AND
cd agent && gradle jvmTest --continue), commit, push, and update PIPELINE.md in the same
commit. End the turn.

Do not open a pull request unless the user asks.

Stop the chain — schedule nothing further — if the work is complete, the user says stop,
or you are blocked on something only the user can resolve. Say which, explicitly, in both
the final message and PIPELINE.md.
```

**What "real progress" means here.** One milestone is a good unit; half of one is acceptable if it
ends at a commit that builds and whose tests pass. What is not acceptable is ending a link with
uncommitted work, a red `check.py`, or a PIPELINE.md that does not describe what is actually open —
the next link starts from this file and nothing else.
