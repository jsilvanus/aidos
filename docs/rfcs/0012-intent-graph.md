# RFC-0012: Intent Graph

Status: Draft

## Abstract

The Intent Graph is a persistent, editable data structure that represents what should happen in a project. It captures goals, priorities, constraints, dependencies, and acceptance criteria. Unlike conversation history (which is ephemeral), the Intent Graph is the canonical representation of the project's purpose and status. It survives conversation, can be edited by both users and AI systems, and is versioned through Git. The Intent Graph is the bridge between human intent and system execution.

## Motivation

Conversation is ephemeral. A user talks to an AI assistant, discusses a problem, and the assistant suggests a solution. But then the conversation ends. If the user comes back to the project days later, they have no record of what they intended, only scattered artifacts.

Aidos needs a persistent, structured representation of intent that:

- **Survives conversation**: The intent outlives any particular exchange.
- **Is queryable**: Sessions can ask "what are we trying to achieve?" and get a clear answer.
- **Supports hierarchy**: Goals have sub-goals. Dependencies connect them.
- **Is editable**: Both users and AI systems can propose changes to the Intent Graph.
- **Maintains status**: What is done? What is in progress? What is blocked?
- **Guides decisions**: Sessions query the graph to understand priorities and constraints.

The Intent Graph is inspired by:

- **Goal hierarchies** in planning systems (break big goals into smaller tasks).
- **Dependency graphs** in build systems (understand what depends on what).
- **State machines** (track transitions from one state to another).
- **Semantic web** (structured knowledge that machines can reason about).
- **Git history** (editable, versioned, with clear change records).

## Goals

1. **Define intent semantics**: What is intent? How is it different from execution or conversation?

2. **Establish graph structure**: What are nodes and edges? How do they relate?

3. **Specify node properties**: Goals, priorities, constraints, dependencies, acceptance criteria, status.

4. **Define editability**: Who can edit the graph? How are edits tracked?

5. **Establish versioning**: How is the graph versioned and stored?

6. **Clarify querying**: How do sessions query the Intent Graph?

7. **Explain AI participation**: How do AI systems propose graph modifications?

## Non-goals

This RFC does not specify the exact graph format (JSON, RDF, graph DB, etc.). That is implementation detail.

This RFC does not mandate specific algorithms for reasoning over the graph (topological sort, critical path, etc.). Those are application-specific.

This RFC does not address collaborative editing conflict resolution. Single-user is the design assumption.

This RFC does not specify the UI for editing the graph. The graph is a data structure; visualizations are separate.

## Design

### Intent vs. Execution

A crucial distinction in Aidos:

**Intent** is WHAT should happen:

- Goal: "Build a weather app with real-time updates"
- Sub-goal: "Design the API"
- Constraint: "Must work offline"
- Acceptance criterion: "App loads in < 2 seconds"

**Execution** is HOW it happens:

- Session S1 queries Claude for API design
- Worker W1 generates code
- Tests are run
- Code is reviewed

Intent is persistent and human-driven. Execution is transient and AI-driven. Conversation happens during execution and is ephemeral. Intent is captured once and referenced throughout.

The Intent Graph decouples intent from execution. A goal remains a goal, even if multiple sessions try different approaches to achieving it.

### Graph Structure

The Intent Graph is a directed acyclic graph (DAG) where:

- **Nodes** represent intents (goals, sub-goals, constraints, tasks).
- **Edges** represent relationships (dependency, sub-goal, blocking, related-to).

Example graph for "Build a Weather App":

```
ROOT: "Build a Weather App"
  │
  ├── SUB_GOAL: "Design API"
  │   ├── CONSTRAINT: "Must work offline"
  │   ├── CONSTRAINT: "Must support caching"
  │   └── ACCEPTANCE: "API spec in OpenAPI format"
  │
  ├── SUB_GOAL: "Implement Backend"
  │   ├── DEPENDENCY: → "Design API" (must complete first)
  │   ├── CONSTRAINT: "Use Rust"
  │   └── ACCEPTANCE: "All endpoints implemented and tested"
  │
  ├── SUB_GOAL: "Implement Frontend"
  │   ├── DEPENDENCY: → "Design API"
  │   ├── CONSTRAINT: "Android first"
  │   └── ACCEPTANCE: "All screens match mockups"
  │
  └── SUB_GOAL: "Launch"
      ├── DEPENDENCY: → "Implement Backend"
      ├── DEPENDENCY: → "Implement Frontend"
      └── ACCEPTANCE: "App on app store"
```

Nodes represent different types of intents:

#### Goal

A desired outcome. Goals are typically high-level. They can have sub-goals.

```
Goal {
  id: NodeId
  title: String
  description: String?
  priority: Int (1=highest)
  # status is derived, not stored — see "Status is derived" below
}
```

#### Sub-Goal

A breakdown of a parent goal. Sub-goals are more specific and actionable.

```
SubGoal {
  id: NodeId
  title: String
  description: String?
  parent_goal: NodeId
  priority: Int
  estimated_effort: String? ("small", "medium", "large")
  # status is derived; ownership is expressed by TARGETED edges (RFC-0019)
}
```

#### Constraint

A requirement that applies to one or more goals. Constraints are non-negotiable.

```
Constraint {
  id: NodeId
  title: String
  description: String?
  applies_to: NodeId
  severity: ConstraintSeverity (must_have | should_have | nice_to_have)
  lifecycle: "active" | "relaxed" | "superseded"   # authorship state, not progress
}
```

Example: "Must work offline" is a must_have constraint on the API design goal.

#### Acceptance Criterion

A measurable condition that indicates a goal is achieved.

```
AcceptanceCriterion {
  id: NodeId
  description: String
  applies_to: NodeId
  check: CriterionCheck?            # mechanical check, if one exists
  verification: Verification?       # how it came to be considered met
}

CriterionCheck {                    # e.g. "tests pass", "file exists", "command exits 0"
  kind: CheckKind
  spec: String
}

Verification {
  met: Boolean
  verified_by_kind: 'USER' | 'CHECK'   # never 'SESSION'
  verified_by_id: UUID?                # the user, or the Run whose check evaluated it
  verified_at: Timestamp
}
```

`verified_by_kind` has no `SESSION` variant, for the same reason `IMPLEMENTS` edges require
confirmation (RFC-0019): a model that can mark its own acceptance criteria met can declare its
own work complete, and derived status would then be derived from the model's opinion of itself.

A criterion is met when a **mechanical check** passes, or when the **user** says so. A session
can run the check — that is ordinary work — but the check's result is the verification, not the
session's report of it. Criteria with no mechanical check are user-verified, which is honest:
"the API spec is in OpenAPI format" is checkable, "the design is good" is not.

Example: "API spec in OpenAPI format" is an acceptance criterion for the "Design API" goal.

#### Dependency

An edge indicating that one intent depends on another.

```
Dependency {
  source: NodeId (depends on)
  target: NodeId (must be done first)
  type: DependencyType (blocking | related_to | related_by_constraint)
}
```

### Node Properties

Every node in the Intent Graph has:

```
IntentNode {
  id: NodeId                        # UUIDv7, globally unique (RFC-0054)
  project_id: UUID

  type: NodeType                    # Goal, SubGoal, Constraint, etc.
  title: String
  description: String?

  priority: Int                     # 1=highest, determines ordering

  # NOTE: there is no authored `status` field. See "Status is derived" below.
  user_assertion: StatusAssertion?  # explicit user claim, with provenance
  lifecycle: NodeLifecycle          # ACTIVE | ARCHIVED — authorship state, not progress

  created_at: Timestamp
  created_by_kind: 'USER' | 'SESSION'
  created_by_id: UUID
  modified_at: Timestamp
  modified_by_kind: 'USER' | 'SESSION'
  modified_by_id: UUID

  constraints: List<NodeId>         # Constraints that apply
  acceptance_criteria: List<NodeId> # Conditions for "done"

  dependencies: List<NodeId>        # Other nodes this depends on
                                    # (dependents are derived; see Acyclicity)

  tags: List<String>
  metadata: Map<String, Any>?
}

StatusAssertion {
  claimed: NodeStatus               # what the user says is true
  asserted_at: Timestamp
  asserted_by: UserId
  note: String?                     # why they overrode the derived value
}
```

Two fields were removed. `owner: SessionId` implied a session holds a node, which does not
survive parallel workers (RFC-0007) — ownership is expressed by `TARGETED` edges instead.
`related_artifacts` and `related_resources` duplicated links that already exist through
`IMPLEMENTS` → Run → `PRODUCED` → ContentNode; a denormalized list here is a fourth place for
provenance to disagree with itself (RFC-0019, "One fact, one place").

### Status is derived, never authored

**An intent node has no stored `status`.** Progress is computed:

```
derived_status(node) =
    from IMPLEMENTS edges (RFC-0019) → the Run outcomes that actually served this node
    ∧ acceptance criteria evaluation
    ∧ status of child nodes and dependencies
```

The reason is a failure mode that would otherwise be certain. A Run implements "add auth" and
writes `status = done`. Then the user reverts the commit; or the Run partially failed and the
model reported success; or a later change broke it. **The field still reads `done`.** An intent
graph that misreports state is worse than none, because the user stops trusting it — and worse,
because it silently feeds task instructions into prompt construction (RFC-0025), so the model
inherits the false belief.

Derived status values:

| Value | Derivation |
|---|---|
| `NOT_STARTED` | no `TARGETED` or `IMPLEMENTS` edges |
| `IN_PROGRESS` | a `TARGETED` Run is non-terminal |
| `NEEDS_REVIEW` | `IMPLEMENTS` edges exist but are unconfirmed (RFC-0019) |
| `DONE` | confirmed `IMPLEMENTS` edges and all acceptance criteria satisfied |
| `BLOCKED` | a dependency is not `DONE`, or the last Run failed unrecoverably |
| `STALE` | was `DONE`, but content it produced is `DANGLING` or was reverted (RFC-0053) |

`STALE` is the state that only exists because status is derived. A stored field cannot represent
"this was done and then undone by something outside the system," and that is a normal event in a
Git-first product.

**The user can always override**, and the override is recorded as a claim rather than as truth:

```
Goal: Add user authentication
  Derived:   NEEDS_REVIEW  (2 runs, unconfirmed)
  You said:  DONE          (3 days ago — "shipped manually, runs were exploratory")
```

Both are shown. The derived value never disappears. A model reading the graph sees both, and the
distinction between "the system observed this" and "the user asserted this" is preserved through
to prompt construction.

**Cost:** one join and a small evaluator. **Benefit:** the graph cannot lie.

### Acyclicity

The Intent Graph is a DAG, and this is enforced at write time rather than assumed:

- Adding a dependency edge that would create a cycle is rejected with `intent.cycle_rejected`
  (RFC-0029). A dependency cycle deadlocks status derivation and any future planner.
- Parent/child containment is likewise acyclic.
- `dependents` is **derived** by reverse lookup, not stored. Storing both directions means they
  can disagree, and reconciling them is work with no upside.

The check is a reachability test on insert — cheap at intent-graph scale (tens to hundreds of
nodes), and it turns a class of subtle deadlock into an immediate, explainable error.

### Editability

The Intent Graph is editable by:

#### Users (Direct Edits)

Users can:
- Create new goals and sub-goals.
- Update priorities, descriptions, status.
- Add or remove constraints and acceptance criteria.
- Mark goals as done.
- Archive completed goals.

User edits are changes to the Intent Graph and should be tracked (ideally through Git commits with commit messages).

#### AI Systems (Proposals Only)

**A session can never write to the Intent Graph. It can only create a proposal, which only the
user resolves.**

This is the load-bearing rule of this RFC, and the reason is a closed loop. The model *reads*
intent — task instructions come from the current intent node (RFC-0025, precedence 5) — and the
model *proposes* intent. If it could also approve, it would invent goals, read its own invented
goals back as instructions, and drift arbitrarily from what the user wanted, each step locally
plausible and none of them checked. The approval gate is what breaks the loop.

An earlier version of this RFC contained exactly that defect in its own example: *"AI proposes
sub-goals → **Driver approves** → AI creates worker sessions."* A driver session is a model, not
a user. That sequence is the loop with no human in it.

### Proposals

A proposal is a **separate object, not a node in a "proposed" state.**

That distinction matters. A proposed node placed in the graph is already in the graph: it
appears in queries, it feeds prompt construction, and if the user never answers it lingers
indefinitely as pseudo-intent that everything downstream treats as real.

```kotlin
data class IntentProposal(
    val id: UUID,
    val projectId: UUID,
    val operations: List<IntentOperation>,   // atomic: all applied or none
    val rationale: String,                   // why, in the model's words
    val proposedByRunId: UUID,
    val proposedAt: Instant,
    val runTaint: TrustLevel,                // RFC-0027
    val state: ProposalState,
    val resolvedBy: UserId?,                 // ONLY a user. never a session.
    val resolvedAt: Instant?
)

sealed class IntentOperation {
    data class AddNode(val parent: NodeId?, val node: IntentNodeDraft) : IntentOperation()
    data class ModifyNode(val id: NodeId, val changes: NodeChanges) : IntentOperation()
    data class AddDependency(val from: NodeId, val to: NodeId) : IntentOperation()
    data class ArchiveNode(val id: NodeId) : IntentOperation()
}

enum class ProposalState { PENDING, ACCEPTED, ACCEPTED_WITH_EDITS, REJECTED, SUPERSEDED, EXPIRED }
```

**Operations are batched and atomic.** Decomposing a goal into five sub-goals is *one* proposal
with five operations, accepted or rejected as a unit. Five separate prompts would train the user
to click through them, which defeats the gate.

**Proposals expire** (default 30 days) rather than accumulating. A stale proposal describing a
plan for code that has since changed is noise, and a pile of them makes the pending list
something the user stops reading.

**Proposals carry the taint of the Run that made them** (RFC-0027). A proposal from a Run that
read untrusted content is shown as such:

> Proposed by *refactor-auth*, which read untrusted content from
> `node_modules/left-pad/README.md`. Review carefully.

Untrusted content cannot cause an intent change on its own — the user still approves — but the
user deserves to know that the suggestion may originate from a document rather than from
analysis.

### Proposals in context

A pending proposal is included in prompt construction **as a pending proposal, clearly labelled,
never as intent**. The model needs to know one exists so it does not re-propose the same thing;
it must not read it as an accepted goal.

This is the same distinction the derived-status design makes between what the system observed
and what someone asserted, applied to the proposal stage.

### What a session may and may not do

| | |
|---|---|
| **May** | read the graph; create proposals; propose `IMPLEMENTS` edges (RFC-0019) |
| **May not** | create, modify, or archive nodes directly |
| **May not** | resolve any proposal, including its own or another session's |
| **May not** | set status — status is derived (see below) |
| **May not** | confirm an `IMPLEMENTS` edge — confirmation is the user's or acceptance criteria's |

The row that most often gets violated in implementations is the last two. "Mark goals as done
when acceptance criteria are met" was previously listed as something an AI session does; it is
not. Acceptance criteria are *evaluated*, and the evaluation is what marks the goal — the model
does not get to assert the outcome of its own work.

### Audit

Every proposal and every resolution is audited with the actor (RFC-0046): which Run proposed,
which user resolved, what changed, and the rationale. The Intent Graph's Git snapshot commit
message references the proposal ID, so `git log` on the intent snapshot reads as a decision
history rather than a series of unexplained state changes.

### Versioning and Storage

The Intent Graph is persistent. It is stored (at least) in Git. Changes to the graph can be:

- **Committed as Git commits**: Each significant change to the Intent Graph is a commit.
- **Annotated with reasoning**: Commit messages explain why the change was made.
- **Auditable**: The full history of the graph is queryable.

Example commit:

```
commit 3a1b2c...
Author: Session S1
Date:   2025-08-01

  Add sub-goal: "Implement local sync for offline support"
  
  Reasoning: User constraint "must work offline" conflicts with REST API.
  Solution: Use local database with eventual consistency sync.
  
  Related Intent Node: goal_api_design (node-0042)
```

### Graph Diff and Merge

Because the Intent Graph is structured, we can compute meaningful diffs:

```
Diff from yesterday's graph:

+ Added sub-goal: "Write tests for payment module"
  Priority: High
  Parent: "Implement payment system"

- Removed constraint: "Must support IE11"
  Reason: IE11 is EOL

~ Changed status: "Design phase" from "in_progress" → "done"
  Verified by: Session S1
  Verification time: 2 hours
```

Diffs show:
- Structural changes (nodes added/removed/moved).
- Property changes (status, priority, description).
- Reasoning (why the change was made).

Merging is future work but the graph structure supports it.

### Querying the Intent Graph

Sessions query the graph to understand the project:

```
Session queries:
  "What goals are blocked?"
  → Returns: [goal_X, goal_Y] with reasons for blocking

  "What is the highest priority next step?"
  → Returns: sub_goal_Z with full context

  "What constraints apply to the API design?"
  → Returns: [offline_support, sub_1s_latency, ...]

  "What acceptance criteria must be met?"
  → Returns: acceptance_1, acceptance_2, ... with verification status
```

### AI Participation

An AI session can:

1. **Understand intent**: read the graph and the pending proposals.
2. **Propose refinements**: decomposition, constraints, priorities — as proposals (above).
3. **Propose `IMPLEMENTS` edges**: assert that a Run served a goal, subject to confirmation
   (RFC-0019).
4. **Flag conflicts**: detect contradictory goals or unsatisfiable dependencies, as a proposal
   or a note.

It cannot update status, resolve proposals, or confirm its own `IMPLEMENTS` assertions.

Corrected example workflow — note who approves:

```
Driver session reads Intent Graph
→ sees goal: "Implement payment system"
→ asks the model to decompose it
→ creates ONE proposal with four AddNode operations, plus rationale
→ Run parks: Task(kind = USER_PROMPT), Run state YIELDED     ← RFC-0006
        ⋮                    (may be hours or days)
→ USER reviews and accepts, with edits to two of the four
→ proposal ACCEPTED_WITH_EDITS; nodes created; audited
→ completion event resumes the Run                            ← RFC-0009
→ driver creates worker sessions for the accepted sub-goals
```

The pause is real and may be long: a proposal on a phone at 23:00 is answered the next morning.
That is exactly what the durable execution model exists to survive (RFC-0009) — the Run is a row,
not a suspended coroutine, so the process can die and restart in between.

**Unattended Runs do not propose-and-wait.** A scheduled Run (RFC-0044) that would park on a
proposal instead records the proposal as pending, completes, and notifies. Blocking a background
Run on a human who is asleep holds resources for nothing.

## Data Model


> **Schema note.** `schema/project.sql` is the canonical DDL. The block below is the same
> definition, reproduced here so this RFC is readable on its own; where the two ever differ,
> the schema file governs and this RFC is the bug.

```sql
-- Note the absence of a `status` column. Status is derived (see "Status is derived").
CREATE TABLE intent_nodes (
    id                  TEXT PRIMARY KEY,             -- UUIDv7 (RFC-0054)
    project_id          TEXT NOT NULL,
    type                TEXT NOT NULL,                -- GOAL|SUB_GOAL|CONSTRAINT|ACCEPTANCE_CRITERION
    title               TEXT NOT NULL,
    description         TEXT,
    priority            INTEGER NOT NULL DEFAULT 100,
    lifecycle           TEXT NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE|ARCHIVED (authorship, not progress)
    parent_id           TEXT,
    -- user override of derived status, stored as a timestamped claim
    asserted_status     TEXT,
    asserted_at         TEXT,
    asserted_by_user_id TEXT,
    assertion_note      TEXT,
    -- acceptance criteria only
    check_kind          TEXT,
    check_spec          TEXT,
    verification_met    INTEGER,
    verified_by_kind    TEXT,                         -- USER | CHECK. never SESSION.
    verified_by_id      TEXT,
    verified_at         TEXT,
    created_at          TEXT NOT NULL,
    created_by_kind     TEXT NOT NULL,
    created_by_id       TEXT NOT NULL,
    modified_at         TEXT NOT NULL,
    modified_by_kind    TEXT NOT NULL,
    modified_by_id      TEXT NOT NULL,
    tags                TEXT NOT NULL DEFAULT '[]',
    row_version         INTEGER NOT NULL DEFAULT 1,
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (parent_id)  REFERENCES intent_nodes(id),
    CHECK (verified_by_kind IS NULL OR verified_by_kind IN ('USER', 'CHECK'))
);

-- Dependencies only. `dependents` is derived by reverse lookup.
-- Acyclicity is enforced on insert, not assumed.
CREATE TABLE intent_edges (
    id           TEXT PRIMARY KEY,
    project_id   TEXT NOT NULL,
    from_node_id TEXT NOT NULL,
    to_node_id   TEXT NOT NULL,
    edge_kind    TEXT NOT NULL,                       -- DEPENDS_ON|CONSTRAINS|ACCEPTS
    created_at   TEXT NOT NULL,
    FOREIGN KEY (project_id)   REFERENCES projects(id),
    FOREIGN KEY (from_node_id) REFERENCES intent_nodes(id),
    FOREIGN KEY (to_node_id)   REFERENCES intent_nodes(id),
    UNIQUE (from_node_id, to_node_id, edge_kind)
);

CREATE INDEX idx_intent_project    ON intent_nodes(project_id, lifecycle, priority);
CREATE INDEX idx_intent_parent     ON intent_nodes(parent_id);
CREATE INDEX idx_intent_edges_from ON intent_edges(from_node_id, edge_kind);
CREATE INDEX idx_intent_edges_to   ON intent_edges(to_node_id, edge_kind);
```

Two things the schema makes enforceable that prose could not:

**There is no `status` column.** Writing the table is what makes the derived-status decision
concrete — it is considerably harder to accidentally add a status field to a table that visibly
does not have one than to a design document that says status should be derived.

**`CHECK (verified_by_kind IN ('USER','CHECK'))`.** The rule that a session may not verify its own
acceptance criteria is enforced by the database, not by a code path that could be forgotten.

```sql
CREATE TABLE intent_proposals (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL,
    operations_json TEXT NOT NULL,        -- atomic batch
    rationale TEXT NOT NULL,
    proposed_by_run_id TEXT NOT NULL,
    proposed_at TEXT NOT NULL,
    run_taint TEXT NOT NULL,              -- RFC-0027
    state TEXT NOT NULL,                  -- PENDING | ACCEPTED | ... | EXPIRED
    resolved_by_user_id TEXT,             -- ONLY a user (RFC-0046)
    resolved_at TEXT,
    expires_at TEXT NOT NULL,
    audit_ref TEXT NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (proposed_by_run_id) REFERENCES runs(id)
);

CREATE INDEX idx_proposals_pending ON intent_proposals(project_id, expires_at)
    WHERE state = 'PENDING';
```

`resolved_by_user_id` has no session variant by construction. A schema that cannot express
"a session approved this" is a stronger guarantee than a rule saying it must not.

> **A second, conflicting data model was removed here.** This section previously restated the
> graph as an `IntentGraph { nodes, edges, root, version, metadata }` pseudo-structure that
> disagreed with the canonical DDL above in three ways that mattered: a `version: Int` described
> as "Git commit count or sequential", which is precisely the device-local sequence number **D16
> forbids** for intent (it must stay file-serializable with globally unique IDs); an untyped
> `metadata: Map<String, Any>?` bag; and a single `root: NodeId`, which contradicts the MVP's flat
> goal list. The DDL above is the model. There is no second one.

## Lifecycle

### Creation

When a project is created, the Intent Graph is initialized:

```
Initialize Intent Graph
Create root node: "Project Goal"
Set status: "not_started"
Optionally import from template
```

### Growth

As work progresses, the graph grows:

```
User adds sub-goals for major areas
AI suggests further decomposition
Sessions mark goals as in_progress as work begins
Constraints and acceptance criteria are added
Nodes are connected with dependencies
```

### Refinement

The graph evolves:

```
Status updates (not_started → in_progress → done)
Priorities are adjusted based on learning
Constraints are relaxed or tightened
New insights trigger graph restructuring
Artifacts are linked to nodes
```

### Archival

Completed goals are archived:

```
Mark node as "done"
Verify all acceptance criteria are met
Optionally archive (move out of active view)
Preserve in Git history
```

## Examples

### Example 1: Weather App Project

```
ROOT: "Build a Weather App" (in_progress)
│
├── GOAL: "Design Phase" (done)
│   ├── SUB: "Design API" (done)
│   │   ├── CONSTRAINT: "Must work offline"
│   │   ├── CONSTRAINT: "Real-time updates"
│   │   └── ACCEPTANCE: "OpenAPI spec approved"
│   ├── SUB: "Design Database Schema" (done)
│   └── SUB: "Design UI Mockups" (done)
│
├── GOAL: "Implementation Phase" (in_progress)
│   ├── SUB: "Implement Backend" (in_progress)
│   │   ├── DEPENDENCY: → "Design API"
│   │   ├── ACCEPTANCE: "All endpoints tested"
│   │   └── OWNER: Session S1
│   │
│   ├── SUB: "Implement Frontend" (not_started)
│   │   ├── DEPENDENCY: → "Design API", "Design UI Mockups"
│   │   ├── ACCEPTANCE: "UI matches mockups"
│   │   └── OWNER: (waiting)
│   │
│   └── SUB: "Write Integration Tests" (not_started)
│       └── DEPENDENCY: → "Implement Backend", "Implement Frontend"
│
└── GOAL: "Launch" (not_started)
    ├── DEPENDENCY: → "Implementation Phase"
    ├── CONSTRAINT: "Must have 95%+ test coverage"
    └── ACCEPTANCE: "Released on app store"
```

### Example 2: Research Project

```
ROOT: "Analyze Migration Patterns" (in_progress)
│
├── GOAL: "Data Collection" (done)
│   ├── SUB: "Clean dataset" (done)
│   └── SUB: "Remove outliers" (done)
│
├── GOAL: "Analysis" (in_progress)
│   ├── SUB: "Temporal analysis" (in_progress) [Owner: S2]
│   ├── SUB: "Spatial analysis" (blocked)
│   │   └── BLOCKING: "Need better mapping library"
│   └── SUB: "Statistical hypothesis testing" (not_started)
│
├── GOAL: "Visualization" (not_started)
│   └── DEPENDENCY: → "Analysis"
│
└── GOAL: "Publication" (not_started)
    └── DEPENDENCY: → "Visualization"
```

User receives AI suggestion:

```
AI: "I see 'Spatial analysis' is blocked on 'better mapping library'.
     I found a promising library: maplibre-gl. Should I:
     (1) Add as sub-goal: 'Evaluate maplibre-gl'
     (2) Update constraint: 'Must use maplibre-gl'
     (3) Create worker to prototype with it?"

User: "Add sub-goal and create worker"
→ Graph updated, new worker session created
```

## Security Considerations

### Intent Privacy

The Intent Graph is project-local. Only the user and their sessions can read it. It does not leave the project without explicit export.

### Audit Trail

Changes to the Intent Graph are recorded (in Git). Who changed what, when, and why are all tracked.

### AI Proposals

AI-proposed changes are clearly marked and require user confirmation. The system does not silently accept AI suggestions.

### Constraint Enforcement

Constraints are not enforced automatically (the system does not prevent a user from violating a constraint). Instead, constraints are advisory and logged for auditing.

## MVP Scope

**The Intent Graph is built last.** It is a leaf in the dependency graph (RFC-0099): the
execution model, the content graph, and the agent loop all work without it, and nothing depends
on it. It is also the hardest of the three graphs to get right. Building it early is how a
project spends a year on planning machinery before proving the execution loop.

**MVP is a task list, not a DAG.** Concretely:

1. Flat or single-level goals with titles, descriptions, and priority.
2. `TARGETED` edges from Runs, so the list knows what is being worked on.
3. **Derived status** — this is not deferrable, because retrofitting derivation after a stored
   `status` field exists means migrating data that was never trustworthy.
4. User assertions with provenance.
5. **The proposal gate** — sessions propose, only users resolve. Also not deferrable: a system
   that ships with sessions writing intent directly cannot later be told to stop, because by
   then the graph is full of unreviewed model output and nobody can tell which parts the user
   actually wanted.
6. Git snapshot of the graph, with conflict detection (RFC-0053).

The MVP does not include:

- Sub-goal hierarchies, dependencies, and the acyclicity checker — a task list has no cycles.
- Constraints and acceptance criteria as node types.
- Confirmed `IMPLEMENTS` edges (MVP shows `NEEDS_REVIEW` and stops there).
- Proposal expiry sweeps, `SUPERSEDED` detection, and accept-with-edits (MVP is accept or
  reject whole).
- Visual editing, templates, diff/merge UI.

### A note on presentation

The Intent Graph is a graph in the data model. It should **not** be a node-link diagram in the
UI. A force-directed canvas is unusable on a phone and only marginally useful on a desktop.

The forms that work: an **outline or checklist** with derived status and a visible override
marker; a **"what's next"** query answering priority and blockers; and a **timeline** of
status transitions. Reserve any canvas view for a desktop power-user feature, if ever.

The same principle applies to the other two graphs: the Execution Graph presents as a timeline,
and the Resource Graph as an on-demand provenance trail ("why does this file look like this?").
Graphs are how the data is modelled, not how it is shown.

## Future Work

### Visual Editing

Interactive graph editor where users can:
- Drag to create nodes.
- Draw edges to connect goals.
- Visualize dependencies.
- See critical path.

### Graph Templates

Predefined structures for common projects:

```
"Web App Template": Includes typical phases (design, backend, frontend, launch)
"Research Template": Includes (literature review, experiments, analysis, publication)
"Data Pipeline Template": Includes (data collection, cleaning, processing, analysis)
```

### AI-Driven Decomposition

AI analyzes a goal and automatically suggests a decomposition:

```
Goal: "Build an e-commerce platform"
AI suggests:
  - User Management System
  - Product Catalog
  - Shopping Cart
  - Payment Processing
  - Order Management
  - Admin Dashboard
  With dependencies and estimated effort
```

### Constraint Propagation

Automatically propagate constraints down the graph:

```
Constraint on root goal: "Must work offline"
→ Propagates to all sub-goals
→ All implementations must support offline
```

### Critical Path Analysis

Compute the critical path (longest dependency chain) to identify scheduling bottlenecks.

### Semantic Intents

Enrich nodes with semantic information:

```
Node: "Implement payment integration"
Tags: ["payment", "stripe", "critical", "security"]
Risks: ["PCI compliance", "fraud detection"]
Related domains: ["backend", "financial-services"]
```

AI and knowledge engines can use semantic tags for better matching.

### Graph Merge and Branching

Support alternative branches of the Intent Graph:

```
Main branch: Implement feature X
Experiment branch: Try alternative approach
Compare branches, merge best version back
```

### Intent Replay

Replay the Intent Graph as it was at any point in time:

```
"Show me the project goals as of 2025-07-15"
→ Reconstruct graph from Git history at that date
```

## Resolved questions

Every one of these concerned machinery the MVP explicitly excludes — hierarchies, dependencies,
and the acyclicity checker are all out of scope, because a task list has no cycles. They are
answered here rather than left open, since an open question on a leaf subsystem reads as unsettled
design when it is really unscheduled work.

- **Severity levels on constraints?** Not in the MVP; constraints are not a node type there.
  Revisit with a real case where two constraints conflict and the resolution differs by severity.
- **Circular dependencies — prevented or flagged?** Neither, in the MVP: a flat task list cannot
  express a cycle. When dependencies land, **flagged**, consistent with D10's derived status —
  a cycle makes status `STALE` rather than making the write fail, because refusing the write
  loses the user's input to protect an invariant they can see.
- **Node owners?** No. Single-user is a design assumption (D16, RFC-0046), and "which session"
  is already answered by `TARGETED` edges without a column that would need migrating when it is
  wrong.
- **Scale to thousands of nodes?** Not a real case for a personal project graph. The Execution
  and Resource graphs are the ones that grow; this one is bounded by what a person will type.
- **Confidence scores?** No. `asserted_status` with provenance already distinguishes a user's
  claim from a derived value, and a confidence number on a goal is a number nobody can calibrate.
- **Automatic evaluation of acceptance criteria?** Yes, by design — `check_kind` exists for
  exactly that, and D6 requires the verifier be a mechanical check or the user, never a session.
  Not in the MVP, which stops at `NEEDS_REVIEW`.
- **A/B testing of alternative paths?** No. That is a branching-strategy question, and Git
  already answers it.
