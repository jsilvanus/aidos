package dev.aidos.intent

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.broker.AuditLog
import dev.aidos.kernel.ActorRef
import dev.aidos.kernel.AidosError
import dev.aidos.kernel.ErrorClass

/**
 * Detects cycles in the intent graph when adding edges (RFC-0012).
 *
 * When a new intent_edges row is about to be inserted, this checks whether adding it would
 * create a cycle. A cycle deadlocks status derivation and is rejected immediately with
 * error code `intent.cycle_rejected`.
 *
 * The check is a reachability test: if a path already exists from to_node_id to from_node_id,
 * then adding from_node_id → to_node_id would create a cycle.
 *
 * Cost: O(E + V) per edge insert. At intent-graph scale (tens to hundreds of nodes),
 * this is negligible.
 */
class IntentCycleDetector(
    private val driver: SqlDriver,
    private val audit: AuditLog,
    private val idGen: () -> String,
    private val nowIso: () -> String,
) {

    /**
     * Checks if adding a dependency edge would create a cycle.
     *
     * Returns null if the edge is safe. Returns [AidosError] if a cycle would be created.
     *
     * If a cycle is detected, writes an audit row with kind=IntentCycleDetected.
     */
    fun checkEdgeForCycle(
        projectId: String,
        fromNodeId: String,
        toNodeId: String,
        actor: ActorRef,
    ): AidosError? {
        // Reachability check: is there a path from toNodeId to fromNodeId?
        // If yes, adding fromNodeId → toNodeId creates a cycle.
        val canReach = canReachViaGraph(toNodeId, fromNodeId, projectId)
        if (!canReach) {
            return null  // Safe
        }

        // Cycle detected. Emit audit and return error.
        val auditId = idGen()
        audit.write(
            id = auditId,
            projectId = projectId,
            kind = "IntentCycleDetected",
            actor = actor,
            subjectRef = "$fromNodeId → $toNodeId",
            detailJson = """{"from":"$fromNodeId","to":"$toNodeId"}""",
            nowIso = nowIso(),
        )

        return AidosError(
            code = "intent.cycle_rejected",
            errorClass = ErrorClass.INVALID,
            detail = "Adding edge $fromNodeId → $toNodeId would create a cycle",
        )
    }

    /**
     * Checks if a path exists from source to target in the intent graph.
     *
     * Uses depth-first search with a visited set to avoid revisiting nodes.
     */
    private fun canReachViaGraph(
        source: String,
        target: String,
        projectId: String,
    ): Boolean {
        val visited = mutableSetOf<String>()
        return dfs(source, target, projectId, visited)
    }

    private fun dfs(
        current: String,
        target: String,
        projectId: String,
        visited: MutableSet<String>,
    ): Boolean {
        if (current == target) return true
        if (current in visited) return false

        visited.add(current)

        // Get all nodes that current depends on (outgoing edges with kind='DEPENDS_ON')
        val nextNodes = getOutgoingNodes(current, projectId)
        for (next in nextNodes) {
            if (dfs(next, target, projectId, visited)) {
                return true
            }
        }

        return false
    }

    /**
     * Returns all nodes that [nodeId] points to via DEPENDS_ON edges.
     */
    private fun getOutgoingNodes(nodeId: String, projectId: String): List<String> {
        val result = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT to_node_id FROM intent_edges " +
                "WHERE project_id = ? AND from_node_id = ? AND edge_kind = 'DEPENDS_ON'",
            parameters = 2,
            binders = { bindString(0, projectId); bindString(1, nodeId) },
            mapper = { resultSet ->
                while (resultSet.next()) {
                    result.add(resultSet.getString(0))
                }
            },
        )
        return result
    }
}
