# RFC-0110: Dual-Mode Intent Graph (Life & Coding Management)

Status: **Draft**
Phase: **1 (Foundation) / 3 (UX & Multi-Surface)**
Depends on: **0002 (Core Data Model), 0003 (Agent Model), 0006 (Security Model), 0010 (Project Model), 0012 (Intent Graph — superseded), 0019 (Execution Graph), 0049 (Platform Capability Profiles), 0052 (Runtime API), 0053 (Git Backend and Reconciliation), 0104 (Inference Locality)**
Related: **0008 (Execution Engine), 0011 (Prompt Assembly), 0044 (Notifications and Background Work), 0047 (Project Templates), 0054 (Scope Model), 0107 (Remote Agent Hosting)**

## Abstract

This RFC supersedes [RFC-0012 (Intent Graph)](0012-intent-graph.md) and extends the Aidos Intent Graph inside the headless Agent Runtime (`agent/intent`) to natively power both **Life Management** (Tasks-style human/agent task lists with mixed ownership and dependency handoffs) and **Coding Management** (decomposition of software goals by high-capacity planners into small, self-contained atomic pieces sized for constrained local mobile models). It retains RFC-0012's non-deferrable invariants — derived-only status, acyclicity, and the session proposal gate — while adding dual actor ownership, hierarchical dispatch policies, atomic coding metadata, split verification across `MOBILE` and `DESKTOP`, and dual Git-snapshot + live Runtime API synchronization.

## Motivation

Two distinct workflows share the exact same underlying need inside a project's `.aidos/state.db` (`schema/project.sql`):

1. **Life Management (Tasks with Mixed Agent/Human Ownership):**
   A personal task manager inside an Aidos project avoids unnecessary `TrustLevel.UNTRUSTED` external MCP taint when reading or organizing one's own tasks, while unifying execution tracking via `execution_edges`. However, real life tasks are not purely passive human checklists nor purely autonomous agent tasks:
   - `"Add milk to shopping list"` (`AGENT`-driven) executes via an agent Run and transitions to `DONE`.
   - `"Buy milk / send shopping list as order"` (`HUMAN`-driven) depends on `"Add milk to shopping list"`, starts `BLOCKED`, and automatically becomes actionable (`NOT_STARTED` -> `listActionableForHuman`) once the agent completes its prerequisite.
   - Conversely, user completion of a `HUMAN` prerequisite (`"Confirm grocery budget"`) immediately unblocks downstream `AGENT` work (`"Generate order draft"`).

2. **Coding Management (Asymmetric Cross-Device Planning & Atomic Execution):**
   A common multi-device workflow uses a beefier desktop/cluster model paired with the human to design and decompose a coding goal, and a smaller local model on `MOBILE` (`PlatformProfile.MOBILE`, `LOCAL_ONLY`) to knock out bite-sized tasks on the go. A flat task list cannot tell a mobile model *which* subset of files (`scope_paths_json`) or *how many steps* (`estimated_steps`) fit its context/compute budget, nor handle platform-unavailable verification (`SHELL_COMMAND` tests on Android per RFC-0049).

## Goals

1. Preserve RFC-0012's core safety and correctness guarantees:
   - **Derived status, never authored** (`DerivedStatusComputer`), querying canonical `execution_edges` and recursive `DEPENDS_ON` targets.
   - **Sessions propose; only users resolve** (`IntentProposalEngine`, backed by `intent_proposals` and `audit_log`).
   - **DAG acyclicity** enforced across existing and batched dependency edges (`IntentCycleDetector`).
2. Add **Actor Ownership (`actor_kind`)**: distinguish `HUMAN`, `AGENT`, and `HYBRID` tasks so autonomous dispatch never executes `HUMAN`-only tasks and human completions unblock `AGENT` dependents.
3. Add **Hierarchical Grouping & Cascading Dispatch Policy (`dispatch_policy`)**: support `GROUP` container nodes / `parent_id` trees and configurable `INHERIT` / `MANUAL_START` / `AUTO_WHEN_READY` dispatch rules backed by project setting `intent.default_dispatch_policy` (`ScopeClass.PROJECT_SAFE`).
4. Add **Atomic Coding Metadata & Split Verification**: express `granularity`, `target_profile`, `min_locality`, `estimated_steps`, and `scope_paths_json` so mobile local sessions receive focused file context and finite step budgets, while platform-restricted checks park cleanly in `NEEDS_REVIEW` until `DESKTOP` confirmation (`CHECK` / `USER`).
5. Support **Dual Synchronization**: deterministic Git snapshot export/import with conflict marking (`CONFLICTED`, RFC-0053) plus live `RuntimeClient.intents: IntentCommands`.

## Non-goals

- Multi-tenant collaborative CRDT conflict resolution (single-user per RFC-0046).
- Prescribing Jetpack Compose / Desktop UI screens (tracked separately from RFC-0110 core engine & `RuntimeClient.intents`).
- Allowing AI sessions to confirm their own `IMPLEMENTS` edges or resolve `intent_proposals`.

## Design

### Dual-Mode Ownership (`actor_kind`) and Status Derivation

Every `intent_nodes` row specifies `actor_kind IN ('HUMAN', 'AGENT', 'HYBRID')`:

- **`HUMAN`:** Executed by the user. Never eligible for autonomous agent dispatch. Derives `DONE` when verified by `USER` (`verified_by_kind = 'USER' AND verification_met = 1`) or asserted `DONE` via `completeHumanNode`, provided any `DEPENDS_ON` prerequisites and `ACCEPTS` criteria are met. Derives `IN_PROGRESS` when `asserted_status = 'IN_PROGRESS'`.
- **`AGENT`:** Executed by AI session Runs (`TARGETED` -> `IMPLEMENTS`). Derives `IN_PROGRESS` while a targeting Run is non-terminal (`PENDING`, `RUNNING`, `SLEEPING`, `YIELDED`, `INTERRUPTED`), `NEEDS_REVIEW` when an unconfirmed `IMPLEMENTS` edge exists or acceptance criteria remain unmet, and `DONE` when `IMPLEMENTS` is confirmed (`confirmed = 1` or `verified_by_kind IN ('USER', 'CHECK') AND verification_met = 1`) and all `ACCEPTS` criteria are met.
- **`HYBRID`:** Agent prepares/drafts via a Run -> derives `NEEDS_REVIEW` even after `IMPLEMENTS` is recorded until the user confirms/completes the human handoff (`verified_by_kind = 'USER' AND verification_met = 1`).
- **`GROUP` containers (`type = 'GROUP'`):** When a `GROUP` node has active child nodes (`parent_id = group.id AND lifecycle = 'ACTIVE'`), its derived status aggregates its children (`DONE` when all children are `DONE`, otherwise `IN_PROGRESS` / `NEEDS_REVIEW` / `BLOCKED` / `NOT_STARTED`).

Dependency blocking evaluates **recursively**: a node `A` with `DEPENDS_ON -> B` derives `BLOCKED` whenever `derive(B) != "DONE"` (excluding archived nodes).

### Hierarchical Grouping and Cascading Dispatch Policy

- **Node column `dispatch_policy`:** `INHERIT` (default), `MANUAL_START`, or `AUTO_WHEN_READY`.
- **Project setting `intent.default_dispatch_policy` (`ScopeClass.PROJECT_SAFE`, default `MANUAL_START`):** Declared in `Settings` (`agent/settings`).
- **Resolution order:** Walk `node -> parent_id` upward until a node specifies `MANUAL_START` or `AUTO_WHEN_READY`; if the root is reached with `INHERIT`, use the project's resolved `intent.default_dispatch_policy`.
- When an `AGENT` node's dependencies all reach `DONE` (status transitions from `BLOCKED` to `NOT_STARTED`) and its effective dispatch policy is `AUTO_WHEN_READY`, `listReadyForAgent(..., onlyAutoDispatch = true)` surfaces it for automatic scheduling on compatible device profiles/localities.

### Coding Management: Atomic Decomposition & Split Verification

1. **Planner Proposal Batch:** A `DESKTOP` (or `HEADLESS_SERVER`) session decomposes a parent `GOAL` into `SUB_GOAL` and `ATOMIC_TASK` (`granularity = 'ATOMIC'`) nodes in a single `IntentProposal` using `AddNode`, `AddEdge(DEPENDS_ON | CONSTRAINS | ACCEPTS)`, and `ModifyNode`. Validation supports intra-batch node references and combined DB + batch cycle detection.
2. **Phone-Ready Constraints & Prompt Pinning:**
   - `target_profile` (`MOBILE` | `DESKTOP` | `HEADLESS_SERVER` | `ANY`) and `min_locality` (`LOCAL_ONLY` | `TRUSTED_CLUSTER` | `ANY`) filter which tasks `listReadyForAgent` offers to a given device/runtime.
   - `estimated_steps` bounds the Run `max_steps` ceiling in `IntentRunCoordinator`.
   - `scope_paths_json` pins the exact repo-relative file paths for `IntentPromptRenderer` so a small local phone model loads targeted files into Layer 3 `[INTENT GRAPH]` context without expensive repo-wide discovery.
3. **Split Verification (`MOBILE` `NEEDS_REVIEW` -> `DESKTOP` `CHECK`):**
   - If a `MOBILE` Run completes work on an atomic node that has `ACCEPTS` criteria requiring `check_kind = 'SHELL_COMMAND'` (unavailable on `MOBILE` per RFC-0049), `IntentRunCoordinator.completeTargetedRun` records an unconfirmed `IMPLEMENTS` edge (`confirmed = 0`), leaving the node in `NEEDS_REVIEW`.
   - Downstream dependent tasks stay `BLOCKED` until synced to `DESKTOP` where tests pass and `confirmPendingImplementation(..., confirmedByKind = "CHECK", ...)` confirms the implementation and criteria, transitioning the node to `DONE` and unblocking the next task.

### Dual Synchronization (Git Snapshot & Live Runtime API)

- **Git Snapshot (`IntentSnapshotCodec`):** Deterministically exports active/archived nodes and edges sorted by stable keys for Git commits inside the project (`.aidos/intent-snapshot.json` or caller-managed tracked path). `importAndReconcile` merges incoming snapshots by `row_version`, flagging divergent concurrent modifications with `asserted_status = 'CONFLICTED'` per RFC-0053 without dropping data.
- **Live Runtime API (`RuntimeClient.intents: IntentCommands`):** Exposes node CRUD, edge management, human completion, proposal creation/resolution, agent readiness queries, and snapshot export/reconciliation across in-process and remote/CLI clients.

## Data Model

> **Schema note.** `schema/project.sql` is canonical. The DDL below matches `schema/project.sql` verbatim.

```sql
CREATE TABLE intent_nodes (
    id                   TEXT PRIMARY KEY,
    project_id           TEXT NOT NULL,
    type                 TEXT NOT NULL,                   -- GROUP|GOAL|SUB_GOAL|ATOMIC_TASK|CONSTRAINT|ACCEPTANCE_CRITERION
    title                TEXT NOT NULL,
    description          TEXT,
    priority             INTEGER NOT NULL DEFAULT 100,
    lifecycle            TEXT NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE|ARCHIVED (authorship, not progress)
    parent_id            TEXT,
    -- dual-mode ownership, dispatch & atomic sizing (RFC-0110)
    actor_kind           TEXT NOT NULL DEFAULT 'AGENT',   -- HUMAN|AGENT|HYBRID
    dispatch_policy      TEXT NOT NULL DEFAULT 'INHERIT', -- INHERIT|MANUAL_START|AUTO_WHEN_READY
    granularity          TEXT NOT NULL DEFAULT 'GOAL',    -- GROUP|GOAL|SUB_GOAL|ATOMIC
    target_profile       TEXT,                            -- MOBILE|DESKTOP|HEADLESS_SERVER|ANY
    min_locality         TEXT,                            -- LOCAL_ONLY|TRUSTED_CLUSTER|ANY
    estimated_steps      INTEGER,
    scope_paths_json     TEXT NOT NULL DEFAULT '[]',
    due_at               TEXT,
    recurrence_json      TEXT,
    -- user override of derived status, stored as a timestamped claim
    asserted_status      TEXT,
    asserted_at          TEXT,
    asserted_by_user_id  TEXT,
    assertion_note       TEXT,
    -- acceptance criteria only
    check_kind           TEXT,
    check_spec           TEXT,
    verification_met     INTEGER,
    verified_by_kind     TEXT,                            -- USER | CHECK. never SESSION (RFC-0012)
    verified_by_id       TEXT,
    verified_at          TEXT,
    created_at           TEXT NOT NULL,
    created_by_kind      TEXT NOT NULL,
    created_by_id        TEXT NOT NULL,
    modified_at          TEXT NOT NULL,
    modified_by_kind     TEXT NOT NULL,
    modified_by_id       TEXT NOT NULL,
    tags                 TEXT NOT NULL DEFAULT '[]',
    row_version          INTEGER NOT NULL DEFAULT 1,
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (parent_id)  REFERENCES intent_nodes(id),
    CHECK (verified_by_kind IS NULL OR verified_by_kind IN ('USER', 'CHECK')),
    CHECK (actor_kind IN ('HUMAN', 'AGENT', 'HYBRID')),
    CHECK (dispatch_policy IN ('INHERIT', 'MANUAL_START', 'AUTO_WHEN_READY')),
    CHECK (granularity IN ('GROUP', 'GOAL', 'SUB_GOAL', 'ATOMIC'))
);

CREATE INDEX idx_intent_project ON intent_nodes(project_id, lifecycle, priority);
CREATE INDEX idx_intent_parent  ON intent_nodes(parent_id);
CREATE INDEX idx_intent_actor   ON intent_nodes(project_id, actor_kind, lifecycle);

CREATE TABLE intent_edges (
    id           TEXT PRIMARY KEY,
    project_id   TEXT NOT NULL,
    from_node_id TEXT NOT NULL,
    to_node_id   TEXT NOT NULL,
    edge_kind    TEXT NOT NULL,                           -- DEPENDS_ON|CONSTRAINS|ACCEPTS
    created_at   TEXT NOT NULL,
    FOREIGN KEY (project_id)   REFERENCES projects(id),
    FOREIGN KEY (from_node_id) REFERENCES intent_nodes(id),
    FOREIGN KEY (to_node_id)   REFERENCES intent_nodes(id),
    UNIQUE (from_node_id, to_node_id, edge_kind)
);

CREATE INDEX idx_intent_edges_from ON intent_edges(from_node_id, edge_kind);
CREATE INDEX idx_intent_edges_to   ON intent_edges(to_node_id, edge_kind);

CREATE TABLE intent_proposals (
    id                  TEXT PRIMARY KEY,
    project_id          TEXT NOT NULL,
    operations_json     TEXT NOT NULL,                    -- atomic batch
    rationale           TEXT NOT NULL,
    proposed_by_run_id  TEXT NOT NULL,
    proposed_at         TEXT NOT NULL,
    run_taint           TEXT NOT NULL,                    -- RFC-0027
    state               TEXT NOT NULL,                    -- PENDING|ACCEPTED|ACCEPTED_WITH_EDITS|REJECTED|SUPERSEDED|EXPIRED
    resolved_by_user_id TEXT,
    resolved_at         TEXT,
    expires_at          TEXT NOT NULL,
    audit_ref           TEXT NOT NULL,
    FOREIGN KEY (project_id)         REFERENCES projects(id),
    FOREIGN KEY (proposed_by_run_id) REFERENCES runs(id),
    FOREIGN KEY (audit_ref)          REFERENCES audit_log(id)
);

CREATE INDEX idx_proposals_pending ON intent_proposals(project_id, expires_at) WHERE state = 'PENDING';
```

## Security

- **Proposal gate preserved:** AI sessions may only call `IntentProposalEngine.propose(...)`. They cannot call `resolve(...)`, directly mutate `intent_nodes`, or confirm `IMPLEMENTS` edges.
- **Verification isolation preserved:** `CHECK (verified_by_kind IS NULL OR verified_by_kind IN ('USER', 'CHECK'))` remains enforced by SQLite so no session can self-verify acceptance criteria.
- **Human-task isolation:** `IntentRunCoordinator` refuses to start an agent Run targeting a node with `actor_kind = 'HUMAN'`, and `IntentPromptRenderer` explicitly fences `HUMAN` tasks as user-owned context rather than autonomous agent instructions.
- **Audit trail integrity:** `IntentProposalEngine.propose` writes `audit_log` before inserting `intent_proposals` so `FOREIGN KEY (audit_ref) REFERENCES audit_log(id)` succeeds under strict foreign-key enforcement; `resolve` records `IntentProposalResolved` with the user `ActorRef`.
- **Untrusted taint visibility:** Proposals retain `run_taint` (RFC-0027), and a `PROJECT_SAFE` setting (`intent.default_dispatch_policy`) prevents session overrides.

## MVP

1. Updated `intent_nodes` schema in `schema/project.sql` + `Settings.intentDefaultDispatchPolicy` (`PROJECT_SAFE`).
2. Fixed & expanded `agent/intent` (`IntentModels`, `DerivedStatusComputer`, `IntentCycleDetector`, `IntentProposalEngine`, `IntentGraphRepository`, `IntentSnapshotCodec`).
3. `IntentPromptRenderer` in `agent/prompt` + `IntentRunCoordinator` in `agent/executor`.
4. `RuntimeClient.intents: IntentCommands` in `agent/api` (`RealRuntimeClient`, `MockRuntimeClient`, `SocketRuntimeClient` stub).
5. Full JVM unit & integration tests covering Life Management handoffs, cascading dispatch policies, Coding Management atomic decomposition, `MOBILE` -> `DESKTOP` split verification, and Git snapshot reconciliation.

## Future Work

- Android & Desktop Compose UI views (Life Tasks view and Coding Atomic Backlog view).
- Socket wire protocol (`Methods`/`Wire`) support for `intents.*` and live AIDIN push replication.
- Automated background `AUTO_WHEN_READY` WorkManager/scheduler trigger hook for `AGENT` tasks on device wake/sync.
- Automatic mechanical `SHELL_COMMAND` runner integration on `DESKTOP` Run completion.
