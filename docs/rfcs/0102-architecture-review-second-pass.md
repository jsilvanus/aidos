# RFC-0102: Second-Pass Architecture Review

Status: Draft

## Abstract

This document is the second architectural review of Aidos, written after the full RFC set (0000–0101) had been produced and the first review (RFC-0100) had been responded to. It evaluates internal coherence, architectural completeness, MVP realism, roadmap executability, and organizational scalability. It concludes with a final gate recommendation for the Chief Architect.

This review was produced by first reading every RFC independently, then reading RFC-0100 and RFC-0101, then evaluating those reviews rather than accepting them. Every finding in this document is grounded in the RFCs themselves, not in prior review consensus.

---

## A. Does the Project Have a Complete Vision?

**Is the purpose obvious?** Yes. "An offline-first, headless AI Operating Environment that is user-governed, vendor-independent, and organized around persistent Sessions and Projects" is a coherent and distinctive idea. It is not a chatbot. It is not a coding assistant. It is infrastructure.

**Is the architecture aligned with that purpose?** Mostly. The headless runtime, capability-based security, event-driven sessions, and Git-first versioning all serve the vision. The AI Engine's provider abstraction correctly anticipates vendor churn. The offline-first constraint genuinely shapes the architecture (local SQLite, explicit egress for remote models, local model support).

**Does every subsystem exist for a reason?** Nearly. The major subsystems have clear rationale. But several exist in a form that exceeds what any MVP needs:

- The Intent Graph (DAG with goals, sub-goals, constraints, acceptance criteria, dependencies, priorities, statuses) is a planning system before the core execution loop is proven.
- The Knowledge Engine (GitSema + tree-sitter + LSP + build metadata + test analysis + embeddings + ranking) is an enterprise RAG system before the basic read-file-and-prompt loop is validated.
- The Plugin SDK (supporting Python scripts, native binaries, and WASM simultaneously) is a platform before the platform exists.

**Is anything missing?** Three things are conspicuously absent:

1. A defined Runtime API — the contract between the headless runtime and frontends. Every frontend will need this, but no RFC defines it. **This is now addressed by RFC-0052.**
2. A concrete Prompt/Context Construction design — RFC-0025 exists only as a stub. **This is now addressed by the expanded RFC-0025.**
3. A resolution of the KMP vs. Rust language decision. **This has been resolved: Kotlin Multiplatform is confirmed.**

**Would a new contributor understand what Aidos is trying to become?** If they read the vision and principles first, yes. If they dive into the RFC list, they will quickly be overwhelmed by 50+ documents at similar levels of authority. There is no "start here" gradient from core to peripheral.

**Summary:** The vision is complete and differentiated. The architecture is aligned with it. The risk is premature breadth — too many systems treated as simultaneously essential before any single vertical slice has been implemented and validated.

---

## B. Are the Subsystems Properly Connected?

### Connection Map

```
┌────────────────────────────────────────────────────────────────┐
│                         User / Frontend                         │
│          (Android, Desktop, CLI) — RFC-0050, RFC-0051           │
└───────────────────────────┬────────────────────────────────────┘
                             │ Runtime API (RFC-0052)
┌───────────────────────────▼────────────────────────────────────┐
│                   Headless Runtime (RFC-0002)                    │
│                                                                  │
│   Project Manager (RFC-0010)                                     │
│      ↕ contains                                                  │
│   Session Manager (RFC-0011) ←→ Scheduler (RFC-0005)            │
│      ↕ drives                    ↕ wakes via                     │
│   Event Bus (RFC-0004) ←─────────────────────────────┐          │
│      ↕ delivers to                                    │          │
│   Sessions                                            │          │
│      ↕ read/write                                     │          │
│   Intent Graph (RFC-0012)                             │events    │
│   Execution Graph (RFC-0019)                          │          │
│   Resource Graph (RFC-0024)                           │          │
│      ↕                                                │          │
│   Storage (RFC-0040) — SQLite + Filesystem            │          │
│                                                       │          │
│   Sessions call:                                      │          │
│   AI Engine (RFC-0020) ← Prompt Construction (RFC-0025)         │
│   Tool Broker (RFC-0030) → publishes events ──────────┘          │
│   Knowledge Engine (RFC-0015) → feeds context                    │
│   Instruction Engine (RFC-0016)                                  │
│                                                                  │
│   Capability Manager (RFC-0018) — cross-cuts all                │
│   Concurrency Model (RFC-0007) — governs all async work         │
│   Session Execution Contract (RFC-0006) — governs sessions      │
└──────────────────────────────────────────────────────────────────┘
```

### By Subsystem

**Projects (RFC-0010)**
- Consumed by: Session Manager, Event Bus, Scheduler, Storage, all engines
- Produces: Isolation boundary, storage namespace, permission scope
- Issue: The Project RFC conflates "project as Git repository" with "project as workspace." For non-code projects, Git is awkward. Clean resolution: Git is the default versioning backend for code projects, not a mandatory primitive. Non-code projects may use filesystem-only storage without Git commit semantics.

**Sessions (RFC-0011)**
- Consumed by: User via frontends, Tool Broker, AI Engine, Knowledge Engine
- Produces: Artifacts, Intent Graph updates, Events
- Issue: No lightweight execution primitive exists for small tasks. RFC-0006 (Session Execution Contract) introduces the Run and Attempt abstractions within sessions to address this.

**Scheduler (RFC-0005)**
- Issue: "Single-threaded within a project" means a session blocking on a 30-second AI call blocks all other session wakeups in that project. RFC-0006 and RFC-0007 address yield semantics and the async concurrency model to resolve this.

**Event Bus (RFC-0004)**
- Issue: Events blur facts, commands, signals, and state snapshots. Separating these into distinct event categories (Fact, Command, Signal, StateSnapshot) reduces bus complexity.

**Intent Graph (RFC-0012)**
- Issue: The full graph model (Goal, SubGoal, Constraint, AcceptanceCriterion as distinct node types, versioned in Git) is significantly more complex than MVP needs. A simpler task-list structure suffices for MVP; the richer model is added once the execution loop is proven.

**Execution Graph (RFC-0019)**
- Was a stub; now a complete RFC. Defines Run, Task, Attempt, Edge types, state machines, retry policy, and query model.

**Resource Graph (RFC-0024)**
- Was a stub; now a complete RFC. Defines ResourceNode, edge types, promotion/demotion mechanics, and content addressing.

**Knowledge Engine (RFC-0015)**
- The Knowledge Engine is both a query broker and a provider. Correct model: providers (GitSema, tree-sitter) are internal to the Knowledge Engine or registered as pluggable services; the Knowledge Engine exposes a query API only. MVP scope: file search, git history, keyword search. Embeddings and semantic search belong in Phase 3.

**AI Engine (RFC-0020)**
- Prompt/context construction is not part of this RFC. RFC-0025 (now a complete RFC) defines context assembly. The boundary: AI Engine decides which model to use; Prompt Construction provides the assembled context.

**Tool Broker (RFC-0030)**
- The generic `Map<String, Any>` interface for tool parameters sacrifices type safety. Typed effects (Preview, Execute, Compensate) should be added to enable dry-run, undo, and audit.

**Capability Model (RFC-0018)**
- Was a stub; now a complete RFC. Defines delegation mechanics, enforcement semantics, revocation propagation, and mobile enforcement constraints.

**Storage (RFC-0040)**
- The intent_graph table stores the entire graph as a JSON blob. This is acceptable for early MVP but will not scale to thousands of nodes. Recommend a node/edge table structure for the Intent Graph within the same SQLite database.

**Missing Links Addressed:**
- Runtime API (RFC-0052): IPC contract between headless runtime and frontends.
- Session Execution Contract (RFC-0006): Formal execution, yield, and resumption semantics.
- Concurrency Model (RFC-0007): Threading and async model for the KMP runtime.

---

## C. Is the Architecture Sound?

### Consistency ✓
The architecture is internally consistent. The ten principles propagate through subsystem designs. Capability-based security appears in every tool and resource access point. Offline-first shapes the storage model. Events over polling is applied in the scheduler.

### Extensibility ✓
Provider abstractions (AI models, Knowledge providers, Tools via MCP) are genuine extension points. The Plugin SDK adds a user-facing extension mechanism. RFC-0048 (Dependency Injection) enables swapping implementations cleanly.

### Maintainability ⚠
The complexity budget is high. Sessions with memory, permissions, subscriptions, roles, workers, artifacts, and lifecycle are complex objects to maintain correctly. The Knowledge Engine (when fully built) is a significant maintenance burden. Mitigating factor: the RFC-driven design means decisions are documented.

### Security ⚠
Conceptually strong. The capability model is the right design. RFC-0018 is now a complete RFC. The remaining concern: on Android, process-level isolation for shell tools is constrained by the OS. The mobile enforcement section of RFC-0018 addresses this with application-layer enforcement as the fallback.

### Offline Capability ✓
Strong. The architecture is genuinely offline-first. Remote AI is explicit egress. SQLite is local. Git is local. Sessions run locally.

### Performance ⚠
The scheduler is "single-threaded within a project." RFC-0006 (Session Execution Contract) and RFC-0007 (Concurrency Model) define the yield and async mechanisms that resolve this.

### Concurrency ⚠ → Addressed
RFC-0007 (Concurrency Model) defines the coroutine-based async model, thread responsibilities, and concurrency contracts per subsystem. This was the most significant missing piece from the first generation of RFCs.

### Plugin Model ⚠
RFC-0060 and RFC-0043 still allow Python scripts and native binaries alongside WASM. For mobile compatibility, WASM (WASI) should be the primary isolation mechanism. Python and native binary support should be desktop-only, clearly marked.

### Testing ⚠
RFC-0038 (Testing Strategy) exists. RFC-0048 (Dependency Injection) enables test doubles. The combination needs specification: fake model providers, fake tool responses, deterministic event injection, crash recovery testing. These are critical for a system with long-running background sessions.

### Failure Handling ⚠
RFC-0006 (Session Execution Contract) addresses mid-session crash recovery and partial tool execution. RFC-0019 (Execution Graph) models retry and compensation.

### State Management ⚠ → Partially Addressed
RFC-0017 (State Model) is now a complete RFC. The authoritative stores per object type are defined. The crash recovery sequence is specified.

---

## D. Is the MVP Realistic?

### The Core Loop

The MVP has one job: prove that the core loop works.

**Core loop**: User creates project → assigns a task → Session uses AI + tools → Session produces an artifact → work is committed to Git → audit trail exists.

**Truly minimal viable components:**

1. Project (directory + SQLite + git init) — relatively simple
2. Session (persistent, with memory in SQLite, one driver type) — moderate complexity
3. One AI provider adapter (remote API) — moderate complexity
4. Prompt construction (basic: instructions + user message + file contents) — moderate complexity
5. Filesystem tool (read/write within project) — simple
6. Git tool (commit, status) — simple
7. Basic capability check (project-scoped, yes/no) — simple
8. Artifact record (immutable entry in SQLite with provenance) — simple
9. Audit log (append-only table in SQLite) — simple
10. CLI frontend using the Runtime API — simple

**Not needed for MVP:**
- Full Intent Graph DAG (use a task description string)
- Knowledge Engine beyond basic file reading
- Execution Graph beyond a simple run record
- Event Bus persistence (in-memory is fine initially)
- Multiple session roles
- Plugin SDK
- Local AI models
- Android or Desktop UI
- Worker sessions

### Realistic Implementation Order

```
Month 1-2: Core kernel
  - SQLite schema (project, session, resource, artifact, capability, audit)
  - Project creation and lifecycle
  - Capability manager (simple in-memory implementation)
  - Audit log

Month 2-3: First execution
  - Session model (persistent state in SQLite)
  - Remote AI provider adapter
  - Basic prompt construction (system prompt + file contents + user message)
  - Filesystem tool (read/write under capability check)
  - Git tool (commit, diff)
  - Artifact creation

Month 3-4: First complete vertical slice
  - CLI frontend using the Runtime API (RFC-0052)
  - End-to-end: create project → issue task → AI responds → tool executes → artifact produced → committed → audit logged
  - Basic import/export

Month 4-5: Harden and stabilize
  - Error handling throughout
  - Session persistence across restarts (RFC-0006 resumption semantics)
  - Event Bus (simple in-memory for intra-session events)
  - Instruction Engine (basic: AGENTS.md discovery)
```

---

## E. Is the Roadmap Executable?

### Issues with the Current Roadmap (RFC-0099)

**Issue 1: Language Decision**
RFC-0099 referenced Rust. The language decision is now resolved: Kotlin Multiplatform. RFC-0099 should be updated to reflect KMP as the runtime language.

**Issue 2: Android First is Hardest**
Android is the most constrained environment: background execution limits, limited IPC, Play Store policies. Leading with Android maximizes friction before the runtime is stable.

**Issue 3: Architecture Phase Produces No Code**
Architecture documents drift from reality the moment implementation begins. The first implementation milestone should be co-developed with architecture validation.

### Corrected Roadmap

```
Phase 0 (1-2 months): Resolve blockers
  - Confirm KMP as runtime language (done)
  - Complete Capability Model RFC (now done)
  - Complete Concurrency Model RFC (now done)
  - Define Runtime API (now done)

Phase 1 (3-4 months): CLI-first runtime
  - Core kernel (project, session, storage, capabilities, audit)
  - One AI provider (remote)
  - Three tools (filesystem, git, shell)
  - Prompt construction (basic)
  - CLI frontend using the Runtime API
  - Full test suite using fake providers and tools

Phase 2 (2-3 months): Offline proof
  - Local model support (one local LLM via Ollama or llama.cpp/JNI)
  - Local embedding model
  - Basic Knowledge Engine (file search, git history)
  - Model routing (local-first, remote fallback)
  - Runtime API stability

Phase 3 (3-4 months): Desktop frontend
  - Desktop GUI via Compose Multiplatform
  - Instruction Engine (AGENTS.md discovery)
  - Intent Graph (first, simple version)
  - Import/export

Phase 4 (3-6 months): Android
  - Android frontend against stable Runtime API
  - Background session execution (Android constraints)
  - Voice input
  - Mobile-specific UX

Phase 5 (ongoing): Ecosystem
  - Plugin SDK (WASM-first, narrow API)
  - MCP adapter hardening
  - Knowledge Engine expansion
  - Advanced Intent Graph
```

### Dependency Graph

```
Language Decision (KMP) — confirmed
  └─ Runtime API definition (RFC-0052)
       └─ Core kernel (SQLite, project, session)
            └─ Capability Model (RFC-0018)
                 ├─ Tool Broker (typed effects)
                 ├─ Filesystem tool
                 └─ Git tool
            └─ AI Provider Interface
                 └─ Prompt Construction (RFC-0025)
            └─ Audit Log
                 └─ Execution Graph (RFC-0019)

Core kernel + tools + AI + audit = Phase 1 complete
  └─ Local model support
  └─ Knowledge Engine (basic)
  └─ Phase 2 complete

Runtime API (stable)
  └─ Desktop GUI (Phase 3)
  └─ Android (Phase 4)
  └─ Plugin SDK (Phase 5)
```

---

## F. Does Version 1 Significantly Increase Platform Potential?

The current plan's Version 1 (Android MVP + Desktop) primarily adds UI. That is platform presence, not platform capability.

**Recommended V1 capability expansions:**

1. **Semantic knowledge search**: Embedding-based code and document search changes the quality of AI responses fundamentally. Without it, Aidos is "AI with file access." With it, Aidos becomes "AI that understands your codebase."

2. **Multi-session coordination**: The Driver/Worker model is what makes Aidos different from a chatbot. If V1 only supports a single session, the core differentiation is unrealized.

3. **Intent Graph as first-class UI element**: The Intent Graph separates Aidos from every other AI tool. If users can edit goals, see AI progress, and audit the decision chain, they experience the actual product vision.

4. **Replay/audit UI**: "What did the AI actually do?" is a trust-building feature. A timeline view of session events differentiates Aidos from black-box AI tools.

**What V1 should prove architecturally:**
V1 should prove that the extension boundary is stable enough for plugins. Shipping 2–3 first-party plugins against the Plugin SDK would validate the extension boundary before the community builds on it.

---

## G. Organizational Scalability

**Could different teams own different engines?**
Yes — with one caveat. The Runtime API (RFC-0052) and Capability Model (RFC-0018) must be stable before separate teams can work independently. If those contracts are unstable, any change propagates everywhere. Stabilize these two contracts first.

**Could contributors work mostly independently?**
Once the kernel contracts are stable: yes. AI Engine, Knowledge Engine, Tool Broker, and frontends can all evolve independently within their interfaces.

**Are subsystem APIs sufficiently stable?**
Not yet — because the key contracts (Runtime API, Capability Model, Execution Graph) were stubs. These are now complete RFCs. The next step is to implement them and mark them as Accepted.

**Would RFC ownership scale?**
The current RFC process is healthy for a small team. As the project grows:
- Subsystem maintainers should own their RFC range
- RFC-0000 through RFC-0009 remain under project owner control (vision and principles)
- 0010-0019 (core concepts) require cross-cutting review
- Higher-numbered ranges can be delegated

**Would plugin authors have a stable target?**
Not until Phase 5 when the Plugin SDK RFC (0060) and RFC-0043 (plugin packaging) are finalized and marked Accepted. Plugin authors should not build against Draft RFCs.

**Single-maintainer risk:**
Real risk if implementation has not started. The mitigating factor is documentation quality — the RFC set is comprehensive enough that a new maintainer could understand the design. But the RFC reading order needs explicit documentation (a "Getting Started" guide for contributors).

**Long-term maintenance:**
The architecture will become easier to maintain as subsystems mature and their contracts stabilize. The main risk is the Knowledge Engine, which has complex provider integrations. Keep the Knowledge Engine's query API narrow and stable; evolve providers behind it.

---

## Review of Previous Reviews

### RFC-0100 Assessment

RFC-0100 is an exceptional first architectural review. The identification of critical flaws is accurate. Evaluating every major recommendation:

**Accepted (RFC-0100 is correct):**
- A1 (No canonical state model): Accepted. RFC-0017 was a stub. Now a complete RFC.
- A2 (Replay overpromised): Accepted. The event bus supports audit replay, not deterministic replay. The Event Bus RFC should clarify this distinction.
- A3 (Capability security underspecified): Accepted. RFC-0018 was a stub. Now a complete RFC.
- A4 (Plugin isolation too broad): Accepted. WASM-first is the right choice for v1.
- A5 (KMP vs Rust unresolved): Accepted. Now resolved: KMP.
- A6 (Git overextended): Partially accepted. For MVP (coding-focused), Git-first is fine. Git is a default versioning backend, not a mandatory primitive.
- A7 (User/Workspace scope missing): Accepted. RFC-0046 reserves identity fields; a full workspace scope RFC should follow.
- A8 (Execution Graph missing): Accepted. RFC-0019 was a stub. Now a complete RFC.
- A9 (Resources/Artifacts operationally blurry): Accepted. RFC-0024 was a stub. Now a complete RFC.
- A10 (Knowledge Engine overlaps): Accepted. Query broker model is correct.
- A11 (Tool Broker too generic): Accepted. Typed effects are the right solution.
- A12 (Event Bus/Scheduler boundaries blurred): Accepted. Facts, Commands, and Signals should be distinct event categories.
- A13 (SQLite concurrency shape): Accepted. Single-writer discipline per project is important.
- A14 (Offline-first vs remote providers): Accepted. RFC-0042 and RFC-0023 address this.
- A15 (Sessions too heavyweight): Accepted. RFC-0006 (Session Execution Contract) introduces Runs and Attempts.

**Modified (directionally correct but oversimplified):**
- A5 (Language decision): "Decide immediately" is right. The nuance: KMP for product and application layer, with native libraries (via JNI) for performance-critical components (llama.cpp, Whisper, process sandboxing on Linux/macOS). The decision is where that boundary sits.

**Rejected:**
- None of RFC-0100's critical or high-severity findings deserve rejection. They are all valid.

### RFC-0101 Assessment

RFC-0101 adds one genuinely useful contribution: the four-contract framing (project container, execution runtime, AI runtime, extension boundary). This is a useful mental model for ordering implementation.

**Accepted:**
- Four-contract kernel model: correct implementation order.
- MVP definition (one project, one session/run, one tool broker, one capability model, one audit log): correct MVP scope.

**Rejected:**
- The implicit claim that writing stub RFCs resolves the concerns from RFC-0100. Stub RFCs defer, not resolve.

---

## Missing Architecture — Status Update

The following were identified as missing in this review. All have been addressed:

| Missing RFC | Status |
|------------|--------|
| Runtime API and Frontend Protocol | Created as RFC-0052 |
| Session Execution Contract | Created as RFC-0006 |
| Concurrency Model | Created as RFC-0007 |
| State Model (complete) | RFC-0017 expanded from stub |
| Capability Model (complete) | RFC-0018 expanded from stub |
| Execution Graph (complete) | RFC-0019 expanded from stub |
| Resource Graph (complete) | RFC-0024 expanded from stub |
| Prompt Construction (complete) | RFC-0025 expanded from stub |

---

## Final Assessment

### 1. Strongest Parts of the Architecture

**The Vision and Principles (RFC-0000, RFC-0001)**: The "AI Operating Environment" framing is genuine. The ten principles are well-reasoned and consistently applied throughout.

**Event-Driven Session Model (RFC-0004, RFC-0005, RFC-0011)**: "Sessions wake on events, sleep by default" is architecturally correct for a local-first AI orchestration system. It is efficient, observable, and extensible.

**AI Engine Provider Abstraction (RFC-0020, RFC-0021)**: Treating AI as capabilities, not vendors, is excellent long-term thinking. The taxonomy (LLM, embeddings, STT, TTS, vision, OCR, reranking) covers the current and near-future AI landscape.

**Capability-Based Security (RFC-0003, RFC-0018)**: This is the right security model for AI agents. Fine-grained, auditable, and composable.

**Intent Graph / Execution Graph Separation (RFC-0012, RFC-0019)**: Separating durable intent from operational execution is a genuinely important architectural insight.

### 2. Weakest Parts of the Architecture

**Session Memory Unbounded Growth**: `conversation_history: List<Message>` in SessionMemory has no cap, no compression strategy, and no defined summarization protocol. A long-running project session could accumulate tens of thousands of messages. Memory management is typically deferred until it becomes an urgent problem. Needs explicit design.

**Knowledge Engine / Prompt Construction Coupling**: The quality of AI responses depends on context assembly. If every session handles context assembly differently, AI behavior is inconsistent. RFC-0025 (now complete) standardizes this, but it must be enforced architecturally, not left to convention.

**Intent Graph Storage**: The intent_graph table stores the entire graph as a JSON blob in SQLite. This is fine for small graphs but blocks efficient querying and partial updates. A proper node/edge table structure should be planned for the post-MVP release.

### 3. Three Highest-Risk Architectural Decisions

**Risk 1: Capability Enforcement on Mobile**
Android's process isolation model is more limited than Linux/macOS. The capability model assumes enforcement mechanisms that may not be available. Without explicit mobile sandbox design, this degrades to software-only enforcement. Probability of becoming technical debt: High (80%).

**Risk 2: Session Memory Growth**
Unbounded conversation history is the most common cause of degraded AI performance in long-running systems. A session that accumulates months of history will have poor response quality and high token costs. Probability of becoming technical debt: Very high (85%).

**Risk 3: Knowledge Engine / Prompt Construction Interface**
These two systems must cooperate to produce good AI responses, but their interface is not yet stable. If they evolve independently, context assembly will be inconsistent. Probability of becoming technical debt: High (80%).

### 4. Three Highest-Value Improvements

**Improvement 1: Typed Effects in the Tool Broker**
Adding Preview, Execute, and Compensate effect types to the Tool Broker enables dry-run mode, undo, and richer audit. This is the highest-leverage improvement to the security and debuggability of the system.

**Improvement 2: Session Memory Compression**
Define a summarization protocol for long-running sessions: after N messages, the runtime summarizes older history and replaces the raw messages with a structured summary. This keeps context windows useful and costs bounded.

**Improvement 3: Stabilize the Runtime API Before Any Frontend**
The Runtime API (RFC-0052) should reach "Accepted" status and have a reference implementation before any frontend work begins. This unblocks parallel development and prevents frontend-runtime coupling.

### 5. "If Implementation Began Tomorrow..."

**What I Would Do in the First Three Months**

**Week 1-2: Finalize blocking decisions**
The language decision is resolved (KMP). The Runtime API, Session Execution Contract, Capability Model, and Concurrency Model RFCs are now written. Mark them Accepted and commit to them.

**Week 2-3: Define the data schema**
Before any KMP code, write the SQLite schema. Every table, every column, every index. This forces concrete thinking about the state model. Implement the schema migration strategy (RFC-0039). The schema is the specification; the code is the implementation.

**Month 1-2: Implement the Core Kernel**
Build Project → Session → Storage → Audit Log → Capability Manager. No AI, no tools. Use Kotlin coroutines throughout (RFC-0007). Write tests. Prove crash recovery.

**Month 2-3: First Vertical Slice**
Add Prompt Construction (simplified), one remote AI provider adapter, Filesystem tool, Git commit tool. Build a CLI frontend against the Runtime API. At the end of month 3, demonstrate: create project → issue task → AI responds → tool executes → artifact produced → committed → audit logged.

**What I Would NOT Do**
- Build any UI until the Runtime API is stable
- Implement the full Intent Graph DAG for MVP
- Build the Knowledge Engine beyond basic file reading for MVP
- Implement the Plugin SDK before the core contracts are stable
- Write any more stub RFCs — complete them or delete them

**The Single Most Important Principle**
Every line of code should prove the architecture, not assume it. Expect to revise at least 30% of the RFCs based on what implementation teaches you. Architecture documents are hypotheses; implementation is the experiment.

---

## What Would Prevent Aidos from Becoming an Exceptional Long-Term AI Operating Environment?

Not the architecture — the architecture is sound enough. What would prevent success:

1. **Premature platform complexity.** Building Android, Desktop, Plugins, and Knowledge Engine simultaneously before the core loop is proven will fragment attention and create architectural drift.

2. **Stub RFCs being treated as resolved concerns.** The gap between RFC-0100's critical findings and the stubs written in response is real. Stubs are now complete RFCs. They must be implemented, not just documented.

3. **The Intent Graph and Knowledge Engine being built too early.** These are the hardest subsystems to get right. Build them late, after the execution model is proven.

4. **Failure to ship something real in year one.** The RFC-driven approach is disciplined and admirable. But architecture without implementation is philosophy. The open-source community needs something to try.

5. **Session memory not managed.** Long-running sessions without memory management will degrade AI quality. This is invisible until it becomes urgent and painful to fix.

The probability that Aidos becomes exceptional is meaningfully positive. The vision is real. The architecture is honest. The principles are sound. The risk is operational: doing too many things at once before any one thing works.

---

*This review represents a second-pass, independent architectural assessment. It should be treated as one input, not as a prescription. The people building Aidos know their context better than any reviewer can. The value of this review is identifying the questions that most deserve deliberate answers before the first line of production code is committed.*
