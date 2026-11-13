package dev.aidos.intent

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.broker.AuditLog
import dev.aidos.kernel.ActorRef
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.AidosError
import dev.aidos.kernel.ErrorClass
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Intent operations that can be batched into a proposal (RFC-0012).
 */
@Serializable
sealed class IntentOperation {
    @Serializable
    data class AddNode(
        val parentId: String?,
        val id: String,
        val type: String,
        val title: String,
        val description: String?,
        val priority: Int,
    ) : IntentOperation()

    @Serializable
    data class ModifyNode(
        val id: String,
        val title: String? = null,
        val description: String? = null,
        val priority: Int? = null,
    ) : IntentOperation()

    @Serializable
    data class AddDependency(
        val fromNodeId: String,
        val toNodeId: String,
    ) : IntentOperation()

    @Serializable
    data class ArchiveNode(
        val id: String,
    ) : IntentOperation()
}

/**
 * A proposal to modify the intent graph (RFC-0012).
 *
 * Sessions propose; only users resolve. The operations are atomic: all accepted or all rejected.
 */
@Serializable
data class IntentProposal(
    val id: String,
    val projectId: String,
    val operations: List<String>,  // JSON-serialized operations
    val rationale: String,
    val proposedByRunId: String,
    val proposedAt: String,
    val runTaint: String,          // RFC-0027
    val state: String,             // PENDING|ACCEPTED|ACCEPTED_WITH_EDITS|REJECTED|SUPERSEDED|EXPIRED
    val resolvedByUserId: String?,
    val resolvedAt: String?,
    val expiresAt: String,
)

/**
 * Proposes batched modifications to the intent graph (RFC-0012).
 *
 * Sessions can propose, but only users can resolve (approve/reject).
 * Operations are atomic: all or none.
 *
 * Rules:
 * - No SESSION variant: resolved_by_user_id is set by user, never by session
 * - Acyclic: operations that would create cycles are rejected at insert time
 * - Taint tracking: proposal carries the Run's taint level (RFC-0027)
 */
class IntentProposalEngine(
    private val driver: SqlDriver,
    private val audit: AuditLog,
    private val cycleDetector: IntentCycleDetector,
    private val idGen: () -> String,
    private val nowIso: () -> String,
) {

    private val json = Json

    /**
     * Proposes a batch of intent modifications.
     *
     * Returns the proposal ID, or throws [AidosError] if any operation is invalid.
     * Validation includes:
     * - Nodes exist (for modify/dependency operations)
     * - No self-dependencies
     * - Acyclicity (cycle detector runs for AddDependency ops)
     *
     * All operations are serialized as JSON and stored atomically in one row.
     */
    fun propose(
        projectId: String,
        runId: String,
        operations: List<IntentOperation>,
        rationale: String,
        runTaint: String,
        actor: ActorRef,
        expiresAtIso: String,
    ): String {
        // Validate all operations upfront, before inserting
        for (op in operations) {
            when (op) {
                is IntentOperation.AddNode -> {
                    // Node existence: id must be unique (checked at insert time)
                    // Parent exists check (if parent is specified)
                    if (op.parentId != null && !nodeExists(op.parentId, projectId)) {
                        throw AidosError(
                            "intent.invalid_parent",
                            ErrorClass.INVALID,
                            "Parent node ${op.parentId} does not exist",
                        )
                    }
                }

                is IntentOperation.ModifyNode -> {
                    // Node must exist
                    if (!nodeExists(op.id, projectId)) {
                        throw AidosError(
                            "intent.node_not_found",
                            ErrorClass.NOT_FOUND,
                            "Node ${op.id} does not exist",
                        )
                    }
                }

                is IntentOperation.AddDependency -> {
                    // Both nodes must exist
                    if (!nodeExists(op.fromNodeId, projectId)) {
                        throw AidosError(
                            "intent.node_not_found",
                            ErrorClass.NOT_FOUND,
                            "Node ${op.fromNodeId} does not exist",
                        )
                    }
                    if (!nodeExists(op.toNodeId, projectId)) {
                        throw AidosError(
                            "intent.node_not_found",
                            ErrorClass.NOT_FOUND,
                            "Node ${op.toNodeId} does not exist",
                        )
                    }

                    // No self-dependencies
                    if (op.fromNodeId == op.toNodeId) {
                        throw AidosError(
                            "intent.self_dependency",
                            ErrorClass.INVALID,
                            "A node cannot depend on itself",
                        )
                    }

                    // Check for cycles
                    val cycleError = cycleDetector.checkEdgeForCycle(
                        projectId,
                        op.fromNodeId,
                        op.toNodeId,
                        actor,
                    )
                    if (cycleError != null) {
                        throw cycleError
                    }
                }

                is IntentOperation.ArchiveNode -> {
                    // Node must exist
                    if (!nodeExists(op.id, projectId)) {
                        throw AidosError(
                            "intent.node_not_found",
                            ErrorClass.NOT_FOUND,
                            "Node ${op.id} does not exist",
                        )
                    }
                }
            }
        }

        // All validations passed. Serialize operations and insert proposal.
        val proposalId = idGen()
        val operationsJson = operations.map { json.encodeToString(IntentOperation.serializer(), it) }
        val operationsJsonArray = buildJsonObject {
            putJsonArray("ops") {
                for (opJson in operationsJson) {
                    add(Json.parseToJsonElement(opJson))
                }
            }
        }.toString()

        driver.execute(
            identifier = null,
            sql = "INSERT INTO intent_proposals " +
                "(id, project_id, operations_json, rationale, proposed_by_run_id, " +
                "proposed_at, run_taint, state, expires_at, audit_ref) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)",
            parameters = 9,
        ) {
            bindString(0, proposalId)
            bindString(1, projectId)
            bindString(2, operationsJsonArray)
            bindString(3, rationale)
            bindString(4, runId)
            bindString(5, nowIso())
            bindString(6, runTaint)
            bindString(7, expiresAtIso)
            // audit_ref: will be filled by the audit log
            val auditId = idGen()
            bindString(8, auditId)
        }

        // Write audit row
        audit.write(
            id = idGen(),
            projectId = projectId,
            kind = "IntentProposalCreated",
            actor = actor,
            subjectRef = proposalId,
            detailJson = """{"operationCount":${operations.size},"runTaint":"$runTaint"}""",
            nowIso = nowIso(),
        )

        return proposalId
    }

    /**
     * Returns true if a node with this ID exists in this project.
     */
    private fun nodeExists(nodeId: String, projectId: String): Boolean {
        var exists = false
        driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM intent_nodes WHERE id = ? AND project_id = ?",
            parameters = 2,
            binders = { bindString(0, nodeId); bindString(1, projectId) },
            mapper = { resultSet ->
                if (resultSet.next()) {
                    exists = resultSet.getLong(0) > 0
                }
            },
        )
        return exists
    }
}
