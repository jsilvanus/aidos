# RFC-0002: Runtime

Status: Accepted 2026-08-03

## Abstract

The Aidos runtime is a headless, event-driven system that orchestrates AI-augmented work within projects. It is the central computation platform to which all frontends connect. The runtime manages sessions, coordinates with AI services, brokers access to external tools, and maintains project state.

## Motivation

Before defining how to build specific subsystems, we must establish the overall architecture—the topology of components, their responsibilities, and how they communicate. The runtime architecture must be:

- **Headless**: The runtime is independent of any frontend. Multiple UIs can drive the same runtime.
- **Event-driven**: The runtime reacts to events (user actions, timers, notifications, Git changes) rather than polling for work.
- **Long-lived**: Sessions persist across time, sleeping and waking as needed. The runtime is not stateless; it remembers.
- **Project-scoped**: The runtime manages projects, and everything within a project has clear boundaries.
- **Modular**: Subsystems (AI Engine, Tool Broker, Knowledge Engine, Storage) are pluggable and isolated from each other.

This RFC establishes that architecture at a high level, leaving implementation details to subordinate RFCs.

## Technology

The Aidos runtime is implemented in **Kotlin Multiplatform (KMP)**. This is a locked decision established in RFC-0000.

KMP provides:
- A single codebase that targets Android (JVM) and Desktop (JVM via Compose Multiplatform).
- Kotlin Coroutines for the async concurrency model (RFC-0007).
- SQLite via a KMP-compatible SQLite driver.
- Cross-platform filesystem and network abstractions.

Where native performance or OS-specific sandboxing is required (local model inference via llama.cpp, Whisper transcription, process sandboxing on Linux/macOS), the runtime uses native libraries accessed via JNI bindings. The boundary between KMP code and native libraries is kept narrow and explicit — native code is wrapped in thin Kotlin interfaces.

The frontend layer (Android UI, Desktop UI) is built on Jetpack Compose and Compose Multiplatform respectively. Frontends communicate with the runtime via the Runtime API (RFC-0052).

## Goals

1. **Define the major subsystems** within the runtime and their responsibilities.

2. **Establish the data flow** through the system: how events enter, how work flows through components, how results are stored.

3. **Clarify the distinction between runtime and frontends**, and how they communicate.

4. **Specify the project model** as the organizational unit for all work within the runtime.

5. **Establish the session model** as the execution unit for work.

6. **Define how the runtime interacts with external AI services, tools, and knowledge sources** without coupling to them.

## Non-goals

This RFC does not specify:
- The format of events (that is RFC-0004).
- The exact API between runtime and frontends (that is RFC-0052).
- Implementation details of storage (RFC-0040 covers storage).
- How to train or select models (RFC-0020 covers the AI Engine).
- How to integrate specific tools (RFC-0030 covers the Tool Broker).

## Design

### Overall Architecture

The Aidos runtime consists of the following major components:

```
┌─────────────────────────────────────────────────────────────┐
│                        Frontends                             │
│             (Android UI, Desktop UI, CLI, etc.)              │
└────────────┬──────────────────────────────────────────────────┘
             │ API Calls / Event Stream Subscriptions
             │
┌────────────▼──────────────────────────────────────────────────┐
│                   Aidos Runtime (Headless)                     │
│                                                                 │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐        │
│  │  Project     │  │  Session     │  │  Event Bus   │        │
│  │  Manager     │  │  Manager     │  │  (RFC-0004)  │        │
│  └──────────────┘  └──────────────┘  └──────────────┘        │
│                                                                 │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐        │
│  │  Intent      │  │  Artifact    │  │  Resource    │        │
│  │  Graph       │  │  Manager     │  │  Manager     │        │
│  │  (RFC-0012)  │  │  (RFC-0024)  │  │  (RFC-0024)  │        │
│  └──────────────┘  └──────────────┘  └──────────────┘        │
│                                                                 │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐        │
│  │  Knowledge   │  │  AI Engine   │  │  Tool Broker │        │
│  │  Engine      │  │  (RFC-0020)  │  │  (RFC-0030)  │        │
│  │  (RFC-0015)  │  └──────────────┘  └──────────────┘        │
│  └──────────────┘                                              │
│                                                                 │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐        │
│  │  Storage     │  │  Permission  │  │  Scheduler   │        │
│  │  Engine      │  │  System      │  │  (RFC-0005)  │        │
│  │  (RFC-0040)  │  │  (RFC-0003)  │  └──────────────┘        │
│  └──────────────┘  └──────────────┘                            │
│                                                                 │
└─────────────────┬──────────────────────────────────────────────┘
                  │ Integration Points
                  │
    ┌─────────────┼─────────────┬─────────────┐
    │             │             │             │
┌───▼─┐    ┌──────▼──┐    ┌─────▼────┐    ┌──▼────┐
│ Git │    │  Models  │    │   MCP    │    │Local  │
│ Ops │    │ (Claude, │    │ Servers  │    │Files  │
│     │    │ OpenAI, │    │          │    │       │
└─────┘    │ local)   │    └──────────┘    └───────┘
           └──────────┘
```

### Core Components

#### Project Manager

The Project Manager is responsible for:
- Creating, opening, listing, and deleting projects.
- Maintaining project metadata (name, description, created_at, etc.).
- Ensuring each project is backed by a Git repository.
- Managing project-level configuration and permissions.

A project is the container for all work. It contains:
- A Git repository — one working tree, with worker refs under `refs/aidos/**` (RFC-0053).
- A SQLite database for project-local state.
- One or more sessions.
- An Intent Graph.
- Resources and Artifacts.
- Configuration and secret storage (encrypted).

#### Session Manager

The Session Manager is responsible for:
- Creating, waking, sleeping, and archiving sessions.
- Maintaining session state and context.
- Managing session permissions and capabilities.
- Routing events to sessions and coordinating responses.

A session is a long-lived, pausable actor. Sessions:
- Persist across time (they are stored, not created anew each interaction).
- Can sleep and wake (they respond to events).
- Maintain context and memory (conversation history, task state, local variables).
- Operate under a set of explicit permissions and capabilities.
- Can be **reconstructed** from their decision history — what was attempted, in what order,
  under which authority. Not replayed (D1).

Sessions are the execution unit of Aidos. Work happens within sessions. Users interact with sessions through frontends.

#### Event Bus (RFC-0004)

The Event Bus is the central coordination mechanism. All asynchronous communication flows through it:
- User interactions (commands, input).
- Timer events.
- Filesystem changes.
- Git updates.
- MCP notifications.
- Session-to-session communication.
- External notifications (webhooks, etc.).

The Event Bus is fully described in RFC-0004. Key properties:
- Events are typed and versioned.
- Subscribers can filter events by type or topic.
- Events are persisted for replay and audit.
- The bus is in-process; distribution is a future concern.

#### Intent Graph (RFC-0012)

The Intent Graph is a persistent data structure that represents what the system should do:
- It is not conversation history; it is a structured representation of goals and plans.
- It is editable by both AI systems (which propose changes) and users (who approve or modify).
- It persists across sessions.
- It is versioned through Git.

The Intent Graph is the "what"; execution (how to achieve the intent) is the responsibility of sessions.

#### Content Graph (RFC-0024)

A single service manages all project content. Resources and artifacts are not separate
subsystems: both are `ContentNode`s distinguished by a mutability policy, with provenance edges
recording derivation. RFC-0013 and RFC-0014 are superseded.

- Mutable knowledge (architecture documents, standards, notes) is `VERSIONED` or
  `MUTABLE_LATEST` and versioned through Git.
- Outputs (patches, plans, transcripts, reports) are `IMMUTABLE` or `APPEND_ONLY`.
- Every node carries outbound labels (sensitivity, egress eligibility) and an inbound trust
  level (RFC-0027), assigned automatically by origin.
- Node records and provenance are permanent; payloads are reclaimable (RFC-0056).

Maintaining two parallel content models produced conflicting storage rules and two places to
enforce egress policy.

#### Agent Loop and Executor (RFC-0008, RFC-0009)

The component that turns model output into authorized effects, and the checkpointed executor
that drives Runs across process death. These are the centre of the runtime; every other
subsystem exists to serve or constrain them.

#### Knowledge Engine (RFC-0015)

The Knowledge Engine synthesizes project understanding from multiple sources:
- Git history.
- Codebase analysis (via tree-sitter, LSP).
- Build metadata.
- Resource documents.
- Custom knowledge providers.

It presents a unified, queryable view of project context that informs both users and AI agents.

#### AI Engine (RFC-0020)

The AI Engine orchestrates interactions with AI models:
- It is model-agnostic. It can work with LLMs, embeddings, vision, speech, and future model types.
- It is provider-agnostic. Multiple providers (Anthropic, OpenAI, local, etc.) can coexist.
- It handles rate limiting, retry logic, and error handling.
- It is subject to permissions (a session must have permission to query a model).

#### Tool Broker (RFC-0030)

The Tool Broker mediates access to external tools and systems:
- Git operations.
- Filesystem access (files, directories).
- Shell execution.
- MCP server integration.
- HTTP requests.

Tools are capability-based. A session must have permission to use a tool.

#### Storage Engine (RFC-0040)

The Storage Engine manages persistent state:
- SQLite for structured data (sessions, artifacts, metadata).
- Git for versioned content (Intent Graph, resources, decision history).
- Filesystem for files and cached knowledge.
- Optional encryption at rest for secrets.

#### Permission System (RFC-0003)

The Permission System enforces capability-based access control:
- Each session has an explicit capability set.
- Every tool access is checked against capabilities.
- Permissions are auditable and revocable.
- No implicit trust; everything is explicit.

#### Scheduler (RFC-0005)

The Scheduler wakes sessions based on events:
- Timers (fixed intervals, specific times).
- Event subscriptions (filesystem changes, Git updates, user input).
- External notifications.

Sessions are dormant by default. The scheduler wakes them when relevant events occur.

### Data Flow

A typical workflow flows through the runtime as follows:

1. **User Input**: A frontend sends a command or request to the runtime (e.g., "write a test for this function").

2. **Event Generation**: The runtime generates an event (UserCommand) and posts it to the Event Bus.

3. **Session Wake**: The scheduler wakes the relevant session (or creates a new one).

4. **Context Assembly**: The session loads its state from storage, assembles the Intent Graph, and queries the Knowledge Engine for relevant context.

5. **AI Consultation**: The session queries the AI Engine with the user's intent and context. The AI proposes a plan or action.

6. **Intent Graph Update**: The session updates the Intent Graph (with user approval, if required).

7. **Tool Invocation**: The session invokes tools via the Tool Broker (subject to permissions): creating files, running commands, querying Git.

8. **Artifact Generation**: The session produces artifacts (patches, reports, transcripts).

9. **Storage**: The session commits its work: artifacts are stored, the Intent Graph is updated in Git, session state is persisted to SQLite.

10. **Event Broadcast**: The session broadcasts completion events for other sessions or frontends to observe.

11. **Session Sleep**: The session becomes dormant, waiting for the next event.

### Isolation and Boundaries

The runtime enforces strong isolation:

- **Project Isolation**: Projects do not share storage or state. Each project is independent.
- **Session Isolation**: Sessions within a project are isolated. They can communicate through artifacts and events, but they do not share memory.
- **Permission Isolation**: Sessions operate under their capability set. A session without shell permission cannot run commands.
- **Process Isolation**: Tool execution (especially shell commands) should run in isolated contexts to prevent cross-contamination.

### Frontends

Frontends are thin clients that communicate with the headless runtime via a stable API:

- They send commands and queries to the runtime.
- They subscribe to event streams from the runtime.
- They display state and results to the user.
- They collect user input and forward it to the runtime.

Frontends are stateless. All state lives in the runtime. Multiple frontends can simultaneously interact with the same runtime.

## Data Model

### Project

```
Project {
  id: UUID
  name: String
  description: String
  created_at: Timestamp
  root_dir: Path                    # Git repository root
  metadata: Map<String, Any>
  permissions: PermissionSet
}
```

### Session

```
Session {
  id: UUID
  project_id: UUID
  name: String
  created_at: Timestamp
  last_active: Timestamp
  state: SessionState (active | sleeping | archived)
  context: SessionContext           # Loaded state, memory, current work
  capabilities: CapabilitySet
  history: EventLog                 # All events this session has processed
  artifacts: List<ArtifactId>
}
```

### Event (RFC-0004)

```
Event {
  id: UUID
  type: EventType
  timestamp: Timestamp
  source: ComponentId
  payload: Map<String, Any>
  topic: String?                    # For filtering
  metadata: Map<String, Any>
}
```

### Artifact (RFC-0014)

```
Artifact {
  id: UUID
  project_id: UUID
  created_at: Timestamp
  creator: SessionId | UserId
  content_type: String
  content: Bytes
  metadata: Map<String, Any>
  provenance: List<ArtifactId>      # IDs of artifacts this was derived from
  content_hash: String              # For deduplication and integrity
}
```

### Resource (RFC-0013)

```
Resource {
  id: UUID
  project_id: UUID
  name: String
  path: RelativePath                # Path within project
  content: String
  updated_at: Timestamp
  updated_by: SessionId | UserId
  git_commit: String                # Commit that last modified this
}
```

## Security

The runtime enforces security at multiple levels:

1. **Capability Tokens**: Sessions receive explicit capabilities that enable specific operations.
2. **Permission Checks**: Every tool access is checked against capabilities before execution.
3. **Audit Logging**: All operations are logged with timestamp, actor, action, and result.
4. **Isolation**: Sessions and processes are isolated to prevent cross-contamination.
5. **Secret Storage**: Credentials and API keys are stored encrypted at rest.

RFC-0003 elaborates on the security model.

## MVP

The MVP is ordered so that the execution kernel is proven **before** any AI is involved
(RFC-0099). If a Run cannot survive `kill -9` deterministically, no amount of model quality
matters, and every later bug is misattributed to the model.

Kernel first, with no AI and no tools:

1. **Scopes and identity** (RFC-0054): user and project scope; UUIDv7.
2. **State store**: the schema, executed in CI, with migrations.
3. **Capability manager** (RFC-0018): handles, named exercise, attenuation, revocation epochs.
4. **Execution graph and checkpointed executor** (RFC-0019, RFC-0009).
5. **Audit log** and **budget ledger** (RFC-0028).
6. **Project lock** (RFC-0055) and crash recovery.

Then the first vertical slice:

7. **Agent loop** (RFC-0008) with one remote provider adapter.
8. **Prompt construction** (RFC-0025) with escaping and redaction.
9. **Trust and taint** (RFC-0027).
10. **Filesystem and Git tools** (JGit, RFC-0053) with typed effects and preview.
11. **CLI frontend** over the Runtime API (RFC-0052).

Then the offline proof: local model, local embeddings, basic knowledge index, routing policy —
validated on a mid-range phone in airplane mode before any UI work begins.

The MVP does not include:
- Plugin host (MCP is the v1 extension mechanism; RFC-0043).
- Shell on MOBILE (it does not exist there; RFC-0049).
- Full Intent Graph DAG (a task description suffices).
- Multi-device sync or collaboration.

## Future Work

### Distribution

The MVP is single-machine. Future versions may support:
- Remote execution (sessions on remote machines under user control).
- Decentralized sync (Git-based project sync across devices).
- Peer-to-peer communication.

### Advanced Knowledge Engine

The MVP has basic knowledge synthesis. Future versions should add:
- Semantic indexing of code and documents.
- Natural language indexing.
- Cross-project knowledge graphs.
- Codebase-specific LLMs (fine-tuned on project context).

### Plugins and Extensions

A plugin system should allow users to:
- Add custom tools.
- Extend the AI Engine with new model providers.
- Add custom knowledge providers.
- Customize schedulers and event handling.

### Streaming and Real-time

The MVP uses request/response. Future versions should support:
- Streaming responses from the AI Engine.
- Real-time collaborative editing.
- Live updates from tools (tail -f, watch, git monitor).

### Persistence and Replay

A core design goal is to support full session replay. Future work should implement:
- Complete session history serialization.
- Replay engine that can step through a session's decisions.
- Time-travel debugging (query what the system knew at time T).

### Multi-modal Workflows

The MVP focuses on text. Future versions should natively support:
- Voice input (STT) and output (TTS).
- Image handling (vision, OCR).
- Multimodal AI models.
- Annotation and markup of media.
