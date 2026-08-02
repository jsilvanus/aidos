# RFC-0013: Resources

Status: **Superseded by RFC-0024**

> **This RFC is retained for its motivation and vocabulary, not as an implementation target.**
>
> RFC-0024 (Resource Graph) unifies resources and artifacts into a single `ContentNode`
> abstraction in which mutability is a *policy field* rather than a type distinction. A
> "resource" is a `ContentNode` with `MutabilityPolicy.VERSIONED` or `MUTABLE_LATEST`.
>
> The distinction this RFC draws is real and worth understanding — outputs become inputs, and
> the promotion path matters — but maintaining two parallel content models produced conflicting
> storage rules and two places to enforce egress policy. Implement RFC-0024.

## Abstract

Resources are mutable project knowledge that persist across time and are reused by multiple sessions. Resources capture the durable, evolving understanding of a project: architecture decisions, coding standards, design guidelines, roadmaps, meeting notes, domain knowledge. Unlike artifacts (which are immutable outputs), resources are living documents that grow and refine as the project progresses. Resources are versioned through Git and are the primary knowledge base that informs session decision-making.

## Motivation

Projects accumulate knowledge over time:

- Why did we choose this architecture?
- What coding conventions should we follow?
- What decisions have we already made?
- What are the known limitations or gotchas?
- What prior work exists on this problem?

This knowledge needs to be:

- **Persistent**: It survives across sessions and restarts.
- **Discoverable**: Sessions should find relevant resources when they need them.
- **Evolving**: Resources should be updated as understanding improves.
- **Authoritative**: Resources are the canonical source of project knowledge (not conversation history).
- **Reusable**: One resource can inform multiple sessions' decisions.
- **Attributed**: It should be clear who contributed each piece of knowledge and when.

Resources solve these requirements. They are distinct from:

- **Artifacts** (RFC-0014): Artifacts are immutable outputs (plans, patches, reports). Resources are mutable inputs (standards, guidelines, decisions).
- **Conversation**: Conversation is ephemeral. Resources are permanent.
- **Intent Graph** (RFC-0012): The Intent Graph describes what should happen (goals). Resources describe how to achieve it (knowledge, patterns, standards).
- **Code in Git**: Resources are metadata and decision records, not executable code.

## Goals

1. **Define resource semantics**: What constitutes a resource? What is its lifecycle?

2. **Establish resource types**: What kinds of resources are common? How do they differ?

3. **Specify resource discovery**: How do sessions find relevant resources?

4. **Define editability and versioning**: How are resources maintained and evolved?

5. **Clarify relationships**: How do resources relate to sessions, artifacts, the Intent Graph, and Git?

6. **Explain resource indexing**: How are resources indexed and searched?

## Non-goals

This RFC does not specify the exact file format for resources (Markdown, RST, AsciiDoc, etc.). Multiple formats are acceptable.

This RFC does not mandate the exact tagging or discovery mechanism. Semantics are what matter; implementation varies.

This RFC does not address multi-user resource editing (future work). Single-user is the design assumption.

This RFC does not specify how to handle resource conflicts or versioning strategies. Basic Git versioning is sufficient for MVP.

## Design

### What Is a Resource?

A **Resource** is a persistent, mutable document or record that captures project knowledge. Resources are:

- **Project-scoped**: They belong to a project and are isolated to that project.
- **Reusable**: Intended to inform multiple sessions over time.
- **Evolving**: They can be updated as understanding improves.
- **Attributed**: Changes are tracked (who edited, when, why).
- **Discoverable**: Sessions can search and find relevant resources.

A resource is not executable code (that is in Git). A resource is not a goal or plan (that is the Intent Graph). A resource is not an output or artifact (that is immutable).

### Resource Types

Resources fall into several categories:

#### Architecture and Design

Capture high-level decisions about system structure:

- **Architecture Document**: "How is the system organized? What are the major components?"
- **Design Decisions**: "Why did we choose this architecture? What alternatives were considered?"
- **API Specification**: "What endpoints exist? What do they do?"
- **Data Model**: "What are the core entities and relationships?"
- **Tech Stack**: "What technologies are we using and why?"

Example:

```markdown
# Architecture

## Overview
Our system uses a microservices architecture with:
- API Gateway (nginx)
- User Service (Rust)
- Product Service (Rust)
- Order Service (Node.js)
- Message Queue (RabbitMQ)

## Why Microservices?
- Independent scaling of services
- Technology diversity (different services use different stacks)
- Team autonomy

## Trade-offs
- Operational complexity (many services to run)
- Distributed tracing overhead
- Eventual consistency challenges
```

#### Coding Standards and Conventions

Establish norms for how code is written:

- **Naming Conventions**: "How do we name variables, functions, classes?"
- **Style Guide**: "How should code be formatted?"
- **Testing Standards**: "What is our test coverage target?"
- **Documentation Requirements**: "What must be documented?"
- **Git Workflow**: "How do we branch and merge?"

Example:

```markdown
# Coding Standards

## Naming
- Classes: PascalCase
- Functions: snake_case
- Constants: UPPER_SNAKE_CASE
- Private members: prefixed with underscore

## Testing
- Unit tests for all public functions
- Integration tests for API endpoints
- Minimum coverage: 80%
- Test file colocation: tests/ directory

## Code Review
- All PRs require review by 2+ maintainers
- CI must pass before merge
- No committing directly to main
```

#### Roadmap and Planning

Document the project's direction:

- **Product Roadmap**: "What features are planned? In what order?"
- **Release Plan**: "When do we release what?"
- **Milestones**: "What are the major checkpoints?"
- **Constraints and Assumptions**: "What are we assuming about the future?"

Example:

```markdown
# Roadmap

## Q3 2025
- [ ] User authentication
- [ ] Payment integration
- [ ] Real-time notifications

## Q4 2025
- [ ] Mobile app
- [ ] Analytics dashboard
- [ ] Enterprise features

## Constraints
- Must maintain backward compatibility
- API changes require 2-week deprecation period
```

#### Domain Knowledge

Capture subject-matter expertise:

- **Domain Glossary**: "What are the key terms in this domain?"
- **Domain Patterns**: "What patterns are common in this domain?"
- **Lessons Learned**: "What have we learned about this problem space?"
- **External Research**: "What do we know from literature, industry, best practices?"

Example:

```markdown
# Payment Processing

## Key Terms
- PCI DSS: Standards for handling payment card data
- Tokenization: Storing proxy tokens instead of actual card numbers
- 3D Secure: Additional authentication for card transactions

## Patterns
- Always use PCI-compliant payment processor (not handling cards directly)
- Retry failed transactions with exponential backoff
- Webhook verification is critical (verify processor's signatures)

## Common Mistakes
- Storing card numbers in database (PCI violation)
- Synchronous payment calls (causes timeouts)
- Ignoring webhook deliveries
```

#### Meeting Notes and Decisions

Record key discussions and conclusions:

- **Meeting Minutes**: "Who attended? What was decided?"
- **Decision Log**: "What decisions have we made and why?"
- **RFC Records**: "What proposals were accepted or rejected?"

Example:

```markdown
# Decision: Use Rust for backend

Date: 2025-07-15
Participants: [list]

**Decision**: Implement backend in Rust (not Python or Go)

**Rationale**:
- Performance: 10x+ faster than Python
- Memory safety: Fewer runtime errors
- Concurrency: Async/await is cleaner than Go

**Trade-offs**:
- Steeper learning curve
- Compilation time
- Smaller ecosystem than Go

**Alternatives Considered**:
- Go: Simpler, but garbage collection concerns
- Python: Familiar, but performance insufficient

**Status**: Approved 2025-07-15
```

#### Troubleshooting and FAQs

Capture common problems and solutions:

- **Known Issues**: "What bugs or limitations do we have?"
- **Workarounds**: "How do we work around known issues?"
- **FAQ**: "What questions do new developers have?"

Example:

```markdown
# Known Issues

## Issue: Tests hang on macOS
**Symptom**: `cargo test` never finishes
**Cause**: File watcher doesn't handle large projects
**Workaround**: Disable watcher: `RUST_BACKTRACE=1 cargo test --test-threads=1`
**Status**: Fixed in next release

## FAQ

Q: How do I run tests in Docker?
A: `docker-compose up test-runner`

Q: Why is the API slow?
A: Check if background indexing is running. Temporarily disable: `curl -X POST /admin/stop-indexing`
```

### Resource Lifecycle

#### Creation

A resource is created when:

1. A user explicitly creates a new resource (e.g., "Create architecture document").
2. A system component generates a resource (e.g., API spec is auto-generated from code).
3. A session creates a resource as part of its work.

On creation:

```
Allocate resource ID
Set name and type
Create initial content
Set ownership (who created it)
Set status: "draft"
Optionally tag it
Store in project database
Optionally commit to Git
```

#### Evolution

Resources evolve over time:

```
User/session edits content
Changes are tracked (diff, who, when)
Status may change: draft → review → published → archived
Tags may be added
New versions are created
```

#### Publication

A resource can be published (marked as authoritative):

```
Resource status: draft → published
Meaning: This resource is considered stable and trustworthy
Other sessions can cite this resource with confidence
Changes to published resources are flagged as potentially breaking
```

#### Archival

Old resources can be archived:

```
Resource status: published → archived
Meaning: This resource is historical, not current guidance
Sessions can still read it (for historical context)
But they won't discover it by default in searches
```

### Resource Discovery

Sessions need to find relevant resources. Discovery mechanisms include:

#### Search

Sessions can search by keyword:

```
Session queries: "Find resources about payment"
System returns: Payment Processing guide, API spec (payment endpoints), ...
```

#### Tagging

Resources are tagged with keywords:

```
Resource: "API Specification"
Tags: ["api", "documentation", "backend", "rest"]

Session queries: "Find resources tagged 'rest'"
System returns: [API Specification, REST best practices, ...]
```

#### Relationships

Resources can link to each other:

```
Resource A (Architecture Document) links to Resource B (Tech Stack Decision)
Resource C (Coding Standards) links to Resource A (for formatting context)

Session reading A sees references to B and C
```

#### Semantic Discovery (Future)

The Knowledge Engine (RFC-0015) can index and search resources semantically:

```
Session: "I need to implement a cache. What patterns have we used?"
Knowledge Engine searches resource embeddings
Returns: [Caching patterns document, Performance decisions, Prior cache implementation]
```

#### Context-Aware Discovery (Future)

Based on what a session is working on, suggest relevant resources:

```
Session is editing file: src/payment/processor.rs
System suggests: "Payment Processing" resource (tagged for this module)
```

### Resource Editing

#### User Edits

Users edit resources directly (via UI or by modifying files in Git):

```
User opens "Architecture Document"
Edits text
Saves
Change is tracked: timestamp, author, diff
```

#### AI Suggestions

AI sessions can suggest resource updates:

```
AI: "I notice the API spec is outdated (we added endpoints last month).
     Should I update the spec?"

User: "Yes"
→ AI-proposed changes are applied (with audit trail)
```

#### Collaborative Editing (Future)

Multiple sessions might want to edit a resource. Future work should handle:

- Concurrent edits with merge.
- Suggestion/review workflows.
- Approval before publication.

### Relationship to Other Concepts

#### Sessions (RFC-0011)

Sessions read and may update resources. A session's decisions are informed by resources. When a session completes work, it may update resources to record lessons learned.

#### Intent Graph (RFC-0012)

The Intent Graph describes goals. Resources provide the knowledge to achieve them. A goal might reference relevant resources:

```
Goal: "Implement payment system"
Referenced resources: [Payment Processing guide, API spec, Security standards]
```

#### Artifacts (RFC-0014)

Artifacts are immutable outputs (plans, patches). Resources are mutable inputs (knowledge, standards). An artifact might cite a resource:

```
Artifact: "Payment implementation plan"
References resources: ["Payment Processing guide", "API Specification"]
```

#### Git

Resources are versioned through Git. Each significant update to a resource is committed. The Git history is the audit trail.

#### Knowledge Engine (RFC-0015)

The Knowledge Engine indexes resources (along with code, tests, and other project knowledge). Sessions query the Knowledge Engine to find relevant resources.

## Data Model (Conceptual)

```
Resource {
  id: UUID                          # Unique within project
  project_id: UUID
  
  name: String
  type: ResourceType                # architecture, standards, roadmap, etc.
  description: String?
  
  content: String                   # Markdown, text, or other format
  content_format: String            # "markdown", "rst", "json", etc.
  
  status: ResourceStatus            # draft, published, archived
  
  created_at: Timestamp
  created_by: SessionId | UserId
  modified_at: Timestamp
  modified_by: SessionId | UserId
  
  tags: List<String>                # For discovery ("api", "backend", ...)
  
  linked_resources: List<UUID>      # Other resources referenced
  linked_artifacts: List<UUID>      # Artifacts that cite this resource
  linked_intent_nodes: List<UUID>   # Intent nodes that reference this
  
  version: Int?                     # Git-based versioning
  git_commit: String?               # Latest commit hash
  
  metadata: Map<String, Any>?
}
```

## Lifecycle Examples

### Example 1: Architecture Document Evolution

```
1. User creates resource "Architecture Document"
   Status: draft
   Content: Initial sketch of system design
   
2. Session S1 reads it, finds gaps
   Session updates the document with missing details
   Adds section on database layer
   
3. User reviews, publishes
   Status: draft → published
   
4. Months later, system changes
   Session S2 proposes: "Update architecture for new microservices"
   User reviews proposals, approves some
   
5. Document is updated
   Version 2 published
   Version 1 kept in Git history
   
6. New service is deprecated
   Relevant section archived (or moved to "legacy")
   Status: published (but marked as historical)
```

### Example 2: Coding Standards Emergence

```
1. Project starts, no formal standards
   
2. Session S1 implements feature, makes style choices
   S1 creates Resource: "Naming Conventions" (what we should do)
   Status: draft
   
3. Session S2 reads it, agrees with most
   Suggests refinement: "Also document constant naming"
   
4. User reviews, publishes standards
   Status: published
   
5. New developer joins (Session S3)
   First thing: Read coding standards
   Asks questions, resolves via PR to the resource
   
6. Over time, standards are maintained and refined
   Resource becomes the source of truth
```

## Security Considerations

### Resource Privacy

Resources are project-local. They do not leave the project without explicit export.

### Audit Trail

All changes to resources are tracked (Git commits, modification timestamps, who edited).

### Sensitive Information

Resources should not contain secrets (API keys, passwords). If secrets are referenced, they should be redacted or stored separately.

### Edit Permissions

Resources can be restricted to certain roles or sessions (future work). Not all sessions can edit all resources.

## MVP Scope

The MVP includes:

1. **Basic resource types**: Architecture, standards, roadmap, decisions, FAQ.
2. **Creation and editing**: Users can create and edit resources.
3. **Tagging**: Tag resources with keywords.
4. **Discovery**: Search by name and tags.
5. **Versioning**: Git-based versioning (commits).
6. **Status tracking**: Draft, published, archived.
7. **Session reading**: Sessions can read resources and cite them in artifacts.

The MVP does not include:

- Semantic discovery (future).
- AI-proposed edits (future).
- Resource approval workflows (future).
- Complex access control (future).
- Embedded media or rich formatting beyond text (future).

## Future Work

### Resource Approval Workflow

When a session or user proposes changes to a published resource, the changes require review:

```
Session proposes: "Update API spec"
→ Change is flagged as "pending review"
→ Owner/admin reviews, approves or rejects
→ If approved, becomes new published version
```

### Resource Templates

Predefined templates for common resource types:

```
"API Specification Template": Includes sections for endpoints, parameters, responses
"Architecture Template": Includes sections for components, dependencies, decisions
"Decision Log Template": Includes date, participants, rationale, alternatives
```

### Semantic Indexing

The Knowledge Engine indexes resources semantically:

```
Session: "How do we handle rate limiting?"
Knowledge Engine searches semantic index of resources
Returns resources about rate limiting (even if not explicitly tagged)
```

### Resource Graphs

Resources can form graphs of relationships:

```
Architecture Document
  ↓ references
Tech Stack Decision
  ↓ used by
Coding Standards
  ↓ informs
Code Review Checklist
```

### Collaborative Resource Editing

Multiple sessions can edit a resource concurrently with proper conflict resolution.

### Resource Publishing to External Systems

Export resources to external documentation systems (wiki, confluence, etc.).

### Auto-Generated Resources

Some resources can be auto-generated from code:

```
API Specification: Generated from code annotations
Dependency Graph: Generated from build system
Test Coverage Report: Generated from test runs
```

### Resource Recommendations

Based on a session's work, recommend relevant resources:

```
Session is implementing a cache
→ System recommends: "Caching patterns" resource
```

## Open Questions

- Should resources have different permission levels (read-only for some sessions)?
- How should resource discovery scale if there are thousands of resources?
- Should resources be organized hierarchically (parent/child)?
- How should AI-generated resource suggestions be weighted vs. user-created?
- Should there be a "change log" showing all edits to a resource?
- How should resources handle versioning if Git is unavailable (MVP offline)?
- Should resources support embedded media (images, diagrams)?
- How should cross-project resource sharing work (future)?
