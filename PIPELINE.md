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

### Immediate next five items (selected from the backlog)

These are the next five items to execute from the live backlog, in order. The first item is already
under active work on this branch; the next four are the highest-value follow-ons without waiting on
hardware or product decisions.

1. **Fix `AgentLoopTaskRunner` recovery metadata** — persist each tool's actual `RecoveryClass`
   instead of hardcoded literals, and keep the model-call path at `PURE` only where it is truly a
   non-effectful call.
2. **Enforce RFC-0042 networking and egress controls** — add a centralized policy gate for outbound
   URLs, loopback/private ranges, and explicit allowlists before any `HttpTool`-style call can leave
   the process.
3. **Fix RFC-0046 actor attribution** — stop hardcoding `actorKind = "SESSION"` and
   `device_id = "runtime"`; emit the real actor identity and device identity in every audit row.
4. **Finish the Android inference slice** — prove a real GGUF loads on a device, prompt execution
   reaches native llama.cpp, and the direct tester exposes the required load/generation metrics.
5. **Harden the inference lifecycle and resource ownership** — finish bounded admission, model/load
   deduplication, cancellation propagation, deterministic disposal, and concurrency protection for the
   active engine runtime.

### 2. Android Engine — complete the offline runtime

The Engine is the critical path from the platform-neutral runtime to the product described in the
Goal. Treat this as one ordered workstream. Do not start higher-level agent features until the
lower layers below have observable, tested behavior.

#### 2.1 Real Android inference — finish the current inference slice

**Current state:** PR #57 is the in-flight first slice. It introduces an Android-specific llama.cpp
adapter/backend and an internal direct-inference tester without moving the JVM-only `:modelruntime`
module onto Android.

Finish and verify it:

- Build the Android app with the native llama.cpp binding and verify native library loading on a
  real ARM64 device.
- Load an actual GGUF from the Engine model store; do not use a mock model for the acceptance test.
- Verify prompt → generated text through the real native backend.
- Verify incremental native generation reaches `ModelAdapter.invokeStreaming()` as deltas rather
  than synthesising chunks from a completed response.
- Keep the internal tester deliberately separate from HTTP Test Chat: it is the lowest-level
  inference diagnostic and must be usable even when the HTTP stack is the thing under test.
- Make the tester expose model selection, prompt, generated text, load time, TTFT, generation
  time, input/output/total tokens, tok/s, stop reason, and failure state.
- Add an instrumentation smoke test around the smallest practical real GGUF fixture/model. If the
  fixture is too large for CI, keep the test device-gated and document exactly what is verified.
- Verify R8/proguard and release packaging; native libraries must survive minification and be loaded
  from the expected ABI.
- Verify model-file path resolution against the real downloader/installer rather than assuming a
  filename convention.
- Verify that a missing, corrupt, unsupported, or incompatible GGUF produces a useful classified
  error rather than a generic crash.
  - **2026-09-04 update:** Engine HTTP now classifies model-load failures into explicit API outcomes
    (`model_not_installed` 404, `invalid_model` 400, `incompatible_model` 422, `model_load_failed`
    500) instead of a generic inference failure; keep extending this mapping as native error causes
    become more precise.

**Done when:** a real Android device can select an installed GGUF, run a prompt, and visibly receive
true incremental output from the native backend; failure and cancellation are observable; the
Android/native build is actually verified. Do not mark this done from JVM tests alone.

#### 2.2 Inference lifecycle, serialization, cancellation, and resource ownership

After real inference works, make it safe to run continuously in a phone process.

- Define the ownership of every loaded native model/context and make disposal deterministic.
- Serialize generation per model/context unless the native backend is explicitly proven safe for
  concurrent generation. Never allow two requests to corrupt a shared native context.
- Decide and document whether different models may generate concurrently; if yes, bound the number
  of simultaneously resident/active models.
- Introduce explicit request states: queued, loading, running, completed, cancelled, failed.
- Add bounded request queueing. A full/busy Engine must return a defined `503`-style condition rather
  than hanging callers or spawning unbounded coroutines.
- **2026-09-04 update:** Engine HTTP inference now runs through a bounded request manager with
  explicit busy/shutdown `503` outcomes, per-model generation serialization, and lifecycle metrics
  counters (queued/running/completed/cancelled/failed). Keep extending it with deeper native-cancel
  support and memory-admission checks.
- Propagate cancellation from UI/HTTP client through Engine → adapter → native generation as far as
  the binding permits. If native cancellation is unavailable, make the limitation explicit and
  ensure the native context is not reused unsafely.
- Ensure unload cannot race with generation. A model may only be disposed after all references and
  active requests have left it.
- Add model load deduplication: simultaneous requests for the same unloaded model share one load
  rather than loading the GGUF twice.
- Add admission/resource checks before loading: available memory, estimated model/context footprint,
  configured limits, and clear refusal when the request cannot safely fit.
- Add LRU or equivalent eviction for loaded models, with active-request protection and deterministic
  cleanup.
- Record lifecycle metrics: load count/time, resident models, estimated memory, queue depth,
  generation duration, cancellation count, failure count.
- Test concurrency, cancellation, unload races, duplicate loads, queue saturation, and eviction
  with a fake backend; then repeat the critical cases with the real Android backend where practical.

**Done when:** no request can race model disposal, duplicate-load, or shared-context generation;
bounded overload produces an explicit failure; cancellation reaches the deepest supported layer; and
all resource ownership paths have tests.

#### 2.3 True streaming through the complete Engine

The direct tester must become the reference implementation for streaming; HTTP must not re-tokenize
completed text.

- Make `ModelAdapter.invokeStreaming()` the canonical streaming seam.
- Wire native llama.cpp token/delta production → adapter → `InferenceBackend` → Engine service → HTTP
  SSE without buffering the complete response first.
- Preserve ordering and never emit duplicate or lost deltas.
- Emit a single terminal event containing usage/token counts and stop reason.
- Distinguish normal completion, cancellation, model failure, and transport disconnect.
- Propagate HTTP client disconnect to request cancellation.
- Ensure backpressure does not create an unbounded in-memory stream buffer.
- Keep the direct tester and HTTP path on the same lower-level streaming implementation so the tester
  catches regressions in the transport path rather than becoming a second implementation.
- Add tests for event ordering, terminal-event uniqueness, cancellation, client disconnect, slow
  consumers, backend failure, and empty/very-short generations.

**Done when:** an HTTP client receives tokens while generation is still running, with no
post-hoc tokenisation of a completed response, and disconnect/cancellation releases the native work.

#### 2.4 Real embeddings

The current embedding implementation is not acceptable as the knowledge-index runtime.

- Replace placeholder/zero vectors with actual llama.cpp embedding inference.
- Define how embedding-capable GGUFs/models are identified and loaded separately from chat models.
- Expose the actual vector dimension and validate it at the Engine boundary.
- Ensure embedding inference has the same lifecycle, memory, cancellation, and concurrency rules as
  generation where applicable.
- Add deterministic tests for vector length, non-zero output, repeatability within defined tolerance,
  and incompatible-model errors.
- Add a small real-device validation against the model used for G3/M22.
- Keep embedding models from silently consuming the same chat-model slot unless the resource manager
  explicitly permits it.

**Done when:** the knowledge index can obtain real vectors from the Engine and the vector dimension
and model capability are explicit and tested.

#### 2.5 Model management and catalog integrity

The Engine's installed files and its model catalog must agree; filenames alone are not authority.

- `/v1/models` now exists in Engine HTTP; extend it to remain stable across catalog/install drift
  and keep its metadata authoritative under reconciliation/integrity failures.
- Return stable model IDs, capabilities, format, size, quantization where known, installed state,
  loaded state, and relevant metadata.
- Reconcile catalog metadata against installed files at startup and after download/delete.
- Verify digest/checksum against authoritative metadata or a trusted sidecar; do not derive the
  expected digest from the file being verified.
- Handle partial downloads, renamed files, duplicate artifacts, stale catalog entries, corrupt files,
  and deleted models.
- Make model deletion safe with respect to active requests and loaded native contexts.
- Keep download/install, catalog, runtime loading, and eviction responsibilities separated.
- Add tests for reconciliation and integrity failures.

**Done when:** `/v1/models` reports the real installed state and the Engine refuses to execute a model
whose integrity/capability metadata cannot be trusted.

#### 2.6 Android persistence and real `RuntimeClient` wiring

`RealRuntimeClient` has persistence seams, but the Android application still needs to provide the
Android implementations.

- Add the Android SQLDelight driver/source set required by the existing storage abstraction.
- Wire an Android `RuntimeClientFactory` equivalent into `MainActivity`/the application service.
- Use `Context.filesDir` or the chosen Android app-private storage root, not desktop path helpers.
- Wire `userDriver`, `projectDbFactory`, and the appropriate project persistence dependencies.
- Do not invent an Android `ProjectLocker` implementation without testing the real-device file-lock
  semantics; keep that seam deferred if the platform behavior remains unverified.
- Verify session/run/task persistence across app process death and restart.
- Verify a real project can be opened, queried, edited, and committed from Android rather than only
  through the in-memory client.
- Add instrumentation coverage for persistence and restart behavior.

**Done when:** the Android app's sessions and runs are backed by the real project database and survive
process restart, with no accidental fallback to the in-memory maps.

#### 2.7 Engine HTTP/API hardening

Once direct inference and streaming are real, harden the service boundary.

- Define consistent status/error mapping: invalid request, missing model, loading, busy, unsupported
  capability, native failure, cancellation, and internal failure must be distinguishable.
- Ensure authentication/token checks and Binder-discovered service identity remain enforced for every
  HTTP path.
- Ensure no endpoint bypasses the Engine's lifecycle/resource manager.
- Add request size limits, generation limits, timeout policy, and safe defaults.
- Ensure error responses never expose filesystem paths, native internals, prompts containing secrets,
  or stack traces unnecessarily.
- Add API compatibility tests for chat, streaming, embeddings, model listing, and model management.
- Add concurrent-client tests and service-restart tests.

**Done when:** every externally reachable inference request crosses the same authority, lifecycle,
and resource boundaries and has a deterministic API-level outcome.

#### 2.8 STT / multimodal native backends

STT and multimodal support are secondary to a reliable text LLM path, but they must not remain as
fake implementations hidden behind completed-looking APIs.

- Verify the current STT handler's actual input contract and replace placeholder/image-based audio
  handling with a real native STT backend where the RFC requires it.
- Define model capability metadata for text generation, embeddings, speech, vision, and future
  multimodal models rather than guessing from filenames.
- Implement only the capabilities actually required by the accepted RFCs; unsupported modalities
  must return explicit `unsupported` rather than pretending to work.
- Validate native ABI/loading and memory behavior on the target Android device.
- Add instrumentation tests for the minimum real STT path before marking it complete.

**Done when:** every advertised Engine modality is either genuinely exercised on Android or explicitly
reported as unsupported/deferred; no placeholder implementation is presented as production behavior.

#### 2.9 Binder/service lifecycle and security integration

The Engine is a local service with a security boundary, not just a library.

- Verify foreground-service startup, restart, shutdown, and native-runtime cleanup on actual Android.
- Verify Binder discovery and handshake against a real app process/service.
- Verify approval/trust state is enforced before model access where required by the existing design.
- Verify token issuance/refresh/expiry and loopback HTTP authorization end to end.
- Verify multiple clients cannot cross session/project/model authority boundaries.
- Verify service restart does not leave stale native handles, locks, or authorizations.
- Add instrumentation/security tests for unauthorized access, revoked trust, stale tokens, restart,
  and concurrent clients.

**Done when:** the Engine can be restarted and reconnected without leaking resources or widening
authority, and the real Android service boundary passes the security suite.

#### 2.10 Engine integration and end-to-end tests

Build the test ladder from cheap deterministic tests to real-device proof.

- Unit tests for lifecycle/state machines using a fake `InferenceBackend`.
- JVM integration tests for HTTP/API behavior with a deterministic fake backend.
- Android instrumentation tests for service startup, Binder handshake, model discovery, and direct
  inference.
- Real-GGUF smoke test on target ARM64 hardware.
- Real streaming test that observes multiple deltas before completion.
- Real cancellation test.
- Real model-load/unload/reload test.
- Real memory-pressure/eviction test where safe to perform.
- Real embeddings test.
- Real project persistence/restart test.
- Real API end-to-end test through the service boundary.
- Capture measurements required by G3/M26: model, quantization, prompt size, output size, load time,
  TTFT, generation tok/s, peak/estimated memory, device model, Android version, and thermal/battery
  conditions where relevant.

**Done when:** each Engine claim has a corresponding observable test, and device-dependent claims are
marked only after device evidence exists.

#### 2.11 Engine UI — diagnostic first, product UI second

The Engine UI should expose enough state to diagnose the runtime without becoming a second runtime.

- Keep the internal inference tester as a diagnostic surface.
- Add model list/status and loaded/unloaded state.
- Show loading/busy/queued/error states clearly.
- Expose cancellation.
- Show inference metrics useful for diagnosing hardware/model problems.
- Make service/runtime availability explicit rather than silently falling back to mocks.
- Add basic project/session state once real `RuntimeClient` persistence is wired.
- Avoid building polished chat UX until the runtime has passed real-device validation.

**Done when:** a developer can diagnose model availability, inference state, errors, and performance
from the Android app without attaching a debugger.

#### 2.12 G3/G4 closure

The final Engine work is not complete until the offline product thesis is demonstrated.

- **M21:** real local LLM inference on the target mid-range Android phone.
- **M22:** knowledge index using real embeddings and real repository data on device.
- **M26 / G3:** perform and record the defined on-device measurement; do not infer it from CI.
- **M34:** reproducible Android/F-Droid build and installation validation.
- **M35 / G4:** complete the human scenario: open a real repository, ask a code question offline,
  receive a useful answer, edit, review diff, and commit.
- Record failures as blockers or lessons, not as green status lines.

**Engine gate:** G3/G4 may only be marked complete after the entire chain is exercised on real
hardware: project persistence → repository/index access → model loading → inference → streaming →
answer → edit → diff → commit.

### Engine sequencing rule

Use this order unless a concrete dependency proves otherwise:

**2.1 real inference → 2.2 lifecycle/resources → 2.3 true streaming → 2.4 embeddings →
2.5 model management → 2.6 Android persistence → 2.7 API hardening → 2.8 STT/multimodal →
2.9 Binder/security integration → 2.10 integration tests → 2.11 UI → 2.12 G3/G4.**

Do not jump to agent orchestration, MCP, or additional product features because an Engine layer is
awkward. Fix the seam and add the test first. Do not call an Engine feature complete merely because
its platform-neutral implementation compiles.

### 3. MCP (RFC-0031) — the SDK migration landed; the wiring did not

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

### 4. RFC-0011 driver/worker orchestration — designed, not built

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

### 5. `RealRuntimeClient` is still in-memory — sessions/runs now hydrate; Android isn't wired

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
`System.getProperty("user.home")`), i.e. an Android equivalent of `daemon`'s `RuntimeClientFactory`.
`ProjectLocker` is deliberately left out of that follow-up: its own doc comment already flags
Android's implementation as unverified/deferred (real-device `FileLock` behavior, same status as
capability's `SqliteDirHandle`), so don't invent one blind.

**Why this is untracked rather than just built:** neither this sandbox nor CI's `test-agent` job
(`gradle jvmTest`) can compile `androidMain` — there's no Android SDK in either place today (lesson
in `lessons.md`: "`gradle jvmTest` passing... proves nothing about whether `androidMain` can see
what it imports"). Writing the SqlDriver/factory/`MainActivity` wiring blind, with no way to
compile-check it, is the wrong tradeoff until there's a real Android build available to verify
against — flagged here rather than guessed at.

### 6. A mapping test is owed

A test asserting every non-derived kernel field has a schema column. Noted when the kernel was
written, deferred because there was nothing to map to yet. It is the third leg of the CI that keeps
design and code together, alongside `schema/check.py` and the module test suites.

### 7. Blocked on real hardware — not on code

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
  amendments are marked sections inside the RFC, not new files beside them.
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