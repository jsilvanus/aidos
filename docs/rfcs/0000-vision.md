# RFC-0000: Vision

Status: Accepted 2026-08-03

## Abstract

Aidos is an offline-first AI Operating Environment designed to make AI agents practical, controllable personal tools under user governance. It is neither a chat application nor a coding assistant wrapper, but rather a platform architecture that can accommodate multiple problem domains and multiple forms of intelligence.

## Motivation

Current AI tooling falls into two categories: cloud-dependent applications that lock users into proprietary systems and single-vendor ecosystems, or chatbot interfaces that treat AI as a conversational turn-taking system. Neither model suits the long-term reality we are entering—a world in which AI reasoning is embedded across productivity workflows, local computation is viable, and users retain meaningful control over their data and tools.

Aidos is built on the observation that individuals need infrastructure, not just applications. This infrastructure must be:

- **Resilient to vendor change**: The architecture should outlive any particular LLM provider, model class, or deployment model. Today's leading AI services may be obsolete in five years. The runtime must remain relevant.
- **Independent of connectivity**: Users should work offline without compromising capability. Network should enhance the system, not enable it.
- **Governed by users, not platforms**: The user should own their execution environment, their data, their workflows, and their decision history. Lock-in and forced cloud dependency are failures of governance.
- **Generic across problem domains**: The initial focus is coding. But the architecture must accommodate personal productivity, research, planning, life management, and workflows we cannot yet imagine, without requiring redesign.

The name Aidos reflects this philosophy. In Greek philosophy, Aidos represents propriety and respect for natural order. Here, it means respecting the user's agency and maintaining the integrity of a system designed to serve, not to manipulate or constrain.

## Goals

1. **Establish a headless, event-driven runtime** that serves as the computational substrate for multiple frontends and use cases.

2. **Define a model-agnostic AI Engine** capable of orchestrating many forms of intelligence—LLMs, embeddings, vision, speech, and future model classes—without architectural changes.

3. **Make offline-first computation the default**, treating network access as a deliberate feature rather than a requirement.

4. **Embed version control as a primitive**, making Git a core abstraction rather than an external tool.

5. **Make permissions explicit and capability-based**, so users understand and control what workloads can access.

6. **Design for long-term transparency and auditability**, with session replay and decision logging built into the architecture from the start.

7. **Create a platform for multi-domain workflows**, where the same underlying architecture supports coding, productivity, planning, research, and future applications.

## Non-goals

This RFC does not define how to implement the runtime. Implementation details belong in subordinate RFCs (Runtime, Storage, AI Engine, etc.).

This RFC does not address collaboration workflows. Aidos is single-user by design. Multi-user scenarios are handled through import/export and async patterns, not through built-in real-time collaboration.

This RFC does not mandate specific technology choices. Kotlin, SQLite, Git, and Android are locked decisions, but this document states the architectural rationale, not the toolchain.

This RFC does not define UI. The runtime is headless. Frontends—Android, desktop, CLI, or embed—are clients that discover and invoke runtime capabilities.

## Design

### A New Model: The AI Operating Environment

Aidos reconceives what an "AI application" should be. Rather than a chatbot or a code completion service, it is an operating environment where:

- **Sessions** are the execution unit. A session is a long-lived, pausable actor that maintains state, context, and decision history. Sessions wake because of external events—user interaction, timers, Git changes, MCP notifications—and perform work in response.

- **The Intent Graph** separates intent (what should happen) from execution (how it happens). This graph is persistent, auditable, and editable. LLMs may propose changes to the graph, but users retain veto power.

- **Projects** are the organizational unit. Everything—sessions, intent graphs, resources, artifacts, knowledge, Git repositories, configuration—lives within a project. A project is a closed system that encapsulates purpose and context.

- **Resources** are mutable project knowledge: architecture documents, coding standards, roadmaps, notes, decisions. They form the persistent context that informs every session.

- **Artifacts** are immutable outputs: plans, patches, transcripts, screenshots, reports. They are connected through provenance, making the lineage of work auditable.

- **Knowledge Engines** synthesize project understanding from many sources: Git history, tree-sitter parsing, LSP information, build metadata, custom instruction files. This unified knowledge base informs both the user and AI agents.

- **The Event Bus** is the circulatory system. Timers, filesystem events, Git changes, MCP notifications, and user interactions all flow through an event system that wakes dormant sessions and coordinates work.

This model is fundamentally different from a conversation. Conversation is stateless and ephemeral. Aidos is stateful and persistent. The user is not "talking to" an AI; they are orchestrating a system that includes AI as a component.

### Offline-First as an Architectural Primitive

Offline-first is not merely a feature or a fallback. It is an architectural constraint that shapes everything else. Because the runtime must work without internet:

- All essential computation happens locally. Coordination with remote AI services is synchronous, explicit, and gated by permissions.
- State is kept in local storage (SQLite). Sync is an explicit operation, not a background service.
- Sessions do not await network calls in their main loop. Work can proceed with degraded capability.
- The architecture must support both local and remote models, and permit clean switching between them.

This constraint forces a clearer separation of concerns. It prevents the naive pattern of "sync everything to the cloud and trust that it will work," which is the path to lock-in and loss of user control.

### Git-First

Git is not an add-on. It is a first-class primitive in the model. Projects are Git repositories. Sessions frequently work inside isolated Git worktrees. Decision history, intent changes, and resource updates all flow through Git.

This choice has several consequences:

- The version control history becomes a permanent record of the project's evolution and reasoning.
- Merge conflicts and branching workflows are natural to Aidos rather than foreign.
- Export and backup are trivial: the entire project is a Git repository.
- Collaboration (when it exists in future versions) is mediated by Git, not by real-time collaboration APIs.

### Vendor Independence

The runtime should remain useful regardless of which LLM provider is dominant in a given year. This means:

- The architecture assumes no particular model class. Aidos can orchestrate LLMs, embeddings, vision, speech, symbolic reasoning, or hybrid systems.
- The AI Engine is pluggable. Model providers are implementations of a standard interface, not baked into the architecture.
- Sessions should not depend on a specific provider's API or capability. If a provider becomes unavailable, the session may degrade but should not break entirely.

This is not idealism; it is pragmatism. The foundation should be robust to the inevitable shift in the AI landscape.

### User Governance

"User control" in Aidos means more than a privacy toggle. It means:

- Users own the runtime. It runs on their device.
- Users own the data. It lives in local storage.
- Users own the decisions. The Intent Graph is editable. LLMs advise; users decide.
- Users own the history. Sessions and artifacts are preserved for replay and audit.
- Users own the extensions. The plugin system allows custom tools without requiring platform approval.

Implicit trust is not permitted. Every capability that a session uses requires explicit permission. If a session needs to run shell commands, write files, or query a remote API, the permission must be granted and recorded.

## Data Model

Vision does not specify data structures, but it does constrain them:

- A **Project** contains a directory tree anchored in Git. Within this tree live:
  - A SQLite database (for sessions, intent graph, artifacts, metadata).
  - One or more Git worktrees (for coding and versioned content).
  - Resource files (markdown, configuration, instruction files).

- **Sessions** are first-class entities with persistent identity, state, and history. They may wake and sleep many times.

- **The Intent Graph** is a persistent data structure (editable, serializable, queryable). It is not conversation history; it is a structured record of goals and plans.

- **Artifacts** are immutable once created. They carry metadata: creation time, creator, provenance, content hash.

- **Knowledge** is indexed and queryable. Providers contribute their views; consumers query the unified index.

## Security

Vision establishes that Aidos uses capability-based security:

- Sessions operate under explicit, limited permissions.
- Secrets (API keys, credentials) are stored with encryption at rest.
- Permissions are visible to the user and auditable.
- Revocation is immediate and certain.

These principles are elaborated in RFC-0003 (Security).

## MVP

The MVP demonstrates the core model:

1. A Kotlin Multiplatform runtime (Android and desktop focus).
2. SQLite for local storage.
3. A long-lived session that performs coding tasks.
4. An intent graph that persists across sessions.
5. Integration with a single LLM provider (Claude API).
6. Offline capability for non-model tasks (file I/O, Git, filesystem operations).
7. A basic permission model for file access and shell execution.
8. Export of the complete project as a Git repository.

The MVP does not require:
- Multiple AI providers (one will suffice).
- Multi-user workflows.
- Advanced knowledge engine features (basic Git parsing is enough).
- Plugin SDK.
- Desktop or mobile UIs (headless is acceptable for MVP).

## Future Work

### Multi-Model Orchestration

As the AI ecosystem matures, Aidos should seamlessly orchestrate different model types—using embeddings for semantic search, vision for image understanding, speech for audio interaction—within a single coherent Intent Graph.

### Local Model Support

As local inference becomes more capable, Aidos should make it trivial to run models locally for privacy-sensitive workloads or when offline.

### Knowledge Engines

The MVP will have basic knowledge synthesis. Future versions should integrate more sophisticated knowledge providers: semantic indexing, codebase analysis, natural language indexing of decisions and resources.

### Instruction Ecosystem

Aidos should natively support instruction files from existing ecosystems (AGENTS.md, CLAUDE.md, .cursor/rules, GitHub Copilot instructions) and extend them as needed.

### Replay and Temporal Queries

The design goal is to support full session replay, allowing users to step through the reasoning of a past session or to query "what did the system know at time T?"

### Plugins and Extensions

A plugin system should allow users to add custom tools, knowledge providers, and instruction sources without modifying core Aidos.

### Collaboration

When multi-user support is added, it will be mediated through Git and async patterns, not through real-time protocol changes.
