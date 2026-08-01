# RFC-0014: Artifacts

Status: Accepted

## Abstract

Artifacts are immutable outputs created by sessions. They record the tangible results of work: plans, patches, reports, generated code, test results, transcripts, images, and analyses. Artifacts are immutable once created, carry full provenance information (who created them, from which intent, at what time, from which artifacts), and form a lineage graph that traces the history of work. Artifacts are the audit trail and evidence of productivity in Aidos.

## Motivation

Work produces outputs. A session queries an AI to design an API, and the AI's response is an output. A worker writes tests, and the test file is an output. A report summarizes findings, and the document is an output.

These outputs need to be:

- **Preserved**: They should not disappear when the session ends.
- **Attributed**: It should be clear who created them and when.
- **Immutable**: Once created, they should not change (to maintain audit integrity).
- **Lineaged**: It should be possible to trace back "where did this come from?"
- **Discoverable**: Sessions should be able to find relevant artifacts.
- **Referenceable**: Artifacts should be citable in other artifacts and decisions.

Artifacts solve these requirements. They are distinct from:

- **Resources** (RFC-0013): Resources are mutable (architecture documents evolve). Artifacts are immutable.
- **Intent Graph nodes** (RFC-0012): The Intent Graph describes what should happen. Artifacts show what actually happened.
- **Git commits**: Artifacts are higher-level outputs (plans, reports, summaries). Git commits are low-level code changes.

## Goals

1. **Define artifact semantics**: What is an artifact? What makes it immutable?

2. **Establish artifact types**: What kinds of outputs are artifacts? How do they differ?

3. **Specify provenance tracking**: How do we record where each artifact came from?

4. **Define relationships**: How do artifacts relate to sessions, intent, and other artifacts?

5. **Clarify lifecycle**: When is an artifact created, updated, and archived?

6. **Explain querying and discovery**: How do sessions find relevant artifacts?

## Non-goals

This RFC does not specify exact artifact storage mechanisms (file, database, Git). That is implementation detail.

This RFC does not mandate versioning for artifacts. Artifacts are immutable; versioning is not needed (though old versions can be archived as separate artifacts).

This RFC does not address artifact merging or conflict resolution. Single-user is the design assumption.

This RFC does not specify the exact format or structure of provenance metadata. The semantics matter; encoding varies.

## Design

### What Is an Artifact?

An **Artifact** is an immutable output created by a session. It represents the tangible result of work. Artifacts are:

- **Immutable**: Once created, artifacts do not change. To create a new version, a new artifact is created.
- **Attributed**: Every artifact records its creator (which session), creation time, and optionally, the intent it addresses.
- **Lineaged**: Artifacts track their parents (which artifacts they were derived from).
- **Discoverable**: Artifacts are tagged and queryable so sessions can find them.
- **Auditable**: The complete lineage of an artifact can be traced back to its origins.

### Artifact Types

Artifacts encompass a wide variety of outputs:

#### Plans and Designs

High-level abstractions and strategies:

- **Design Document**: Proposed system design, architecture, API design.
- **Plan**: Step-by-step plan to achieve a goal (e.g., "plan to implement payments in 3 phases").
- **Proposal**: Suggested approach to a problem with tradeoffs.
- **Specification**: Detailed specification for a component or feature.

Example:

```
Artifact Type: Design Document
Title: "Weather App API Design"
Created by: Session S1
At: 2025-08-01 10:30
From intent: goal_weather_app_design

Content:
# Weather API Design

## Endpoints
- GET /weather/current
- GET /weather/forecast?days=7
- POST /weather/alert (with geolocation)

## Data Model
[...]

## Rationale
Design emphasizes offline capability (cache-first) and real-time updates.
```

#### Generated Code

Code output by sessions:

- **Function Implementation**: A single function or small module.
- **File**: A complete source file (src/main.rs, app.py, etc.).
- **Codebase**: A larger chunk of code (entire service, library).
- **Patch**: A diff or patch ready to be applied.

Example:

```
Artifact Type: Patch
Title: "Implement offline caching for API"
Created by: Session S1
At: 2025-08-01 14:20
Parent artifacts: [design_document_artifact_id]

Content:
--- a/src/api.rs
+++ b/src/api.rs
@@ -42,6 +15,18 @@
 impl Client {
+  fn cache_response(&self, key: &str, value: String) {
+    self.cache.insert(key, value);
+  }
+
   pub async fn get_weather(&self, lat: f32, lon: f32) -> Result<Weather> {
     // Try cache first
+    if let Some(cached) = self.cache.get(&format!("{},{}", lat, lon)) {
+      return Ok(cached);
+    }
```

#### Analysis and Reports

Synthesized understanding:

- **Report**: Analysis of code, data, or findings (e.g., "test coverage report", "performance analysis").
- **Summary**: High-level overview of work done.
- **Transcript**: Conversation history or reasoning trace.
- **Findings**: Conclusions from analysis or research.

Example:

```
Artifact Type: Report
Title: "Test Coverage Analysis"
Created by: Session S2
At: 2025-08-02 09:15

Content:
# Test Coverage Report

## Summary
Overall coverage: 87% (up from 82%)

## Coverage by module:
- src/api: 92%
- src/db: 78%
- src/cache: 95%

## Gaps:
- Error handling in db connection retry logic (currently 0%)
- Edge cases in rate limiter (currently 45%)

## Recommendations:
1. Add tests for db retry scenarios
2. Increase rate limiter test coverage
```

#### Transcripts and Logs

Records of activity:

- **Conversation Transcript**: Record of exchanges with AI.
- **Execution Log**: Record of tool execution (shell commands, API calls).
- **Decision Log**: Record of decisions made and reasoning.

Example:

```
Artifact Type: Conversation Transcript
Title: "API Design Discussion"
Created by: Session S1
At: 2025-08-01 11:00

Content:
[Session → AI]
"I need to design an API for weather data. Requirements:
- Offline support
- Real-time updates
- Mobile-friendly"

[AI → Session]
"Here's my recommendation:
1. Use local cache (SQLite) for offline support
2. Use WebSocket for real-time updates
3. Keep response payloads < 1MB for mobile"

[Session → AI]
"The WebSocket approach concerns me. How do we handle connection drops?"

[AI → Session]
"Good question. Implement automatic reconnect with exponential backoff..."
```

#### Media and Artifacts

Non-text outputs:

- **Image**: Screenshot, diagram, visualization.
- **Dataset**: CSV, JSON data.
- **Archive**: ZIP or tarball of multiple files.

### Provenance

Every artifact carries complete provenance:

```
Artifact Provenance {
  creating_session: SessionId         # Which session created this
  created_at: Timestamp               # When
  
  addressed_intent: IntentNodeId?     # Which goal/task does this address
  
  parent_artifacts: List<ArtifactId>  # What was this derived from
  related_artifacts: List<ArtifactId> # What artifacts are related
  
  creator_notes: String?              # Why was this created? What does it represent?
  
  metadata: Map<String, Any>?
}
```

Provenance allows tracing back:

```
Artifact: "Payment system implementation"
← Parent: "Payment API design"
  ← Parent: "Business requirements"
    ← Parent: "Product roadmap resource"
```

This lineage shows the complete chain from business need to implementation.

### Artifact Lifecycle

#### Creation

An artifact is created when a session produces something worthy of preservation:

```
Session performs work (write code, run AI query, analyze data)
Session decides to create an artifact
Artifact is recorded with:
  - ID
  - Type
  - Content
  - Provenance (creating session, time, intent, parents)
  - Tags
  - Status: "draft"
```

Initially, artifacts are "draft" (not yet finalized or reviewed).

#### Finalization

A session or user reviews and finalizes the artifact:

```
Artifact status: draft → final
Meaning: This artifact is considered ready for use
CI might run (tests, linting) to verify code artifacts
Metadata (tags, related artifacts) is reviewed
```

#### Usage

Finalized artifacts are used:

- Cited in other artifacts ("based on design document X").
- Applied (patches are applied to code).
- Referenced in decisions or plans.
- Analyzed for patterns and examples.

#### Archival

Old artifacts can be archived:

```
Artifact status: final → archived
Meaning: This artifact is historical, not current
It is still queryable and traceable
But it's not recommended for current use
```

### Artifact Relationships

Artifacts form a graph of relationships:

```
Design Document (artifact_1)
  ↓ derived from
Requirements (artifact_2)
  ↓ derived from
User Research (artifact_3)

Implementation Patch (artifact_4)
  ↓ derived from
Design Document (artifact_1)

Test Report (artifact_5)
  ↓ derived from
Implementation Patch (artifact_4)
```

Relationships include:

- **Parent**: This artifact was derived from that artifact.
- **Related**: These artifacts are related but don't have a derivation relationship.
- **Cites**: This artifact references that resource (RFC-0013) or artifact.
- **Addresses**: This artifact addresses that intent node (RFC-0012).

### Discovery and Querying

Sessions need to find relevant artifacts:

#### Search

Sessions search by keyword:

```
Session queries: "Find artifacts about payment"
System returns: [Payment API design, Payment patch, Payment tests, ...]
```

#### Tagging

Artifacts are tagged:

```
Artifact: "Offline caching implementation"
Tags: ["caching", "offline", "performance", "backend"]

Session queries: "Find artifacts tagged 'offline'"
System returns: [artifacts with offline tag]
```

#### Type Filtering

Sessions can filter by artifact type:

```
Session queries: "Find all test results"
System returns: [test_report_1, test_report_2, ...]
```

#### Lineage Navigation

Sessions can trace lineage:

```
Session looks at artifact X
Sees parents: [X1, X2]
Follows parent X1
Sees it was derived from [Y1]
Follows Y1
Finds original source
```

#### Intent-based Discovery

Sessions can find artifacts that address specific intent nodes:

```
Intent node: "Implement payment system"
Query: "Find artifacts addressing this intent"
Returns: [design doc, implementation patch, test report, API spec]
```

### Artifact Mutations (Rare)

While artifacts are immutable in principle, rare situations may require updates:

- **Metadata updates**: Tags, status, related artifacts (does not change content).
- **Corrections**: If an artifact contains factually incorrect information (typo, bug), a correction can be applied (recorded as an explicit amendment).

Content corrections are rare and must be audited:

```
Artifact X (original)
Amendment 1: "Fixed typo in line 3"
Amendment 2: "Corrected algorithm description"

Viewers see both original and amendments
Full audit trail preserved
```

## Data Model (Conceptual)

```
Artifact {
  id: UUID                          # Unique within project
  project_id: UUID
  
  type: ArtifactType                # plan, design, code, report, transcript, etc.
  title: String
  description: String?
  
  content: Bytes                    # File content (text, binary, etc.)
  content_type: String              # "text/plain", "application/json", "text/patch"
  content_hash: String              # SHA256 for integrity
  
  status: ArtifactStatus            # draft, final, archived
  
  created_at: Timestamp
  created_by: SessionId
  finalized_at: Timestamp?
  finalized_by: SessionId | UserId?
  
  provenance: ArtifactProvenance {
    addressed_intent: IntentNodeId?
    parent_artifacts: List<ArtifactId>
    related_artifacts: List<ArtifactId>
    creator_notes: String?
  }
  
  tags: List<String>
  
  amendments: List<Amendment>?      # For rare corrections
  
  metadata: Map<String, Any>?
}

Amendment {
  id: UUID
  applied_at: Timestamp
  applied_by: UserId | SessionId
  reason: String
  content_delta: Diff?              # Or full replacement for severe corrections
}
```

## Lifecycle Examples

### Example 1: Code Generation Workflow

```
1. Session S1 queries AI: "Generate user auth module"
   AI responds with code

2. Session creates artifact
   Type: File
   Content: auth.rs (generated code)
   Addressed intent: goal_user_auth
   Status: draft
   Tags: ["authentication", "backend", "generated"]

3. Session runs tests on the code
   Tests fail on edge case

4. Session revises code, creates new artifact
   Type: File  
   Content: auth.rs (revised)
   Parent: [auth.rs draft artifact]
   Status: draft

5. Tests pass
   Artifact status: draft → final

6. Artifact is applied (committed to repository)
   Used as basis for implementation
```

### Example 2: Analysis Report Chain

```
1. Session S1 runs code analysis tool
   Artifact: Code Quality Report (draft)
   Tags: ["analysis", "code-quality"]

2. Session S2 reviews findings
   Creates artifact: Summary of findings
   Parent: [Code Quality Report]
   Status: final

3. Session S3 reads summary
   Creates artifact: Refactoring Plan (addressing findings)
   Parents: [Code Quality Report, Summary]
   Addressed intent: goal_improve_code_quality
   Status: draft

4. Plan is approved
   Status: final

5. Implementation happens
   Artifact: Refactored code patches
   Parents: [Refactoring Plan]
   Status: final

6. Tests run post-refactoring
   Artifact: Post-refactoring test report
   Parents: [Refactored code patches]
   Compares to: [Pre-refactoring test report]
```

## Security Considerations

### Artifact Integrity

Artifacts are immutable to prevent tampering. Content hashes ensure integrity can be verified.

### Access Control

Artifacts are project-local. They do not leave the project without explicit export.

### Sensitive Content

Artifacts should not contain unencrypted secrets. If artifacts reference secrets (e.g., "use API key stored in project config"), the secret itself is not included.

### Audit Trail

All artifact operations (creation, finalization, archival, amendments) are logged.

## MVP Scope

The MVP artifact model includes:

1. **Basic artifact types**: Plans, designs, generated code, reports, transcripts.
2. **Creation and storage**: Sessions create artifacts; they are stored.
3. **Tagging and discovery**: Artifacts can be tagged and searched.
4. **Provenance**: Record of creating session, time, addressed intent.
5. **Parent relationships**: Track which artifacts this was derived from.
6. **Status tracking**: Draft and final status.
7. **Immutability**: Artifacts do not change (amendments are rare/future).

The MVP does not include:

- Amendments or corrections (future).
- Complex relationship queries (future).
- Media handling beyond text (future).
- Artifact versioning (future; use separate artifacts for versions).
- Artifact publishing to external systems (future).

## Future Work

### Artifact Amendments

Formalize the amendment process for rare corrections:

```
Artifact has typo
User proposes amendment: "Fix spelling on line 42"
Amendment is applied, recorded in audit trail
Viewers see both original and amendment
```

### Artifact Versioning

Instead of amendments, create new artifacts for versions:

```
Artifact v1: "API Design (initial)"
Artifact v2: "API Design (after feedback)"
Artifact v3: "API Design (final)"

Each is immutable; versions are separate artifacts.
Provenance links show evolution.
```

### Rich Relationships

Support more complex artifact relationships:

```
"This implementation patch is based on this design"
"This test report validates this implementation"
"This refactoring is an improvement on this prior implementation"
```

### Artifact Graphs and Dependency Analysis

Compute artifact lineage DAGs, critical paths, and dependency analysis:

```
"Which artifacts are blocking progress?"
"What is the minimum set of artifacts needed to understand this feature?"
"Show the complete lineage of this production code."
```

### Artifact Publishing

Export artifacts to external systems:

```
Export design documents to wiki
Export reports to collaboration platform
Export code patches to GitHub
```

### Artifact Search and Recommendation

Semantic search over artifact content:

```
"Find artifacts about performance optimization"
→ Search artifact embeddings
→ Return relevant artifacts even if not explicitly tagged
```

Recommend relevant artifacts based on session activity:

```
Session is implementing a cache
→ Recommend: Prior cache implementations, performance reports
```

### Artifact Compression and Summarization

For large artifacts or when storage is limited:

```
Compress old artifacts
Summarize long reports (keep summary, archive details)
Extract key findings from transcripts
```

### Immutable Provenance Chains

Use cryptographic signatures to create tamper-evident artifact lineage:

```
Artifact is signed
Each parent link is signed
Provenance chain is verifiable
```

## Open Questions

- Should artifacts have access control (only certain sessions can read/create them)?
- How should artifact amendments be handled? Create new artifact, or allow in-place correction?
- Should large artifacts (e.g., entire codebases) be stored as artifacts, or only references?
- How should artifact discovery scale if there are millions of artifacts?
- Should artifacts support comments or annotations from other sessions?
- Should there be a "review" workflow where artifacts require approval before becoming "final"?
- How should artifact conflicts be handled if two sessions create conflicting artifacts addressing the same intent?
- Should artifacts have expiration dates (auto-archive after N days)?
- How should artifacts relate to Git commits? Is a commit an artifact?
