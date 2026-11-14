package dev.aidos.intent

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.broker.AuditLog
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ActorRef
import kotlinx.serialization.json.Json

/**
 * Deterministic Git-friendly JSON snapshot exporter and reconciler for the Intent Graph (RFC-0110, RFC-0053).
 *
 * Exports nodes and edges in canonical sorted order so diffs are stable across devices.
 * On import, unseen nodes are inserted in parent-first order, strictly higher `rowVersion`
 * records fast-forward local state, and equal-version divergent edits mark `asserted_status = 'CONFLICTED'`
 * instead of silently dropping either change.
 */
class IntentSnapshotCodec(
    private val driver: SqlDriver,
    private val nowIso: () -> String,
    private val cycleDetector: IntentCycleDetector? = null,
) {
    private val effectiveCycleDetector: IntentCycleDetector by lazy {
        cycleDetector ?: IntentCycleDetector(
            driver = driver,
            audit = AuditLog(driver, "snapshot-reconciler"),
            idGen = { "audit-snapshot-${nowIso()}" },
            nowIso = nowIso,
        )
    }
    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun exportSnapshot(projectId: String): IntentGraphSnapshot {
        val nodes = IntentGraphRepository.readNodes(driver, projectId, includeArchived = true)
            .sortedBy { it.id }
        val edges = IntentGraphRepository.readEdges(driver, projectId)
            .sortedWith(
                compareBy<IntentEdgeRecord>(
                    { it.edgeKind.name },
                    { it.fromNodeId },
                    { it.toNodeId },
                    { it.id },
                ),
            )
        return IntentGraphSnapshot(
            schemaVersion = 1,
            projectId = projectId,
            exportedAt = nowIso(),
            nodes = nodes,
            edges = edges,
        )
    }

    fun exportJson(projectId: String): String =
        prettyJson.encodeToString(IntentGraphSnapshot.serializer(), exportSnapshot(projectId))

    fun importAndReconcile(projectId: String, snapshotJson: String): SnapshotReconcileResult {
        val snapshot = prettyJson.decodeFromString(IntentGraphSnapshot.serializer(), snapshotJson)
        require(snapshot.projectId == projectId) {
            "Snapshot belongs to project '${snapshot.projectId}', expected '$projectId'."
        }

        val existingNodes = IntentGraphRepository.readNodes(driver, projectId, includeArchived = true)
            .associateBy { it.id }
            .toMutableMap()

        var addedNodes = 0
        var updatedNodes = 0
        val conflictedNodeIds = mutableListOf<String>()

        val orderedIncomingNodes = sortParentsFirst(snapshot.nodes)
        for (incoming in orderedIncomingNodes) {
            val existing = existingNodes[incoming.id]
            if (existing == null) {
                insertImportedNode(projectId, incoming)
                existingNodes[incoming.id] = incoming
                addedNodes++
                continue
            }

            if (payloadEquivalent(existing, incoming)) {
                if (incoming.rowVersion > existing.rowVersion) {
                    fastForwardNode(projectId, incoming)
                    existingNodes[incoming.id] = incoming
                }
                continue
            }

            when {
                incoming.rowVersion > existing.rowVersion -> {
                    fastForwardNode(projectId, incoming)
                    existingNodes[incoming.id] = incoming
                    updatedNodes++
                }
                incoming.rowVersion == existing.rowVersion -> {
                    markConflicted(projectId, existing, incoming)
                    conflictedNodeIds.add(existing.id)
                }
                // incoming.rowVersion < existing.rowVersion: local already advanced; preserve local state.
            }
        }

        val existingEdges = IntentGraphRepository.readEdges(driver, projectId)
            .map { Triple(it.fromNodeId, it.toNodeId, it.edgeKind) }
            .toMutableSet()

        var addedEdges = 0
        val sortedEdges = snapshot.edges.sortedWith(
            compareBy<IntentEdgeRecord>(
                { it.edgeKind.name },
                { it.fromNodeId },
                { it.toNodeId },
                { it.id },
            ),
        )
        for (edge in sortedEdges) {
            val key = Triple(edge.fromNodeId, edge.toNodeId, edge.edgeKind)
            if (key in existingEdges) continue
            if (edge.fromNodeId !in existingNodes || edge.toNodeId !in existingNodes) continue

            if (edge.edgeKind == IntentEdgeKind.DEPENDS_ON) {
                val cycleErr = effectiveCycleDetector.checkEdgeForCycle(
                    projectId = projectId,
                    fromNodeId = edge.fromNodeId,
                    toNodeId = edge.toNodeId,
                    actor = ActorRef(ActorKind.RUNTIME, "snapshot-reconciler"),
                )
                val createsCycle = cycleErr != null
                if (createsCycle) {
                    val localNode = existingNodes[edge.fromNodeId]
                    if (localNode != null && edge.fromNodeId !in conflictedNodeIds) {
                        markEdgeCycleConflict(projectId, localNode, edge)
                        conflictedNodeIds.add(edge.fromNodeId)
                    }
                    continue
                }
            }

            driver.execute(
                identifier = null,
                sql = """
                    INSERT INTO intent_edges (id, project_id, from_node_id, to_node_id, edge_kind, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(from_node_id, to_node_id, edge_kind) DO NOTHING
                """.trimIndent(),
                parameters = 6,
            ) {
                bindString(0, edge.id)
                bindString(1, projectId)
                bindString(2, edge.fromNodeId)
                bindString(3, edge.toNodeId)
                bindString(4, edge.edgeKind.name)
                bindString(5, edge.createdAt)
            }
            existingEdges.add(key)
            addedEdges++
        }

        return SnapshotReconcileResult(
            addedNodes = addedNodes,
            updatedNodes = updatedNodes,
            conflictedNodeIds = conflictedNodeIds,
            addedEdges = addedEdges,
        )
    }

    private fun sortParentsFirst(nodes: List<IntentNodeRecord>): List<IntentNodeRecord> {
        val remaining = nodes.associateBy { it.id }.toMutableMap()
        val ordered = mutableListOf<IntentNodeRecord>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.values
                .filter { it.parentId == null || it.parentId !in remaining }
                .sortedBy { it.id }
            if (ready.isEmpty()) {
                ordered.addAll(remaining.values.sortedBy { it.id })
                break
            }
            for (node in ready) {
                ordered.add(node)
                remaining.remove(node.id)
            }
        }
        return ordered
    }

    private fun payloadEquivalent(a: IntentNodeRecord, b: IntentNodeRecord): Boolean =
        a.copy(rowVersion = 0, modifiedAt = "", assertedAt = null) ==
            b.copy(rowVersion = 0, modifiedAt = "", assertedAt = null) &&
            a.assertedStatus == b.assertedStatus

    private fun insertImportedNode(projectId: String, node: IntentNodeRecord) {
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO intent_nodes (
                    id, project_id, type, title, description, priority, lifecycle, parent_id,
                    actor_kind, dispatch_policy, granularity, target_profile, min_locality,
                    estimated_steps, scope_paths_json, due_at, recurrence_json,
                    asserted_status, asserted_at, asserted_by_user_id, assertion_note,
                    check_kind, check_spec, verification_met, verified_by_kind, verified_by_id, verified_at,
                    created_at, created_by_kind, created_by_id,
                    modified_at, modified_by_kind, modified_by_id,
                    tags, row_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            parameters = 35,
        ) {
            bindFullNode(this, projectId, node)
        }
    }

    private fun fastForwardNode(projectId: String, node: IntentNodeRecord) {
        driver.execute(
            identifier = null,
            sql = """
                UPDATE intent_nodes SET
                    type = ?, title = ?, description = ?, priority = ?, lifecycle = ?, parent_id = ?,
                    actor_kind = ?, dispatch_policy = ?, granularity = ?, target_profile = ?, min_locality = ?,
                    estimated_steps = ?, scope_paths_json = ?, due_at = ?, recurrence_json = ?,
                    asserted_status = ?, asserted_at = ?, asserted_by_user_id = ?, assertion_note = ?,
                    check_kind = ?, check_spec = ?, verification_met = ?, verified_by_kind = ?,
                    verified_by_id = ?, verified_at = ?,
                    modified_at = ?, modified_by_kind = ?, modified_by_id = ?,
                    tags = ?, row_version = ?
                WHERE project_id = ? AND id = ?
            """.trimIndent(),
            parameters = 32,
        ) {
            bindString(0, node.type.name)
            bindString(1, node.title)
            bindString(2, node.description)
            bindLong(3, node.priority.toLong())
            bindString(4, node.lifecycle.name)
            bindString(5, node.parentId)
            bindString(6, node.actorKind.name)
            bindString(7, node.dispatchPolicy.name)
            bindString(8, node.granularity.name)
            bindString(9, node.targetProfile?.name)
            bindString(10, node.minLocality?.name)
            bindLong(11, node.estimatedSteps?.toLong())
            bindString(12, IntentJson.encodeStringList(node.scopePaths))
            bindString(13, node.dueAt)
            bindString(14, node.recurrenceJson)
            bindString(15, node.assertedStatus?.name)
            bindString(16, node.assertedAt)
            bindString(17, node.assertedByUserId)
            bindString(18, node.assertionNote)
            bindString(19, node.checkKind?.name)
            bindString(20, node.checkSpec)
            bindLong(21, node.verificationMet?.let { if (it) 1L else 0L })
            bindString(22, node.verifiedByKind)
            bindString(23, node.verifiedById)
            bindString(24, node.verifiedAt)
            bindString(25, node.modifiedAt)
            bindString(26, node.modifiedByKind)
            bindString(27, node.modifiedById)
            bindString(28, IntentJson.encodeStringList(node.tags))
            bindLong(29, node.rowVersion.toLong())
            bindString(30, projectId)
            bindString(31, node.id)
        }
    }

    private fun markConflicted(
        projectId: String,
        existing: IntentNodeRecord,
        incoming: IntentNodeRecord,
    ) {
        val now = nowIso()
        val note = "Snapshot conflict at v${incoming.rowVersion}: remote title='${incoming.title}', " +
            "remote actor=${incoming.actorKind}, remote modifiedBy=${incoming.modifiedById}"
        driver.execute(
            identifier = null,
            sql = """
                UPDATE intent_nodes SET
                    asserted_status = 'CONFLICTED',
                    asserted_at = ?,
                    assertion_note = ?,
                    modified_at = ?,
                    row_version = row_version + 1
                WHERE project_id = ? AND id = ?
            """.trimIndent(),
            parameters = 5,
        ) {
            bindString(0, now)
            bindString(1, note)
            bindString(2, now)
            bindString(3, projectId)
            bindString(4, existing.id)
        }
    }

    private fun markEdgeCycleConflict(
        projectId: String,
        localNode: IntentNodeRecord,
        edge: IntentEdgeRecord,
    ) {
        val now = nowIso()
        val note = "Snapshot edge ${edge.fromNodeId} DEPENDS_ON ${edge.toNodeId} creates a cycle."
        driver.execute(
            identifier = null,
            sql = """
                UPDATE intent_nodes SET
                    asserted_status = 'CONFLICTED',
                    asserted_at = ?,
                    assertion_note = ?,
                    modified_at = ?,
                    row_version = row_version + 1
                WHERE project_id = ? AND id = ?
            """.trimIndent(),
            parameters = 5,
        ) {
            bindString(0, now)
            bindString(1, note)
            bindString(2, now)
            bindString(3, projectId)
            bindString(4, localNode.id)
        }
    }

    private fun bindFullNode(
        binder: app.cash.sqldelight.db.SqlPreparedStatement,
        projectId: String,
        node: IntentNodeRecord,
    ) {
        binder.bindString(0, node.id)
        binder.bindString(1, projectId)
        binder.bindString(2, node.type.name)
        binder.bindString(3, node.title)
        binder.bindString(4, node.description)
        binder.bindLong(5, node.priority.toLong())
        binder.bindString(6, node.lifecycle.name)
        binder.bindString(7, node.parentId)
        binder.bindString(8, node.actorKind.name)
        binder.bindString(9, node.dispatchPolicy.name)
        binder.bindString(10, node.granularity.name)
        binder.bindString(11, node.targetProfile?.name)
        binder.bindString(12, node.minLocality?.name)
        binder.bindLong(13, node.estimatedSteps?.toLong())
        binder.bindString(14, IntentJson.encodeStringList(node.scopePaths))
        binder.bindString(15, node.dueAt)
        binder.bindString(16, node.recurrenceJson)
        binder.bindString(17, node.assertedStatus?.name)
        binder.bindString(18, node.assertedAt)
        binder.bindString(19, node.assertedByUserId)
        binder.bindString(20, node.assertionNote)
        binder.bindString(21, node.checkKind?.name)
        binder.bindString(22, node.checkSpec)
        binder.bindLong(23, node.verificationMet?.let { if (it) 1L else 0L })
        binder.bindString(24, node.verifiedByKind)
        binder.bindString(25, node.verifiedById)
        binder.bindString(26, node.verifiedAt)
        binder.bindString(27, node.createdAt)
        binder.bindString(28, node.createdByKind)
        binder.bindString(29, node.createdById)
        binder.bindString(30, node.modifiedAt)
        binder.bindString(31, node.modifiedByKind)
        binder.bindString(32, node.modifiedById)
        binder.bindString(33, IntentJson.encodeStringList(node.tags))
        binder.bindLong(34, node.rowVersion.toLong())
    }
}
