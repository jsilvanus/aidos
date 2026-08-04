# Aidos Architecture Review — Third Pass (August 2026)

Status: Review (not an RFC; no normative authority)
Scope: RFC-0000 through RFC-0102, `ARCHITECTURE.md`, `docs/`
Assumption: implementation has not started.

---

## 0. Method, and why this pass is different

Two reviews already exist. RFC-0100 is a broad first-principles critique. RFC-0102 is a
second pass that evaluates RFC-0100 and reports which findings were resolved.

Repeating either would be worthless. This pass does something the previous two did not:

1. **It reads the RFCs written *in response* to RFC-0100 adversarially** (RFC-0006, 0007,
   0017, 0018, 0019, 0024, 0025, 0052), rather than accepting that their existence closes
   the findings that motivated them.
2. **It cross-checks documents against each other line by line**, hunting for contradictions
   between RFCs rather than weaknesses inside one RFC.
3. **It treats the code-shaped parts of the RFCs (Kotlin data classes, SQL DDL, state
   machines) as specifications and type-checks them against each other.** This is where the
   architecture is least honest with itself, because prose can be vague and a schema cannot.

Everything below is grounded in a citation. Where I disagree with RFC-0100 or RFC-0102, I
say so.

### The meta-finding, which outranks everything in Section A

**There is no mechanism that turns review findings into RFC changes. Reviews are filed as
new RFCs; the reviewed RFCs are never edited.** RFC-0102's status table (lines 403–413)
reports eight concerns as "addressed." At least four are not:

| RFC-0100 finding | RFC-0102 status | Actual state of the RFC text |
|---|---|---|
| A2 — replay overpromised; downgrade to audit replay | "Accepted… Event Bus RFC should clarify" | `0004-event-bus.md:301,331,361` still promise deterministic replay and a "Time Machine" |
| A11 — Tool Broker needs typed effects, dry-run, idempotency | "Accepted. Typed effects are the right solution" | `0030-tool-broker.md:92` still `Map<String, Any>`; dry-run is still an *Open Question* at line 365 |
| A3 — capabilities need path canonicalization | "Accepted. RFC-0018 now a complete RFC" | `0018-capability-model.md:161` takes `resourceRef: String`; canonicalization, symlinks, `..`, and Unicode normalization appear nowhere |
| A6/A7 — user and workspace scopes missing | "RFC-0046 reserves identity fields; a full workspace scope RFC should follow" | It never followed. Six RFCs now *use* a `workspace` scope that no RFC defines (`0010`, `0033`, `0035`, `0036`, `0046`, `0060`) |

This is the most expensive problem in the repository, because it means the review process
produces the *feeling* of resolution without the substance. Before acting on anything else
here, adopt the rule: **a review finding is closed only by a diff to the RFC it concerns,
referenced by commit.** Reviews are input; RFCs are the artifact.

A secondary symptom: 52 of 53 RFCs are `Status: Draft`. `CLAUDE.md` says RFCs are frozen
after acceptance and RFC-0102 says to mark the kernel contracts Accepted. Nothing is
Accepted. There is currently no signal to a contributor about which documents they may rely
on.

---

## A. Architectural flaws

Ranked by severity. Each finding states the defect, the evidence, why it matters, the cost
to fix now, and the cost of deferral.

---

### CRITICAL

#### A1. The agent loop — the single most important interface in the system — is not specified anywhere

**Defect.** Aidos is an AI runtime whose core operation is: assemble context → call a model →
receive tool calls → execute them → feed results back → repeat until done. That loop is
described nowhere. Specifically, the following are absent from all 53 documents:

- How tools are described *to a model*. `grep -rn "json schema"` over `docs/rfcs/` returns
  nothing. RFC-0030's `Capability.parameters: List<Parameter>` (line 113) has no type system.
  Every current model API and MCP itself require JSON Schema tool definitions.
- How a model's emitted tool call is validated, mapped to a Tool Broker invocation, and bound
  to a capability.
- How tool results are serialized back into the next turn.
- Whether parallel tool calls are supported (they change the Run/Task/Attempt model
  fundamentally).
- Who decides the loop terminates, and what the stop conditions are.
- How tool definitions consume the token budget (RFC-0025 budgets nine context sources; tool
  schemas are not one of them, yet they are often the largest fixed cost in an agent prompt).

RFC-0020 mentions "Function calls → [Tool Broker] → Execution" at line 326 as a pipeline
diagram and stops there. RFC-0019 has `TaskKind.MODEL_CALL` and `TaskKind.TOOL_CALL` but no
edge semantics binding a model call to the tool calls it produced.

**Why it matters.** This is not a missing detail; it is the missing centre. Everything else —
Intent Graph, Knowledge Engine, Execution Graph, capability checks — is scaffolding around a
loop that has never been designed. It is also the interface most tightly coupled to provider
APIs, so it is exactly where vendor lock-in will occur if it is not abstracted deliberately.
The claim "runtime outlives vendors" is currently unbacked: there is no seam between
provider tool-call formats and the runtime's own representation.

**Cost now:** low-to-medium — one RFC plus a Kotlin interface, ~2 weeks of design.
**Cost deferred:** very high. This interface will be invented ad hoc inside the first
session implementation, will hard-code one provider's shape, and will then be load-bearing
for the Execution Graph, audit, replay, and the plugin SDK.

---

#### A2. The security model is not capability-based. It is an ACL keyed by session identity, and it therefore fails against the threat it exists to stop.

**Defect.** RFC-0003 and RFC-0018 claim object-capability security. The actual mechanism is:

```kotlin
// 0018-capability-model.md:157-162
suspend fun check(subjectId: UUID, permission: Permission, resourceRef: String): CapabilityCheckResult
```

The caller presents an *identity* and a *string*; the runtime searches for any grant held by
that identity that permits the operation. That is ambient authority scoped to a subject —
the textbook definition of an access-control list, and precisely what capability discipline
exists to eliminate. A true capability is an unforgeable reference whose *possession and
designation are the same act*: you cannot name a file you were not given.

Three consequences follow directly:

1. **The confused-deputy claim at `0018:270-281` is false.** The RFC argues that because the
   check lives inside the Tool Broker and capabilities carry `subjectId`, confused deputies
   are structurally prevented. They are not. The deputy problem is about *designation*, not
   *identity*. A session holding `fs:write:/project/**` will have that authority applied to
   whatever path arrives in the parameters — including a path chosen by an attacker.

2. **Prompt injection is therefore a privilege-escalation vector, and the architecture has no
   defence at the authority layer.** RFC-0025 treats injection purely as a prompt-formatting
   problem (delimiters, an anti-injection sentence). But the reason injection is dangerous is
   that the injected instruction inherits the session's ambient authority. Nothing in the
   architecture reduces authority when untrusted content enters the context. `ContentNode`
   (`0024:46-66`) carries `sensitivityLevel` and `egressEligibility` — *outbound* labels only.
   There is no *inbound* trust label and no taint propagation.

3. **Whole classes of subject are missing.** `SubjectKind` is `{ SESSION, WORKER }`
   (`0018:64`). Plugins, MCP servers, and frontends are not capability subjects at all.
   RFC-0100 B6 explicitly recommended `session | worker | plugin | frontend | user`. So an
   MCP server's tool executes under the *session's* authority with no attenuation — the same
   deputy problem, one level out.

**Additional concrete defects in RFC-0018:**

- `allowsDelegation: true` is required by the attenuation rules (line 230) but is not a field
  on `Capability` or `CapabilityConstraints`.
- `exercisedCount` is declared "tracked in SQLite" (line 126) but there is no
  `exercised_count` column in the DDL (lines 285–300).
- `CapabilityConstraints` has byte and duration limits but **no cost or token limit**, despite
  RFC-0100 B6 recommending `max cost`. Combined with A6 below, nothing in the architecture
  can bound spend.
- Scope containment for delegation is defined as "the delegated scope must be contained
  within the parent scope" over glob `pathPattern` strings. Glob subset-checking is subtle and
  is not specified; a wrong implementation silently grants escalation.
- "The SQLite `capabilities` table must not be writable by any code path except the Capability
  Manager" (line 310) is unenforceable inside a single process sharing one connection. State
  it as a convention, or enforce it with a separate database and a process boundary.

**Recommendation.** Either implement real capabilities or stop calling them that. Real
version, and it is cheap now:

- Tools receive **handles**, not strings. `fs:read` takes an opened directory handle scoped at
  grant time; path resolution happens *inside* the handle, so `../` cannot escape by
  construction. This removes the entire canonicalization attack surface rather than trying to
  filter it.
- `exercise(capabilityId, operation, args)` — the session names *which* authority it is using.
  Denial becomes explicit, audit becomes precise, and the runtime stops guessing.
- Add `trustLevel` to context items and a rule: **a Run whose prompt included UNTRUSTED
  content executes under an attenuated capability set for the remainder of the Run.** This is
  the only structural defence against injection, and it is nearly free if added before the
  Tool Broker exists.

**Cost now:** medium (~3–4 weeks of design + the Tool Broker signature).
**Cost deferred:** critical. Every tool written against a string-path API must be rewritten.

---

#### A3. Durable continuations are assumed but are not implementable as described

**Defect.** RFC-0006 specifies that a Run yields at a serialization point, persists a
`Continuation` descriptor, and later "resumes from the continuation descriptor"
(`0006:107-126`). RFC-0007 specifies that all session work is ordinary Kotlin `suspend`
functions on a coroutine dispatcher (`0007:45-47`).

These are incompatible. **Kotlin coroutine continuations are not serializable.** You cannot
persist a suspended coroutine's stack to SQLite and restore it in a new process. The
`Continuation` record in RFC-0006 (lines 222–234) stores a serialization-point *identifier*
and an operation type — which is a label, not a program counter. Resuming from it requires
that session logic be expressed as an **explicit, persistable state machine**, not as ordinary
straight-line Kotlin.

This is the classic durable-execution problem. There are three known solutions, and the
architecture must pick one *before* the session executor is written, because it determines
how all session logic is authored:

- **(a) Interpreter over a persisted plan.** Session logic is data (a step list) that the
  runtime interprets; each step boundary is a checkpoint. Most restrictive, simplest,
  auditable, and it composes naturally with the Execution Graph — `Task` already *is* a step.
- **(b) Deterministic replay of a workflow function** (the Temporal model). Re-execute from
  the top on resume, serving previously-recorded results from a history log instead of
  re-calling the model. Powerful, but requires strict determinism discipline in session code
  and is a large runtime to build.
- **(c) Re-derivation.** On restart, discard the in-flight step and let the model re-plan from
  persisted state. Cheapest; costs one model call and gives up exactness.

RFC-0006 currently reads as if (b) were free and describes none of its machinery.

**Recommendation.** Adopt (a). The Execution Graph already models Run → Task → Attempt; make
the runtime a driver over that table rather than a language-level coroutine that pretends to
be durable. Then a "continuation" is just "the next PENDING Task row," recovery is a query,
and RFC-0006's serialization points become a natural consequence rather than a hopeful
assertion. Note that (a) also solves A1 cleanly: the agent loop *is* the interpreter.

**Cost now:** low if decided now — it is a design choice, not code.
**Cost deferred:** very high; it invalidates the shape of every session-facing API.

---

#### A4. Git-first plus SQLite-authoritative is incoherent the moment the user touches Git

**Defect.** RFC-0017 asserts: "There are no cases where two stores have conflicting authority
over the same field" (line 72). The ownership table on the same page contradicts this. Git is
authoritative for resource content and `aidos.toml`; SQLite is authoritative for intent graph
structure while Git holds "periodic snapshots" (lines 57–60).

The architecture then never addresses the defining property of a Git-first system: **the user
and other tools mutate Git out-of-band.** `git checkout`, `git pull`, `git rebase`, branch
switching, and `git stash` all change the working tree and history underneath a running
runtime. After any of them:

- Resource content hashes in SQLite are stale, and by the stated rule ("the authoritative
  store wins") SQLite will be silently *updated to match* — meaning a user who ran `git revert`
  on an intent snapshot sees no change, because SQLite is authoritative for intent structure.
- `ContentNode.storageLocation = FilesystemPath` (`0024:135`) may now point at a file that
  does not exist on this branch. `IMMUTABLE` artifacts referencing branch-specific paths are
  silently dangling.
- The Knowledge index is derived and rebuilds (fine), but the Execution Graph's
  `input_snapshot`/`output_snapshot` records now describe a tree state that no longer exists.

No RFC defines reconciliation on branch switch, external commit, merge, or `pull`. There is no
file-watcher-driven invalidation protocol for Git-level changes (RFC-0004 has `GitCommit`
events but nothing consumes them for state reconciliation).

**Compounding: the project is split across two locations that sync differently.** RFC-0040
puts the database at `~/.aidos/projects/myapp/storage.db` (line 57) — outside the repository.
Therefore:

- "A project is portable — it is just a Git repository" (`0010:190`) is false. Cloning the repo
  on a second device yields the files and none of the sessions, artifacts, capabilities,
  intent, or audit trail.
- There are two competing portability mechanisms with different fidelity: Git (content only)
  and the `.aidos-project` ZIP (`0041:52`). Neither is a sync story, and RFC-0099 Phase 4
  promises "cross-device synchronization / real-time sync" on top of this split.
- The path is keyed by project *name*, so two projects named `myapp` collide.

**Recommendation.**

1. Delete the sentence at `0017:72`; it is false and it suppresses the design work.
2. Introduce an explicit **reconciliation protocol**: on project open and on every observed Git
   HEAD change, the runtime diffs Git state against recorded hashes and resolves per object
   class, with a user-visible report for anything it cannot resolve.
3. Accept RFC-0100 A6 properly: Git is the versioning backend for *content*, and the runtime
   owns operational state. Do not commit runtime state to Git, and stop claiming the repo is
   the project.
4. Decide, explicitly and in an RFC, whether the DB lives inside the repo directory (in
   `.aidos/`, git-ignored) or in `~/.aidos`. In-repo-but-ignored is better: it keeps the
   project a single movable directory and makes name collisions impossible.

**Cost now:** medium. **Cost deferred:** very high — this is data loss, and users will hit it
in week one because developers switch branches constantly.

---

#### A5. Android-first is incompatible with coding-first, and both are stated as requirements

**Defect.** Assembling the constraints that the RFCs themselves state:

- `0018:264` — "**No shell execution on Android.** The `shell:exec` permission is not granted
  on Android."
- `0031:59` — the MVP MCP transport is stdio, started via `"command": "python -m mcp.server.github"`.
  Android cannot spawn arbitrary executables or interpreters from an app; there is no Python,
  and `exec` of non-app-bundled binaries has been blocked since API 29 (W^X enforcement).
- No RFC names a Git implementation. `grep -rn "jgit\|libgit2"` over the RFCs returns nothing.
  On Android there is no `git` binary to shell out to (see above), so Git-first requires
  either JGit — which **does not support worktrees**, the feature RFC-0032 calls first-class —
  or libgit2 via JNI with per-ABI native builds.

So the first platform, per RFC-0099 Phase 2, ships with: no shell, no stdio MCP, and an
unresolved Git backend that probably cannot do worktrees. Coding without the ability to run a
build, a test, or a linter is not coding. Meanwhile the flagship differentiator — workers in
isolated worktrees — is unavailable on the platform being built first.

Additionally, "sessions sleep and wake on events" collides with Android's execution model in
ways RFC-0007 waves through at lines 189–193. A foreground service requires a persistent
user-visible notification and, since Android 14, a declared FGS type with enforcement;
`WorkManager` periodic work has a 15-minute floor; exact alarms need a special-access
permission since Android 12. "Long-lived sessions woken by timers" is close to the hardest
thing to do on Android and is the entire premise of the personal-productivity use case.

RFC-0102 (line 232) already flagged "Android first is hardest." I go further: it is not merely
harder, it is **contradictory with the stated first use case**, and no amount of engineering
resolves it because the blockers are OS policy.

**Recommendation.** Reverse the platform order. Desktop/CLI first; Android when the Runtime
API is stable, positioned as a *companion* surface (review, approve, voice capture, monitor,
notify) rather than a full execution host. This costs nothing today and saves a year.
Keep "Android is a first-class target" as an architectural constraint — it is a genuinely
useful forcing function for the runtime's resource discipline — while dropping "Android is the
first shipped frontend."

**Cost now:** zero — it is a roadmap edit.
**Cost deferred:** catastrophic; a year of work against the most constrained platform, for a
product that cannot perform its first use case there.

---

### HIGH

#### A6. Nothing in the architecture bounds cost, and several mechanisms actively amplify it

Autonomous sessions, woken by events, invoking paid remote models, with:

- no cost ceiling in `CapabilityConstraints` (`0018:120-127`);
- `session_usage` held in memory with no persistence or enforcement (`0020:397`);
- an "Event Limit: a session can generate at most N new events per run" (`0005:185`) that does
  **not** prevent cross-session cycles — session A wakes B, B wakes A — because the limit is
  per run;
- boot-time replay of the pending event queue (`0005:294`), which can produce a wake storm and
  a burst of model calls on startup;
- RFC-0045 as a 51-line stub with a `ResourceBudget` shape and no enforcement semantics.

A wake cycle between two sessions with a remote model is an unbounded bill and an unbounded
token burn, and it is reachable by accident, not just by attack. For a single-user
open-source product, one user's four-figure API bill is an existential reputational event.

**Recommendation.** Treat budget as a capability constraint, not an accounting feature.
`maxCostUnits` and `maxModelCalls` on every capability, decremented transactionally in the
same SQLite write that records the Attempt. Add wake-amplification control: a causality-depth
counter on events (`Event.causality` already exists — carry a depth), and a per-project
rate limiter on session wakes with a visible circuit breaker.

**Cost now:** low. **Cost deferred:** high, and it is the kind of debt discovered via incident.

---

#### A7. The Event Bus is an unauthenticated broadcast channel that bypasses the capability model

RFC-0004 claims "A session cannot read another session's events" (line 398). The subscription
mechanism contradicts it: subscriptions are topic-pattern strings, `*` matches everything
(line 236), and there is no capability governing subscription. There is no `event:subscribe`
permission in RFC-0018's permission list.

Event payloads carry real content — the RFC's own `ToolCompleted` example (lines 158–174)
embeds full `stdout` of `cargo test`. So a narrowly-scoped worker session can subscribe to
`tool:**` and observe every command output, model response, and file operation in the project,
including from sessions with wider capabilities. The Event Bus is a side channel around the
entire security model, and it is also the coordination mechanism the architecture depends on.

**Recommendation.** Subscriptions are capability-checked, and events carry a visibility class.
Facts about a session are visible to that session, its parent, and the user by default;
everything else requires an explicit grant. Split the bus per RFC-0100 A12 (Facts / Commands /
Signals) — and note that the current design lets a *command* be delivered as a *fact*, which
is how event buses accumulate policy and become impossible to secure later.

---

#### A8. "Events are processed in timestamp order, not arrival order" is not implementable, and it contradicts RFC-0007

`0004:299` mandates wall-clock ordering. This requires knowing that no earlier-timestamped
event will arrive later, which requires unbounded buffering. It also makes ordering depend on
inputs the runtime does not control: filesystem mtimes, MCP server clocks, and NTP steps.

RFC-0007 says the opposite: per-project FIFO in publication order (`0007:112-120`). One of
these must go, and it should be RFC-0004's.

Related, in the same RFC: the `events` DDL (lines 270–283) is not valid SQLite (`index(...)`
inside `CREATE TABLE`) and omits the `logical_timestamp` column that the data model at line
373 requires. Both are symptoms of schemas that have never been executed. See A12.

---

#### A9. `Run` is defined twice, incompatibly, and the Execution Graph records provenance four different ways

**Two `Run` types.** `0006:203-218` and `0019:70-95` both define `Run` with different fields.
RFC-0006 says a Run spawns Attempts (`attempt_count`, line 55); RFC-0019 says Attempts belong
to Tasks, and Runs contain Tasks. The containment hierarchy of the central execution object
differs between the two RFCs that define it.

**Four sources of truth for "which artifact did this produce":**
1. `runs.artifact_ids` JSON array (`0019:286`)
2. `tasks.output_ref` (`0019:302`)
3. `execution_edges` rows with `edge_kind = 'PRODUCED'` (`0019:330`)
4. `provenance_edges` in the Resource Graph (`0024:263`)

They can disagree, and nothing reconciles them. `CONTAINS` edges likewise duplicate the
`tasks.run_id` foreign key. The generic `execution_edges` table has no foreign keys at all,
because it spans heterogeneous node kinds — so SQLite cannot enforce integrity on the
provenance graph, which is the feature the graph exists to provide.

**Data classes and DDL disagree.** `Task.attemptIds` and `Task.currentAttemptId` and
`Run.taskIds` exist in Kotlin (`0019:116-117, 82`) and in no table. `Run.lastSerializationPoint`
exists in RFC-0006 and in no table.

**State machines do not compose.** `TaskState.AWAITING_APPROVAL` exists; there is no defined
mapping to a `RunState`. A Run whose Task is blocked on a user approval for three days is
described by RFC-0019 as `RUNNING` and by RFC-0006 as `YIELDED`, with nothing specifying which.

**Recommendation.** Delete `Run` from RFC-0006 and reference RFC-0019. Model containment with
foreign keys only; delete `CONTAINS` from `EdgeKind`. Delete `runs.artifact_ids` and
`tasks.output_ref`; derive from edges. Write one composed state table: for each `RunState`, the
set of legal child `TaskState`s.

---

#### A10. Capability revocation is contradicted by the concurrency model, and is ineffective where it matters most

- `0007:185` rule 4: "Read-only configuration (project metadata, **capability definitions**) is
  safe to read from any dispatcher after it has been loaded, because it is treated as immutable
  once loaded."
- `0018:236`: "Revocation is immediate."

Capabilities are cached in at least two places (the Capability Manager's cache and the
session's in-memory set — `0018:240,244`) and RFC-0007 declares them immutable-once-loaded.
Revocation cannot be immediate under that rule.

Separately, revocation is defined to not affect in-flight operations (`0018:246`). For a
30-minute shell command or a long model call — exactly the operations a user reaches for the
revoke button during — revocation does nothing. RFC-0100 recommended a `revocation_epoch`;
it was not adopted.

**Recommendation.** A single monotonically increasing revocation epoch per project; every
check compares the capability's epoch against the current one; caches are epoch-stamped and
invalidated by comparison rather than by notification. And define kill semantics for in-flight
work — a revoke that cannot stop the thing it is revoking should say so in the UI rather than
be documented as "acceptable behavior."

---

#### A11. Three distinct concepts are all named `Capability`

- RFC-0018: a security grant (subject, scope, constraints, expiry).
- RFC-0030 line 105: a tool operation descriptor (`"git:write"`, with parameters).
- RFC-0020: a model class (LLM, embedding, STT).

They will collide as Kotlin types in the same module, and — more damagingly — they already
collide in the reasoning. `0030:64` "list capabilities this tool provides" and `0018:165`
"load all active capabilities for a session" are unrelated operations sharing a word in a
security-critical subsystem.

**Recommendation.** `Capability` = security grant, exclusively. Tool operations become
`Operation` (or `ToolOperation`). Model classes become `Modality` or `ModelKind`. This is a
one-hour edit today and a permanent source of security-reasoning errors if left.

---

#### A12. The schemas have never been executed, and several are invalid or unreachable

Beyond A8's invalid `CREATE TABLE`: `0024:228` queries `SELECT kind FROM artifact_kinds` —
a table that does not exist in any RFC. `created_by TEXT` is documented as "session or user ID"
(`0024:57`) with no discriminator and no foreign key; the same untyped-polymorphic-reference
pattern recurs in `execution_edges` and `Artifact.creator`. Indexes are missing for stated
queries (`runs.state` is queried at `0019:266-270` and not indexed). The `version INTEGER`
column mandated on every table (`0017:214`) has no optimistic-concurrency protocol that uses
it — it is a dead field that implementers will interpret differently.

Two contradictory blob thresholds: artifacts inline below **1MB** (`0017:54`) versus content
nodes inline below **512KB** (`0024:147`), for what RFC-0024 says is the same object.

**Recommendation.** Do what RFC-0102 said at line 467 and has not been done: write the actual
schema, in one file, and run it. `sqlite3 < schema.sql` in CI is the cheapest architectural
test available, and it would have caught every item in this finding.

---

#### A13. Nothing ever reclaims storage, on a mobile-first product

Events retained "for the lifetime of the project" (`0004:286`); Attempts append-only with
`input_snapshot`/`output_snapshot` (`0019:352`); a `PromptPackage` JSON per Attempt
(`0025:271`); artifacts that can never be deleted while referenced by provenance edges, and
provenance edges that can never be deleted at all (`0024:126,176`); tool stdout stored in
events *and* attempts *and* artifacts.

There is no retention policy, no compaction, no GC, and no quota. RFC-0099 lists "Storage:
< 1GB per active project" as a success metric with no mechanism to achieve it. On a phone this
is the failure mode users notice first.

**Recommendation.** Define retention as a first-class policy per object class, with compaction
of old events into summaries and content-addressed dedup for blobs (RFC-0024 lists dedup as
Future Work; it should be day one — `content_hash` is already there and already indexed).

---

#### A14. Label-based egress control with no labelling mechanism is not a control

RFC-0024's `sensitivityLevel` / `egressEligibility` are the enforcement point for the entire
privacy story (`0024:280-289`, `0025:198-202`). Nothing assigns them. There is no default
policy, no inference (listed as Future Work at `0024:319`), and no UI. In MVP everything will
be created with one default value, and:

- if the default is `INTERNAL`, the primary MVP flow — remote model + project code — is blocked
  by policy and someone will disable the check;
- if the default is `PUBLIC`/`ELIGIBLE`, the control is inert and the privacy claim is theatre.

**Recommendation.** Default by *origin*, computed automatically: files under a Git remote
marked private → `INTERNAL`; anything matching secret patterns → `SECRET`; tool output from a
network tool → `UNTRUSTED` (see A2's inbound trust dimension). Automatic, conservative, and
overridable beats a field nobody fills in.

---

#### A15. Cross-process concurrency on a project is undefined

RFC-0007 gives careful in-process discipline: per-project session mutex, single SQLite writer.
It says nothing about **two runtimes**. And the architecture makes two runtimes normal:

- RFC-0052 loads the runtime as a library inside the desktop app process (line 44) *and*
  defines a CLI that talks to a "running runtime daemon" (line 50).
- Projects are Git repositories, so the user will run `git` in a terminal on the same directory.
- RFC-0002 claims "Multiple frontends can simultaneously interact with the same runtime"
  (line 287), which the in-process design cannot deliver across applications.

Two processes with WAL-mode SQLite will not corrupt the database, but they will corrupt
*everything above it*: two Capability Managers with independent caches, two schedulers waking
the same sessions, two writers to the Git index.

**Recommendation.** A project lock file with owner PID and liveness, acquired on open. One
runtime per project, enforced. Decide whether the runtime is a library or a daemon — the
answer is daemon, or the headless claim is decoration.

---

#### A16. The Runtime API is unauthenticated, and the runtime spawns processes that can reach it

`0052:371`: "all connections from the same device are trusted." The CLI transport is a Unix
domain socket (line 50). `CapabilityCommands.approve(requestId)` is on that interface (line
175).

Therefore any local process can approve capability requests — including the stdio MCP servers
and plugins that the runtime itself spawns as child processes. A malicious MCP server can
request a capability and then approve its own request. The human-in-the-loop that the entire
security model rests on is bypassable by the component most likely to be hostile.

**Recommendation.** Socket permissions plus a per-connection token minted at runtime start and
readable only by the user; child processes spawned by the Tool Broker get a scrubbed
environment and no token. Approval commands additionally require a frontend connection flagged
as user-interactive.

---

#### A17. Worker sessions are in the MVP; their authority is not

`0011:482` lists worker sessions in MVP scope. `0018:330` defers delegation "until Worker
sessions are implemented." Circular deferral, and the gap is filled by ambient authority:
workers with no defined capability derivation will inherit whatever is convenient.

Separately, the flagship Driver/Worker flow has no execution representation. `0011:420` has the
driver "Yield (worker is running)", but RFC-0006's `SuspendedOperation` is
`{ AiCall, ToolCall, UserPrompt }` (lines 230–234) — **waiting on a child session is not a
representable suspension.** The multi-session model that RFC-0102 calls the core
differentiator (line 317) cannot be expressed in the execution contract.

RFC-0011's own Open Questions (lines 572–581) list orphaned workers, capability inheritance,
and loop detection as unresolved. These are not edge cases; they are the semantics.

---

#### A18. Three incompatible session state machines

| RFC | States |
|---|---|
| `0005:335` | created, sleeping, ready, running, yielding, archived |
| `0011:375` | sleeping, woken, running, yielding, archived |
| `0017:93` | CREATED, SLEEPING, RUNNING, ARCHIVED |

`SessionState` is exposed on the public Runtime API (`0052:153`), so this leaks into the
frontend contract. RFC-0005 has not been updated for RFC-0006/0007 at all — it still declares
"Aidos is single-threaded within a project" (line 57) and specifies a blocking `while True`
scheduler loop (lines 223–263) that runs sessions inline and cannot observe new events until
the work queue drains.

---

### MEDIUM

#### A19. Prompt construction has a circular dependency with model selection

`budget = model.contextWindow - model.maxResponseTokens - SAFETY_MARGIN` (`0025:73`) requires a
chosen model. `PromptPackage.modelCapabilityRequest` (line 110) is "what capability the AI
Engine should route to" — the model is chosen *after*. Fix by making it explicitly two-phase:
resolve model → assemble to that model's budget. Also note that history summarization performs
"a local lightweight model call" *during* prompt assembly (line 99), which is a nested model
call at a non-serialization point and breaks RFC-0006's state machine.

#### A20. RFC-0025's MVP ships the appearance of injection defence without the substance

MVP includes "structural sandboxing" but explicitly excludes "separator injection prevention"
(`0025:320`) — i.e. delimiters without escaping, which is the one part that makes delimiters
work. The same RFC's Security section says the constructor "must be tested against known
injection patterns before any model integration" (line 302). Either move escaping into MVP or
remove the claim.

#### A21. The AI Engine is modelled per-project; models are device-global

`AIEngine { project_id: UUID }` with a per-project `model_cache` and `memory_budget`
(`0020:380-398`). A 7B model is multi-gigabyte and one instance saturates a phone's RAM. Two
projects would download it twice and try to load it twice. Model catalog, downloads, weights,
and the loaded-model slot are **user-scope** resources with a global queue. This is the
clearest possible demonstration that the missing user/workspace scope (RFC-0100 A7) is a real
architectural hole, not a naming preference. It also breaks RFC-0010's "Compute Isolation"
claim (line 176).

#### A22. Worktrees do not provide the isolation the architecture assumes

RFC-0032 makes worktrees the isolation mechanism for parallel workers (lines 55–71). Git
worktrees share one `.git` directory: refs, `packed-refs`, config, and hooks are common state,
and concurrent commits contend on them. Meanwhile RFC-0007 says Git operations that touch
tracked files "should acquire the per-project session lock" (line 159) — which serializes the
parallelism worktrees exist to enable. And JGit, the likely Android/JVM backend, does not
implement worktrees. Filesystem isolation is real; Git-metadata isolation and parallelism are
not.

#### A23. Three overlapping content models coexist with no deprecation

RFC-0024 supersedes RFC-0013 and RFC-0014, but both remain as full RFCs, `ARCHITECTURE.md`
(lines 103–105) and RFC-0002 (Artifact Manager / Resource Manager, lines 172–186) still present
the old model, and RFC-0017's ownership table is written in the old vocabulary with a
conflicting blob threshold (A12). A reader has no way to know which is current.

#### A24. `ARCHITECTURE.md`'s layer diagram describes a dependency structure the system does not and cannot have

Lines 34–81 present a strict downward stack: Session Manager → AI Engine → Tool Broker →
Knowledge Engine → Intent Graph → Storage. In reality the Knowledge Engine depends on the AI
Engine (embeddings) and the Tool Broker sits beside, not below, both. As drawn, the diagram
asserts a cycle. This is the first document a contributor reads.

#### A25. RFC-0031's own example commits a live secret to a Git-tracked config

`"env": { "STRIPE_API_KEY": "sk_..." }` (`0031:67`) inside project configuration, which
RFC-0017 line 60 declares Git-authoritative. Combined with RFC-0010's `api_keys: SecretStore`
in project config (line 207), the documented path puts credentials in version control, in
direct contradiction of RFC-0035. And because that same config declares MCP `command` strings,
**importing or cloning a project is arbitrary code execution** — the risk RFC-0100 A4 raised
for plugins, reproduced in a component that is in V1 scope.

### LOWER — likely three-year debt

- Untyped `payload: Map<String, Any>` on permanently-retained events, with versioning deferred
  to Future Work (`0004:441`). Event schema evolution is mandatory from day one.
- `Session.capabilities: CapabilitySet` embedded in the session record (`0011:223`) duplicates
  the `capabilities` table — two sources of truth for authority.
- Session memory as an unbounded `conversation_history: List<Message>` blob rewritten on every
  wake: write amplification and no queryability. RFC-0102 named this its highest-probability
  debt (85%) and RFC-0011 still lists it as an Open Question.
- "Replayable (session can be re-run with a different prompt)" (`0011:199`) conflates replay
  with re-execution.
- RFC-0043 (plugin sandbox) is a 51-line stub that specifies "signed packages" with no trust
  root, while RFC-0060 (593 lines) describes a much broader model. RFC-0102 said to stop
  writing stub RFCs; RFC-0043, 0044, 0045, 0046, 0035, 0036, 0037, 0038, 0039, 0047, 0048 are
  all still stubs of 46–58 lines.
- RFC-0050 says the Android app "is independent of the runtime; it communicates via local IPC";
  RFC-0052 says the runtime runs in-process in the app. Contradiction.
- RFC-0099 is stale: dates run Q2 2025 – Q2 2026 (all now past), Phase 1 is marked complete,
  and the RFC index omits everything added since (0006, 0007, 0017–0019, 0024–0026, 0035–0048,
  0052). "Runtime uptime: 99.9%" is a SaaS metric on a local single-user application.

---

## B. Better ideas

Ordered by how much complexity they remove.

### B1. Make the Execution Graph the runtime, not a record of it

Currently the Execution Graph is described as "the persistent record… operational state, not
user-facing" (`0019:43`) — a log written *beside* execution. Invert it: **the Run/Task/Attempt
tables are the program.** The executor's loop is:

```
next PENDING Task for this Run → execute → write Attempt → repeat
```

One change collapses four problems: durable continuations become a query (A3), recovery
becomes "resume the loop" rather than a bespoke procedure (RFC-0006 §Resumption), the audit
trail is a byproduct rather than a duplicate write (A9), and the agent loop (A1) has an obvious
home — a model call is a Task that *appends* Tasks.

This is the single highest-leverage simplification available, and it is only available before
implementation.

### B2. Collapse the engine count

Reserve "engine" for components that own execution. Current names inflate registries and
brokers into peers of the runtime, which is why `ARCHITECTURE.md` needed a fictional layering
(A24).

| Now | Proposed | What it actually is |
|---|---|---|
| AI Engine | **Model Runtime** (user-scope) + **Inference Router** (per-request) | a catalog, adapters, and a routing policy |
| Knowledge Engine | **Knowledge Index** + **Query Broker** | an index and a ranked-query API |
| Instruction Engine | **Instruction Resolver** | a pure function over files |
| Media Engine | *(does not exist; do not create)* | see B7 |
| Tool Broker | **Effect Broker** | keeps the name, gains typed effects |

RFC-0100 B2 proposed this; it was never applied. It is a rename with no code cost today.

### B3. Three scopes, defined now, not later

`user` / `workspace` / `project`. Six RFCs already use `workspace` as if it existed (A2, A21,
and `0035`, `0036`, `0046`, `0060`). Making it real now is a table and an ID field. Making it
real after everything is `project_id`-keyed is a migration of every table in the system.

Assignment: model weights, model catalog, secrets vault, installed plugins, device identity,
global settings, notification preferences → **user**. Cross-project resources and settings
defaults → **workspace**. Sessions, runs, intent, content nodes, capabilities → **project**.

### B4. Capabilities as handles, plus inbound taint

Detailed in A2. Two mechanisms:

```kotlin
// designation and authority are the same object
interface DirHandle { suspend fun read(rel: RelPath): ByteArray }   // cannot escape by construction
fun exercise(capabilityId: CapabilityId, op: Operation): Result     // caller names the authority
```

plus `TrustLevel { TRUSTED, UNTRUSTED }` on every context item, and the rule that a Run whose
context contains `UNTRUSTED` content runs with attenuated authority for the rest of the Run.
This is the only structural answer to prompt injection, and it is nearly free before the Tool
Broker exists.

### B5. Typed effects — finally adopt RFC-0100 A11

```kotlin
sealed interface Effect {
    interface Read : Effect                                   // no approval, cacheable
    interface Mutate : Effect { fun preview(): Diff }          // approval + dry-run + undo
    interface Egress : Effect { val destination: Host }        // privacy classification applies
    interface Notify : Effect                                  // user-visible, rate-limited
}
```

with an `idempotencyKey` on retryable calls. This is what makes RFC-0019's `RetryPolicy`
safe — retrying a non-idempotent `Mutate` is currently specified (max 3 attempts, `0019:360`)
and is a data-corruption bug waiting to be written.

### B6. Audit-log-first, and delete the deterministic-replay claim

Adopt RFC-0100 B5 and A2 explicitly, in the RFC text: append-only audit log for provenance,
domain tables for current state, no event-sourced reconstruction. Then rewrite `0004:299-361`
and delete "Event Bus as a Time Machine." Deterministic replay of LLM-driven work is not
achievable without capturing every model output, tool result, clock read, and file state — at
which point you have a recording, not a replay. Promise the recording. It is the more
valuable feature anyway, and it is honest.

### B7. Do not build a Media Engine

It appears in the project brief's runtime-concept list and in no RFC — the one case where the
absence is correct. Media needs are: MIME detection, metadata extraction, thumbnails, and
transcription/OCR pipelines. Those are a `ContentNode` kind plus two AI capabilities that
already exist in RFC-0020's taxonomy. An "engine" would be a wrapper around things that are
already engines. RFC-0100 A16 said this; agreed and reinforced.

### B8. Model routing as declared policy, not engine heuristics

`0020:166-168` has the engine choose "prefer local, if unavailable fall back to remote."
Silent local→remote fallback is an egress decision made by a heuristic, in a product whose
first principle is offline-first. Make routing a policy object the user owns, with explicit
degradation states (RFC-0100 B9: available-locally / available-remotely-with-approval /
unavailable-offline / disabled-by-policy), surfaced in the UI, and recorded per Attempt.

### B9. A dependency rule that prevents the cycles

State one law: **services depend on kernel contracts, never on each other.** Kernel = identity
and scopes, state store, audit log, capability manager, effect broker, execution graph.
Everything else (projects, content graph, AI, knowledge, instructions, Git, import/export) is a
service above it. This is RFC-0100 B1; it is right, and it also resolves A24 by giving
`ARCHITECTURE.md` a diagram that is true.

### B10. Naming corrections

- `Capability` → security grant only; `Operation` for tool operations; `ModelKind` for
  modalities (A11).
- `Resource`/`Artifact` → retire as types; keep as *policies* on `ContentNode` (RFC-0024 already
  does this; finish the job by deprecating RFC-0013/0014).
- `Session` is the most overloaded word in the product — it means a persistent actor, but every
  user arrives expecting "a chat." Consider `Agent` or `Workspace Thread`. This is a product
  risk as much as an architectural one.

---

## C. Roadmap improvements

### C1. What is wrong with the current plan

RFC-0099 is stale (see Lower-severity findings) and both prior reviews already proposed
corrections that were never merged into it. Beyond staleness:

- Phase 1 (0–3 months) bundles "write 25+ RFCs" with "runtime operational" and "local model
  support" and "offline-first proven." That is 12+ months of work.
- Android leads (A5) — the platform that cannot run the first use case.
- Phase 3 targets "Plugin API stable (won't break in next year)" while RFC-0102 correctly puts
  plugins in Phase 5.
- The critical path is drawn as strictly serial (`0099:361-372`), which is precisely what
  prevents parallel contribution.

### C2. What must be true before any implementation starts

Not more RFCs. Three artifacts, each of which is executable or checkable:

1. **`schema.sql`** — the complete SQLite schema, in one file, that runs. This resolves A9,
   A12, A18, and the 1MB/512KB conflict by forcing a single answer. RFC-0102 recommended it at
   line 467; it is the highest-value pre-code deliverable and it takes days, not weeks.
2. **`RuntimeClient` + `ToolBroker` + `CapabilityManager` interfaces in KMP common, compiling,
   with no implementations.** Contracts before implementations, as the brief asks.
3. **Four decisions recorded in RFC form**: durable-execution model (A3), Git backend
   (A5 — JGit vs libgit2/JNI vs git CLI, and therefore whether worktrees are real), scope model
   (B3), platform order (A5).

### C3. Corrected phasing

```
Phase 0 — Decisions and contracts (3–4 weeks, one person)
  schema.sql that runs · kernel interfaces that compile · four decision RFCs
  Exit: `sqlite3 < schema.sql` green in CI; interfaces published; Runtime API marked Accepted

Phase 1 — Kernel (2 months)
  Identity/scopes · state store · audit log · capability manager · effect broker
  execution-graph driver (B1) · crash recovery · test harness with fakes
  Exit: a Run of hard-coded Tasks executes, checkpoints, survives kill -9, and is auditable
        — with no AI and no tools

Phase 2 — First vertical slice (2 months)
  Agent loop (A1) · one remote provider adapter · prompt construction · fs + git tools
  CLI frontend over the Runtime API
  Exit: create project → task → model → tool → artifact → commit → audit, end to end

Phase 3 — Offline proof (2 months)
  Model Runtime at user scope (A21/B3) · one local LLM · local embeddings
  knowledge index (files + git history only) · routing policy with degradation states

Phase 4 — Desktop (3 months)          Phase 5 — Android companion (3 months)
  Compose desktop over the frozen API   review/approve/notify/voice — not a full execution host

Phase 6 — Extension boundary
  WASM plugin host · MCP hardening with a real trust model · knowledge provider SDK
```

Note what moves: the **execution kernel is proven with no AI in it**. If the kernel cannot
survive `kill -9` mid-Run deterministically, no amount of AI quality matters, and every later
bug will be misattributed to the model.

### C4. Dependency graph

```
                        schema.sql + kernel interfaces
                                    │
        ┌───────────────┬───────────┼───────────────┬────────────────┐
        ▼               ▼           ▼               ▼                ▼
  Capability      Execution      Audit log     Content graph    Runtime API
   manager      graph driver         │          (RFC-0024)      (RFC-0052)
        │               │            │               │                │
        ├─ Effect broker┤            └───────────────┤                ├─ CLI
        │      │        │                            │                ├─ Desktop
        │      ├─ fs tool                            ├─ Knowledge      └─ Android
        │      ├─ git tool                           │   index
        │      └─ shell tool (desktop only)          └─ Import/export
        │
        └─ Model runtime (user scope)
                 │
                 ├─ Provider adapters ──┐
                 └─ Inference router     ├─ Agent loop ──┐
                                        │                │
              Instruction resolver ─────┼─ Prompt        ├─ Sessions
              Knowledge query broker ───┘  construction  │
                                                         └─ Intent graph (last)
```

Two things read off this graph. First, **Intent Graph is a leaf**, not a foundation — it can
and should be built last, contradicting its placement as a core concept in `ARCHITECTURE.md`.
Second, **the agent loop is the convergence point** of five subsystems, which is why leaving it
unspecified (A1) blocks everything.

### C5. Parallel workstreams, and the contract each needs frozen first

| Stream | Frozen contract it needs | Can start after |
|---|---|---|
| Storage / migrations | `schema.sql` | Phase 0 |
| Security | `CapabilityManager` + effect taxonomy | Phase 0 |
| Tools (fs, git, shell) | `ToolBroker` + `Effect` | Phase 0 |
| AI providers | `ModelRequest`/`ModelResponse` + tool-call envelope | Phase 0 (needs A1 first) |
| Knowledge | `KnowledgeContextProvider` (`0025:221`) | Phase 0 — genuinely independent |
| Frontends | `RuntimeClient` + `MockRuntimeClient` | Phase 0 |
| Testing | fakes for provider, tool, clock, filesystem | Phase 0 |

The brief's example — Knowledge Engine → GitSema → embeddings → search API — is correct and is
the cleanest parallel stream in the system, *provided* `KnowledgeContextProvider` is frozen
first. It already exists in RFC-0025 and is a good interface. Freeze it.

### C6. Stabilise first / do not stabilise yet

**Freeze now:** object IDs and scopes; capability grant/check/revoke; effect schema;
audit envelope; run/task/attempt schema; content-node schema; model request/response
including the tool-call envelope; `RuntimeClient`; migration contract.

**Do not freeze:** plugin SDK surface; knowledge provider internals; intent graph shape; UI
view modes; MCP trust policy; anything in RFC-0099 Phase 4.

### C7. Process

Mark the frozen contracts `Accepted` and nothing else. Delete or complete the eleven stub RFCs
— RFC-0102 said this at line 481 and eleven stubs remain. Add a CI check that every SQL block
in `docs/rfcs/*.md` parses.

---

## D. Missing RFCs

The obvious gaps (state, capabilities, execution graph, resource graph, prompt construction,
secrets, settings, observability, testing, serialization, networking, plugin packaging, model
memory, notifications, performance, identity, project templates, DI) already have RFC numbers —
though eleven of them are stubs. So this section lists only what is genuinely absent.

### D1. Agent Loop / Tool-Use Protocol — **highest priority**
The core execution cycle (A1): tool schema representation, model-emitted tool-call
normalization across providers, result encoding, parallel calls, termination conditions, and
the mapping to Task/Attempt. Deserves an RFC because it is the primary vendor-independence
seam and because five subsystems converge on it.

### D2. Durable Execution Model
Which of interpreter / deterministic-replay / re-derivation (A3), and the consequences for how
session logic is authored. Without this RFC, RFC-0006 is unimplementable as written.

### D3. Git Integration Strategy and External-Mutation Reconciliation
Backend choice (JGit / libgit2+JNI / git CLI), what that implies for worktrees and for Android,
and the reconciliation protocol for branch switches, pulls, rebases, and external commits (A4,
A5, A22). This is the RFC that makes "Git-first" a real claim rather than an aspiration.

### D4. Scope Model (User / Workspace / Project)
Six RFCs already depend on it (B3, A21). It is the missing foundation under the model catalog,
secrets vault, plugin installation, and device identity.

### D5. Trust, Taint, and Untrusted Content
The inbound counterpart to RFC-0024's egress labels: where untrusted content enters (tool
output, MCP results, imported projects, retrieved documents), how taint propagates through
context assembly, and how authority attenuates in response (A2, B4). This is the RFC that makes
prompt injection an architectural concern rather than a prompt-engineering one.

### D6. Cost, Quota, and Runaway Control
Budgets as capability constraints, wake amplification limits, circuit breakers (A6). RFC-0045
is a stub about CPU and memory; the actual risk is money and tokens.

### D7. Concurrency Across Processes
Project locking, single-runtime enforcement, daemon-vs-library resolution, and coexistence with
the user's own `git` invocations (A15).

### D8. Retention, Compaction, and Storage Lifecycle
What is deleted, when, and by what policy — for events, attempts, prompt packages, artifacts,
and indexes (A13). Mobile-first makes this mandatory.

### D9. Threat Model
RFC-0003 lists "define the threat model" as a goal and does not deliver one. Required: what is
in scope (malicious project content, hostile MCP servers, prompt injection, local malware,
model exfiltration), what is explicitly out (a compromised OS, a malicious user), and which
control answers each. Without it, every security discussion in the repo is unfalsifiable.

### D10. Error Taxonomy and Failure Semantics
`ErrorCategory` (`0019:179`) and `ErrorCode` (`0052:386`) are two disjoint enumerations of the
same domain. One taxonomy, shared by the Execution Graph, the Runtime API, and the Tool Broker,
with retryability and user-visibility as properties of each class.

---

## Appendix — direct answers to the twenty questions

Where I differ from RFC-0100's answers, I mark it **[differs]**.

1. **Is "Everything is a Project" correct?** No, as stated. It is correct as a *work* boundary
   and wrong as a *storage, security, and global-state* boundary. RFC-0020's per-project
   `AIEngine` (A21) is the proof. Adopt three scopes (B3). Restate the principle as "everything
   actionable belongs to a project."

2. **Should Intent Graph be separate from Execution Graph?** Yes — but the priority is
   inverted. Execution Graph is foundational and should be built first and richly; Intent Graph
   is a leaf (C4) and should be a task list in v1. **[differs from RFC-0102, which calls the
   separation one of the architecture's strongest points — the separation is right, the implied
   equal weighting is not.]**

3. **Are Resources and Artifacts sufficiently distinct?** They are *too* distinct. RFC-0024
   already correctly collapses them into `ContentNode` with a mutability policy. Finish it:
   deprecate RFC-0013/0014 and update RFC-0002 and `ARCHITECTURE.md` (A23).

4. **AI Engine or Model Manager?** Neither as one component. Split into a user-scope **Model
   Runtime** (catalog, weights, load/unload) and a per-request **Inference Router** (policy,
   routing, degradation). The current single per-project object is wrong on both axes (A21).

5. **Is Media Engine justified?** No. Do not build it (B7). A `ContentNode` kind plus existing
   AI capabilities covers every stated need.

6. **Is Git fundamental or a plugin?** Fundamental for content versioning, not for runtime
   state. Neither "the spine" nor "a plugin": a **versioning provider** with Git as the default
   backend. And the claim is currently unbacked — no backend has been chosen and worktrees may
   be unavailable on the first platform (D3).

7. **Will SQLite become a limitation?** Not as a metadata store; it is the right choice. It
   will become one for: vector search (use a dedicated index), large blobs (content-address to
   files), append-only event volume (compaction, D8), and cross-process access (A15). The
   binding constraint is not SQLite — it is that nothing ever deletes (A13).

8. **Is KMP still the best choice?** Yes, given Android matters and the team is small. It is not
   the risk it is treated as. The real risks are the JNI boundaries — llama.cpp, Whisper,
   possibly libgit2 — and per-ABI native packaging. Keep KMP; make each native boundary a thin,
   explicitly-versioned interface so it can be replaced or moved out-of-process later.

9. **Are sessions too heavyweight?** Yes, and RFC-0006's Runs only partly fix it. A session
   carries identity, memory, capabilities, subscriptions, workers, artifacts, and a lifecycle;
   most useful work ("transcribe this", "run the formatter") wants none of that. Add a
   **Task-without-session** primitive: a Run with a capability set and no persistent actor.

10. **Is event sourcing worth adopting from the beginning?** No. Append-only audit log plus
    domain tables (B6). Full event sourcing on untyped `Map<String, Any>` payloads retained
    forever would be among the most expensive mistakes available here.

11. **Should replay be implemented through event logs?** No. Event logs give audit
    reconstruction, not replay. Store a *recording* — prompts, model outputs, tool results,
    versions — and present it as a timeline. Delete the deterministic-replay claims from
    RFC-0004 (A8, B6).

12. **Does the Knowledge Engine overlap too much with GitSema?** Yes, and RFC-0100's fix
    (query broker, GitSema as one provider) is right and still not reflected in RFC-0015. Add:
    providers must declare freshness and invalidation, because the Git-mutation problem (A4)
    lands hardest on the index.

13. **Is the Tool Broker too generic?** Yes — unchanged since RFC-0100 despite being accepted
    twice. Typed effects, preview, idempotency keys, and a cancellation method that currently
    does not exist despite RFC-0006 calling it (A9, B5).

14. **Are plugins sufficiently isolated?** No. RFC-0043 is a 51-line stub promising signed
    packages with no trust root, and RFC-0060 describes something much broader. More urgently:
    **MCP is the plugin system that ships first**, it has no trust model, no sandbox, no
    capability subject, and its documented config is remote-code-execution-by-clone (A25, A2).

15. **Is capability security expressive enough?** It is not capability security (A2). Expressive
    enough is the wrong question until designation is fixed.

16. **Does the architecture support future distributed execution?** Partially. RFC-0046 reserves
    identity fields, which is the right instinct. But `Event.causality` is a single parent
    pointer, not a vector clock or a causal history; wall-clock ordering (A8) is unusable
    distributed; and there are no signed events. Reserve: actor ID, device ID, logical clock,
    and a signature field on audit records. Do not build more.

17. **Can it support teams later without breaking changes?** Not as designed — but the blocker
    is smaller than it looks. The hard part is not permissions, it is that **operational state
    lives in SQLite outside Git** (A4), so there is nothing to merge. Fix the scope model (B3)
    and the split-state problem now, and teams become an additive feature later.

18. **Is multimodal support truly first-class?** Conceptually yes, operationally no. The
    taxonomy is good. But `PromptPackage` is text-only (`0025:106-120`), `ContextItemKind` has no
    image or audio member, and multi-modal context is listed as Future Work at line 327. The
    architecture accommodates other modalities; it does not yet carry them.

19. **Does offline-first conflict with remote providers?** Not inherently. It conflicts with
    the *current* design in one specific place: silent local→remote fallback as an engine
    heuristic (`0020:166`, B8). Make degradation explicit and user-owned and the tension
    disappears.

20. **What becomes technical debt within three years?** In descending order of confidence:
    (1) whatever agent loop gets improvised in month two (A1) — near certain;
    (2) the capability model, once tools have string-path APIs (A2) — near certain;
    (3) session memory growth (A13, and RFC-0102 agrees at 85%);
    (4) storage that never reclaims, on phones (A13);
    (5) Git/SQLite divergence, surfacing as user-visible data loss (A4);
    (6) the event schema, being untyped and retained forever (Lower findings);
    (7) Android background execution, if Android leads (A5).

---

## Closing judgement

The vision is coherent and differentiated, and the RFC corpus is unusually thorough for a
pre-implementation project. Both prior reviews said the main risk is premature breadth. I
agree, but I would name the risk more precisely:

**The architecture has been specified outward — many subsystems, each internally reasonable —
without ever specifying inward.** The centre of the system, the loop where a model's output
becomes an authorised effect on the user's machine, is the one thing no document describes.
Every critical finding above is downstream of that: the capability model is an ACL because
nothing forced it to mediate a real call site; the continuation model is aspirational because
no loop was there to checkpoint; the Execution Graph duplicates provenance four ways because it
records a process that was never defined.

The correction is not to write more RFCs. It is to specify the centre (D1, D2), make the
schema executable (C2), prove a kernel that survives `kill -9` with no AI in it (C3 Phase 1),
and reverse the platform order (A5). Those four moves cost roughly six weeks now and are, on
the evidence of this review, worth well over a year later.

One structural recommendation above all: **close review findings with diffs, not with new
documents.** The single clearest signal in this repository is that two good reviews produced
new RFCs and almost no edits — and the same defects are still sitting in the same lines.

---

*This is a third-pass independent review. It is one input, deliberately skeptical, and it is
wrong about some things. The findings that carry citations are checkable; check them.*
