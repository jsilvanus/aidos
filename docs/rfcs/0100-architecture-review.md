# RFC-0100: Comprehensive Architecture Review

Status: Draft

## Purpose

This review evaluates the accepted Aidos RFCs as one coherent architecture before implementation begins. It is intentionally skeptical: the goal is to find structural mistakes while they are still inexpensive to fix, not to restate the existing design.

## Executive judgment

Aidos has a strong north star: local-first user agency, a headless runtime, explicit capabilities, and model/vendor independence. The biggest architectural risk is not any single component; it is that too many first-class abstractions are being introduced before their boundaries are proven. Projects, sessions, intent graphs, resources, artifacts, knowledge, instructions, tools, events, plugins, media, storage, Git, and models are all treated as platform primitives. That creates a high risk of building a distributed operating system inside a single-user application before the MVP has proven the core loop.

The recommended correction is to stabilize a smaller kernel first:

1. **Project container and resource graph**: identity, storage, resources, artifacts, Git links, settings, secrets, and capabilities.
2. **Execution runtime**: sessions, workers, scheduler, event log, tool broker, cancellation, and audit.
3. **AI runtime**: model catalog, provider adapters, prompt/context assembly, model invocation, and usage accounting.
4. **Extension boundary**: plugins, MCP adapters, knowledge providers, and import/export.

Everything else should be layered on those contracts rather than becoming an equally privileged subsystem.

---

## A. Architectural flaws

### Critical severity

#### A1. The architecture lacks a single canonical state model

Many RFCs define their own persistence concepts: projects contain SQLite and Git; events may be persisted; sessions maintain state and memory; intent graphs are versioned; resources are mutable; artifacts are immutable; knowledge providers cache indexes; plugins have lifecycle and configuration. There is no single answer to: **what is the source of truth for state, history, identity, and causality?**

This is the most important flaw because it affects replay, audit, sync, import/export, plugin safety, and future collaboration. If Git is the spine, SQLite is the metadata store, and the event log is the replay substrate, the architecture must define which one wins during conflict, restore, migration, and partial failure.

**Recommendation:** create a State Model RFC before implementation. It should define:

- Object identity and stable IDs.
- Authoritative stores for each object type.
- Transaction boundaries across SQLite, filesystem, Git, model calls, and tool calls.
- Conflict resolution order.
- Snapshot and checkpoint semantics.
- Event log retention and compaction.
- Recovery after crash halfway through a tool invocation.

**Implementation cost if fixed now:** medium.

**Cost if deferred:** very high; retrofitting replay and import/export after divergent state paths exist will be painful.

#### A2. Replay is a goal, but nondeterminism is not modeled

Several RFCs imply replay or temporal inspection, but the architecture does not yet identify all nondeterministic inputs: LLM outputs, remote model changes, wall-clock time, filesystem races, Git state, shell command output, MCP server behavior, network responses, random IDs, environment variables, OS scheduling, model sampling parameters, and plugin versions.

Event logs alone are insufficient for replay. They can support audit and timeline reconstruction, but deterministic replay requires capturing inputs, outputs, versions, capabilities, prompts, retrieved context, model metadata, tool responses, and sometimes snapshots of external resources.

**Recommendation:** downgrade the promise from deterministic replay to **audit replay** unless a Replay RFC defines deterministic boundaries. Store enough data to reconstruct what happened, not necessarily to re-execute identically.

**Implementation cost if fixed now:** low to medium.

**Long-term impact:** high; inaccurate replay claims will undermine trust.

#### A3. Capability security is underspecified for real enforcement

The RFCs correctly prefer explicit permissions, but current concepts are closer to permission labels than enforceable object capabilities. The design needs details for delegation, attenuation, revocation, time bounds, path canonicalization, network scopes, plugin isolation, prompt-injection resistance, and confused-deputy prevention.

Specific weak points:

- A session may spawn workers, but worker capability derivation is not precise.
- Tools and MCP servers can expose arbitrary behavior behind generic interfaces.
- Project plugins are discoverable from project directories, which can make opening an untrusted project equivalent to running code.
- Shell and filesystem capabilities need OS-level containment, not only runtime checks.
- Remote model permissions need data classification and egress controls, not just provider approval.
- Revocation cannot be “immediate and certain” for already-running subprocesses, network calls, or plugins unless cancellation and kill semantics are defined.

**Recommendation:** make a Capability Model RFC blocking the Tool Broker, Plugin SDK, Shell, Filesystem, MCP, and Remote Models implementations. Treat capabilities as unforgeable handles bound to subject, object, operation, constraints, expiry, and audit context.

**Implementation cost if fixed now:** medium.

**Cost if deferred:** critical; security boundaries are difficult to retrofit.

#### A4. Plugin isolation is optimistic and conflicts with offline-first and mobile-first

The Plugin SDK allows libraries, processes, executables, Python scripts, Rust, and WebAssembly. That is too broad for v1, especially on Android. A plugin model that works on desktop may not work on Android due to sandboxing, binary distribution, background limits, and store policies.

Project-local plugins are particularly dangerous. They create a supply-chain and prompt-injection bridge: an imported project can carry executable extensions that ask for capabilities during a workflow.

**Recommendation:** restrict v1 plugins to one portable isolation target, preferably WASI/WebAssembly plus a narrow host API, or defer third-party plugins until after core contracts stabilize. MCP can cover many early integration needs without loading arbitrary code into the runtime.

**Implementation cost if fixed now:** medium.

**Maintenance impact:** high reduction in platform-specific complexity.

#### A5. The roadmap conflicts with the stated technology decisions

The project context says Android first, desktop second, Kotlin Multiplatform, SQLite, and Compose. The roadmap still says Phase 1 uses a Rust headless runtime and Phase 2 depends on Rust FFI/mobile bindings. That is a major inconsistency. A KMP runtime and a Rust runtime imply different build systems, plugin ABI strategies, mobile packaging, memory models, concurrency primitives, and developer skill sets.

**Recommendation:** decide immediately whether the runtime kernel is KMP-first or Rust-core-with-KMP-frontends. If KMP is the locked decision, update the roadmap and runtime RFCs before implementation.

**Implementation cost if fixed now:** low.

**Cost if deferred:** very high; language/runtime reversals are among the most expensive early mistakes.

#### A6. Git-first is overextended into domains where Git is a poor primitive

Git is excellent for source code, text resources, patches, branches, and export. It is weak for large media, frequently changing binary artifacts, private secrets, local model files, notification state, event logs, embeddings, and personal productivity records.

“Everything is Git-first” risks forcing non-code workflows into developer tooling assumptions. Life management projects, voice notes, timers, photos, OCR outputs, and recurring sessions should not require the user to reason about commits, branches, or merges.

**Recommendation:** keep Git as a first-class project resource and default versioning provider for code/text, but not as the universal project spine. Define a Versioned Store abstraction where Git is one backend. For coding projects, Git can be required; for generic projects, Git should be optional or hidden.

**Implementation cost if fixed now:** medium.

**Long-term maintenance impact:** high; this prevents coding assumptions from leaking into all domains.

### High severity

#### A7. “Everything is a Project” is useful but too broad without workspaces and personal scopes

Project is a good boundary for code and research. It is awkward for cross-cutting concerns: user identity, global settings, model catalog, downloaded local models, secrets, plugin installation, notification preferences, recurring routines, and cross-project knowledge. If everything must live inside a project, shared state will be duplicated or hidden in ad hoc global stores.

**Recommendation:** define three scopes:

- **User scope:** identity, global settings, installed plugins, model catalog, secrets vault, device inventory.
- **Workspace scope:** a collection of projects and cross-project resources.
- **Project scope:** bounded work context with resources, artifacts, sessions, and repository links.

Keep “everything actionable belongs to a project” rather than “everything is a project.”

#### A8. Intent Graph and Execution Graph are correctly separate, but Execution Graph is missing

The separation between intent and execution is architecturally correct. Intent is durable, editable, and user-facing; execution is operational, transient, failure-prone, and machine-facing. However, the RFCs define Intent Graph while leaving Execution Graph implicit in sessions, scheduler queues, tool calls, artifacts, and events.

Without an Execution Graph, the system cannot cleanly represent dependencies, retries, partial completion, worker fan-out, cancellation, idempotency, critical paths, or provenance from intent to outputs.

**Recommendation:** add an Execution Graph RFC. It should map intent nodes to execution attempts, tool invocations, model calls, worker sessions, produced artifacts, failure causes, and compensating actions.

#### A9. Resources and Artifacts are conceptually distinct but operationally blurry

The distinction “resources are mutable context; artifacts are immutable outputs” is useful. But outputs often become inputs: a generated design document may become project guidance; a transcript may be summarized into knowledge; a patch may become repository state; an imported document may be both source material and artifact.

**Recommendation:** model both as nodes in a **Resource Graph** with lifecycle state and mutability policy rather than separate silos. Artifact can be an immutable resource subtype with provenance. Promotion from artifact to resource should be explicit and audited.

#### A10. The Knowledge Engine overlaps with GitSema, Instruction Engine, resources, and search

The Knowledge Engine risks becoming a catch-all for “anything useful to the AI.” GitSema, tree-sitter, LSP, embeddings, build metadata, instructions, resources, artifacts, and Git history all produce knowledge, but they have different freshness, authority, and permission models.

**Recommendation:** make the Knowledge Engine a query broker and indexing coordinator, not a monolithic knowledge base. Providers should declare domains, freshness, authority, cost, and invalidation rules. GitSema should be one provider, not a privileged sub-engine.

#### A11. Tool Broker is too generic without typed effects and transactions

A generic tool interface is flexible, but too much flexibility makes security, UI preview, undo, retries, and audit difficult. Shell, filesystem, Git, MCP, notification, and model tools have very different semantics.

**Recommendation:** define tool effects as typed contracts:

- Read-only query.
- Deterministic transform.
- Filesystem mutation.
- Git mutation.
- Network egress.
- User notification.
- External side effect.

Require preview/dry-run support for high-risk effects where possible. Require idempotency keys for retryable calls.

#### A12. Event Bus and Scheduler boundaries are blurred

The Event Bus publishes facts; the Scheduler decides work. Some RFC language makes the bus a “time machine” and a wakeup mechanism, while the scheduler also owns subscriptions, queues, fairness, boot, shutdown, and cross-session coordination. If events become commands, the bus will accumulate policy.

**Recommendation:** separate event categories:

- **Facts:** immutable things that happened.
- **Commands:** requested actions.
- **Signals:** lossy wakeups/progress.
- **State snapshots:** compacted materialized state.

The scheduler should consume facts/commands and own execution policy. The bus should not own business logic.

#### A13. SQLite is appropriate, but the architecture must design around its concurrency shape

SQLite is a strong fit for local-first metadata, especially on mobile. It becomes a limitation if used for high-volume event streams, vector search, large blobs, concurrent writers, distributed sync, or plugin-owned schemas without strict boundaries.

**Recommendation:** use SQLite as the authoritative metadata store with WAL mode and clear single-writer discipline. Store blobs and model files in content-addressed files. Use dedicated indexes for embeddings if necessary. Define migrations and schema ownership before plugins exist.

#### A14. Offline-first conflicts with remote providers unless data egress is first-class

Remote models are compatible with offline-first only if remote invocation is modeled as optional, explicit egress. The architecture needs more than provider abstraction: it needs privacy classification, prompt construction audit, redaction, offline fallback behavior, cost budgets, and user-visible degradation states.

**Recommendation:** model remote calls as network side effects requiring egress capabilities, data classification, and audit records containing prompt hashes, model/provider versions, cost, and retention policy.

#### A15. Sessions may become too heavyweight

Long-lived sessions with memory, permissions, event subscriptions, workers, archives, replay, and coordination are powerful but risk becoming the only execution abstraction. Many tasks are small: remind me, summarize this file, transcribe audio, run formatter, classify a document.

**Recommendation:** introduce lighter **Tasks** or **Runs** under sessions. A session is a user-facing continuity container; a run is an execution attempt; a worker is an isolated executor. This prevents every automation from becoming a permanent actor.

### Medium severity

#### A16. Media Engine is justified, but it should not be an engine yet

Multimodal support must be first-class, but a separate Media Engine risks premature abstraction. Early needs are media resource handling: MIME detection, metadata extraction, thumbnails, transcription/OCR pipelines, and viewers.

**Recommendation:** start with a Media Resource Provider and pipeline interfaces. Promote to Media Engine only when scheduling, streaming, hardware acceleration, and cross-modal transformations require it.

#### A17. AI Engine is better than Model Manager, but the boundary is too wide

“AI Engine” is the right name if it orchestrates capabilities, routing, usage, privacy, and multimodal pipelines. If it only lists and invokes models, “Model Manager” would be more honest. The missing boundary is prompt/context construction; that should not be scattered across sessions.

**Recommendation:** split AI runtime into:

- Model Catalog.
- Provider Adapters.
- Inference Router.
- Context/Prompt Builder.
- Usage and Policy Ledger.

The AI Engine can be the facade over these pieces.

#### A18. Kotlin Multiplatform is plausible but creates ecosystem risk

KMP and Compose align with Android-first and desktop-second. Risks include native library interop for local models, plugin sandboxing, filesystem differences, mobile background work, and performance-sensitive indexing. Rust would be stronger for sandboxing and systems integration; KMP is stronger for product velocity across Android/desktop.

**Recommendation:** keep KMP if Android-first is non-negotiable, but isolate performance-sensitive and sandbox-sensitive components behind process or library boundaries so Rust/C++ can be introduced selectively later.

#### A19. Future distributed execution and teams are not supported without breaking semantics

Single-user local-first is a reasonable starting point. But later teams/distribution require identity, authority, object ownership, conflict resolution, transport security, CRDT or merge semantics, and remote attestation. These cannot be bolted on invisibly.

**Recommendation:** do not build team features now, but reserve fields and semantics: actor IDs, device IDs, causality IDs, signed events, object ownership, and merge policies.

#### A20. Observability is missing as a core design concern

Aidos will run long-lived agents, local models, plugins, shell commands, and background tasks. Without structured logs, metrics, traces, audit events, and crash recovery, debugging will be extremely difficult.

**Recommendation:** add Observability RFC before nontrivial runtime work.

### Lower severity but likely future debt

- Instruction precedence will become a prompt-injection and UX problem unless explained visually.
- Import/export will need schema versioning and compatibility matrices.
- Notifications and timers require OS-specific semantics, especially Android background limits.
- Settings and configuration are mentioned but not architected.
- Secrets are mentioned but need a real vault design.
- Build metadata as knowledge requires language-specific adapters and invalidation.
- “Accepted” status for all RFCs may discourage necessary redesign before implementation.

### Direct answers to required review topics

1. **Everything is a Project:** correct as a user-facing work boundary, wrong as a universal storage/security/global-state abstraction. Add user and workspace scopes.
2. **Intent Graph separate from Execution Graph:** yes. But Execution Graph must be explicit.
3. **Resources vs Artifacts:** conceptually useful, operationally insufficient. Model both in a Resource Graph with mutability/provenance policies.
4. **AI Engine vs Model Manager:** AI Engine is better if it includes routing, context construction, policy, and accounting. Otherwise rename to Model Manager.
5. **Media Engine:** justified long term, premature for v1. Start as media resource pipelines.
6. **Git fundamental or plugin:** fundamental for coding projects; should be a first-class versioning provider, not mandatory for all project types.
7. **SQLite limitation:** not for local metadata; yes for blobs, high-volume event streams, vectors, multi-writer plugins, and distributed sync.
8. **KMP long-term:** reasonable for Android/desktop UX and shared logic; risky for sandboxing/local inference. Use strict boundaries for systems components.
9. **Sessions too heavyweight:** yes unless lightweight runs/tasks exist.
10. **Event sourcing from beginning:** adopt append-only audit events from day one; defer full event-sourced state reconstruction until proven necessary.
11. **Replay through event logs:** audit replay yes; deterministic replay requires much more capture than event logs.
12. **Knowledge Engine vs GitSema:** overlap risk is high. GitSema should be a provider under a query broker.
13. **Tool Broker too generic:** yes without typed effects, previews, idempotency, and policy hooks.
14. **Plugins isolated enough:** no. The current design is too broad and mobile-hostile.
15. **Capability security expressive enough:** not yet. Needs formal delegation, attenuation, revocation, and OS/process enforcement.
16. **Future distributed execution:** not yet, but can be preserved with actor/device IDs and causality semantics.
17. **Teams later:** possible only if identity, ownership, conflict, and signed audit records are reserved early.
18. **Multimodal first-class:** conceptually yes, operationally no until media resource pipelines and prompt/context multimodal packaging are specified.
19. **Offline-first vs remote models:** compatible if remote model calls are explicit, auditable egress with local fallback and privacy policy.
20. **Three-year debt:** plugin ABI, event/replay semantics, Git overreach, session heaviness, capability model, prompt construction, storage migrations, and observability.

---

## B. Better ideas

### B1. Define a smaller runtime kernel

Replace the current many-engine mental model with a small kernel and layered services:

```text
Aidos Kernel
├─ Identity and scopes
├─ State store and object registry
├─ Event/audit log
├─ Capability manager
├─ Scheduler and execution graph
├─ Tool/effect broker
└─ Extension host boundary

Core Services
├─ Project service
├─ Resource/artifact graph service
├─ AI service
├─ Knowledge query service
├─ Instruction service
├─ Git/versioning service
└─ Import/export service
```

This reduces circular dependencies because every service depends on the kernel contracts, not on each other directly.

### B2. Replace “engines” with contracts where possible

The word “engine” should be reserved for components that own execution. Many current engines are really registries, brokers, indexes, or pipelines.

Suggested naming:

- AI Engine → AI Runtime or AI Service.
- Knowledge Engine → Knowledge Index and Query Broker.
- Instruction Engine → Instruction Resolver.
- Media Engine → Media Pipeline.
- Tool Broker remains Tool Broker, but with typed effect contracts.

### B3. Use Resource Graph as the unifying content abstraction

A Resource Graph can unify resources, artifacts, media, imported files, generated reports, transcripts, patches, and knowledge references.

Minimal node fields:

- ID, project/workspace scope, type, MIME/media type.
- Storage locator: SQLite row, file path, Git object, content-addressed blob, external URI.
- Mutability: mutable, append-only, immutable.
- Provenance: created by, derived from, tool/model/session/run IDs.
- Security labels: sensitivity, egress eligibility, capability requirements.
- Version pointer or content hash.

This makes Resources vs Artifacts a policy distinction instead of a hard architectural split.

### B4. Add Execution Graph now, keep Intent Graph small

Intent Graph v1 should be simple: goals, constraints, status, owner, links, and user-approved changes.

Execution Graph v1 should capture:

- Run IDs.
- Attempt numbers.
- Dependencies.
- Tool/model calls.
- Produced resources/artifacts.
- Cancellation and retry state.
- Error taxonomy.
- Capability grants used.

This gives the scheduler, replay, audit, and UI a common operational model.

### B5. Adopt audit-log-first, not full event sourcing

Full event sourcing is expensive and easy to get wrong. Start with:

- Append-only audit log for security and provenance.
- Domain tables for current state.
- Optional event handlers for projections.
- Periodic snapshots/checkpoints for large timelines.

This preserves replay/audit options without forcing all state reconstruction through events.

### B6. Make capabilities concrete object handles

Capability format should include:

```text
capability_id
subject: session | worker | plugin | frontend | user
object: project | resource | path | tool | model | secret | network destination
operations: read | write | execute | delete | invoke | egress | notify
constraints: path scope, host allowlist, max cost, max duration, content labels, time expiry
parent_capability_id
revocation_epoch
audit_context
```

Workers receive attenuated child capabilities, never ambient project permissions.

### B7. Prefer WASM or out-of-process plugins for v1

Simpler plugin rule:

- Built-in plugins can be native.
- Third-party plugins must run out of process or in WASM.
- Project-local plugins are disabled by default.
- Plugins cannot request capabilities during headless execution without prior policy.
- Plugin APIs are versioned by protocol, not by in-process ABI.

### B8. Treat prompt construction as a first-class subsystem

Aidos will live or die by context quality. Prompt construction should have its own contract:

- Inputs from intent, resources, instructions, knowledge, user messages, and tool state.
- Precedence and conflict rules.
- Token/cost budgeting.
- Privacy filtering and redaction.
- Citation/provenance tracking.
- Model-specific adapters.

Do not let every session hand-roll prompts.

### B9. Use explicit degradation modes

Offline-first systems need clear capability states:

- Available locally.
- Available remotely with approval.
- Unavailable offline.
- Disabled by policy.
- Degraded substitute available.

Expose this through AI routing, tools, scheduler, and UI.

### B10. Simplify MVP drastically

MVP should prove one vertical slice:

1. Create/open a coding project.
2. Resolve instructions.
3. Ask a session to perform a task.
4. Build context from files/Git/instructions.
5. Invoke one model provider.
6. Use filesystem/Git tools under capabilities.
7. Produce a patch/report artifact.
8. Commit/export/audit the result.

Do not build general plugins, media, distributed workers, or full replay in MVP.

---

## C. Roadmap improvements

### C1. Roadmap correction

The current roadmap should be re-ordered around stable contracts, not UI platforms. Android first is fine, but Android should not drive core semantics. The headless runtime API must be stable enough that Android, CLI, and tests exercise the same behavior.

### C2. Recommended implementation order

```text
Phase 0: Architecture reset gates
├─ Resolve KMP vs Rust runtime decision
├─ State Model RFC
├─ Capability Model RFC
├─ Execution Graph RFC
├─ Prompt Construction RFC
└─ Observability RFC

Phase 1: Kernel contracts
├─ Object identity and scopes
├─ SQLite schema and migrations
├─ Resource Graph minimal schema
├─ Append-only audit/event log
├─ Capability manager
├─ Runtime API boundary
└─ Test harness

Phase 2: Coding vertical slice
├─ Project service
├─ Filesystem tool
├─ Git/versioning tool
├─ Instruction resolver
├─ AI provider adapter: one remote provider
├─ Context builder
├─ Session + Run execution
└─ Artifact/report generation

Phase 3: Offline proof
├─ Local model catalog
├─ Local embedding provider
├─ Basic semantic/code search
├─ Model routing and degradation states
└─ Android background constraints validated

Phase 4: Platform UX
├─ Android UI
├─ Notifications/timers
├─ Voice input pipeline
├─ Import/export
└─ Desktop shell after API stabilizes

Phase 5: Extension ecosystem
├─ WASM/out-of-process plugin host
├─ MCP adapter hardening
├─ Knowledge provider SDK
├─ Tool plugin SDK
└─ Version compatibility tests
```

### C3. Dependency graph

```text
State Model
├─ Storage
├─ Import/Export
├─ Replay/Audit
├─ Resource Graph
└─ Schema Versioning

Capability Model
├─ Tool Broker
├─ Shell
├─ Filesystem
├─ Git
├─ MCP
├─ Remote Models
└─ Plugins

Execution Graph
├─ Sessions
├─ Scheduler
├─ Workers
├─ Artifacts/Provenance
├─ Event Log
└─ Replay

Prompt Construction
├─ Instruction Resolver
├─ Knowledge Query Broker
├─ AI Runtime
├─ Privacy/Egress Policy
└─ Model Routing

Resource Graph
├─ Resources
├─ Artifacts
├─ Media Pipelines
├─ Knowledge Indexing
├─ Import/Export
└─ Search
```

### C4. Parallel work opportunities

Teams can work in parallel if contracts are frozen early:

- **Storage team:** migrations, object registry, resource graph tables, audit log.
- **Security team:** capability handles, policy evaluation, audit integration, sandbox requirements.
- **AI team:** provider interface, model catalog, usage ledger, prompt/context builder.
- **Tools team:** filesystem/Git/shell tools against typed effect API.
- **Knowledge team:** provider interface, GitSema provider, tree-sitter provider, embeddings provider.
- **Frontend team:** Android UI against mocked runtime API and event streams.
- **Testing team:** deterministic fixtures, fake model provider, fake tool broker, crash recovery tests.

### C5. Interfaces to stabilize first

1. Object IDs, scopes, and lifecycle states.
2. Capability request/grant/check/revoke API.
3. Tool invocation and typed effect schema.
4. Event/audit envelope.
5. Session/run/execution graph schema.
6. Resource graph schema.
7. AI model request/response envelope.
8. Prompt construction input/output contract.
9. Storage migration contract.
10. Runtime/frontend API.

### C6. What should not stabilize yet

- Plugin SDK surface.
- Media Engine API.
- Distributed execution protocol.
- Team/collaboration semantics.
- Model recommendation cookbook.
- UI view modes.
- Marketplace/registry behavior.

---

## D. Missing RFCs

### D1. State Model RFC

Needed because state is currently split across Git, SQLite, filesystem, event logs, resources, artifacts, indexes, and plugin configuration. This RFC should define source of truth, transactions, conflict resolution, snapshots, and recovery.

### D2. Capability Model RFC

Needed because security is central and must be enforceable before tools/plugins/MCP/shell are implemented. It should cover capability handles, delegation, attenuation, revocation, policy, sandbox mapping, and audit.

### D3. Execution Graph RFC

Needed because Intent Graph intentionally avoids execution details, but sessions/scheduler/tools need a shared operational graph for retries, workers, dependencies, and provenance.

### D4. Resource Graph RFC

Needed to unify resources, artifacts, media, transcripts, imported data, generated outputs, and knowledge references. It should define mutability, provenance, versioning, promotion, and search.

### D5. Prompt Construction and Context Assembly RFC

Needed because AI quality, privacy, token budgeting, instruction precedence, and knowledge retrieval all converge here. This is more central than many currently accepted RFCs.

### D6. Secrets and Credentials RFC

Needed for provider keys, Git credentials, MCP tokens, plugin secrets, encryption at rest, OS keychain integration, backup/export behavior, and redaction.

### D7. Settings and Configuration RFC

Needed for user/workspace/project/session/plugin settings, precedence, schema, sync/export, and UI discoverability.

### D8. Observability RFC

Needed for structured logs, metrics, traces, audit records, crash dumps, plugin diagnostics, model usage, performance, and privacy-preserving telemetry.

### D9. Testing Strategy RFC

Needed because this architecture will otherwise be hard to verify. Include fake providers, fake tools, golden prompt tests, migration tests, capability tests, crash recovery tests, and mobile lifecycle tests.

### D10. Serialization and Versioning RFC

Needed for persisted objects, plugin APIs, import/export, event schemas, model requests, and future distributed compatibility.

### D11. Networking and Egress RFC

Needed for remote models, MCP HTTP, plugin network access, proxy settings, TLS, certificate pinning, offline behavior, and privacy classification.

### D12. Plugin Packaging and Sandbox RFC

Needed because the current Plugin SDK is too broad. It should define package format, signing, trust levels, WASM/process isolation, project-local plugin policy, and mobile compatibility.

### D13. Model Memory RFC

Needed to distinguish user-approved memory, session memory, provider-side retention, embeddings, summaries, and sensitive information. Without this, “memory” will become a privacy and UX hazard.

### D14. Notifications, Timers, and Background Work RFC

Needed because Android, desktop, and future life-management workflows have very different scheduling and notification constraints.

### D15. Performance and Resource Budget RFC

Needed for local models, indexing, battery usage, mobile storage, background tasks, concurrent workers, and cost-aware routing.

### D16. Identity, Actors, and Future Collaboration RFC

Needed even for single-user systems to future-proof audit logs and distributed execution. Define user IDs, device IDs, session actors, plugin actors, signatures, and ownership.

### D17. Project Templates and Project Types RFC

Needed because coding, research, life management, and media projects have different default resources, capabilities, Git expectations, and UI surfaces.

### D18. Dependency Injection and Runtime Composition RFC

Needed for KMP testing, desktop/mobile differences, provider replacement, plugin loading, and mocked runtime APIs.

---

## Final recommendation

Before implementation, pause acceptance of the current RFC set and add a short “architecture reset” milestone. Do not rewrite everything. Instead, resolve the foundational contracts that cut across all RFCs: state, capabilities, execution graph, resource graph, prompt construction, observability, and runtime language choice.

The simplest durable version of Aidos is not “many engines coordinated by events.” It is a small local-first kernel with explicit state, explicit authority, explicit execution, and replaceable providers. Build that first, then let the platform grow.
