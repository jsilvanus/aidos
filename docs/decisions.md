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

A Run whose context has admitted untrusted content operates under a reduced capability set for
its remainder. In-project reversible work stays frictionless; egress, secrets, out-of-project
mutation, and `UNSAFE` effects require per-call approval naming the tainting source.

Prompt injection is dangerous because the next tool call carries the session's authority, not
because the model read the text. Delimiters ask the model to enforce a boundary; models are not
reliable enforcement points.

**Tuning note:** if prompts prove too frequent in practice, loosen the defaults — do not remove
the mechanism. Frequent prompts train dismissal, which is the failure mode.
**RFCs:** 0027, 0025, 0018.

### D8 — Budget divides on delegation · `SETTLED`

A driver holding 10,000 cost units delegating to three workers **divides** that allowance. It
does not multiply it. Without the rule, fan-out is an unbounded spend multiplier and
orchestration becomes the most expensive way to use the product.

Follows from RFC-0018's equal-or-more-restrictive delegation rule, but is stated because the
natural implementation gives each worker a fresh budget.
**RFCs:** 0011, 0028, 0018.

### D9 — Run budget defaults: 24 steps, 8 model calls · `SETTLED`

A product-feel decision, not an engineering one. Conservative at Run scope; absent above it.
Nagging users about monthly limits they did not ask for is worse than a per-Run ceiling that
catches runaways.
**RFCs:** 0028.

---

## Graphs

### D10 — Intent status is derived, never authored · `SETTLED`

Computed from `IMPLEMENTS` edges, acceptance criteria, children, and dependencies. User
overrides are stored as timestamped claims shown *alongside* the derived value, never replacing
it.

A stored field becomes a lie the moment a Run is reverted, partially fails, or is later broken —
and it then feeds prompt construction, so the model inherits the false belief. Adds `STALE`,
which a stored field cannot represent and which is a normal event in a Git-first product.

**Not deferrable:** retrofitting derivation after a stored field exists means migrating data
that was never trustworthy.
**RFCs:** 0012.

### D11 — `TARGETED` (fact) and `IMPLEMENTS` (assertion) are separate edges · `SETTLED`

`TARGETED` is written by the runtime at Run creation. `IMPLEMENTS` is asserted at completion and
carries `confirmed`. They diverge constantly — a Run started to fix a bug often ends up
refactoring something else.
**RFCs:** 0019.

### D12 — Cross-graph edges point one way · `SETTLED`

**The Execution Graph is the only graph with outbound cross-graph edges.** Intent and Resource
never reference each other or reference execution. Reverse directions are queries.

Rationale in descending cost: write amplification on a Git-snapshotted structure; two sources of
truth; each side must survive without the other; direction encodes authorship.

**If a traversal is awkward, extend `ProvenanceService` — do not add an edge.**
**RFCs:** 0019, 0024.

### D13 — Declared plans for anything spawning workers · `SETTLED`

Two Task creation modes: emergent (the loop appends Tasks as the model emits calls) and declared
(a batch with `DEPENDS_ON` proposed upfront, approved before execution). Declared is required
when a plan spawns workers, exceeds a cost estimate, or is requested.

The line is reversibility: a plan you watch unfold step by step needs no gate; one that commits
five sessions to hours of work does.
**RFCs:** 0019, 0011.

---

## Concurrency

### D14 — At most one *effectful* Task per Run is `RUNNING` · `SETTLED`

Reformulated from "at most one Task". `Read` effects may run concurrently; everything else
serializes.

The invariant was never about concurrency — it was about the audit trail being able to say what
happened in what order, and **reads have no order that matters**. They are `PURE`, so recovery
is re-execution with no idempotency question.

**Sequencing:** write `nextRunnableTask` to return a set and recovery to iterate (near-zero cost
now); enable concurrent reads at v1; never relax for `Mutate`, which would contend on the
worktree lock inside a Run and make the audit trail a genuine partial order.
**RFCs:** 0006, 0009, 0019.

### D15 — Parallelism is across Runs; the worktree is the lock · `SETTLED`

The contended resource is the working tree and Git index, not the project. Treeless workers
build commits against the object database and contend on nothing, so they run genuinely in
parallel — each with its own Run, transcript, Execution Graph subtree, and audit attribution.

On mobile the real limit is device-global model inference, not the tree: five workers is not
five times faster.
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
**Complementary, not alternatives:** Git-backed sync gives offline continuity of intent and
knowledge; pairing gives access to compute.

**Decides now:** intent and memory must stay file-serializable with globally unique IDs — no
device-local sequence numbers, no autoincrement IDs in those two structures.
**RFCs:** 0099, 0046, 0053.

### D17 — MCP ships in the MVP: stdio on desktop, HTTP on every profile · `SETTLED`

MCP stdio lands with the first vertical slice (Phase 2), not in a later ecosystem phase.

The MVP is CLI-first and therefore DESKTOP, where stdio MCP works. More importantly, **it
validates the tool abstraction while that is still cheap to change** — if `ToolDescriptor`, the
effect taxonomy, and capability subjects cannot absorb tools the runtime did not write, that is a
finding worth having in month four rather than month fourteen.

**Consequence to accept:** the MVP is no longer purely first-party. The MCP trust model becomes
MVP-critical rather than future hardening.
**Does not change D18.**

**Amended 2026-08-04 — streamable HTTP is in the MVP too, on every profile.** This decision was
originally titled *"MCP ships in the MVP, desktop only"* and scoped the MVP to stdio. That scope
limit is lifted: HTTP transport ships in Phase 2 alongside stdio, and MCP is therefore available
on MOBILE when online.

The reason the limit existed was platform reality — Android cannot spawn arbitrary binaries, so
*stdio* MCP genuinely does not exist there — and it was over-applied to MCP as a whole. Network
connectivity is already a used, decided path in the MVP: M23 routes to remote model providers as
user-owned policy, and Git fetch/push egress on every profile. Withholding HTTP MCP did not keep
the network boundary closed; it only kept one tool family from crossing a boundary that others
already cross. RFC-0049 and RFC-0050 had in fact already modelled HTTP MCP as available
everywhere — `AvailabilityTier.NETWORKED` names it by name — so the corpus was written for this
and only the MVP phasing said otherwise.

**What it costs, stated rather than discovered:**

- **Every call to an HTTP server is `Egress` by construction.** A stdio server can hold `Read`
  and no `Egress`; an HTTP server reaches the network to do anything at all. Consequently a
  tainted Run cannot call an HTTP MCP server *at all* under D7, where it could still call a stdio
  one for a read. The remembered per-`(server, project)` egress grant (D30) carries the
  ergonomics.
- **Credentials move from the spawn environment to request headers.** There is no child process,
  so `secret_ref` resolves into an `Authorization` header. The promise that a secret never enters
  project configuration or the audit log has to hold on that path too.
- **The endpoint is a new trust surface.** A URL in a user-scope file is somewhere the runtime
  will POST project content. HTTPS is required, certificates are validated, and redirects to a
  different host are refused — otherwise the endpoint is one redirect away from an exfiltration
  target.
- **The scrubbed-environment defence does not apply, and does not need to.** With no child
  process there is nothing to hand a runtime token to, and the server cannot reach the local
  filesystem or the runtime socket at all. The risk moves rather than growing: from *a local
  process holding your privileges* to *your project content on someone else's machine*.

**The invariant that keeps the thesis testable:** the core mobile use case must not depend on MCP
at all. An unreachable server is a degraded tool family (RFC-0049), never a failure, and G3 is
measured with the network off to prove nothing on the thesis path degrades without it.

**RFCs:** 0031, 0099, 0049, 0050.

### D18 — No plugin host in v1; WASM-only when it lands · `SETTLED`

MCP is a protocol spoken to a separate process the user installed deliberately. A plugin host
loads arbitrary code into the runtime. Different trust problems.

When built: WASM/WASI only — one isolation target, because a menu of them means the weakest
defines the system's security. User-scope installation; project-local plugins never.
**Decides now:** nothing may require in-process native loading.
**RFCs:** 0043, 0060.

### D19 — Remote-client Android reserved, not built · `SETTLED`

A future Android build may be a client of a remote runtime *in addition to* hosting its own.
Reserved: no client paths in the Runtime API, resumable event streams (`sinceSequence`),
`FRONTEND` capability subjects, transport-agnostic `RuntimeClient`.

**Design constraint for whoever builds it:** a phone should be both, and losing the remote must
degrade to local operation rather than failure.
**RFCs:** 0052, 0055, 0049.

### D20 — Three of the original runtime concepts changed status · `SETTLED`

- **Media Engine:** not built. A `ContentNode` kind plus two existing AI capabilities covers
  every stated need; an "engine" would wrap things that are already engines.
- **Resources / Artifacts:** collapsed into `ContentNode` with mutability as a policy field.
  RFC-0013 and RFC-0014 are superseded.
- **Intent Graph:** demoted to a leaf. Nothing depends on it; build it last and small.

**RFCs:** 0024, 0012, 0013, 0014.

---

## Implementation posture

### D21 — Embeddings live outside the operational database · `SETTLED`

The index is at `.aidos/index/`, never in `state.db` — embedding writes would contend with the
single writer and inflate the file the user backs up with entirely rebuildable data.

**Start with brute force and measure.** For a few thousand unique blobs, an exhaustive cosine
scan over a memory-mapped array is milliseconds — inside the query target, with no dependency,
no build step, and no corruption mode. An ANN index earns its place only when measurement shows
brute force missing the target on a real repository on a real phone.
**RFCs:** 0015, 0045.

### D22 — Build less prompt machinery, not more · `SETTLED`

Implement precedence, hard reserved sections, and a simple recency window over conversation
history. Do **not** build adaptive compression, semantic chunking, or relevance-scored eviction.

Context windows are growing; much of the scarcity this machinery addresses may not exist in two
years. The rolling window is in scope and already specified. The layer above it — where effort
disappears and a larger context window makes the work retroactively pointless — is not.

**Revisit when:** measurement shows a long session degrading in quality *before* hitting its
budget. The precedence hierarchy is the extension point.
**RFCs:** 0025.

### D23 — `ToolDescriptor` stays structurally MCP-shaped · `SETTLED`

`name`, `description`, `inputSchema` as JSON Schema. Runtime-only fields (`effect`,
`requiredPermission`, `availability`) stay strictly additive and never mix into what a model or
an MCP server sees.

If MCP becomes universal, `ToolDescriptor` degrades gracefully into a thin translation layer
rather than a competing model requiring bidirectional mapping. Doubly load-bearing given D17.

**Concretely:** no custom schema dialect, no Aidos-specific type system, no restructured
parameter model.
**RFCs:** 0008, 0031.

---

### D24 — Local inference requires a foreground service; background Runs otherwise prepare only · `SETTLED`

**A Run may make a local model call only in the foreground.** Concretely:

- **Primary path (a).** A Run using a local model runs under a foreground service with a visible
  ongoing notification. The FGS window is long enough for a full inference step, so no
  sub-step escape hatch is needed in the deadline budget (RFC-0009).
  **The user may pocket the phone.** A foreground service holding a wake lock keeps running
  while the app is backgrounded and the screen is locked, across many chained steps — start an
  analysis, put the phone away, come back to a result. That is the point of (a), not an
  incidental benefit, and the ongoing notification is what buys it. What does *not* work is
  `WorkManager`: ~10 minutes, no timing guarantee, deferred by Doze. Deterministic preparation
  belongs there; anything reaching a model call does not (RFC-0050).
- **Fallback (d).** Without an FGS — unavailable, or the user declined it — a background Run does
  deterministic work only: index, fetch, reconcile Git, assemble context. On reaching a model
  call it **parks** with suspension reason `ForegroundRequired` (RFC-0006) and notifies *"ready
  to continue"*. Inference happens when the user opens the app.
- **(b) is rejected**, not deferred. Mid-generation checkpointing — persisting a KV cache and
  resuming in the next window — sounds like the sophisticated answer and is probably the worst
  one: the cache is hundreds of megabytes, and serializing it per window plausibly costs more in
  I/O and battery than the inference it preserves.
- **(c) is rejected.** Routing background Runs to remote models contradicts offline-first exactly
  where it was promised.

**Why.** The naive framing — "a model call exceeds any available window" — is wrong. A foreground
service holding a wake lock runs for minutes; ~500 tokens from a 3B model is around 50 seconds
and fits comfortably. It is `WorkManager`'s ceiling and Doze that do not accommodate it. So the
question was never whether inference fits, but what we are willing to require to make it fit —
and a visible notification is the *correct* user experience for a phone doing sustained work,
not a cost to engineer around. An agent consuming your battery should say so.

**Consequence, stated plainly:** RFC-0044's recurring sessions complete autonomously when the
user has granted a foreground service, and otherwise prepare and wait. Both states are
explainable in one sentence, which is the test that matters. The fallback also makes the
subsequent foreground session faster, since context is already assembled.

**Would change the answer:** on-device models getting materially faster (fallback becomes rare);
Android tightening FGS further (pushes toward (d) as primary); measured battery cost at G3
proving unacceptable (pushes background toward (c) while keeping local in foreground).

**Long-form analysis:** `docs/decisions/D24-android-inference-windows.md`.
**RFCs:** 0044, 0049, 0009, 0006.

---

### D25 — Diff review on a phone: earlier, and by hunk · `SETTLED`

**Review moves earlier and gets smaller.** Per-mutation `Preview` — already required for every
`EffectKind.Mutate` for security reasons — is the primary review surface. The commit screen
separates changes the user already approved from those they did not, and directs attention at
the second set. Line-level review of that residue is a **hunk card stack**: one hunk per screen,
keep/skip/revert, visible progress. Raw unified diff stays one tap away. Model-generated diff
summaries are deferred past G4.

**Why it is a Phase 2 decision, not a Phase 4 one.** It determines what the Runtime API and the
Git tool must return. `diff(): String` in Phase 2 means the Android app inherits a diff parser
it should never contain — on the device with the least CPU, furthest from JGit, reimplemented by
every frontend. **The API returns structured hunks with stable identity**, decided at M9,
implemented at M13, consumed at M31.

**Hunk identity** is `(path, base blob hash, hunk index)`. If the base moves mid-review, the
review restarts visibly. Silent renumbering during partial staging is how a user stages the
wrong lines.

**Cost, stated plainly:** applying a *subset* of hunks to the index is real work. JGit gives an
`EditList` but no hunk-level staging, so the resulting blob is constructed by hand, with tests
for overlapping edits, CRLF, missing trailing newline, binary, renames, and mode changes. It is
the only expensive item here. If it must be cut, cut staging and keep the card stack for
reading.

**Rejected:** scrollable unified diff as the *primary* surface — a diff line is ~120 columns and
a phone shows ~40 at a readable size; it fails every clause of "comfortably, one-handed, on a
bus". **Deferred:** model-summarized diffs — a model call at the moment the user is waiting to
commit, and a D6 hazard, since a model summarizing the diff it just produced is reporting on its
own work at the point where a wrong summary is least likely to be checked.

**Consequence:** RFC-0050's "Git Browser (Optional)" stops being optional. It is the product.

**Long-form analysis:** `docs/decisions/D25-phone-diff-review.md`.
**RFCs:** 0050, 0052, 0032, 0053, 0030.

---

### D26 — Glance and voice may approve only the benign class · `SETTLED`

Development on the move has three attention modes — focused, glance, eyes-free — and an approval
must be answerable in the mode the user is actually in. But approving without reading is exactly
what the capability model exists to prevent, so the two must be separated by rule rather than by
hoping.

**An approval is *benign*, and therefore glanceable and voice-answerable, when all of:**

```
effect      is Read, or Mutate(IN_PROJECT, reversible = true)
recovery    is not UNSAFE
run.taint   is TRUSTED
capability  is already granted — this is an exercise, not a new grant
```

**Amended 2026-08-03 — `reversible` is a separate axis from `RecoveryClass`.** The original
predicate used "not `UNSAFE`" as the proxy for reversible. That is wrong, and branch switching
found it: discarding uncommitted changes is in-project, untainted, and perfectly re-runnable
after a crash — so it satisfied every clause and became approvable by saying *"approve"* while
cycling. `RecoveryClass` asks whether an effect can be *re-executed*; `reversible` asks whether
the user can get their work back. `EffectKind.Mutate` now carries the flag (RFC-0053).

Everything else — egress, out-of-project mutation, `UNSAFE`, a tainted Run, a new grant —
requires the full card with its preview, and says so. It does not become approvable by being
looked at harder. Voice approvals are additionally **off by default** (`speech.voice_approvals`):
answering a capability request by speech extends how authority can be exercised, and that should
be something the user turned on.

**Amended 2026-08-03 — voice gets three tiers, not one.** The original rule above gave voice the
benign class and nothing else. The target interaction is richer than that: a spoken notification
interrupts, the user asks *what*, *where*, *why*, *what if I refuse*, hears structured answers,
and then decides — while cycling. That user has verified the request **more** thoroughly than
someone tapping approve on a card they glanced at, and the rule should follow verification rather
than modality.

| Tier | What | Answered by |
|---|---|---|
| **1 · Benign** | the class above | a single *"approve"* |
| **2 · Readback** | out-of-project mutation, `UNSAFE` effects | runtime states path, scope, blast radius; user replies with a **distinct phrase naming the action**, never a bare *"yes"* |
| **3 · Never by voice** | egress of project content, any tainted Run, any **new** capability grant | parks — *"that one needs your eyes"* |

Tier 3 holds because each of those changes the *authority envelope* rather than exercising it:
egress is irreversible and unobservable afterwards, a tainted Run already has an adversary in its
context, and a new grant is what every other check depends on. A structured readback cannot
verify them, because what needs checking is not a fact the runtime owns.

Tier 2 requires a naming phrase because *"yes"* is the single most likely word to be misrecognised
out of music, ambient noise, or a sentence addressed to someone else. `tier2` is a separate
opt-in from `tier1`.

**And a structural rule that makes the Q&A safe:** the questions are answered from a **fixed
vocabulary, by template, from runtime-owned fields** — no inference and no attacker-controlled
text. A user may ask to hear the actual content, and **that turn ends the approval exchange**;
re-approving starts over. Hearing attacker-authored text and granting authority must not be
possible in one breath, because that is exactly the sequence an injection needs.

**Why not simply forbid all glance approvals.** Because then every approval is a full card, and a
user interrupted twelve times per Run learns to tap through — which is D7's tuning note, and the
failure mode is worse than the thing it was protecting against.

**The classifier is not new policy.** It is the signal set RFC-0027 already uses to decide what
needs approval, applied one level further to decide what needs *reading*.

**Corollary — no attacker prose in an approval prompt.** Spoken approvals are composed only from
runtime-owned structured fields: tool name, effect kind, path, scope, taint. Model output and
file content are never read aloud inside an approval, because a hostile repository could
otherwise craft text that sounds, to someone who cannot see the screen, like a request they would
grant. Prompt injection aimed at the human. The boundary is structural for the same reason
RFC-0025 keeps untrusted content out of the system turn.

**Also settled here:** the Run Summary is a **projection of the Execution Graph, not a model
call** — D6 applies with extra force, because a glance summary is consumed *instead of* the
detail rather than alongside it. A generated summary is also uncheckable, costs an inference at
the worst moment, and parks when there is no foreground service (D24) — which is exactly the
eyes-free case.

**RFCs:** 0057, 0050, 0027, 0018, 0049.

---

### D27 — Native dependencies: only where nothing else works and a crash is bounded · `SETTLED`

**The rule.** Accept a native dependency only when **both** hold:

1. **No viable pure-JVM alternative exists** — not "none is as fast", but none is good enough.
2. **A crash is bounded by machinery that already exists**, so a segfault degrades rather than
   destroys.

This is the counterpart to D4, and the pair is the policy. D4 rejected libgit2-via-JNI for Git
on grounds — per-ABI builds, contributor build complexity, a native crash surface in a
safety-critical component — that apply word for word to an inference engine. The tests above are
why the answers differ:

| | Pure-JVM alternative? | Blast radius of a crash |
|---|---|---|
| **Git** | **Yes** — JGit, good enough | Corrupted history. Unbounded, unrecoverable |
| **Inference** | **No** — nothing competitive exists | A failed Run. Bounded, already recoverable |

The second column is the load-bearing half. RFC-0009 assumes the process dies without warning,
because Android does that routinely — so a segfault in the inference engine is a failure mode the
durable execution model **already handles**: the Run resumes from its last checkpoint. A native
crash mid-`git commit` is exactly what D4 refused to risk, and no checkpoint saves it.

**Applied:**

- **llama.cpp — accepted.** Inference passes both tests. See RFC-0022.
- **JGit — unchanged.** Git fails test 1.
- **tree-sitter — leans reject.** Used for structural extraction in the knowledge index. An
  alternative arguably exists (heuristic extraction) and the blast radius is an incomplete graph
  rather than a lost Run, so the presumption is against it. Revisit with a specific proposal.

**Costs accepted, stated rather than discovered:** per-ABI builds (`arm64-v8a` at minimum,
`x86_64` for the emulator); F-Droid reproducible builds are harder with native code and RFC-0050
commits to F-Droid, so this is validated early rather than at M34; and the GGUF loader becomes a
concrete attack surface — a C++ parser reading a file from the internet, mitigated by digest
verification and not fully.

**Forecloses:** a pure-JVM build of Aidos with local inference. Anyone wanting one gets remote
providers only, which is a supported configuration (RFC-0022, "when nothing fits").
**RFCs:** 0022, 0049, 0050.

### D28 — GGUF via llama.cpp for LLM; format is per model kind · `SETTLED`

**Local LLM inference is GGUF, executed by llama.cpp.** The format decision made this
inevitable — GGUF *is* llama.cpp's format — and the previous RFCs chose GGUF without naming the
engine, which left the most consequential dependency in the product unstated.

Three reasons, the third being the one that is not merely convenience:

- **Model availability.** Nearly every open model appears as GGUF within days. ONNX conversion of
  a modern LLM is fiddly — dynamic shapes, KV cache plumbing — and would be ours to do.
- **Quantization quality.** The k-quants are tuned for quality-per-byte and are why a 3B is
  useful on a phone at all.
- **GBNF grammars.** RFC-0021 specifies that an adapter for a model *without* native tool calling
  uses constrained decoding — a grammar admitting only well-formed calls. That is llama.cpp's
  GBNF. It is not an optimisation; it is the mechanism by which a local model that cannot do
  function calling still participates in the agent loop. ONNX Runtime has no equivalent.

**Format is per `ModelKind`, not global.** ONNX Runtime is genuinely better for embeddings, STT
and TTS — `onnxruntime-android` is an official Maven artifact with Kotlin bindings and no
hand-written JNI, and NNAPI gives far better NPU access. It is nevertheless **not in the MVP**: a
second native runtime doubles the APK, the ABI matrix, the crash surface, and the F-Droid
problem, for a benefit concentrated in kinds that are not on the critical path. Embeddings run
through llama.cpp; STT, if voice survives the Phase 4 cut, uses whisper.cpp in the same family.
TTS is the genuine gap, and TTS is M33 — the first thing cut.

**Revisit after G3, with measurement**, specifically for NPU-accelerated embeddings if indexing
proves to be the bottleneck. That is a numbers question and should be settled with numbers.

**RFCs:** 0022, 0021, 0020, 0049.

### D29 — The knowledge engine is a consumed library, not an Aidos subsystem · `SETTLED`

RFC-0015 was written as if Aidos would build a knowledge engine: a provider SPI, an entry
schema, four addressing classes, an invalidation mechanism. It is instead consuming one.
`gitsema-kotlin` — the port of `jsilvanus/gitsema` — already implements the blob-addressed model
RFC-0015 adopted, because RFC-0015 adopted it *from* gitsema.

**The library owns its schema.** Aidos owns the location (`.aidos/index/`, outside `state.db` per
D21), the lifecycle (when indexing starts, when it yields, when it stops), and the resource
envelope (background dispatcher, cancellable batches, no network). It does not own the DDL. The
alternative — Aidos defining an entry shape the library writes through — makes every upstream
schema change an Aidos migration and puts `schema/check.py` in the position of policing tables
Aidos does not understand. The addressing-class table stays in RFC-0015 as a description of what
Aidos relies on and would notice breaking, not as Aidos's design.

**There is no provider SPI.** One provider exists. One interface already exists —
`KnowledgeContextProvider` (RFC-0025). A `KnowledgeProvider` seam with `query`/`is_current`/
`update`/`subscribe` is maintained for hypothetical implementors and is the same speculative
extensibility D18 and D22 refused elsewhere. If a second knowledge source appears, the seam is
cheap to add against a real second case.

Three consequences settled with it:

- **The index covers committed content only** in the MVP. Hash-on-save is elegant and re-embeds a
  file on every keystroke, on a phone, for content superseded within minutes. Uncommitted work
  reaches the model through the filesystem tool, which is how it reaches it anyway when the model
  is the one editing. Debounced hash-on-idle is the upgrade, not the starting point.
- **A query reports coverage** — blobs indexed over blobs known. Two counters. Without it, first
  open of a large repository answers "there is no retry logic here" when the truth is "I have not
  read most of it yet," and G3's measurement cannot distinguish an answer at minute two from an
  answer at minute forty.
- **No secret redaction.** RFC-0015 promised secrets in code would be redacted from the index.
  Nothing funds a scanner, it would have false negatives, and stating it invites reliance. The
  real property is that the index is app-private, never egresses, and holds nothing the repository
  does not already hold. A secret committed to the repository is a secret in the repository.

**Forecloses:** shipping a knowledge feature the library does not have. Aidos's contribution is
the adapter and the resource discipline; capability gaps are upstream work in `gitsema-kotlin`.
**RFCs:** 0015, 0025, 0054.

### D30 — An MCP server's authority is fixed when it is enabled · `SETTLED`

RFC-0031 established that an MCP server is a capability subject holding an attenuated grant, and
RFC-0027 that its results are `UNTRUSTED`. What neither answered is the interaction: RFC-0055 has
`user_interactive` capability requests, so may an untrusted process make the user's device raise
a prompt?

**No.** A server's authority is set when the user enables it for a project and does not grow at
runtime. A call needing more fails with a message naming what is not permitted; the user changes
the registration if they want it. A prompt raised by untrusted code is a phishing surface — the
server picks the moment and part of the wording — and the property lost is worth more than the
ergonomics gained: a server's maximum authority stays knowable by reading one file. Attributed,
`EYES_ONLY` runtime requests (D26 already routes `isNewGrant` there) are future work with their
own threat analysis, not an MVP convenience.

**The grant is by effect class, at enable time.** The user answers one answerable question —
*this server reads the network and writes files outside your project; yes or no?* — and per-call
approval then follows the ordinary tier rules. Per-operation grants sound safer and ask the user
to rule on forty operation names they have never seen, which is a consent dialog that trains
people to click through. Approving every call is unusable; MCP servers are chatty.

**There is no `TRUSTED` promotion for MCP servers.** RFC-0031 let the user mark a server trusted
to relax per-call `Egress` approval. It bought fewer prompts and cost the clearest sentence in
the security model, by putting the word *trusted* on a process whose output is untrusted
permanently. The same ergonomics come from a remembered grant: an approved egress to a named
server, scoped to a project, visible in the capability list and revocable there. That mechanism
already exists and does not require a second concept that reads as if it means more.

**Nothing is spawned by opening a project.** Servers start lazily on first call and shut down
when idle. "Connect on open, show the green dot" is the obvious implementation and it executes
third-party code before the user has asked for anything.

Two adjacent questions close the same way. **MCP resources do not feed the knowledge engine** —
index identity is the blob hash, and an MCP resource has no blob, no stable identity, and no
invalidation story; it would need a fifth addressing class whose only member is *things that
might have changed, we cannot tell*. They are fetched as tool results: untrusted, tainting,
in-context, unindexed. And **Aidos does not expose itself as an MCP server** in v1; that is an
inbound authority surface needing its own subject kind and threat model.

**Forecloses:** an MCP server that adapts its own permissions as it discovers what it needs. Any
server requiring that is configured out of band, deliberately, by the user.
**RFCs:** 0031, 0018, 0027, 0055, 0015.

---

## Open

None.

---

## Revision history

| Date | Change |
|---|---|
| 2026-08-02 | Initial record: D1–D23 settled, D24 open. |
| 2026-08-02 | D24 settled: (a) foreground service primary, (d) preparation-only fallback, (b) and (c) rejected. |
| 2026-08-03 | D25 recommended: diff review moves earlier and goes hunk-by-hunk; Runtime API returns structured hunks. |
| 2026-08-03 | D25 settled as recommended. D26 settled: glance and voice may approve only the benign class. |
| 2026-08-03 | D26 amended: three voice tiers, verification gates authority. `reversible` split from `RecoveryClass`. |
| 2026-08-03 | D27 settled: native dependencies only where nothing else works and a crash is bounded. D28 settled: GGUF via llama.cpp, format per model kind. |
| 2026-08-04 | Legacy RFC audit complete: 46 Accepted, 15 Draft, RFC-0099 excepted. |
| 2026-08-04 | D29 settled: the knowledge engine is a consumed library; no provider SPI; committed content only; queries report coverage. D30 settled: an MCP server's authority is fixed at enable time; no `TRUSTED` promotion; nothing spawns on project open. |
| 2026-08-04 | D17 amended: streamable HTTP MCP ships in the MVP on every profile, not stdio-on-desktop only. Was *"MCP ships in the MVP, desktop only"*. |
