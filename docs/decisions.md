# Aidos Architecture Decisions

Decisions that shape the architecture, with what each one forecloses and what it costs to
revisit. RFCs say *what the system does*; this says *why it is that and not something else*, so
that settled questions stay settled and open ones stay visible.

**Status values:** `SETTLED` — decided, RFCs reflect it. `RECOMMENDED` — proposed with a
rationale, not yet signed off. `OPEN` — needs a decision.

Amend by adding a dated entry rather than editing history. A decision that was reversed is more
useful than one that appears never to have been made.

---

## Foundational

### D1 — Deterministic replay is not a goal · `SETTLED`

The event log supports **audit reconstruction**: what happened, in what order, caused by what,
with which prompt, model, and capability. It does not support re-execution to an identical
state.

Model sampling, provider versions, wall-clock, filesystem races, user-driven Git changes, shell
output, and MCP responses are all non-deterministic. Capturing enough to make re-execution
identical means capturing every output — at which point what exists is a recording, not a
replay.

**Forecloses:** "restore my project to Tuesday", "re-run this session and get the same result".
Restoration is Git's job for content, export/import's for project state.
**RFCs:** 0004.

### D2 — A clone is not the whole project · `SETTLED`

Runtime state lives in a Git-ignored `.aidos/` inside the project directory. `git clone` gives
content and none of the sessions, artifacts, or audit trail. Moving a project with its history
is export/import, which moves the whole directory.

The alternative — committing SQLite to Git — produces unresolvable binary merge conflicts and
repository bloat on every session write. There is no third option.

**RFCs:** 0010, 0054, 0041.

### D3 — Step-machine execution · `SETTLED`

Session logic is an interpreter over persisted Execution Graph rows, not straight-line code.
Kotlin continuations are not serializable, so a design that assumes an uninterrupted process
cannot survive Android eviction — which is routine, not exceptional.

**Cost:** session logic may not hold important state in local variables across a step boundary.
Anything that must survive is a column. Every new contributor trips on this once.
**Reversal cost: total.** This shapes every session-facing API.
**RFCs:** 0009, 0006.

### D4 — JGit on all platform profiles · `SETTLED`

One Git implementation everywhere: no native build matrix, no JNI crash surface in the component
that writes the user's history, identical semantics on phone and desktop.

**Accepted ceiling:** no `git worktree` (treeless workers instead), slower on very large repos,
no LFS, no clean/smudge filters. No hooks execution — which is a feature, since hooks on clone
would be arbitrary code execution.
**Revisitable only wholesale:** adopting libgit2 later means adopting it on every profile.
**RFCs:** 0053, 0032, 0049.

### D5 — Daemon on desktop, in-process on Android · `SETTLED`

Desktop runs a separate runtime process; frontends connect over a socket. Android hosts the
runtime in-process inside a foreground service. Same `RuntimeClient` interface.

Desktop needs multiple frontends and must survive a UI crash; Android has exactly one frontend
by construction and no meaningful multi-process story.
**RFCs:** 0052, 0055.

**Amended 2026-08-14 — the decision for Aidos Agent's own runtime is unchanged; its stated
justification no longer holds platform-wide.** RFC-0103 (Aidos Engine) puts a second, independent
app on MOBILE that legitimately needs a multi-process, multi-frontend-serving shape — the exact
thing this decision's rationale said Android had no story for. RFC-0103's own Motivation names
this decision's premise directly: *"Android has no meaningful multi-process story for this... That
premise no longer holds: a second app, independent of Aidos Agent, is already in development."*

**What survives, narrowed rather than reversed:** Aidos Agent's own `RuntimeClient` — sessions,
projects, capabilities, executor, the agent loop — still has exactly one frontend, still hosts
in-process, and this decision's outcome for that component is untouched. What no longer holds is
the *general* claim about the platform: Android does have a meaningful multi-process story now
(a signature-verified Binder handshake plus a loopback HTTP daemon, authenticated per-request by a
bearer token) — it was simply narrower than "no story at all," and RFC-0103 is what needed it
first. **Aidos Engine takes the daemon shape this decision reserves for desktop**, scoped to that
one component, for the reason RFC-0103 states: its whole purpose is serving several independent
apps on one device, which is exactly the "needs multiple frontends" condition this decision already
uses to justify Desktop's own daemon shape — the same test, applied to a second MOBILE component
this decision didn't anticipate existing.

Read this decision going forward as: *"a component with exactly one frontend by construction hosts
in-process; a component serving several independent frontends runs as a daemon — true on every
profile, not a MOBILE-vs-DESKTOP platform rule."* Aidos Agent satisfies the first clause on MOBILE;
Aidos Engine satisfies the second, on the same profile. **RFCs:** 0052, 0055, 0103.

---

## Authority and trust

### D6 — The model may propose, run, and report — never confirm its own success · `SETTLED`

The single rule behind four mechanisms:

| Mechanism | The gate |
|---|---|
| `IMPLEMENTS` edges | proposed by the model, `confirmed` by user or acceptance criteria |
| Intent proposals | sessions propose, only users resolve |
| Acceptance criteria | verified by a mechanical check or the user, never `SESSION` |
| Declared plans | model proposes, user approves |

Without it: the model reads intent as instructions and writes intent as proposals, so it invents
goals, reads its own inventions back, and drifts — each step locally plausible, none checked.

**Use this as the review question for anything new.**
**RFCs:** 0012, 0019.

### D7 — Taint attenuates authority · `SETTLED`

A Run whose context has admitted untrusted content operates under a reduced capability set for its
remainder. In-project reversible work stays frictionless; egress, secrets, out-of-project mutation,
and `UNSAFE` effects require per-call approval naming the tainting source.

Prompt injection is dangerous because the next tool call carries the session's authority, not because
the model read the text. Delimiters ask the model to enforce a boundary; models are not reliable
enforcement points.

**Tuning note:** if prompts prove too frequent in practice, loosen the defaults — do not remove the
mechanism. Frequent prompts train dismissal, which is the failure mode.
**RFCs:** 0027, 0025, 0018.

### D8 — Budget divides on delegation · `SETTLED`

A driver holding 10,000 cost units delegating to three workers **divides** that allowance. It does not
multiply it. Without the rule, fan-out is an unbounded spend multiplier and orchestration becomes the
most expensive way to use the product.

Follows from RFC-0018's equal-or-more-restrictive delegation rule, but is stated because the natural
implementation gives each worker a fresh budget.
**RFCs:** 0011, 0028, 0018.

### D9 — Run budget defaults: 24 steps, 8 model calls · `SETTLED`

A product-feel decision, not an engineering one. Conservative at Run scope; absent above it.
Nagging users about monthly limits they did not ask for is worse than a per-Run ceiling that catches
runaways.
**RFCs:** 0028.

---

## Graphs

### D10 — Intent status is derived, never authored · `SETTLED`

Computed from `IMPLEMENTS` edges, acceptance criteria, children, and dependencies. User overrides are
stored as timestamped claims shown *alongside* the derived value, never replacing it.

A stored field becomes a lie the moment a Run is reverted, partially fails, or is later broken — and
it then feeds prompt construction, so the model inherits the false belief. Adds `STALE`, which a
stored field cannot represent and which is a normal event in a Git-first product.

**Not deferrable:** retrofitting derivation after a stored field exists means migrating data that was
never trustworthy.
**RFCs:** 0012.

### D11 — `TARGETED` (fact) and `IMPLEMENTS` (assertion) are separate edges · `SETTLED`

`TARGETED` is written by the runtime at Run creation. `IMPLEMENTS` is asserted at completion and carries
`confirmed`. They diverge constantly — a Run started to fix a bug often ends up refactoring something else.
**RFCs:** 0019.

### D12 — Cross-graph edges point one way · `SETTLED`

**The Execution Graph is the only graph with outbound cross-graph edges.** Intent and Resource never
reference each other or reference execution. Reverse directions are queries.

Rationale in descending cost: write amplification on a Git-snapshotted structure; two sources of truth;
each side must survive without the other; direction encodes authorship.

**If a traversal is awkward, extend `ProvenanceService` — do not add an edge.**
**RFCs:** 0019, 0024.

### D13 — Declared plans for anything spawning workers · `SETTLED`

Two Task creation modes: emergent (the loop appends Tasks as the model emits calls) and declared (a batch
with `DEPENDS_ON` proposed upfront, approved before execution). Declared is required when a plan spawns
workers, exceeds a cost estimate, or is requested.

The line is reversibility: a plan you watch unfold step by step needs no gate; one that commits five
sessions to hours of work does.
**RFCs:** 0019, 0011.

---

## Concurrency

### D14 — At most one *effectful* Task per Run is `RUNNING` · `SETTLED`

Reformulated from "at most one Task". `Read` effects may run concurrently; everything else serializes.

The invariant was never about concurrency — it was about the audit trail being able to say what happened
in what order, and **reads have no order that matters**. They are `PURE`, so recovery is re-execution with
no idempotency question.

**Sequencing:** write `nextRunnableTask` to return a set and recovery to iterate (near-zero cost now);
enable concurrent reads at v1; never relax for `Mutate`, which would contend on the worktree lock inside
a Run and make the audit trail a genuine partial order.
**RFCs:** 0006, 0009, 0019.

### D15 — Parallelism is across Runs; the worktree is the lock · `SETTLED`

The contended resource is the working tree and Git index, not the project. Treeless workers build commits
against the object database and contend on nothing, so they run genuinely in parallel — each with its own
Run, transcript, Execution Graph subtree, and audit attribution.

On mobile the real limit is device-global model inference, not the tree: five workers is not five times
faster.
**RFCs:** 0007, 0049.

---

## Scope and extension

### D16 — Sync: none now → Git-backed subset at v1.x → pairing at v2; never full sync · `SETTLED`

"Sync" is not one thing. Once decomposed, the expensive part is the part nobody wants:

| State | Sync? |
|---|---|
| Intent graph, session memory | **yes** — small, append-only or structured, mergeable |
| Content metadata | yes — content-addressed |
| Execution graph, audit | no — historical, large, and arguably device-local by nature |
| Capabilities | **must not** — a desktop grant must not authorize a phone |
| Knowledge index | no — derived; rebuilding is cheaper |

**Rejected:** full CRDT/event-sourced sync. Foundational, and a different product.
**Complementary, not alternatives:** Git-backed sync gives offline continuity of intent and knowledge;
pairing gives access to compute.

**Decides now:** intent and memory must stay file-serializable with globally unique IDs — no device-local
sequence numbers, no autoincrement IDs in those two structures.
**RFCs:** 0099, 0046, 0053.

### D17 — MCP ships in the MVP: stdio on desktop, HTTP on every profile · `SETTLED`

MCP stdio lands with the first vertical slice (Phase 2), not in a later ecosystem phase.

The MVP is CLI-first and therefore DESKTOP, where stdio MCP works. More importantly, **it validates the
tool abstraction while that is still cheap to change** — if `ToolDescriptor`, the effect taxonomy, and
capability subjects cannot absorb tools the runtime did not write, that is a finding worth having in month
four rather than month fourteen.

**Consequence to accept:** the MVP is no longer purely first-party. The MCP trust model becomes MVP-critical
rather than future hardening.
**Does not change D18.**

**Amended 2026-08-04 — streamable HTTP is in the MVP too, on every profile.** This decision was originally
titled *"MCP ships in the MVP, desktop only"* and scoped the MVP to stdio. That scope limit is lifted: HTTP
transport ships in Phase 2 alongside stdio, and MCP is therefore available on MOBILE when online.

The reason the limit existed was platform reality — Android cannot spawn arbitrary binaries, so *stdio* MCP
genuinely does not exist there — and it was over-applied to MCP as a whole. Network connectivity is already a
used, decided path in the MVP: M23 routes to remote model providers as user-owned policy, and Git fetch/push
egress on every profile. Withholding HTTP MCP did not keep the network boundary closed; it only kept one tool
family from crossing a boundary that others already cross. RFC-0049 and RFC-0050 had in fact already modelled
HTTP MCP as available everywhere — `AvailabilityTier.NETWORKED` names it by name — so the corpus was written
for this and only the MVP phasing said otherwise.

**The invariant that keeps the thesis testable:** the core mobile use case must not depend on MCP at all. An
unreachable server is a degraded tool family (RFC-0049), never a failure, and G3 is measured with the network
off to prove nothing on the thesis path degrades without it.

**RFCs:** 0031, 0099, 0049, 0050.

### D18 — No plugin host in v1; WASM-only when it lands · `SETTLED`

MCP is a protocol spoken to a separate process the user installed deliberately. A plugin host loads arbitrary
code into the runtime. Different trust problems.

When built: WASM/WASI only — one isolation target, because a menu of them means the weakest defines the system's
security. User-scope installation; project-local plugins never.
**Decides now:** nothing may require in-process native loading.
**RFCs:** 0043, 0060.

### D19 — Remote-client Android reserved, not built · `SETTLED`

A future Android build may be a client of a remote runtime *in addition to* hosting its own. Reserved: no client
paths in the Runtime API, resumable event streams (`sinceSequence`), `FRONTEND` capability subjects,
transport-agnostic `RuntimeClient`.

**Design constraint for whoever builds it:** a phone should be both, and losing the remote must degrade to local
operation rather than failure.
**RFCs:** 0052, 0055, 0049.

### D20 — Three of the original runtime concepts changed status · `SETTLED`

- **Media Engine:** not built. A `ContentNode` kind plus two existing AI capabilities covers every stated need;
  an "engine" would wrap things that are already engines.
- **Resources / Artifacts:** collapsed into `ContentNode` with mutability as a policy field. RFC-0013 and RFC-0014
  are superseded.
- **Intent Graph:** demoted to a leaf. Nothing depends on it; build it last and small.

**RFCs:** 0024, 0012, 0013, 0014.

---

## Implementation posture

### D21 — Embeddings live outside the operational database · `SETTLED`

The index is at `.aidos/index/`, never in `state.db` — embedding writes would contend with the single writer and
inflate the file the user backs up with entirely rebuildable data.

**Start with brute force and measure.** For a few thousand unique blobs, an exhaustive cosine scan over a
memory-mapped array is milliseconds — inside the query target, with no dependency, no build step, and no
corruption mode. An ANN index earns its place only when measurement shows brute force missing the target on a
real repository on a real phone.
**RFCs:** 0015, 0045.

### D22 — Build less prompt machinery, not more · `SETTLED`

Implement precedence, hard reserved sections, and a simple recency window over conversation history. Do **not**
build adaptive compression, semantic chunking, or relevance-scored eviction.

Context windows are growing; much of the scarcity this machinery addresses may not exist in two years. The
rolling window is in scope and already specified. The layer above it — where effort disappears and a larger
context window makes the work retroactively pointless — is not.

**Revisit when:** measurement shows a long session degrading in quality *before* hitting its budget. The
precedence hierarchy is the extension point.
**RFCs:** 0025.

### D23 — `ToolDescriptor` stays structurally MCP-shaped · `SETTLED`

`name`, `description`, `inputSchema` as JSON Schema. Runtime-only fields (`effect`, `requiredPermission`,
`availability`) stay strictly additive and never mix into what a model or an MCP server sees.

If MCP becomes universal, `ToolDescriptor` degrades gracefully into a thin translation layer rather than a
competing model requiring bidirectional mapping. Doubly load-bearing given D17.

**Concretely:** no custom schema dialect, no Aidos-specific type system, no restructured parameter model.
**RFCs:** 0008, 0031.

### D24 — Local inference requires a foreground service; background Runs otherwise prepare only · `SETTLED`

**A Run may make a local model call only in the foreground.** A Run using a local model runs under a foreground
service with a visible ongoing notification. Without an FGS, a background Run does deterministic preparation
only and parks at `ForegroundRequired`; it does not checkpoint KV caches or silently route to remote inference.

**RFCs:** 0044, 0049, 0009, 0006.

### D25 — Diff review on a phone: earlier, and by hunk · `SETTLED`

Per-mutation `Preview` is the primary review surface. The commit screen separates changes the user already
approved from those they did not; the latter use a hunk card stack with stable identity `(path, base blob hash,
hunk index)`. Raw unified diff remains one tap away.

**RFCs:** 0050, 0052, 0032, 0053, 0030.

### D26 — Glance and voice may approve only the benign class · `SETTLED`

An approval is benign when it is Read, or reversible in-project mutation, under a trusted Run with an existing
grant. Voice has three tiers: benign can be one-word approved; readback can approve certain out-of-project or
unsafe actions with a distinct naming phrase; egress, tainted Runs, and new grants require eyes-on approval.

**RFCs:** 0057, 0050, 0027, 0018, 0049.

### D27 — Native dependencies: only where nothing else works and a crash is bounded · `SETTLED`

Accept a native dependency only when no viable pure-JVM alternative exists and a crash is bounded by existing
recovery machinery. llama.cpp is accepted; Git remains JGit.
**RFCs:** 0022, 0049, 0050.

### D28 — GGUF via llama.cpp for LLM; format is per model kind · `SETTLED`

Local LLM inference is GGUF executed by llama.cpp. GGUF availability, quantization quality, and llama.cpp GBNF
make it the LLM backend. Format is per `ModelKind`, not globally.

**Amended 2026-08-17 by D44:** ONNX Runtime is now accepted as the second Aidos inference backend and is to be
implemented as a general tensor/ML backend. The amendment does **not** displace llama.cpp for GGUF LLMs. ONNX
is the preferred backend for model kinds where tensor/ML execution is a better fit.

**RFCs:** 0022, 0021, 0020, 0049.

### D29 — The knowledge engine is a consumed library, not an Aidos subsystem · `SETTLED`

`gitsema-kotlin` owns its knowledge schema; Aidos owns location, lifecycle, and resource envelope. There is no
speculative provider SPI.
**RFCs:** 0015, 0025, 0054.

### D30 — An MCP server's authority is fixed when it is enabled · `SETTLED`

A server's authority is set when the user enables it and does not grow at runtime. There is no `TRUSTED`
promotion; servers start lazily on first call, not on project open.
**RFCs:** 0031, 0018, 0027, 0055, 0015.

### D31 — A tool description is fenced prose, adopted at enable time · `SETTLED`

MCP descriptions are fenced and adopted per operation at enable time by a hash over `(name, description,
inputSchema)`. Changed or unadopted operations are not offered to the model and never interrupt a Run.
**RFCs:** 0031, 0025, 0027, 0016, 0008.

### D32 — Durable memory is deterministic; nothing is summarized by a model · `SETTLED`

No model-written summary exists in the memory or context path. History drops with an omission marker; structured
facts, decisions, task state, and SQL projections carry durable state.
**RFCs:** 0026, 0025, 0027, 0011, 0057.

### D33 — Memory is session-scoped; project scope is a promotion only a user can make · `SETTLED`

`TASK_STATE` is session-only. `FACT` and `DECISION` begin at session scope and may become project scope only by
explicit user promotion, never when untrusted.
**RFCs:** 0026, 0011, 0012, 0016, 0027.

### D34 — Five RFCs claimed MVP scope no milestone built; reconciled · `SETTLED`

The MVP sections of five RFCs were reconciled against actual milestones. The general rule is that an RFC's MVP
section is a roadmap claim and must name the milestone that builds it.
**RFCs:** 0004, 0005, 0012, 0036, 0047.

### D35 — SQLite binding: SQLDelight's drivers, not its schema codegen · `SETTLED`

Use SQLDelight's runtime/platform drivers for KMP SQLite access, but keep `schema/` as the sole canonical DDL and
do not adopt SQLDelight `.sq` schema code generation.
**RFCs:** 0040, 0039, 0015.

### D36 — Inference backends expose capabilities, not one universal inference shape · `SETTLED`

`InferenceBackend` is the common identity/lifecycle/diagnostics boundary. Actual operations are expressed through
capabilities such as text generation, embeddings, tensor inference, vision, audio, tokenization, batching and
streaming. A backend implements only the capabilities it can actually provide; unsupported operations are explicit,
not fake no-ops.

This prevents the engine from becoming an LLM-shaped abstraction with special cases for every other model kind.
**RFCs:** 0022.

### D37 — Capability-specific interfaces sit above the backend core · `SETTLED`

The runtime uses a small backend core plus capability interfaces, rather than a single god interface or a giant
`infer()` request containing every possible modality. Conceptually: `InferenceBackend`, `TextGenerationBackend`,
`EmbeddingBackend`, `TensorBackend`, and future capability interfaces.

This preserves type safety and makes it possible for ONNX to expose generic tensor inference without pretending
that every ONNX model is a text generator.
**RFCs:** 0022.

### D38 — Backend selection is capability- and policy-driven · `SETTLED`

Model format is a strong candidate signal, not the entire selection policy. Multiple installed backends may be
candidates; Aidos selects a backend that satisfies the model's required capabilities and the device/user policy.
GGUF normally selects llama.cpp; ONNX normally selects ONNX Runtime, but the architecture does not make those
mappings immutable.

The selection layer may consider format, model kind, requested capability, available execution providers,
device constraints and user preference.
**RFCs:** 0022.

### D39 — Aidos chooses execution constraints; backends own accelerator mechanics · `SETTLED`

Aidos may express requirements or preferences such as CPU-only, GPU-preferred, accelerator-required, memory
limits, or supported device classes. The backend owns the concrete mapping to llama.cpp backends or ONNX Runtime
Execution Providers. Aidos does not duplicate provider-specific hardware logic.

This keeps hardware policy above the runtime implementation while allowing ONNX Runtime and llama.cpp to evolve
independently.
**RFCs:** 0022.

### D40 — Streaming is a first-class inference capability · `SETTLED`

Streaming is not required of every backend, but it is a first-class capability when a model can produce useful
incremental output. `infer()` returns a complete `ModelResponse`; `stream()` returns a typed `ModelStream` of output
chunks and completion. Streaming is therefore not synonymous with LLM tokens: it may represent text deltas,
tool-call deltas, tensor/frame chunks, audio chunks, or future output kinds.

A backend advertises streaming support. Consumers must not assume it exists merely because inference exists.
**RFCs:** 0022.

### D41 — Multimodal output types grow from a generic tensor primitive · `SETTLED`

The engine does not attempt to freeze every possible modality up front. Typed outputs cover common semantics such
as text and tool calls, while `TensorOutput` is the generic escape hatch for model results that Aidos does not yet
have a domain-specific type for. Higher-level APIs may interpret tensors as embeddings, detections, classifications,
and other domain results.

This lets ONNX Runtime remain a general ML backend without requiring the kernel to predict every future model
family.
**RFCs:** 0022.

### D42 — Backend interfaces are shared where possible; implementations remain platform-specific · `SETTLED`

The stable Aidos backend/capability contracts live in the shared KMP layer where their types permit it. Backend
implementations and native/platform bindings remain in the appropriate JVM, Android, or other platform source sets.
The common layer must not leak a platform-specific native handle into the public contract.
**RFCs:** 0022.

### D43 — `ModelResponse` is generalized around typed outputs · `SETTLED`

`ModelResponse` is no longer conceptually "the text an LLM returned". It contains typed model outputs plus common
completion metadata such as stop reason and usage. Outputs can include text, tool calls, tensors and future typed
modalities.

Existing LLM consumers retain convenient text/tool-call accessors, so the kernel change does not force every
consumer to inspect a heterogeneous collection manually. Embeddings and other non-text inference can therefore
cross the kernel boundary without inventing a parallel top-level response abstraction for every model family.

The response is still a completed result. Streaming is represented by D40's `ModelStream`, whose chunks use the
same typed output vocabulary.
**RFCs:** 0022.

### D44 — ONNX Runtime is the second inference backend · `SETTLED`

Aidos adds ONNX Runtime as the first secondary inference backend after llama.cpp. It is treated as a general tensor
and ML runtime, not merely as an ONNX LLM implementation. It is therefore the preferred fit for embeddings, vision,
audio and other tensor-oriented model kinds where ONNX is appropriate.

The implementation target is broad: model/session lifecycle, input/output tensor introspection, typed tensors,
dynamic shapes, multiple inputs/outputs, batching, execution-provider discovery/selection, metadata and diagnostics,
with Android/JVM support where the platform backend permits it. llama.cpp remains the GGUF LLM backend.

**Why now:** ONNX adds a genuinely different inference capability rather than duplicating another LLM runtime, and
Hugging Face model discovery is already format/backend-neutral.
**RFCs:** 0022, 0103.

---

## Open

None.

---

## Revision history

| Date | Change |
|---|---|
| 2026-08-02 | Initial record: D1–D23 settled, D24 open. |
| 2026-08-02 | D24 settled: foreground service primary, preparation-only fallback, KV-cache checkpointing and remote background inference rejected. |
| 2026-08-03 | D25 settled: diff review moves earlier and goes hunk-by-hunk. D26 settled and later amended with three voice tiers. |
| 2026-08-04 | Legacy RFC audit and D29–D34 reconciliation; MCP HTTP MVP amendment; memory and MCP authority decisions settled. |
| 2026-08-05 | D35 settled: SQLite binding is SQLDelight's drivers, not its schema codegen. |
| 2026-08-17 | D36–D44 settled: capability-oriented inference backends, policy-driven backend selection, accelerator ownership, first-class streaming, generalized typed model outputs, KMP backend boundaries, and ONNX Runtime as the second backend. |
