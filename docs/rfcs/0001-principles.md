# RFC-0001: Principles

Status: Draft — body not audited against settled decisions (see docs/decisions.md)

## Abstract

Ten architectural principles guide all design decisions in Aidos. These principles resolve tensions between competing goals, clarify what Aidos is not, and ensure that decisions made today remain sensible years from now as the platform evolves.

## Motivation

An architecture is not a static snapshot of a system; it is a set of constraints and value judgments that guide decisions made by many contributors over time. Without clear principles, designs drift toward what is easiest in the moment rather than what is best for the long term.

Aidos operates in a domain where pressures are strong to make short-sighted choices:

- **Connectivity pressure**: It is easier to assume users have internet and offload computation to the cloud. Offline-first requires discipline.
- **Vendor lock-in pressure**: Adopting a specific vendor's SDK feels convenient. Vendor independence requires abstraction and design discipline.
- **Centralization pressure**: A centralized server makes some coordination problems trivial. Decentralization and local-first require more careful thought.
- **Feature pressure**: Every feature request can seem essential. Without clear principles, scope expands without limit.

These ten principles exist to anchor decision-making when pressures mount. They are not perfect or universally optimal, but they are chosen to reflect the vision of Aidos as expressed in RFC-0000.

## Goals

Articulate the core value judgments that Aidos embodies, so that:

1. Decisions can be made consistently across different subsystems and over time.
2. Tradeoffs are explicit. When a principle conflicts with convenience, the tension is visible.
3. Reviewers and future contributors understand why certain choices are made.
4. Non-goals are clear. Aidos deliberately chooses not to be certain things.

## Non-goals

This RFC does not mandate specific implementations. It establishes principles, not algorithms or APIs.

This RFC does not rank principles by priority. All ten are foundational. When principles conflict in a specific scenario, the resolution depends on context and concrete design tradeoffs discussed in subordinate RFCs.

## Design

### The Ten Principles

#### 1. Offline First

The runtime operates fully offline. Network access is a feature for sync and remote execution, not a requirement.

**Rationale**: Users should be able to perform productive work on trains, planes, and places without internet. This requires the runtime to maintain full local capability. It also protects against connectivity interruptions and network latency that plague cloud-dependent systems.

Offline-first is not a luxury; it is a structural requirement that shapes everything else. It means computation happens locally by default. Remote AI services are integrated through a clean interface, not as core dependencies. It means state lives in local storage, not in cloud databases. Sync is explicit, not automatic.

**Implications**: The runtime must include a local storage engine (SQLite, not cloud APIs). Sessions must be designed to work in disconnected mode. Work can proceed offline; some features may degrade when offline, but core functionality persists.

**Tradeoff**: Offline-first rules out certain convenience features that depend on cloud state. Realtime collaboration, for instance, is incompatible with offline-first; instead, Aidos uses import/export and Git-based workflows.

#### 2. User Control

Users own their data, their agents, and their execution environment. No lock-in. No forced cloud dependency.

**Rationale**: The purpose of Aidos is to serve the user, not to serve the platform. This means users must have meaningful control over their environment, not fake control via limited toggles in a settings panel.

User control has several concrete implications: Users own the execution environment (the runtime runs on their device, not someone else's cloud). Users own the data (it lives locally, encrypted at rest if they choose). Users own the workflow history and decisions. Users can export their project and run it elsewhere. Users can inspect and modify their sessions and intent graphs.

**Implications**: The runtime must be open source and runnable by users. The platform must support export. The platform must be transparent about what is stored where.

**Tradeoff**: Supporting user control is more work than a centralized cloud service. Some features are harder when you cannot assume cloud state.

#### 3. Explicit Permissions

Every capability requires explicit user consent. Permissions are visible, auditable, and revocable.

**Rationale**: A system that silently grants broad access is a betrayal of trust. AI agents are powerful; they should not run under implicit blanket permission.

Explicit permissions means:
- A session that wants to run shell commands must have shell permission granted by the user.
- A session that wants to write to the filesystem must have filesystem write permission.
- A session that wants to query a remote API must have network permission.
- Permissions can be scoped (filesystem access to a specific directory, not the entire system).
- Permissions can be revoked at any time.
- Permission grants and revocations are logged and auditable.

**Implications**: The permission model must be designed into the runtime from day one, not added later. Sessions operate under a capability set, not under "user" identity.

**Tradeoff**: Explicit permissions slow down the user experience slightly. A single "allow all" button is simpler than choosing specific permissions. Aidos rejects this tradeoff for the sake of security.

#### 4. Headless Runtime

The core runtime is separate from UI. Multiple frontends can drive the same engine.

**Rationale**: UI changes rapidly. Runtime semantics should be stable. By decoupling runtime from UI, we ensure that:
- The runtime can evolve independently of any particular frontend.
- Multiple frontends can coexist (Android, desktop, CLI, web, embed).
- The runtime can be tested and reasoned about without UI concerns.
- Future frontends (VR, AR, speech-first interfaces) can be added without runtime changes.

**Implications**: The runtime is a headless service. It provides a stable API (RPC, streaming, events). Frontends are clients that call this API. State and computation flow through the runtime; UI is a thin rendering layer.

**Tradeoff**: A tightly coupled UI-and-runtime system can be simpler for a single platform. Headless requires more API design work.

#### 5. Events Over Polling

Subsystems communicate via event streams. No busy-waiting, no frequent database queries.

**Rationale**: Polling is inefficient and introduces latency. Events are the natural model for an interactive system. When something changes, interested parties should be notified, not forced to check repeatedly.

Events over polling means:
- Sessions wake because of events (user input, timers, Git changes, MCP notifications), not because a scheduler queries "is there work to do?"
- Subsystems broadcast state changes; others subscribe.
- The event bus is a first-class architectural component (RFC-0004).

**Implications**: The runtime must include an event system. Subsystems are reactive, not polling-based.

**Tradeoff**: Event systems are more complex than polling. But the efficiency gain and natural responsiveness justify the complexity.

#### 6. Explainability

AI reasoning steps are logged and inspectable. Users understand why agents made decisions.

**Rationale**: AI systems are opaque. Aidos should reduce that opacity. When a session proposes a change to the Intent Graph, users should be able to understand the reasoning that led to that proposal.

Explainability means:
- Reasoning steps are logged (e.g., LLM tokens, embeddings, search results).
- The chain of reasoning can be inspected and replayed.
- Decisions are auditable.
- The Intent Graph itself documents the user's intent and the system's understanding.

**Implications**: Sessions must maintain detailed logs. Artifacts must carry provenance. The UI must expose reasoning, not hide it.

**Tradeoff**: Logging reasoning adds overhead and storage requirements. It is worth the cost for transparency.

#### 7. Open Protocols

Use open standards (Git, MCP, JSON) over proprietary formats.

**Rationale**: Proprietary formats lock users in. Open protocols enable interoperability and long-term portability.

Open protocols means:
- Aidos uses Git for version control, not a proprietary versioning system.
- Aidos uses the Model Context Protocol (MCP) for tool integration, not a custom protocol.
- Aidos exports and imports via standard formats (JSON, Git, etc.), not proprietary archives.
- Instructions and configuration use standard formats (Markdown, JSON, YAML).

**Implications**: When integrating external systems, prefer open standards. When creating new protocols, make them open-source and well-documented.

**Tradeoff**: Open protocols may be less convenient than purpose-built proprietary ones. But the long-term portability and ecosystem benefits outweigh the short-term convenience.

#### 8. Git First

Project state, workflows, and decision history live in Git. Version control is central to the platform.

**Rationale**: Git is a proven, decentralized version control system. By making Git central, Aidos gains several benefits:
- Decentralized storage and sync (no central server required).
- A proven merge model for handling conflicts.
- A complete audit trail (every change is a commit with a message and author).
- Interoperability with existing developer tools.
- Portability (a project is just a Git repository).

**Implications**: Every project is a Git repository. Important state (Intent Graph changes, resource updates) flows through Git commits. Workers build commits directly against the object database rather than in a second checkout (RFC-0053 treeless workers), because the first platform has no `git worktree` and no room for one.

**A clone is not the whole project** (D2). Git carries content and history; sessions, artifacts, capabilities, and the audit trail live in a Git-ignored `.aidos/` beside it. Moving a project *with* its history is export/import (RFC-0041), which moves the directory. An earlier version of this document said export was trivial because it was a clone — that was wrong, and it mattered, because it is the sentence someone would have designed against.

**Tradeoff**: Not all state fits naturally into Git. Some state (session state, temporary queues) belongs in local storage. Aidos uses both.

#### 9. Capability-Based Security

Access to resources is granted via capabilities. No global permissions. Revocation is immediate and certain.

**Rationale**: Global permissions (e.g., "user has shell access") are coarse-grained and hard to revoke cleanly. Capabilities are fine-grained and revocable.

Capability-based security means:
- Sessions receive explicit capabilities (tokens) that grant access to specific resources.
- A capability can be revoked immediately without affecting other capabilities.
- Capabilities can be delegated (a session can pass a capability to a worker).
- There is no global "admin" mode that overrides all checks.

**Implications**: The permission model uses capabilities, not role-based access control. Sessions are not identified by user; they are identified by their capability set.

**Tradeoff**: Capability-based systems are more complex than simple permission checks. But they are more secure and more fine-grained.

#### 10. Everything Is a Project

Projects are the unit of organization. Users, sessions, artifacts, knowledge, and workflows exist within projects.

**Rationale**: A coherent unit of organization simplifies reasoning about scope, permission, and isolation. A project is a closed system: it has its own storage, its own Git repository, its own Intent Graph, its own set of sessions.

Everything is a project means:
- When you start work on a goal, you create a project.
- Sessions live within projects, not globally.
- Resources are project-scoped.
- Artifacts are project-scoped.
- Knowledge is project-scoped.
- Permissions are granted at project granularity.

**Implications**: The data model is organized around projects. Each project is independent. Projects can export and import, but they do not share storage or state.

**Rationale**: This model fits personal use — one person, many projects — which is what Aidos is for. Working with other people is Git's job (D16).

## Data Model

Principles do not specify data structures, but they constrain them:

- Projects are Git repositories rooted in a specific directory.
- Sessions are scoped to projects.
- Storage is local and encrypted at rest (optional).
- Capabilities are tokens or descriptors that grant access to resources.
- State changes flow through Git commits or local database transactions.

## Security

Principles 3, 9, and aspects of 8 establish security constraints:

- Permissions are explicit and fine-grained.
- Capabilities are the access control model.
- Git provides an audit trail.
- Secrets are encrypted at rest.

These are elaborated in RFC-0003 (Security).

## MVP

The MVP demonstrates adherence to all ten principles:

1. **Offline First**: A coding session that works entirely offline.
2. **User Control**: Open-source runtime, local storage, no cloud lock-in.
3. **Explicit Permissions**: Session requests permission to access files or run shell commands.
4. **Headless Runtime**: A daemon that serves API requests from CLI or UI clients.
5. **Events Over Polling**: Sessions wake on timer events, file events, user input.
6. **Explainability**: LLM reasoning steps are logged and inspectable.
7. **Open Protocols**: Uses Git, JSON, and standard Markdown.
8. **Git First**: Project is a Git repository; Intent Graph changes are commits.
9. **Capability-Based Security**: Sessions operate under explicit permission tokens.
10. **Everything Is a Project**: Each coding task is a project with its own session and Intent Graph.

## Future Work

As Aidos evolves, these principles should remain stable. Future work that might challenge principles and require re-evaluation:

- **Collaboration**: **not planned** (D16). Aidos is single-user and **Git is the collaboration
  tool**. This is a closed question, not future work — "future work" is something a contributor
  can reasonably design toward, carrying identity fields, conflict hooks and a
  whose-change-is-this notion, and paying for them permanently. RFC-0046 reserves identity
  fields; reserving them is the entire commitment, and nothing should be built on the
  expectation that more is coming.
- **Real-time collaborative editing**: incompatible with offline-first, and not a direction
  this project will take. Named here so nobody designs around the possibility.
- **Distributed compute**: If Aidos adds support for distributed workers across multiple machines, principle 4 (headless) is stressed but not broken.
- **Privacy-respecting analytics**: Principle 2 (user control) suggests analytics should be opt-in and transparent. This remains true as the system grows.

The principles are meant to be robust to these challenges, not to be overturned by them.
