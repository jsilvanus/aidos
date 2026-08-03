# RFC-0054: Scope Model — User, Workspace, Project

Status: Accepted 2026-08-03

## Abstract

This RFC defines the three scopes in which Aidos state lives: **user**, **workspace**, and
**project**. It replaces "everything is a Project" with "everything actionable belongs to a
project," and assigns every object class to exactly one scope.

## Motivation

Six RFCs already use a `workspace` scope that no RFC defines (RFC-0010, RFC-0033, RFC-0035,
RFC-0036, RFC-0046, RFC-0060). RFC-0100 identified the gap; it was never closed.

The clearest demonstration that the gap is architectural rather than cosmetic is RFC-0020's
`AIEngine { project_id: UUID }` with a per-project model cache. A 7B model is multiple
gigabytes and one instance saturates a phone's memory. Two projects would download the same
weights twice and attempt to load them twice. Model weights are not project state; they are
device state.

The same is true of the secrets vault, installed plugins, device identity, notification
preferences, and the model catalog. Forcing them into project scope produces either duplication
or an undocumented global store — and an undocumented global store is where security bugs live.

## Goals

1. Define the three scopes and their lifetimes.
2. Assign every object class to exactly one scope.
3. Define the resolution order for settings and defaults.
4. Define what crosses scope boundaries and what may not.
5. Define the storage layout for each scope.

## Non-goals

This RFC does not define multi-user semantics (RFC-0046).
It does not define settings schemas (RFC-0036) or the secrets vault (RFC-0035); it defines
where they live.

## Design

### The three scopes

**User scope** — one per Aidos installation per device user. Lifetime: the installation.
Holds everything that is about *this person on this device* rather than about any project.

**Workspace scope** — an optional grouping of projects ("Work", "Personal", "Client X").
Lifetime: user-managed. Holds shared defaults and cross-project resources. A project belongs to
at most one workspace; a project with no workspace inherits directly from user scope.

**Project scope** — one per project. Lifetime: the project. Holds all work: sessions, runs,
intent, content, capabilities, audit.

Workspace is deliberately thin. It exists to prevent the two-scope system from forcing a false
choice between "global to my whole device" and "trapped in one project," and to give
future team support somewhere to attach without redesign (RFC-0046).

### Assignment

| Object class | Scope | Rationale |
|---|---|---|
| Device identity, actor identity | User | Identifies the installation |
| Model catalog (what exists) | User | A catalog of the world, not of a project |
| Model weights on disk | User | Multi-GB; sharing is mandatory, not an optimization |
| Loaded model instances | User | Bounded by device RAM; a global queue |
| Secrets vault | User | Credentials belong to a person, scoped by policy to consumers |
| Installed plugins | User | Executable code is never project-supplied (RFC-0057) |
| MCP server registrations | User (with project opt-in) | Prevents clone-equals-execution |
| Notification preferences | User | Device behaviour |
| Global settings | User | Defaults for everything below |
| Workspace settings, shared resources | Workspace | Cross-project conventions |
| Workspace project list | Workspace | Organization |
| Project metadata, config | Project | |
| Sessions, Runs, Tasks, Attempts | Project | |
| Intent Graph | Project | |
| ContentNodes, provenance | Project | |
| Capabilities | Project | Authority is always project-bounded (see below) |
| Audit log | Project, plus a user-scope stream for cross-project events | |
| Knowledge index | Project | Derived from project content |

### The MCP and plugin rule

MCP servers and plugins are **registered at user scope** and **enabled per project**. A project
config may *request* an MCP server by name; it may never define the command that starts one.

This closes the "cloning a project is arbitrary code execution" hole (RFC-0031, RFC-0057)
without losing the ergonomics: a project can say "I work best with the GitHub MCP server," the
user installs it once at user scope, and every project that asks for it gets it.

### Capability scoping across scopes

Capabilities are project-scoped even when the object they name is not. A session's
`model:query` capability is a project-scoped grant to use a user-scope resource; a
`secrets:read` capability names specific user-scope secret IDs.

The rule: **authority is granted in project scope, and it may reference user-scope objects
only by explicit ID, never by wildcard.** A session may hold `secrets:read` for two named
secrets; it may not hold `secrets:read:*`. This prevents a session in one project from reaching
credentials intended for another.

### Settings resolution

Settings resolve nearest-first:

```
session override → project → workspace → user → runtime default
```

Every resolved setting records its origin scope so the UI can show *why* a value is what it is.
Some settings are marked `user-only` (e.g. the secrets backend, the model storage path) and
cannot be overridden downward; attempting to set them at project scope is a validation error,
not a silent override. This matters because project files can arrive from elsewhere.

### Storage layout

```
~/.aidos/                                  ← user scope
├── config.toml
├── identity/                              device + actor identity
├── secrets/                               vault (RFC-0035)
├── models/                                weights, content-addressed by digest
├── plugins/                               installed, signed (RFC-0043)
├── mcp/                                   registered servers
├── workspaces/<workspace-id>/config.toml  ← workspace scope
└── projects.json                          registry: project id → path, workspace

<project-root>/                            ← project scope (the user's repository)
├── .git/
├── aidos.toml                             project config, Git-tracked
├── AGENTS.md, docs/, src/ ...             the user's content
└── .aidos/                                runtime state, Git-ignored
    ├── state.db                           SQLite
    ├── blobs/                             large content, content-addressed
    └── index/                             knowledge index, derived
```

Two decisions here are load-bearing:

1. **Project runtime state lives inside the project directory, in a Git-ignored `.aidos/`.**
   Not in `~/.aidos/projects/<name>/`. A project is then a single movable directory: copy it,
   and sessions, artifacts, and audit come with it. Name collisions become impossible. The
   earlier layout split a project across two locations that sync differently, which made
   "a project is portable" false.

2. **`.aidos/` is Git-ignored, and `aidos.toml` is Git-tracked.** Runtime state is not
   versioned; project intent and configuration are. `aidos.toml` must therefore never contain
   secrets or authority (RFC-0035, RFC-0057).

The project registry at `~/.aidos/projects.json` maps IDs to paths. It is a cache: a project
directory is self-describing, and a project moved on disk is re-registered on next open by ID.

### IDs

All IDs are UUIDv7 and globally unique, not unique-within-project. Import must never collide,
cross-project references must be expressible (RFC-0024 Future Work), and a project copied to
another device must retain identity. Rows carry `project_id` for isolation, not for uniqueness.

## Data Model

```sql
-- user scope, in ~/.aidos/user.db
CREATE TABLE workspaces (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE project_registry (
    project_id TEXT PRIMARY KEY,
    path TEXT NOT NULL,
    workspace_id TEXT,
    last_opened_at TEXT,
    FOREIGN KEY (workspace_id) REFERENCES workspaces(id)
);

-- The settings table is defined in RFC-0036, which owns it. It appears in BOTH databases,
-- split by scope: user and workspace rows in user.db, project and session rows in state.db.
-- That split is what enforces "SECURITY and SPEND settings are user-scope only" structurally
-- rather than by validation alone -- a project database has nowhere to put one.
--
--   user.db     settings(scope IN ('user','workspace'))
--   state.db    settings(scope IN ('project','session'))
```

> **Schema note.** `schema/user.sql` and `schema/project.sql` are the canonical DDL. An earlier
> version of this RFC carried a second, thinner `settings` definition than RFC-0036's; RFC-0036
> governs.

## Security

The user-scope database and the secrets vault are never included in a project export
(RFC-0041). Exporting a project must not exfiltrate credentials or the model catalog.

A project may not write to user scope. Project config declares *requests* (an MCP server by
name, a model preference); the user grants them. This asymmetry is the mechanism that makes
importing an untrusted project safe.

## MVP

1. User and project scopes with the storage layout above. Workspace scope: schema present,
   single implicit workspace, no UI.
2. Model catalog, weights, and secrets at user scope.
3. Settings resolution with origin tracking; `user-only` enforcement.
4. `.aidos/` inside the project directory, Git-ignored on project creation.
5. UUIDv7 everywhere.

Not in MVP: multiple workspaces, cross-project content references, per-project MCP opt-in UI.

## Future Work

Cross-project knowledge queries, gated by an explicit capability naming source projects.

Workspace-level shared resources and instruction files.

Device scope, if a user runs one installation across multiple devices with divergent hardware.
