# RFC-0024: Resource Graph

Status: Draft

## Abstract

This RFC defines a Resource Graph that unifies mutable resources, immutable artifacts, provenance links, and storage locations under a common identity and lifecycle model. It establishes the distinction between resources and artifacts through lifecycle and mutability policies, defines promotion and demotion mechanics, and specifies the query model for finding content by kind, provenance, or scope.

## Motivation

RFC-0013 (Resources) and RFC-0014 (Artifacts) define resources and artifacts as conceptually distinct:
- Resources: mutable, long-lived context (architecture docs, coding standards, roadmaps)
- Artifacts: immutable outputs (patches, reports, screenshots, transcripts)

In practice, the boundary is operationally fuzzy. An artifact (e.g., a generated architecture review) may later be treated as a resource (referenced as an authoritative document). A resource (e.g., a requirements file) may be produced by a session as an artifact and then promoted.

Without a unified graph:
- Provenance chains cross the resource/artifact boundary with no formal representation.
- Policy enforcement (egress eligibility, redaction) must be duplicated.
- Search across all content objects in a project requires querying two separate stores.
- The session cannot answer "what content objects does this project contain and how are they related?"

## Goals

1. Define a unified `ContentNode` abstraction that covers both resources and artifacts.
2. Define mutability policy as a property of the node, not its type.
3. Define provenance edges between content nodes.
4. Define promotion (artifact → resource) and demotion mechanics.
5. Define the query model for finding content by kind, scope, provenance, and sensitivity.
6. Define storage location mapping for each content node.

## Non-goals

This RFC does not define the storage engine (RFC-0040).
It does not define search algorithms or indexing (RFC-0015 Knowledge Engine).
It does not define artifact creation mechanics (RFC-0014).
It does not define resource editing mechanics (RFC-0013).

## Design

### The ContentNode

Every piece of content in a project is a ContentNode. Nodes differ by their mutability policy, not by their type.

```kotlin
data class ContentNode(
    val id: UUID,
    val projectId: UUID,
    val kind: ContentKind,
    val name: String,
    val description: String?,
    val mutabilityPolicy: MutabilityPolicy,
    val sensitivityLevel: SensitivityLevel,
    val egressEligibility: EgressEligibility,
    val storageLocation: StorageLocation,
    val contentHash: String,           // SHA-256 of content
    val contentType: String,           // MIME type
    val sizeBytes: Long,
    val createdAt: Instant,
    val createdBy: UUID,               // session or user ID
    val updatedAt: Instant,
    val updatedBy: UUID?,
    val version: Int,                  // increments on each update (for mutable nodes)
    val state: ContentNodeState,
    val tags: List<String>
)

enum class ContentKind {
    // Resource kinds (mutable by default)
    ARCHITECTURE_DOCUMENT,
    CODING_STANDARD,
    REQUIREMENTS,
    DECISION_LOG,
    ROADMAP,
    INSTRUCTION_FILE,  // AGENTS.md, CLAUDE.md, etc.
    NOTE,
    CUSTOM_RESOURCE,

    // Artifact kinds (immutable by default)
    CODE_PATCH,
    CODE_FILE,
    REPORT,
    TRANSCRIPT,
    PLAN,
    SCREENSHOT,
    LOG,
    EXPORT_ARCHIVE,
    CUSTOM_ARTIFACT
}

enum class MutabilityPolicy {
    IMMUTABLE,         // Cannot be modified after creation (artifacts)
    APPEND_ONLY,       // Can only have new content added (logs, transcripts)
    VERSIONED,         // Can be modified; all versions are preserved (resources)
    MUTABLE_LATEST     // Can be modified; only the latest version is preserved
}

enum class SensitivityLevel {
    PUBLIC,            // Safe to include in prompts, exports, logs
    INTERNAL,          // Safe for local operations, not for remote models by default
    SENSITIVE,         // Must not be sent to remote models without explicit approval
    SECRET             // Must not appear in prompts, exports, or logs
}

enum class EgressEligibility {
    ELIGIBLE,          // May be sent to remote models/APIs
    REQUIRES_APPROVAL, // User must approve before sending
    BLOCKED            // Never sent externally
}
```

### ContentNodeState

```
CREATING → ACTIVE → SUPERSEDED → ARCHIVED
                 ↓
              DELETED
```

- CREATING: content is being written to storage. Not yet queryable.
- ACTIVE: content is available and current.
- SUPERSEDED: an updated version exists (only for VERSIONED mutability).
- ARCHIVED: content is preserved but no longer active.
- DELETED: the content has been removed. The node record is retained for provenance.

IMMUTABLE nodes transition: CREATING → ACTIVE only. They can never be SUPERSEDED or DELETED while referenced by provenance edges from ACTIVE nodes.

### Storage Locations

Each ContentNode has a StorageLocation that identifies where the content is stored:

```kotlin
sealed class StorageLocation {
    data class SqliteBlob(val tableRef: String, val rowId: UUID) : StorageLocation()
    data class FilesystemPath(
        val relativePath: String,  // relative to project root
        val gitTracked: Boolean
    ) : StorageLocation()
    data class GitObject(
        val commitHash: String,
        val blobHash: String,
        val relativePath: String
    ) : StorageLocation()
}
```

**Small content (< 512KB)**: Stored as SQLite BLOB for fast access and atomic writes.
**Large content (≥ 512KB)**: Stored on the filesystem, with the path and a content hash in SQLite.
**Versioned resources**: Stored as Git-tracked files; each version is a Git commit.

The Resource Graph stores metadata and the storage location reference in SQLite. It does not store content directly (except small blobs).

### Provenance Edges

Provenance edges record how content nodes were derived from each other:

```kotlin
data class ProvenanceEdge(
    val id: UUID,
    val fromNodeId: UUID,     // the source content
    val toNodeId: UUID,       // the derived content
    val edgeKind: ProvenanceEdgeKind,
    val createdAt: Instant,
    val createdByRunId: UUID? // the Run that created this derivation
)

enum class ProvenanceEdgeKind {
    DERIVED_FROM,      // toNode was derived by transforming fromNode
    EXTRACTED_FROM,    // toNode was extracted from fromNode (e.g., a section)
    VERSION_OF,        // toNode is a newer version of fromNode
    REFERENCED_BY,     // toNode explicitly references fromNode in its content
    MERGED_FROM        // toNode is a merge of multiple fromNodes
}
```

Provenance edges are immutable. Once created, they cannot be deleted. This preserves the complete derivation history even if nodes are archived or deleted.

### Promotion and Demotion

**Promotion**: An IMMUTABLE artifact is promoted to a VERSIONED resource.

When a session produces an artifact (e.g., a generated architecture document), a user can promote it to a resource, making it part of the project's authoritative knowledge.

Promotion creates a new ContentNode with:
- A new ID
- `mutabilityPolicy = VERSIONED`
- A `DERIVED_FROM` provenance edge pointing to the original artifact
- The original artifact's state remains ACTIVE and IMMUTABLE

Promotion requires explicit user action (it cannot be triggered by a session without a capability grant for resource mutation).

**Demotion**: A VERSIONED resource is archived.

Demotion transitions the resource to ARCHIVED state. It does not delete the content. Sessions that held references to the resource's ID can still retrieve it, but queries for ACTIVE resources will not include it.

### Query Model

The Resource Graph supports the following queries, executed against SQLite:

**List all active content nodes in a project:**
```sql
SELECT * FROM content_nodes WHERE project_id = ? AND state = 'ACTIVE';
```

**Find content by kind:**
```sql
SELECT * FROM content_nodes WHERE project_id = ? AND kind = ? AND state = 'ACTIVE';
```

**Find provenance chain for a node:**
```sql
-- Recursive CTE to walk the provenance graph
WITH RECURSIVE provenance AS (
    SELECT from_node_id, to_node_id, edge_kind, 0 AS depth
    FROM provenance_edges WHERE to_node_id = ?
    UNION ALL
    SELECT e.from_node_id, e.to_node_id, e.edge_kind, p.depth + 1
    FROM provenance_edges e
    JOIN provenance p ON p.from_node_id = e.to_node_id
    WHERE p.depth < 10
)
SELECT * FROM provenance;
```

**Find all artifacts produced by a session:**
```sql
SELECT cn.* FROM content_nodes cn
WHERE cn.created_by = ? AND cn.kind IN (SELECT kind FROM artifact_kinds);
```

**Find content eligible for inclusion in a remote model prompt:**
```sql
SELECT * FROM content_nodes
WHERE project_id = ? AND egress_eligibility = 'ELIGIBLE' AND state = 'ACTIVE';
```

## Data Model

```sql
CREATE TABLE content_nodes (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    mutability_policy TEXT NOT NULL,
    sensitivity_level TEXT NOT NULL,
    egress_eligibility TEXT NOT NULL,
    storage_location_json TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    created_by TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    updated_by TEXT,
    version INTEGER NOT NULL DEFAULT 1,
    state TEXT NOT NULL DEFAULT 'ACTIVE',
    tags TEXT NOT NULL DEFAULT '[]',  -- JSON array
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

CREATE TABLE provenance_edges (
    id TEXT PRIMARY KEY,
    from_node_id TEXT NOT NULL,
    to_node_id TEXT NOT NULL,
    edge_kind TEXT NOT NULL,
    created_at TEXT NOT NULL,
    created_by_run_id TEXT,
    FOREIGN KEY (from_node_id) REFERENCES content_nodes(id),
    FOREIGN KEY (to_node_id) REFERENCES content_nodes(id)
);

CREATE INDEX idx_content_nodes_project ON content_nodes(project_id, state, kind);
CREATE INDEX idx_content_nodes_hash ON content_nodes(content_hash);
CREATE INDEX idx_provenance_from ON provenance_edges(from_node_id);
CREATE INDEX idx_provenance_to ON provenance_edges(to_node_id);
```

## Security

The Resource Graph is the enforcement point for egress policy. Before the Prompt Construction system (RFC-0025) includes a ContentNode in a prompt sent to a remote model, it must check:

1. `sensitivityLevel` is not SENSITIVE or SECRET.
2. `egressEligibility` is ELIGIBLE (or the user has approved REQUIRES_APPROVAL).

This check must happen in the Prompt Construction system and must reference the ContentNode's current record in SQLite, not a cached copy, to catch sensitivity changes made since the node was loaded.

Sessions may not change the `sensitivityLevel` or `egressEligibility` of content nodes without a specific capability grant.

## MVP

The MVP implements:

1. `ContentNode` with all fields as defined.
2. `ProvenanceEdge` with `DERIVED_FROM` and `VERSION_OF` edge kinds.
3. The SQLite schema as defined.
4. Basic queries: list by project, list by kind, find by ID.
5. Artifact creation (CREATING → ACTIVE transition).
6. Sensitivity and egress eligibility fields (enforced by Prompt Construction).
7. Storage location for SQLite blobs and filesystem paths.

The MVP does not implement:
- Promotion/demotion workflows (the UI and capability grant are post-MVP).
- Git object storage location (filesystem path suffices for MVP).
- Recursive provenance queries (the CTE query is available but not exposed as an API).
- `REFERENCED_BY` and `MERGED_FROM` provenance edges.

## Future Work

Cross-project references: a ContentNode in one project references a ContentNode in another.

Content-addressed storage: deduplicate nodes with the same `contentHash` across a project.

Semantic tagging: automated tagging via the Knowledge Engine based on content analysis.

Differential promotion: promote only changed sections of a large document.

Sensitivity inference: the Knowledge Engine can suggest sensitivity levels based on content analysis (e.g., detecting API keys, personal data).
