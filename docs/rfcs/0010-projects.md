# RFC-0010: Projects

Status: Accepted

## Abstract

A Project is the top-level organizational container in Aidos. It is the unit of encapsulation, isolation, and distribution. Everything that belongs to an Aidos user—their sessions, workflows, code repositories, knowledge bases, decision history, and configuration—lives within a project. Projects are offline-first, Git-backed, and self-contained, making them portable and durable.

## Motivation

In traditional software, there are many organizational models: monorepos, microservices, cloud namespaces, databases, containers. Each choice reflects how the system partitions work and state.

Aidos needs a single, coherent organizational model that works from day one and scales as the platform evolves. The model must:

- **Encapsulate scope**: A user creates a project for a specific goal (build a web app, analyze data, plan a project). Everything for that goal lives in the project.
- **Enable isolation**: Projects should not interfere with each other. Permissions, state, and compute are project-local.
- **Support portability**: A project should be exportable as a single unit and importable elsewhere (another device, another user, future collaboration).
- **Maintain history**: Projects are Git-backed, so the entire decision and work history is preserved.
- **Scale from personal to collaborative**: A single-user project works the same way as a future shared project (import/export, not real-time sync).

The "project" concept solves these problems. It is inspired by how version control systems organize work (a repository is a self-contained unit), how build systems organize targets (each target is a complete specification), and how personal productivity tools organize tasks (each task is a container for subtasks, context, and decisions).

## Goals

1. **Define Project semantics**: What is a project? What does it contain? What are its boundaries?

2. **Establish project ownership and lifecycle**: Who owns a project? When is it created, used, and archived?

3. **Specify project portability**: How are projects exported and imported? What does that mean for state and history?

4. **Define project isolation**: How are projects kept separate? What cannot cross project boundaries?

5. **Establish project storage model**: How is a project's state persisted? What are the constituents (Git, SQLite, filesystem)?

6. **Clarify relationships to other concepts**: How do projects relate to sessions (RFC-0011), the Intent Graph (RFC-0012), resources (RFC-0013), artifacts (RFC-0014), and knowledge (RFC-0015)?

## Non-goals

This RFC does not specify project templates or project creation wizards. Those are UI concerns.

This RFC does not address multi-user collaboration within a single project. Single-user is the design assumption; multi-user would be handled through import/export and async workflows in future work.

This RFC does not specify the exact storage format or file layout. That is implementation detail.

This RFC does not mandate specific Git workflows (branching strategies, merge policies). Those are project-local decisions.

## Design

### What Is a Project?

A **Project** is a closed, self-contained workspace where a user pursues a specific goal using Aidos. It contains all necessary context, state, history, and configuration to understand and work on that goal without referring to other projects.

A project is:

- **Bounded**: It has clear edges. Everything relevant to the goal is inside; everything else is outside.
- **Persistent**: It survives shutdown and restart. State is not ephemeral.
- **Versioned**: Its history is preserved, primarily through Git. The user can see how the project evolved.
- **Portable**: It can be exported as a single unit and imported into another Aidos instance.
- **Offline**: It works entirely offline. Network is optional.
- **Git-first**: Version control is a first-class concept, not an afterthought.

### Project Contents

A project contains:

#### Sessions (RFC-0011)

Long-lived workers that perform tasks within the project. Sessions have identity, state, and history. Multiple sessions can exist within a project, coordinating through artifacts and events.

#### Intent Graph (RFC-0012)

A persistent, editable graph describing what should happen. It captures goals, sub-goals, plans, and status. The Intent Graph survives conversation history; conversation is ephemeral, intent is persistent.

#### Resources (RFC-0013)

Mutable project knowledge: architecture documents, coding standards, roadmaps, meeting notes, domain-specific knowledge. Resources are intended to be reused across many sessions. They evolve over time.

#### Artifacts (RFC-0014)

Immutable outputs: plans, patches, reports, transcripts, generated code, test results. Artifacts record provenance (which session created them, from which intent, at what time). They are append-only and auditable.

#### Git Repositories

One or more Git repositories containing versioned code and content. A project may have a monolithic repository or multiple repositories (via Git submodules or worktrees). Repositories are the source of truth for code and versioned resources.

#### Configuration

Project-wide settings:
- AI model preferences and API keys.
- Default permissions and security policies.
- Integration settings (MCP servers, webhooks, external tools).
- Session templates and defaults.
- Knowledge engine configuration.

#### Knowledge Indexes

Structured indices of project knowledge, produced by the Knowledge Engine (RFC-0015):
- Codebase indices (symbols, dependencies, call graphs).
- Git history indices.
- Natural language indices of resources and artifacts.
- Embeddings of code and documents.

#### Scheduler State

The Scheduler (RFC-0005) maintains project-local state:
- Subscriptions for all sessions.
- Pending timers and scheduled tasks.
- Event log.

### Project Lifecycle

#### Creation

A user creates a project. This involves:

1. Choosing a name and optional description.
2. Initializing a Git repository (or cloning an existing one).
3. Creating a SQLite database for project state.
4. Setting up initial configuration (AI preferences, security policy).
5. Optionally importing from a template (future feature).

After creation, the project is ready for sessions to be created and work to begin.

#### Active Use

A project is used over time. Sessions perform work, artifacts are created, resources evolve, Intent Graph is updated. The Event Bus (RFC-0004) coordinates activity. The Scheduler (RFC-0005) wakes sessions in response to events.

A project remains active as long as the user is working on its goal.

#### Dormancy

A project can be dormant (not actively used) but not deleted. Dormant projects consume minimal resources; they are not running sessions, but their state remains intact. A dormant project can be resumed at any time.

#### Export

A project can be exported. This creates a portable snapshot containing:
- The complete Git history.
- The SQLite database (sessions, intent graph, artifacts, metadata).
- All resources and configuration.
- Optionally, the complete filesystem state.

Exports can be:
- Local (saved to a directory or archive).
- Remote (pushed to a Git remote or cloud storage).
- Encrypted (future feature).

#### Archival

When a project is no longer used, it is archived rather than deleted. Archival means:
- The project is marked as archived.
- It does not appear in active project lists.
- Its state is preserved (it can be unarchived later).
- It may be moved to cold storage (less frequent backup).

#### Deletion

Deletion is rare and explicit. It removes the project entirely (including all Git history, artifacts, and state). This is destructive and should be rare.

### Project Isolation

Projects are isolated from each other:

#### Namespace Isolation

Each project has its own namespace for sessions, artifacts, resources, and configuration. Session IDs, artifact IDs, etc. are unique within the project only.

#### Storage Isolation

Each project has its own SQLite database and file storage. Sessions in one project cannot directly access another project's storage.

#### Permission Isolation

Permissions (capabilities in RFC-0003) are granted at project granularity. A session cannot gain permissions outside its project's scope.

#### Compute Isolation

Sessions in different projects run independently. Scheduling, CPU, and memory are isolated.

#### Knowledge Isolation

The Knowledge Engine (RFC-0015) maintains separate indices per project. A session can only query its own project's knowledge.

### Git as Project Spine

Every project is a Git repository. This provides:

1. **History**: Every change to Intent Graph, resources, and decisions is a commit.
2. **Branching**: Projects can explore alternative workflows in branches.
3. **Merge**: Branches can be merged, resolving conflicts.
4. **Export/Import**: A project is portable (it is just a Git repository).
5. **Audit**: The commit log is an immutable record of who did what and when.

Projects may have multiple Git repositories (for code, documentation, knowledge bases). These are coordinated via Git submodules or worktrees, but the project remains the logical unit.

### Project Configuration

Project configuration includes:

```
Project Config {
  name: String
  description: String?
  
  AI Configuration:
    default_model: ModelId
    model_preferences: Map<UsageType, ModelId>
    api_keys: SecretStore
    
  Security Policy:
    default_permissions: CapabilitySet
    session_permission_defaults: Map<SessionRole, CapabilitySet>
    
  Integration Configuration:
    mcp_servers: List<MCPServerConfig>
    webhooks: List<WebhookConfig>
    
  Session Defaults:
    default_timeout: Duration
    default_memory_limit: Bytes
    worker_isolation: "worktree" | "process" | "sandbox"
    
  Knowledge Engine Configuration:
    enabled_providers: List<ProviderType>
    embedding_model: ModelId
    indexing_schedule: Cron?
    
  Scheduler Configuration:
    max_concurrent_sessions: Int
    event_retention: Duration
}
```

### Relationships to Other Concepts

#### Sessions (RFC-0011)

Sessions belong to projects. A session is always in exactly one project. Its state, permissions, and resources are project-scoped. When a session is archived, it remains in its project's history.

#### Intent Graph (RFC-0012)

The Intent Graph is a project-level data structure. There is one Intent Graph per project. It persists across sessions. Multiple sessions can reference and modify the same Intent Graph.

#### Resources (RFC-0013)

Resources are project-scoped. They are mutable knowledge that persists across sessions. A session can read any resource in its project (subject to permissions); sessions typically cannot modify resources (that is a manual, explicit action).

#### Artifacts (RFC-0014)

Artifacts are project-scoped. They are immutable outputs created by sessions. Artifacts reference their creating session, the intent they satisfy, and parent artifacts (provenance).

#### Knowledge Engine (RFC-0015)

The Knowledge Engine indexes project-local knowledge: code, Git history, resources. It provides a unified, queryable view of project understanding. Queries are project-scoped.

#### Instruction Engine (RFC-0016)

Instructions (from AGENTS.md, CLAUDE.md, etc.) are project-scoped. The Instruction Engine discovers and merges instructions within a project, providing normalized instructions to sessions.

#### Scheduler (RFC-0005)

The Scheduler maintains project-local subscriptions, event logs, and scheduled tasks. Each project has its own scheduler state.

## Data Model (Conceptual)

```
Project {
  id: UUID                          # Unique project identifier
  name: String                      # Human-readable name
  description: String?
  created_at: Timestamp
  owner: UserId?                    # (Single-user MVP: implicit)
  
  storage: ProjectStorage {
    git_root: Path                  # Root directory (Git repository)
    database: DatabaseRef           # SQLite database
  }
  
  configuration: ProjectConfig      # (see above)
  
  state: ProjectState {
    active_sessions: Set<SessionId>
    archived_sessions: Set<SessionId>
    intent_graph: IntentGraphId     # Ref to RFC-0012
    resources: Map<ResourceId, Resource>    # Refs to RFC-0013
    artifacts: Map<ArtifactId, Artifact>    # Refs to RFC-0014
    knowledge: KnowledgeIndexRef    # Ref to RFC-0015
    instructions: InstructionSetRef # Ref to RFC-0016
  }
  
  metadata: Map<String, Any>?
}
```

## Lifecycle

### Creation Timeline

```
User creates project
  ↓
Initialize Git repository
  ↓
Create SQLite database
  ↓
Write initial configuration
  ↓
Create initial Intent Graph
  ↓
Project ready for sessions
```

### Active Timeline

```
Sessions created and woken by events
  ↓
Sessions perform work, create artifacts
  ↓
Resources evolve
  ↓
Intent Graph updates (user or AI-proposed)
  ↓
Knowledge Engine indexes codebase
  ↓
Decision history recorded in Git
```

### Export Timeline

```
User requests export
  ↓
Serialize all project state to export format
  ↓
Include Git history
  ↓
Optionally encrypt
  ↓
Optionally sign
  ↓
Export complete
```

### Import Timeline

```
User has exported project (archive or Git repo)
  ↓
Import into new Aidos instance
  ↓
Recreate Git repository
  ↓
Restore SQLite database
  ↓
Restore configuration
  ↓
Restore session history (archived sessions readable)
  ↓
Project ready to use (can resume sessions or create new ones)
```

## Examples

### Example 1: Solo Developer

A developer creates a project "Weather App":
- One Git repository with the app code.
- Sessions: one main session for feature development, occasional worker sessions for testing.
- Resources: architecture document, design decisions, API specification.
- Artifacts: design mockups, PR templates, test reports.
- Intent Graph: high-level goal (build a weather app), sub-goals (add forecast, add notifications), current status.

The developer can pause the project, come back weeks later, and resume. The Intent Graph reminds them of what they were working on; artifacts show what they've done.

### Example 2: Data Analysis

A researcher creates a project "Bird Migration Study":
- Git repository with analysis code and notebooks.
- Multiple sessions: one for data processing, one for visualization, one for statistical analysis.
- Resources: research notes, methodology document, data dictionary.
- Artifacts: data summaries, plots, preliminary findings.
- Knowledge Engine: indexes code, previous plots, prior analyses.

Sessions can coordinate through the Intent Graph: "goal: understand migration patterns → sub-goals: clean data, visualize, test hypotheses". The Knowledge Engine helps sessions find prior related work.

### Example 3: Future Collaboration

A team of researchers creates a shared project (future work):
- Project is exported by researcher A and imported by researcher B.
- Both continue adding sessions, resources, artifacts.
- Changes are merged through Git (with conflict resolution).
- Import/export happens asynchronously; no real-time collaboration.

## Security Considerations

### Project-Level Permissions

Permissions (RFC-0003) are granted at project granularity. A user grants a session permission to "read this project's resources" or "write to this project's Git". Permissions do not cross project boundaries.

### Secret Storage

Project configuration may contain secrets (API keys, credentials). These are stored encrypted and isolated per project. A session cannot access secrets from other projects.

### Audit Trail

Every change to project state (Intent Graph, resources, artifacts) flows through Git or the event log. The audit trail is immutable and project-local.

### Deletion Risk

Deleting a project is irreversible. This should require explicit user confirmation and possibly be logged or staged (e.g., archive first, delete after N days).

## MVP Scope

The MVP project model includes:

1. **Project creation and management**: Create, open, list, archive projects.
2. **Git backing**: Every project is a Git repository.
3. **Single-user ownership**: Projects belong to the user running Aidos.
4. **Storage and isolation**: Project state is isolated (separate databases, separate session namespaces).
5. **Export**: Projects can be exported as Git repositories.
6. **Sessions**: Projects contain and manage sessions.
7. **Basic configuration**: AI preferences, security defaults.

The MVP does not include:

- Project templates or wizards (future).
- Multi-user collaboration or sharing (future).
- Encrypted exports (future).
- Complex project hierarchies (future).

## Future Work

### Project Templates

Users should be able to create projects from templates:

```
"Web App Template": Includes common resources (architecture patterns),
                   starter Intent Graph, sample sessions.
"Data Analysis Template": Includes notebooks, plotting libraries config.
```

Templates would speed up project creation for common use cases.

### Project Hierarchies

In future versions, projects might have sub-projects or project groups. A research program could have many projects (one per experiment), organized under a parent program.

### Workspace

A "workspace" could group related projects. A user might have "Work" and "Personal" workspaces. This is organizational and does not affect isolation.

### Collaborative Projects

When multi-user support is added (future work), projects could have multiple owners. Collaboration would happen via:
- Import/export: Each user has a copy; changes are merged.
- Git: Multiple users push to shared repositories.
- No real-time sync (conflicts are resolved asynchronously).

### Project Analytics

Future versions could track project metrics: how many sessions, artifacts created, decisions made, time spent. This supports reflection and planning.

### Project Snapshots

In addition to archival, users might create snapshots: "save the project state as of this moment". Snapshots are immutable points in time useful for branching off alternatives or tracking major milestones.

### Project Dependencies

A project might depend on knowledge or artifacts from another project. The Knowledge Engine could support cross-project queries (with permission). This supports knowledge reuse across projects.

### Project Cloning

Clone a project to create a variant. Useful for "what if" exploration: "clone this project and try a different approach".

## Open Questions

- Should projects have access control within multi-user scenarios (future)? Should a user have read-only or read-write access to different projects?
- How should projects handle naming conflicts when exported and imported? Should there be a global namespace or is per-machine uniqueness sufficient?
- Should projects support different "levels of encapsulation"? (e.g., some cross-project sharing of knowledge, but not of state)
- How should the Intent Graph be initialized? Should projects offer templates for common goal structures?
