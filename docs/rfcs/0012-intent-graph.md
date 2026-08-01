# RFC-0012: Intent Graph

Status: Accepted

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
  status: GoalStatus (not_started | in_progress | blocked | done | archived)
  owner: SessionId?
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
  status: GoalStatus
  estimated_effort: String? ("small", "medium", "large")
  owner: SessionId?
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
  status: "active" | "relaxed" | "superseded"
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
  is_met: Boolean
  verified_by: SessionId? | UserId?
  verified_at: Timestamp?
}
```

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
  id: NodeId                        # Unique within the graph
  project_id: UUID                  # Backref to project
  
  type: NodeType                    # Goal, SubGoal, Constraint, etc.
  title: String
  description: String?
  
  status: NodeStatus                # not_started | in_progress | blocked | done | archived
  priority: Int                     # 1=highest, determines ordering
  
  owner: SessionId?                 # Which session is working on this
  
  created_at: Timestamp
  created_by: SessionId | UserId
  modified_at: Timestamp
  modified_by: SessionId | UserId
  
  constraints: List<NodeId>         # Constraints that apply
  acceptance_criteria: List<NodeId> # Conditions for "done"
  
  dependencies: List<NodeId>        # Other nodes this depends on
  dependents: List<NodeId>          # Nodes that depend on this
  
  related_artifacts: List<ArtifactId>  # Artifacts addressing this intent
  related_resources: List<ResourceId>  # Resources relevant to this intent
  
  tags: List<String>                # For querying ("api", "backend", "ui")
  
  metadata: Map<String, Any>?
}
```

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

#### AI Systems (Proposed Edits)

AI sessions can propose modifications to the Intent Graph:

```
AI: "I notice you want an API that works offline. This is challenging because
     REST APIs typically require network. I recommend using a local database
     with sync-on-connect. Should I add this as a sub-goal under 'Design API'?"

User: "Yes, add it"
→ New sub-goal added to graph, marked as "proposed" until user confirms
```

AI proposals should:
- Be clearly marked as "proposed" or "suggested".
- Include reasoning and tradeoffs.
- Require user confirmation before being incorporated.
- Be logged as part of the audit trail.

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

1. **Understand intent**: Read the Intent Graph, understand what the project is trying to achieve.

2. **Propose refinements**: Suggest breaking down a goal, adding constraints, reordering priorities.

3. **Update status**: Mark goals as done when acceptance criteria are met.

4. **Relate artifacts**: Connect created artifacts to the intent nodes they address.

5. **Flag conflicts**: Detect when two goals conflict or dependencies are unsatisfiable.

Example workflow:

```
Driver session reads Intent Graph
→ Sees goal: "Implement payment system"
→ Queries AI: "Break this down into concrete tasks"
→ AI proposes sub-goals: "Design payment schema", "Implement Stripe integration", etc.
→ Driver approves
→ AI creates worker sessions for each sub-goal
```

## Data Model (Conceptual)

```
IntentGraph {
  id: UUID
  project_id: UUID
  
  nodes: Map<NodeId, IntentNode>
  edges: List<Edge>                 # Dependencies and relationships
  
  root: NodeId                      # Top-level goal (typically)
  
  created_at: Timestamp
  version: Int                      # Git commit count or sequential
  
  metadata: Map<String, Any>?
}

Edge {
  source: NodeId
  target: NodeId
  type: EdgeType                    # dependency, constraint_applies_to, etc.
  label: String?
}
```

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

The MVP Intent Graph includes:

1. **Goal hierarchy**: Goals and sub-goals.
2. **Constraints and criteria**: Add constraints and acceptance criteria to goals.
3. **Status tracking**: Track progress (not_started, in_progress, done).
4. **Simple versioning**: Basic Git-based versioning.
5. **Querying**: Sessions can query the graph (what's the next priority? what goals are blocked?).
6. **Editing**: Users can directly edit the graph via UI or API.
7. **AI annotations**: AI sessions can link artifacts to intent nodes.

The MVP does not include:

- Visual graph editing (future).
- Graph templates (future).
- Automatic conflict detection (future).
- Sophisticated diff/merge (future).
- Full proposal workflow for AI (future).

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

## Open Questions

- Should constraints have severity levels (must_have vs. should_have)? This would support prioritization when constraints conflict.
- How should the Intent Graph handle circular dependencies? Should they be prevented or flagged?
- Should nodes have "owners" (sessions or users)? Would this help with responsibility and assignment?
- How should the Intent Graph scale to very large projects with thousands of nodes?
- Should there be a "confidence" score on nodes, reflecting uncertainty?
- Should acceptance criteria be automatically evaluated (e.g., test coverage must be > 90%)?
- How should the Intent Graph support A/B testing of alternative paths?
