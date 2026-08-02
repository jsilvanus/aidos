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

## Major Engines

Aidos consists of several integrated engines:

```
┌─────────────────────────────────────────────────────┐
│                  Frontend (Android, Desktop, Web)   │
│              (RFC-0050, RFC-0051)                   │
└────────────────────┬────────────────────────────────┘
                     │ Runtime API (RFC-0052)
┌────────────────────▼────────────────────────────────┐
│            Headless Runtime (KMP)                   │
│                                                     │
│  ┌─────────────────────────────────────────────┐   │
│  │  Session Manager (RFC-0011)                 │   │
│  │  • Long-lived workers with state & perms    │   │
│  │  • Driver & worker roles                    │   │
│  └─────────────────────────────────────────────┘   │
│                     ↓                               │
│  ┌─────────────────────────────────────────────┐   │
│  │  AI Engine (RFC-0020)                       │   │
│  │  • LLM, embeddings, voice, vision, OCR      │   │
│  │  • Local models (RFC-0022)                  │   │
│  │  • Cloud fallback (RFC-0023)                │   │
│  │  • Model Providers (RFC-0021)               │   │
│  └─────────────────────────────────────────────┘   │
│                     ↓                               │
│  ┌─────────────────────────────────────────────┐   │
│  │  Tool Broker (RFC-0030)                     │   │
│  │  • Unified tool interface                   │   │
│  │  • Git (RFC-0032), Shell (RFC-0033)         │   │
│  │  • Filesystem (RFC-0034)                    │   │
│  │  • MCP (RFC-0031), Plugins (RFC-0060)       │   │
│  │  • Capability-based permissions (RFC-0003) │   │
│  └─────────────────────────────────────────────┘   │
│                     ↓                               │
│  ┌─────────────────────────────────────────────┐   │
│  │  Knowledge Engine (RFC-0015)                │   │
│  │  • Synthesizes understanding                │   │
│  │  • Multiple sources (files, Git, artifacts) │   │
│  │  • Semantic search & indexing               │   │
│  └─────────────────────────────────────────────┘   │
│                     ↓                               │
│  ┌─────────────────────────────────────────────┐   │
│  │  Intent Graph (RFC-0012)                    │   │
│  │  • Persistent goal representation           │   │
│  │  • Editable by sessions                     │   │
│  │  • Separate from execution                  │   │
│  └─────────────────────────────────────────────┘   │
│                     ↓                               │
│  ┌─────────────────────────────────────────────┐   │
│  │  Storage Backend (RFC-0040)                 │   │
│  │  • SQLite by default                        │   │
│  │  • Persistent state, logs, indexes          │   │
│  │  • Export/import projects (RFC-0041)        │   │
│  └─────────────────────────────────────────────┘   │
│                                                     │
└─────────────────────────────────────────────────────┘
                     ↓
              (projects, Git, tools)
```

## Key Concepts

**Projects** (RFC-0010): Top-level containers. Each project is a Git repository with its own:
- Intent Graph (goals you want to accomplish)
- Resources (architecture docs, standards, roadmap)
- Artifacts (outputs: code, analysis, documents)
- Storage (session state, logs, indexes)

**Sessions** (RFC-0011): Long-lived workers. Each session:
- Runs in a project
- Has a role (driver or worker)
- Has permissions (what it can access/do)
- Can be paused/resumed
- Produces artifacts

**Intent Graph** (RFC-0012): An editable, persistent representation of goals. Not code; just what you want to achieve. Sessions run to accomplish Intent Graph nodes.

**Resources** (RFC-0013): Mutable project knowledge. Architecture docs, coding standards, roadmap—whatever needs to be kept in sync as the project evolves.

**Artifacts** (RFC-0014): Immutable outputs with full provenance. When a session produces something (code, analysis, document), it's tracked with who made it, when, and what influenced it.

**Knowledge Engine** (RFC-0015): Synthesizes understanding by combining multiple sources:
- Your project files (Git history)
- Resources (docs you've written)
- Artifacts (previous outputs)
- External APIs (via MCP plugins)

**Instruction Engine** (RFC-0016): Discovers and merges instructions from:
- Your project (custom domain rules)
- Frameworks/languages (conventions)
- Aidos built-ins (safety guidelines)
→ Result: unified instruction set for the AI.

## Which RFC Should I Read Next?

### If you're **new to Aidos:**
1. [RFC-0000: Vision](docs/rfcs/0000-vision.md) — Why Aidos exists; the problem it solves.
2. [RFC-0001: Principles](docs/rfcs/0001-principles.md) — Ten core design principles.
3. [RFC-0010: Projects](docs/rfcs/0010-projects.md) — The top-level concept.

### If you're a **user** (want to use Aidos):
4. [RFC-0050: Android](docs/rfcs/0050-android.md) — First UI; workflows on mobile.
5. [RFC-0051: Desktop](docs/rfcs/0051-desktop.md) — Power-user tools and developer workflows.
6. [RFC-0099: Roadmap](docs/rfcs/0099-roadmap.md) — What's coming next.

### If you're **building on Aidos** (plugins, custom tools):
7. [RFC-0060: Plugin SDK](docs/rfcs/0060-plugin-sdk.md) — Extensibility: tools, models, knowledge sources.
8. [RFC-0030: Tool Broker](docs/rfcs/0030-tool-broker.md) — How tools work; how to integrate them.
9. [RFC-0031: MCP](docs/rfcs/0031-mcp.md) — Model Context Protocol for tool/data discovery.

### If you're **contributing to the runtime** (core development):
10. [RFC-0002: Runtime Architecture](docs/rfcs/0002-runtime.md) — High-level design of the engine.
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
- `/docs/rfcs/` — All 25+ RFCs
- `/ARCHITECTURE.md` — This file (your map)
- `/CLAUDE.md` — Development practices and AI assistance
- `/README.md` — Quick start
