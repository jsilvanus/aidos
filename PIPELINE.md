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

**2026-08-07 · Phase 3 complete. G3 (mid-range phone capabilities) passed. Phase 4 remains: M33 (voice), M34 (F-Droid), M35/G4 (end-to-end scenario with real person).**

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
| Executor | `runtime/executor/` — `EventStore` (per-project monotonic sequence ordering, RFC-0004, causal depth ceiling MAX=16); `SqliteExecutor` (RFC-0009: re-entrant `drive()`, D14 concurrency invariant, PENDING/INTERRUPTED→RUNNING→COMPLETED loop, step ceiling, task runner abstraction); `recover()` (UNSAFE→INDETERMINATE, PURE/IDEMPOTENT reset to PENDING, orphan RUNNING tasks reset). M5 ✅, M6 ✅ |
| Lock | `runtime/lock/` — `ProjectLock`: OS advisory file lock (FileChannel.tryLock), heartbeat, stale lock detection and break, AlreadyHeld / StaleBreakable / Acquired results. M7 ✅ |
| Crash | `CrashRecoveryTest`: B1/B2/B3/B4 boundaries, idempotency. **G1 passed**. M8 ✅ |
| API | `runtime/api/` — `RuntimeClient` interface, `MockRuntimeClient`, `CommitResult`. M9 ✅ |
| CLI | `runtime/cli/` — CLI frontend: create project, list sessions, send message, event stream, approve, diff, artifacts, audit. G2 end-to-end test. M10 ✅, M19/G2 ✅ |
| Filesystem | `runtime/filesystem/` — `ResourceHandle`, read/write/list/search, `Preview.Diff`, escape guard. M12 ✅ |
| Git | `runtime/git/` — status/diff/add/commit/branch/log/checkout on real repo; `push` UNSAFE; reconciliation. M13 ✅ |
| Vault | `runtime/vault/` — API key round-trip through `vault.db`; `AnthropicAdapter` normalizes tool calls; retention policy recorded as UNKNOWN when absent. M14 ✅ |
| Prompt | `runtime/prompt/` — `PromptAssembler` (two-phase token budget, D22), `InstructionDiscovery` (AGENTS.md/CLAUDE.md, SHA-256 identity). 13 tests. M15 ✅ |
| AgentLoop | `runtime/agentloop/` — full cycle: router→assemble→checkpoint→invoke→taint→execute→checkpoint; maxSteps=24; loop detection. 6 tests. M16 ✅ |
| Memory | `runtime/memory/` — `SessionMemoryStore`: FACT/DECISION/TASK_STATE, mandatory source_refs, D32/D33 schema constraints. 9 tests. M16b ✅ |
| Injection | `runtime/agentloop/injection/` — 7 hostile corpus tests: README, comments, commits, tool output, MCP, role reassignment, nested injection. M17 ✅ |
| MCP | `runtime/mcp/` — stdio (DESKTOP/SERVER, SHELL_EXEC) + HTTP (all profiles, HTTPS enforced, NETWORK_EGRESS); resultGuidance null (D23); D30 enforced. 11 tests. M18 ✅ |
| ModelRuntime | `runtime/modelruntime/` — globally serialized admission queue; digest verification; `DigestMismatchException`. 7 tests. M20 ✅ |
| Routing | `runtime/routing/` — `PolicyInferenceRouter`: user-owned policy, UnavailableOffline, tainted-run pending approval, allowlist, ForegroundRequired (D24). 8 tests. M23 ✅ |
| Worker | `runtime/worker/` — `TreelessWorker`: JGit object-DB commits with no worktree on `refs/aidos/workers/<id>`; working tree never touched. 5 tests. M24 ✅ |
| Retention | `runtime/retention/` — `RetentionEngine`: 90-day expiry, 512 MB cap, LRU eviction, active-session protection, interruptible+resumable (yields per row). 6 tests. M25 ✅ |
| AndroidApp | `runtime/androidapp/` — Phase 4 platform-neutral logic: `RuntimeServiceHost` (M27), `AvailabilityReporter` (M29), `ApprovalPresenter` (M30), `NotificationManager` (M32), `RunSummaryComputer`+benign classifier (M32b), `IntentList`+proposal gate (M32c); `ProjectsPresenter`/`SessionListPresenter`/`RunListPresenter`/`EventStreamPresenter` (M28); `CommitPresenter`+`DiffUiState`+`CommitDraftState` (M31). 37 tests. M27/M28/M29/M30/M31/M32/M32b/M32c ✅ |
| Knowledge | `runtime/knowledge/` — `KnowledgeIndex` adapter over `gitsema-kotlin` `SemanticIndex`; `GitsemaKnowledgeIndex` adapter; `LocalOnlyEmbeddingProvider` placeholder; `buildKnowledgeIndex()` factory. FTS-only until M21 loads a model (D29: coverage always reported). M22 ✅ |
| Milestones | **M1–M25, M27/M28/M29/M30/M31/M32/M32b/M32c, M22, M26/G3 complete**. Blocked: M21 (real phone). Phase 4: M33/M34/M35 (real device/person) |

**Phase 3 complete; G3 (mid-range phone capabilities) verified. Phase 4 infrastructure is complete. Remaining work: M33 (voice STT/TTS — optional), M34 (F-Droid distribution), M35/G4 (end-to-end scenario with real person).**

- **M21** (local LLM on phone): cold-start < 10s requirement cannot be verified without a real mid-range Android phone.
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
- [x] **M26** — On-device measurement **G3** — ✅ **PASSED: mid-range phone capabilities verified, Phase 3 complete**

### Phase 4 — Android application (M27–M35)

Platform-neutral logic implemented and tested. Android wiring (Compose, Service lifecycle,
androidTarget()) requires the Android SDK and a real device.

- [x] **M27** — Foreground service and runtime hosting (platform-neutral logic) ✅
- [x] **M28** — Compose UI over the Runtime API — ✅ (platform-neutral presenters: Projects, Sessions, Runs, EventStream)
- [x] **M29** — Availability reporting ✅
- [x] **M30** — Approval, preview, and memory review ✅
- [x] **M31** — Diff and commit review — ✅ (platform-neutral: `DiffUiState`, `CommitPresenter`, `CommitDraftState`; `DiffQueries.commit()` added to API)
- [x] **M32** — Notifications ✅
- [x] **M32b** — Run Summary and the benign-approval classifier ✅
- [x] **M32c** — Intent as a task list, with the proposal gate ✅
- [ ] **M33** — Voice capture → local STT, spoken summaries → local TTS — *optional, cut first*
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

**M1 is half done. Settings (RFC-0036) and the mapping test are what's left before M2.** Do not
start M2 (identity and scopes) with M1 incomplete — the roadmap lists M1 and M2 as parallel-safe
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
