package dev.aidos.androidapp.content

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ContentKind
import dev.aidos.kernel.ContentNode
import dev.aidos.kernel.ContentNodeId
import dev.aidos.kernel.ContentNodeState
import dev.aidos.kernel.EgressEligibility
import dev.aidos.kernel.MutabilityPolicy
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.ProvenanceEdge
import dev.aidos.kernel.ProvenanceEdgeKind
import dev.aidos.kernel.SensitivityLevel
import dev.aidos.kernel.StorageLocation
import dev.aidos.kernel.TrustLevel
import kotlinx.datetime.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists [ContentNode]s and [ProvenanceEdge]s to `content_nodes`/`provenance_edges`
 * (RFC-0024). MVP scope only, per the RFC's own "MVP" section:
 *
 * - Basic queries: by project, by kind, by ID.
 * - [ProvenanceEdgeKind.DERIVED_FROM] and [ProvenanceEdgeKind.VERSION_OF] only — the other
 *   three kinds are explicitly post-MVP in the RFC; [addProvenanceEdge] rejects them.
 * - Acyclicity enforced on insert (RFC-0024 "Acyclicity"): rejects an edge whose target can
 *   already reach its source, since `VERSION_OF` combined with `DERIVED_FROM` can close a loop.
 *
 * Not implemented here, and not part of MVP scope: promotion/demotion workflows, cross-project
 * references, and content-addressed dedup — see RFC-0024's "MVP" and "Future Work" sections.
 */
class SqliteContentNodeStore(private val driver: SqlDriver) {

    private val json = Json { encodeDefaults = true }

    fun create(node: ContentNode) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO content_nodes " +
                "(id, project_id, kind, name, description, mutability_policy, sensitivity_level, " +
                "egress_eligibility, trust_level, storage_location_json, content_hash, content_type, " +
                "size_bytes, created_at, created_by_kind, created_by_id, updated_at, updated_by_kind, " +
                "updated_by_id, content_version, row_version, state, tags) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            parameters = 23,
        ) {
            bindString(0, node.id.value)
            bindString(1, node.projectId.value)
            bindString(2, node.kind.name)
            bindString(3, node.name)
            bindString(4, node.description)
            bindString(5, node.mutabilityPolicy.name)
            bindString(6, node.sensitivityLevel.name)
            bindString(7, node.egressEligibility.name)
            bindString(8, node.trustLevel.name)
            bindString(9, json.encodeToString(node.storageLocation))
            bindString(10, node.contentHash)
            bindString(11, node.contentType)
            bindLong(12, node.sizeBytes)
            bindString(13, node.createdAt.toString())
            bindString(14, node.createdByKind.name)
            bindString(15, node.createdById)
            bindString(16, node.updatedAt.toString())
            bindString(17, node.updatedByKind?.name)
            bindString(18, node.updatedById)
            bindLong(19, node.contentVersion.toLong())
            bindLong(20, node.rowVersion.toLong())
            bindString(21, node.state.name)
            bindString(22, json.encodeToString(node.tags))
        }
    }

    fun get(id: ContentNodeId): ContentNode? =
        query("SELECT $COLUMNS FROM content_nodes WHERE id = ?", parameters = 1) { bindString(0, id.value) }
            .firstOrNull()

    fun listByProject(projectId: ProjectId): List<ContentNode> =
        query("SELECT $COLUMNS FROM content_nodes WHERE project_id = ? ORDER BY created_at", parameters = 1) {
            bindString(0, projectId.value)
        }

    fun listByKind(projectId: ProjectId, kind: ContentKind): List<ContentNode> =
        query(
            "SELECT $COLUMNS FROM content_nodes WHERE project_id = ? AND kind = ? ORDER BY created_at",
            parameters = 2,
        ) {
            bindString(0, projectId.value)
            bindString(1, kind.name)
        }

    /**
     * Adds a provenance edge, enforcing MVP edge-kind scope and acyclicity (RFC-0024).
     * Fails with a `content.cycle_rejected`-prefixed message if [edge] would close a loop.
     */
    fun addProvenanceEdge(edge: ProvenanceEdge): Result<Unit> {
        if (edge.edgeKind != ProvenanceEdgeKind.DERIVED_FROM && edge.edgeKind != ProvenanceEdgeKind.VERSION_OF) {
            return Result.failure(
                IllegalArgumentException(
                    "${edge.edgeKind} is post-MVP (RFC-0024) — only DERIVED_FROM and VERSION_OF are wired"
                )
            )
        }
        if (reaches(edge.toNodeId, edge.fromNodeId)) {
            return Result.failure(
                IllegalStateException(
                    "content.cycle_rejected: adding ${edge.fromNodeId.value} -> ${edge.toNodeId.value} " +
                        "(${edge.edgeKind}) would close a cycle in the provenance graph"
                )
            )
        }
        driver.execute(
            identifier = null,
            sql = "INSERT INTO provenance_edges (id, from_node_id, to_node_id, edge_kind, created_at, created_by_run_id) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            parameters = 6,
        ) {
            bindString(0, edge.id)
            bindString(1, edge.fromNodeId.value)
            bindString(2, edge.toNodeId.value)
            bindString(3, edge.edgeKind.name)
            bindString(4, edge.createdAt.toString())
            bindString(5, edge.createdByRunId?.value)
        }
        return Result.success(Unit)
    }

    /** True if [target] is reachable from [start] by following existing from->to provenance edges. */
    private fun reaches(start: ContentNodeId, target: ContentNodeId): Boolean {
        if (start == target) return true
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(start.value)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current == target.value) return true
            if (!visited.add(current)) continue
            queue.addAll(outgoingTargets(current))
        }
        return false
    }

    private fun outgoingTargets(nodeId: String): List<String> {
        val results = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT to_node_id FROM provenance_edges WHERE from_node_id = ?",
            mapper = { cursor ->
                while (cursor.next().value) results.add(cursor.getString(0)!!)
                QueryResult.Value(Unit)
            },
            parameters = 1,
        ) { bindString(0, nodeId) }
        return results
    }

    private fun query(sql: String, parameters: Int, bind: SqlPreparedStatement.() -> Unit): List<ContentNode> {
        val results = mutableListOf<ContentNode>()
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                while (cursor.next().value) {
                    results.add(
                        ContentNode(
                            id = ContentNodeId(cursor.getString(0)!!),
                            projectId = ProjectId(cursor.getString(1)!!),
                            kind = ContentKind.valueOf(cursor.getString(2)!!),
                            name = cursor.getString(3)!!,
                            description = cursor.getString(4),
                            mutabilityPolicy = MutabilityPolicy.valueOf(cursor.getString(5)!!),
                            sensitivityLevel = SensitivityLevel.valueOf(cursor.getString(6)!!),
                            egressEligibility = EgressEligibility.valueOf(cursor.getString(7)!!),
                            trustLevel = TrustLevel.valueOf(cursor.getString(8)!!),
                            storageLocation = json.decodeFromString(cursor.getString(9)!!),
                            contentHash = cursor.getString(10)!!,
                            contentType = cursor.getString(11)!!,
                            sizeBytes = cursor.getLong(12)!!,
                            createdAt = Instant.parse(cursor.getString(13)!!),
                            createdByKind = ActorKind.valueOf(cursor.getString(14)!!),
                            createdById = cursor.getString(15)!!,
                            updatedAt = Instant.parse(cursor.getString(16)!!),
                            updatedByKind = cursor.getString(17)?.let { ActorKind.valueOf(it) },
                            updatedById = cursor.getString(18),
                            contentVersion = cursor.getLong(19)!!.toInt(),
                            rowVersion = cursor.getLong(20)!!.toInt(),
                            state = ContentNodeState.valueOf(cursor.getString(21)!!),
                            tags = json.decodeFromString(cursor.getString(22)!!),
                        )
                    )
                }
                QueryResult.Value(Unit)
            },
            parameters = parameters,
        ) { bind() }
        return results
    }

    private companion object {
        const val COLUMNS = "id, project_id, kind, name, description, mutability_policy, sensitivity_level, " +
            "egress_eligibility, trust_level, storage_location_json, content_hash, content_type, size_bytes, " +
            "created_at, created_by_kind, created_by_id, updated_at, updated_by_kind, updated_by_id, " +
            "content_version, row_version, state, tags"
    }
}
