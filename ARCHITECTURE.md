# Aidos Architecture Map

This document is your guide to understanding Aidos. Read this first, then dive into specific RFCs based on your interests.

## What is Aidos?

**Aidos is an offline-first, open-source AI Operating Environment.** It's a headless runtime (engine) that can run AI agents and tools on your device without requiring cloud services or internet connectivity. Think of it as a smarter, AI-aware replacement for your terminal, IDE, and knowledge base combined—but entirely under your control.

**Key principles:**
- **Offline-first**: Full capability without internet; cloud is optional.
- **Privacy-by-default**: Your data stays on your device unless you explicitly choose otherwise.
- **User-controlled**: Fine-grained permissions; you decide what each agent can do.
- **Open protocols**: Built on Git, MCP, and standards; no vendor lock-in.
- **Headless runtime**: Separate computation engine from UI; multiple frontends (phone, desktop, web) possible.

**What can Aidos do?**
- Run local AI models (LLMs, embeddings, voice, vision) on your device.
- Execute tools: Git, shell, filesystem, HTTP, and custom tools via plugins.
- Synthesize knowledge: Index your codebase, docs, and projects for semantic search.
- Manage workflows: Track goals in an editable Intent Graph; run sessions to accomplish them.
- Organize output: Create and track artifacts (code, documents, analysis) with full provenance.
- Extend via plugins: Add custom tools, models, knowledge sources, and more.

## How Aidos is layered

Everything depends on kernel contracts. Services depend on the kernel, never directly on each
other. This is the rule that keeps the dependency graph acyclic.

```
┌──────────────────────────────────────────────────────────────┐
│  Frontends — Android (RFC-0050), Desktop (RFC-0051), CLI      │
└───────────────────────────┬──────────────────────────────────┘
                            │  Runtime API (RFC-0052)
                            │  daemon on desktop, in-process on Android (RFC-0055)
┌───────────────────────────▼──────────────────────────────────┐
│  SERVICES                                                     │
│  Projects (0010)      Sessions (0011)     Intent Graph (0012) │
│  Content graph (0024) Knowledge (0015)    Instructions (0016) │
│  Model runtime (0020) Git (0032/0053)     Import/export (0041)│
├───────────────────────────────────────────────────────────────┤
│  KERNEL — every service depends on these; they depend on none │
│                                                               │
│  Scopes and identity (0054)     Capability manager (0018)     │
│  State store (0017/0040)        Effect broker (0030)          │
│  Execution graph (0019)         Durable executor (0009)       │
│  Agent loop (0008)              Event bus (0004)              │
│  Audit log (0037)               Budget ledger (0028)          │
├───────────────────────────────────────────────────────────────┤
│  CROSS-CUTTING                                                │
│  Platform profiles (0049) · Trust and taint (0027)            │
│  Errors (0029) · Concurrency (0007) · Retention (0056)        │
└───────────────────────────────────────────────────────────────┘
```

Note what this diagram does *not* claim. An earlier version drew a strict downward stack —
Sessions above the AI Engine above the Tool Broker above the Knowledge Engine — which asserted a
cycle, since the Knowledge Engine needs embeddings from the AI Engine and the Tool Broker sits
beside both rather than beneath them. There is no such stack. There is a kernel and there are
services.

## The centre: the agent loop

One cycle is the reason the rest exists (RFC-0008):

```
    ┌─────────────────────────────────────────────────────────┐
    │                                                          │
    ▼                                                          │
 select model ─▶ assemble prompt ─▶ [checkpoint] ─▶ call model │
 (RFC-0020)      (RFC-0025)         (RFC-0009)                 │
                                                     │         │
                                                     ▼         │
                                          validate schema      │
                                          resolve capability   │
                                          apply taint policy ──┤
                                          (0008, 0018, 0027)   │
                                                     │         │
                                                     ▼         │
                                          execute effect ──────┘
                                          [checkpoint]
                                          (0030, 0009)
```

Every step boundary is a checkpoint, which is what lets a Run survive Android evicting the
process mid-task and resume where it left off.

## Android first, and what that means

The primary use case is **making progress on Git projects, offline, from a phone**: reading,
understanding, planning, editing, reviewing, committing. Not running CI on a handset.

Android cannot offer a general shell, cannot spawn arbitrary interpreters, and grants only short
background windows. Rather than treating these as exceptions scattered through other documents,
the architecture makes them explicit (RFC-0049):

- **Platform profiles** declare what a device can do. Tools declare where they run.
- **Unavailable tools are never offered to the model**, so it cannot propose them and the user
  never sees a denial for something that could not have worked.
- **Projects declare requirements**, and unmet ones are reported when the project opens — before
  a session spends anything.
- **Work is portable**: a Run that could not run tests still produces a commit, and the record
  says what was skipped so a capable device can continue it.

The constraint improves the runtime rather than limiting it. Short execution windows force
checkpointed execution (RFC-0009); no subprocesses removes the largest class of sandbox escape;
and no worktree support leads to **treeless workers** that build commits directly against the
object database — cheaper and safer than a second checkout, on any platform.

## Key Concepts

**Scopes** (RFC-0054): **user** (device identity, model weights, secrets, plugins, MCP
registrations), **workspace** (grouping and shared defaults), **project** (all work). The
principle is *everything actionable belongs to a project* — not *everything is a project*.

**Projects** (RFC-0010): a Git repository plus runtime state in a Git-ignored `.aidos/`.
Project config expresses preferences and requests; it never carries secrets or authority,
because it arrives over the network.

**Sessions** (RFC-0011): long-lived workers. States are `CREATED → SLEEPING ⇄ RUNNING →
ARCHIVED`. Memory is bounded — a rolling summary plus facts and decisions, not an unbounded
transcript.

**Runs, Tasks, Attempts** (RFC-0019): the Execution Graph. Not a log written beside execution —
**it is the program**. The executor (RFC-0009) drives these rows, which is why recovery is a
query rather than a restore.

**Capabilities** (RFC-0018): security grants. Designation travels with authority: hierarchical
resources are reached through handles that resolve paths against their own root, and every other
exercise names its capability. The runtime never searches for an authority that would permit an
operation.

**Trust and taint** (RFC-0027): content carries an inbound trust level. A Run that has read
untrusted content operates under reduced authority for the rest of the Run. This, not prompt
formatting, is the answer to prompt injection.

**Content nodes** (RFC-0024): resources and artifacts unified. Mutability is a policy field, not
a type. Records and provenance are permanent; payloads are reclaimable (RFC-0056).

**Intent Graph** (RFC-0012): what you want, separate from how it was done. Deliberately a leaf —
build it late and small.

**Effects** (RFC-0030): every tool operation is `Read`, `Mutate`, `Egress`, or `Notify`, with a
mandatory preview for mutations and a declared recovery class for crash handling.

**Knowledge Engine** (RFC-0015): a query broker over pluggable providers, not a monolith.

**Instruction Engine** (RFC-0016): resolves AGENTS.md, CLAUDE.md, and similar into one
instruction set.

## Which RFC Should I Read Next?

### If you're **new to Aidos:**
1. [RFC-0000: Vision](docs/rfcs/0000-vision.md) — Why Aidos exists; the problem it solves.
2. [RFC-0001: Principles](docs/rfcs/0001-principles.md) — Ten core design principles.
3. [RFC-0049: Platform Capability Profiles](docs/rfcs/0049-platform-capability-profiles.md) — What Android-first actually means.
4. [RFC-0010: Projects](docs/rfcs/0010-projects.md) — The top-level concept.

### If you're a **user** (want to use Aidos):
4. [RFC-0050: Android](docs/rfcs/0050-android.md) — First UI; workflows on mobile.
5. [RFC-0051: Desktop](docs/rfcs/0051-desktop.md) — Power-user tools and developer workflows.
6. [RFC-0099: Roadmap](docs/rfcs/0099-roadmap.md) — What's coming next.

### If you're **building on Aidos** (plugins, custom tools):
7. [RFC-0060: Plugin SDK](docs/rfcs/0060-plugin-sdk.md) — Extensibility: tools, models, knowledge sources.
8. [RFC-0030: Tool Broker](docs/rfcs/0030-tool-broker.md) — How tools work; how to integrate them.
9. [RFC-0031: MCP](docs/rfcs/0031-mcp.md) — Model Context Protocol for tool/data discovery.

### If you're **contributing to the runtime** (core development):
10. [RFC-0008: Agent Loop](docs/rfcs/0008-agent-loop.md) — **The centre of the system. Read this first.**
10b. [RFC-0009: Durable Execution](docs/rfcs/0009-durable-execution.md) — How Runs survive eviction.
10c. [RFC-0002: Runtime Architecture](docs/rfcs/0002-runtime.md) — High-level design of the engine.
11. [RFC-0003: Security](docs/rfcs/0003-security.md) — Capability-based access control.
12. [RFC-0004: Event Bus](docs/rfcs/0004-event-bus.md) — How subsystems communicate.
13. [RFC-0005: Scheduler](docs/rfcs/0005-scheduler.md) — Session lifecycle and fairness.
14. [RFC-0011: Sessions](docs/rfcs/0011-sessions.md) — Long-lived workers.
15. [RFC-0020: AI Engine](docs/rfcs/0020-ai-engine.md) — Multi-model support.
16. [RFC-0015: Knowledge Engine](docs/rfcs/0015-knowledge-engine.md) — Synthesizing project understanding.

### If you care about **AI/models**:
- [RFC-0020: AI Engine](docs/rfcs/0020-ai-engine.md) — Reasoning, planning, multi-model support.
- [RFC-0021: Model Providers](docs/rfcs/0021-model-providers.md) — Abstraction for different model sources.
- [RFC-0022: Local Models](docs/rfcs/0022-local-models.md) — Offline-first; no cloud required.
- [RFC-0023: Remote Models](docs/rfcs/0023-remote-models.md) — Cloud models with privacy controls.

### If you care about **tools/integration**:
- [RFC-0030: Tool Broker](docs/rfcs/0030-tool-broker.md) — Unified tool interface.
- [RFC-0032: Git](docs/rfcs/0032-git.md) — Version control as a primitive.
- [RFC-0033: Shell](docs/rfcs/0033-shell.md) — Command execution with sandboxing.
- [RFC-0034: Filesystem](docs/rfcs/0034-filesystem.md) — File access with capability control.
- [RFC-0031: MCP](docs/rfcs/0031-mcp.md) — Third-party tool integration.

### If you care about **infrastructure/storage**:
- [RFC-0040: Storage](docs/rfcs/0040-storage.md) — SQLite backend, schema versioning.
- [RFC-0041: Export/Import](docs/rfcs/0041-export-import.md) — Project portability, backup, encryption.

## Reading Tips

- **RFCs are not specifications.** They're architectural documents explaining the *why* behind design decisions. Implementation details are deliberately omitted.
- **Each RFC is self-contained.** You don't need to read them in order, but cross-references (like "RFC-0003") point you to related concepts.
- **Abstract + Motivation sections** are your quickest read. Start there to see if you care about the RFC.
- **Design section** is where the architecture lives. This is what you'll refer back to.
- **Open Questions section** shows where the design is still being debated. These are good places to contribute.

## Next Steps

- **To try Aidos:** See [RFC-0050: Android](docs/rfcs/0050-android.md) or [RFC-0051: Desktop](docs/rfcs/0051-desktop.md).
- **To contribute:** Start with [RFC-0001: Principles](docs/rfcs/0001-principles.md) to understand the culture, then [CLAUDE.md](CLAUDE.md) for development practices.
- **To write plugins:** [RFC-0060: Plugin SDK](docs/rfcs/0060-plugin-sdk.md).
- **To understand the roadmap:** [RFC-0099: Roadmap](docs/rfcs/0099-roadmap.md).

---

**Repository structure:**
- `/docs/rfcs/` — All RFCs
- `/docs/decisions.md` — Architecture decision record: why it is this and not something else
- `/docs/decisions/` — Long-form analysis behind individual decisions
- `/docs/reviews/` — Architecture reviews
- `/ARCHITECTURE.md` — This file (your map)
- `/CLAUDE.md` — Development practices and AI assistance
- `/README.md` — Quick start
