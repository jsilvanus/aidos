package dev.aidos.kernel

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * The content graph (RFC-0024, RFC-0027). Every piece of content in a project is a
 * [ContentNode]; nodes differ by [MutabilityPolicy], not by type.
 *
 * MVP scope only (RFC-0024 "MVP" section): the node shape, basic queries, and
 * [ProvenanceEdge] limited to [ProvenanceEdgeKind.DERIVED_FROM]/[ProvenanceEdgeKind.VERSION_OF].
 * Promotion/demotion workflows, cross-project references, and [ProvenanceEdgeKind.REFERENCED_BY]/
 * [ProvenanceEdgeKind.MERGED_FROM] are explicitly post-MVP in the RFC and not modelled here.
 */
@Serializable
data class ContentNode(
    val id: ContentNodeId,
    val projectId: ProjectId,
    val kind: ContentKind,
    val name: String,
    val description: String?,
    val mutabilityPolicy: MutabilityPolicy,
    /** Outbound: may this leave the device? */
    val sensitivityLevel: SensitivityLevel,
    val egressEligibility: EgressEligibility,
    /** Inbound: may this influence decisions? (RFC-0027) */
    val trustLevel: TrustLevel,
    val storageLocation: StorageLocation,
    /** SHA-256 of content. */
    val contentHash: String,
    /** MIME type. */
    val contentType: String,
    val sizeBytes: Long,
    val createdAt: Instant,
    val createdByKind: ActorKind,
    val createdById: String,
    val updatedAt: Instant,
    val updatedByKind: ActorKind?,
    val updatedById: String?,
    /** User-visible revision; increments for VERSIONED nodes. */
    val contentVersion: Int,
    /** Optimistic-concurrency token (RFC-0017) — deliberately not [contentVersion]. */
    val rowVersion: Int,
    val state: ContentNodeState,
    val tags: List<String>,
)

@Serializable
enum class ContentKind {
    // Resource kinds (mutable by default)
    ARCHITECTURE_DOCUMENT,
    CODING_STANDARD,
    REQUIREMENTS,
    DECISION_LOG,
    ROADMAP,
    INSTRUCTION_FILE,
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
    CUSTOM_ARTIFACT,
}

/**
 * `CREATING -> ACTIVE -> SUPERSEDED -> ARCHIVED`, with `ACTIVE` also reachable to `DELETED` or
 * `DANGLING`. IMMUTABLE nodes only ever reach `ARCHIVED` or `DANGLING` after `ACTIVE` — never
 * `SUPERSEDED`, which applies to VERSIONED mutability only.
 */
@Serializable
enum class ContentNodeState {
    /** Content is being written to storage. Not yet queryable. */
    CREATING,
    /** Content is available and current. */
    ACTIVE,
    /** An updated version exists (VERSIONED mutability only). */
    SUPERSEDED,
    /** Content is preserved but no longer active. */
    ARCHIVED,
    /** The content has been removed; the node record is retained for provenance. */
    DELETED,
    /**
     * The node's record exists but its content is no longer reachable — typically after an
     * external Git history rewrite or a branch switch (RFC-0053). Not an error state.
     */
    DANGLING,
}

@Serializable
sealed class StorageLocation {
    /** Small content (< 512KB, RFC-0017's threshold): stored as a SQLite BLOB. */
    @Serializable
    data class SqliteBlob(val tableRef: String, val rowId: String) : StorageLocation()

    /** Content backed by a file on disk, relative to the project root. */
    @Serializable
    data class FilesystemPath(val relativePath: String, val gitTracked: Boolean) : StorageLocation()

    /** A specific Git blob at a specific commit (versioned resources). */
    @Serializable
    data class GitObject(val commitHash: String, val blobHash: String, val relativePath: String) : StorageLocation()
}

/**
 * Content -> content lineage (RFC-0024). Immutable once created — provenance edges are never
 * deleted, so the derivation history survives even if the nodes themselves are archived.
 */
@Serializable
data class ProvenanceEdge(
    val id: String,
    val fromNodeId: ContentNodeId,
    val toNodeId: ContentNodeId,
    val edgeKind: ProvenanceEdgeKind,
    val createdAt: Instant,
    val createdByRunId: RunId?,
)

@Serializable
enum class ProvenanceEdgeKind {
    /** toNode was derived by transforming fromNode. */
    DERIVED_FROM,
    /** toNode was extracted from fromNode (e.g. a section). Post-MVP. */
    EXTRACTED_FROM,
    /** toNode is a newer version of fromNode. */
    VERSION_OF,
    /** toNode explicitly references fromNode in its content. Post-MVP. */
    REFERENCED_BY,
    /** toNode is a merge of multiple fromNodes. Post-MVP. */
    MERGED_FROM,
}
