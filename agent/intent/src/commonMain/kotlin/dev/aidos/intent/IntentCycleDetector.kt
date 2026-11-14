package dev.aidos.intent

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.broker.AuditLog
import dev.aidos.kernel.ActorRef
import dev.aidos.kernel.AidosError
import dev.aidos.kernel.ErrorClass
import dev.aidos.kernel.ErrorCodes
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Detects cycles in the intent graph when adding edges (RFC-0012).
 *
 * In `intent_edges`, a `DEPENDS_ON` row `(from_node_id -> to_node_id)` means `from_node_id`
 * depends on `to_node_id` (i.e. `to_node_id` must complete first). Adding `fromNodeId -> toNodeId`
 * creates a cycle if `fromNodeId == toNodeId` or if a directed path already exists from
 * `toNodeId` to `fromNodeId` across persisted `intent_edges` and any not-yet-persisted
 * intra-batch `pendingEdges`.
 *
 * A cycle deadlocks status derivation and is rejected immediately with error code
 * `intent.cycle_rejected`.
 */
class IntentCycleDetector(
    private val driver: SqlDriver,
    private val audit: AuditLog,
    private val idGen: () -> String,
    private val nowIso: () -> String,
) {

    /**
     * Checks if adding a persisted dependency edge (`fromNodeId` DEPENDS_ON `toNodeId`) would
     * create a cycle against persisted `intent_edges`.
     *
     * Returns null if the edge is safe. Returns [AidosError] if a cycle would be created, and
     * writes an audit row with `kind = IntentCycleDetected`.
     */
    fun checkEdgeForCycle(
        projectId: String,
        fromNodeId: String,
        toNodeId: String,
        actor: ActorRef,
    ): AidosError? = checkEdgeForCycle(
        projectId = projectId,
        fromNodeId = fromNodeId,
        toNodeId = toNodeId,
        actor = actor,
        pendingEdges = emptyList(),
    )

    /**
     * Checks if adding `fromNodeId -> toNodeId` would create a cycle against both persisted
     * `intent_edges` (`edge_kind = 'DEPENDS_ON'`) and [pendingEdges] from the same proposal batch.
     *
     * Returns null if safe, or [AidosError] (`intent.cycle_rejected`) after emitting an
     * `IntentCycleDetected` audit row.
     */
    fun checkEdgeForCycle(
        projectId: String,
        fromNodeId: String,
        toNodeId: String,
        actor: ActorRef,
        pendingEdges: Collection<Pair<String, String>>,
    ): AidosError? {
        val createsCycle = if (fromNodeId == toNodeId) {
            true
        } else {
            canReachViaGraph(
                source = toNodeId,
                target = fromNodeId,
                projectId = projectId,
                pendingEdges = pendingEdges,
            )
        }
        if (!createsCycle) {
            return null
        }

        audit.write(
            id = idGen(),
            projectId = projectId,
            kind = "IntentCycleDetected",
            actor = actor,
            subjectRef = "$fromNodeId → $toNodeId",
            detailJson = buildJsonObject {
                put("from", fromNodeId)
                put("to", toNodeId)
            }.toString(),
            nowIso = nowIso(),
        )

        return AidosError(
            code = ErrorCodes.INTENT_CYCLE_REJECTED,
            errorClass = ErrorClass.INVALID_INPUT,
            message = "Adding edge $fromNodeId → $toNodeId would create a cycle",
        )
    }

    /**
     * Checks if a directed `DEPENDS_ON` path exists from [source] to [target] across persisted
     * `intent_edges` and [pendingEdges], using BFS with a visited set so pre-existing cycles
     * cannot cause infinite loops.
     */
    private fun canReachViaGraph(
        source: String,
        target: String,
        projectId: String,
        pendingEdges: Collection<Pair<String, String>>,
    ): Boolean {
        if (source == target) return true

        val adjacency = loadDependencyAdjacency(projectId)
        for ((from, to) in pendingEdges) {
            adjacency.getOrPut(from) { mutableListOf() }.add(to)
        }

        val visited = mutableSetOf(source)
        val queue = ArrayDeque<String>()
        queue.addLast(source)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val nextNodes = adjacency[current] ?: continue
            for (next in nextNodes) {
                if (next == target) return true
                if (visited.add(next)) {
                    queue.addLast(next)
                }
            }
        }

        return false
    }

    /**
     * Loads all persisted `DEPENDS_ON` edges (`from_node_id -> to_node_id`) for [projectId].
     */
    private fun loadDependencyAdjacency(projectId: String): MutableMap<String, MutableList<String>> {
        val adjacency = mutableMapOf<String, MutableList<String>>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT from_node_id, to_node_id FROM intent_edges " +
                "WHERE project_id = ? AND edge_kind = 'DEPENDS_ON'",
            parameters = 1,
            binders = { bindString(0, projectId) },
            mapper = { resultSet ->
                while (resultSet.next().value) {
                    val from = resultSet.getString(0)
                    val to = resultSet.getString(1)
                    if (from != null && to != null) {
                        adjacency.getOrPut(from) { mutableListOf() }.add(to)
                    }
                }
                QueryResult.Value(Unit)
            },
        )
        return adjacency
    }
}
