package dev.aidos.intent

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.broker.AuditLog
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ActorRef
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
 * Lifecycle state of an intent proposal (`schema/project.sql` `intent_proposals.state`).
 */
enum class ProposalState {
    PENDING,
    ACCEPTED,
    ACCEPTED_WITH_EDITS,
    REJECTED,
    SUPERSEDED,
    EXPIRED,
}

/**
 * Resolution decision applied to a `PENDING` proposal (RFC-0012).
 */
sealed class ProposalResolution {
    data object Accept : ProposalResolution()
    data class AcceptWithEdits(val operations: List<IntentOperation>) : ProposalResolution()
    data class Reject(val reason: String? = null) : ProposalResolution()
    data class Supersede(val supersededByProposalId: String? = null) : ProposalResolution()
    data object Expire : ProposalResolution()
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
 * Proposes and resolves batched modifications to the intent graph (RFC-0012).
 *
 * Sessions can propose, but only users can resolve (approve/reject).
 * Operations are atomic: all or none.
 *
 * Rules:
 * - No SESSION variant: resolved_by_user_id is set by user, never by session
 * - Acyclic: operations that would create cycles (including intra-batch cycles) are rejected
 * - Taint tracking: proposal carries the Run's taint level (RFC-0027)
 */
class IntentProposalEngine(
    private val driver: SqlDriver,
    private val audit: AuditLog,
    private val cycleDetector: IntentCycleDetector,
    private val idGen: () -> String,
    private val nowIso: () -> String,
) {

    private val transacter = object : TransacterImpl(driver) {}

    private val json = Json {
        classDiscriminator = "_kind"
        ignoreUnknownKeys = true
    }

    /**
     * Proposes a batch of intent modifications.
     *
     * Returns the proposal ID, or throws if any operation is invalid.
     * Validation handles both persisted rows and intra-batch `AddNode` / `AddDependency` references.
     * Writes the `IntentProposalCreated` audit row before inserting `intent_proposals` inside one
     * atomic transaction so `intent_proposals.audit_ref` satisfies `FOREIGN KEY (audit_ref)`.
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
        if (projectId.isBlank()) {
            throw IllegalArgumentException("intent.invalid_project: projectId must not be blank")
        }
        validateOperations(projectId, operations, actor)

        val proposalId = idGen()
        val operationsJsonArray = encodeOperationsJson(operations)
        val proposedAt = nowIso()

        transacter.transaction {
            val auditId = audit.write(
                id = idGen(),
                projectId = projectId,
                kind = "IntentProposalCreated",
                actor = actor,
                subjectRef = proposalId,
                detailJson = buildJsonObject {
                    put("operationCount", operations.size)
                    put("runTaint", runTaint)
                    put("runId", runId)
                }.toString(),
                nowIso = proposedAt,
            )

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
                bindString(5, proposedAt)
                bindString(6, runTaint)
                bindString(7, expiresAtIso)
                bindString(8, auditId)
            }
        }

        return proposalId
    }

    /**
     * Resolves a `PENDING` proposal atomically.
     *
     * Enforces RFC-0012's approval gate: only `USER` actors can resolve proposals (with `RUNTIME`
     * allowed solely to transition an expired proposal to `EXPIRED`). If accepted, applies all
     * batched operations atomically in topological/dependency order (nodes parent-first, then
     * modifications, then dependency edges, then archival) and writes an `IntentProposalResolved`
     * audit row in the same transaction.
     */
    fun resolveProposal(
        proposalId: String,
        projectId: String,
        resolution: ProposalResolution,
        actor: ActorRef,
    ): ProposalState {
        val allowRuntimeExpiry = resolution is ProposalResolution.Expire && actor.kind == ActorKind.RUNTIME
        if (actor.kind != ActorKind.USER && !allowRuntimeExpiry) {
            throw IllegalArgumentException(
                "intent.only_user_can_resolve: Only USER actors can resolve proposals (got ${actor.kind})",
            )
        }
        if (actor.id.isBlank()) {
            throw IllegalArgumentException("intent.invalid_actor: Actor id must not be blank")
        }

        val now = nowIso()
        val resolvedByUserId = if (actor.kind == ActorKind.USER) actor.id else null

        lateinit var resultState: ProposalState
        transacter.transaction {
            val existing = loadProposalRow(proposalId, projectId)
                ?: throw NoSuchElementException("intent.proposal_not_found: Proposal $proposalId does not exist")

            if (existing.state != ProposalState.PENDING.name) {
                throw IllegalStateException(
                    "intent.proposal_already_resolved: Proposal $proposalId is already ${existing.state}",
                )
            }

            if ((resolution is ProposalResolution.Accept || resolution is ProposalResolution.AcceptWithEdits) &&
                isExpired(now, existing.expiresAt)
            ) {
                throw IllegalStateException(
                    "intent.proposal_expired: Proposal $proposalId expired at ${existing.expiresAt}",
                )
            }

            val operationsToApply: List<IntentOperation>? = when (resolution) {
                is ProposalResolution.Accept -> {
                    resultState = ProposalState.ACCEPTED
                    decodeOperationsJson(existing.operationsJson)
                }
                is ProposalResolution.AcceptWithEdits -> {
                    resultState = ProposalState.ACCEPTED_WITH_EDITS
                    resolution.operations
                }
                is ProposalResolution.Reject -> {
                    resultState = ProposalState.REJECTED
                    null
                }
                is ProposalResolution.Supersede -> {
                    resultState = ProposalState.SUPERSEDED
                    null
                }
                is ProposalResolution.Expire -> {
                    resultState = ProposalState.EXPIRED
                    null
                }
            }

            if (operationsToApply != null) {
                validateOperations(projectId, operationsToApply, actor)
                applyOperations(projectId, operationsToApply, actor, now)
            }

            if (resolution is ProposalResolution.AcceptWithEdits) {
                driver.execute(
                    identifier = null,
                    sql = "UPDATE intent_proposals " +
                        "SET state = ?, operations_json = ?, resolved_by_user_id = ?, resolved_at = ? " +
                        "WHERE id = ? AND project_id = ? AND state = 'PENDING'",
                    parameters = 6,
                ) {
                    bindString(0, resultState.name)
                    bindString(1, encodeOperationsJson(resolution.operations))
                    bindString(2, resolvedByUserId)
                    bindString(3, now)
                    bindString(4, proposalId)
                    bindString(5, projectId)
                }
            } else {
                driver.execute(
                    identifier = null,
                    sql = "UPDATE intent_proposals " +
                        "SET state = ?, resolved_by_user_id = ?, resolved_at = ? " +
                        "WHERE id = ? AND project_id = ? AND state = 'PENDING'",
                    parameters = 5,
                ) {
                    bindString(0, resultState.name)
                    bindString(1, resolvedByUserId)
                    bindString(2, now)
                    bindString(3, proposalId)
                    bindString(4, projectId)
                }
            }

            audit.write(
                id = idGen(),
                projectId = projectId,
                kind = "IntentProposalResolved",
                actor = actor,
                subjectRef = proposalId,
                detailJson = buildJsonObject {
                    put("state", resultState.name)
                    put("proposedByRunId", existing.proposedByRunId)
                    if (resolution is ProposalResolution.Reject && resolution.reason != null) {
                        put("reason", resolution.reason)
                    }
                    if (resolution is ProposalResolution.Supersede && resolution.supersededByProposalId != null) {
                        put("supersededByProposalId", resolution.supersededByProposalId)
                    }
                    if (resolution is ProposalResolution.AcceptWithEdits) {
                        put("editedOperationCount", resolution.operations.size)
                    }
                }.toString(),
                nowIso = now,
            )
        }

        return resultState
    }

    /**
     * Loads a proposal by ID within [projectId], or returns null if absent.
     */
    fun getProposal(proposalId: String, projectId: String): IntentProposal? {
        val row = loadProposalRow(proposalId, projectId) ?: return null
        return row.toModel()
    }

    /**
     * Lists all `PENDING` proposals for [projectId] ordered by proposal timestamp.
     */
    fun listPendingProposals(projectId: String): List<IntentProposal> {
        val proposals = mutableListOf<IntentProposal>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT id, project_id, operations_json, rationale, proposed_by_run_id, " +
                "proposed_at, run_taint, state, resolved_by_user_id, resolved_at, expires_at " +
                "FROM intent_proposals WHERE project_id = ? AND state = 'PENDING' " +
                "ORDER BY proposed_at ASC, id ASC",
            parameters = 1,
            binders = { bindString(0, projectId) },
            mapper = { resultSet ->
                while (resultSet.next().value) {
                    proposals.add(readProposalRow(resultSet).toModel())
                }
                QueryResult.Value(Unit)
            },
        )
        return proposals
    }

    private fun validateOperations(
        projectId: String,
        operations: List<IntentOperation>,
        actor: ActorRef,
    ) {
        // Phase 1: Register all batch-added nodes so parent/modify/dependency checks can resolve them.
        val batchNodesById = mutableMapOf<String, IntentOperation.AddNode>()
        for (op in operations) {
            if (op is IntentOperation.AddNode) {
                if (op.id.isBlank()) {
                    throw IllegalArgumentException("intent.invalid_node_id: Node id must not be blank")
                }
                if (op.title.isBlank()) {
                    throw IllegalArgumentException("intent.invalid_title: Node title must not be blank")
                }
                if (op.id in batchNodesById || nodeExists(op.id, projectId)) {
                    throw IllegalArgumentException("intent.duplicate_node: Node ${op.id} already exists")
                }
                batchNodesById[op.id] = op
            }
        }

        // Phase 2: Validate parent existence and parent hierarchy acyclicity.
        for (op in batchNodesById.values) {
            val parentId = op.parentId
            if (parentId != null) {
                if (parentId == op.id) {
                    throw IllegalArgumentException("intent.invalid_parent: Node ${op.id} cannot be its own parent")
                }
                if (parentId !in batchNodesById && !nodeExists(parentId, projectId)) {
                    throw IllegalArgumentException("intent.invalid_parent: Parent node $parentId does not exist")
                }
            }
        }
        val batchParentMap = batchNodesById.mapValues { it.value.parentId }
        for (nodeId in batchParentMap.keys) {
            val seen = mutableSetOf<String>()
            var cursor: String? = nodeId
            while (cursor != null) {
                if (!seen.add(cursor)) {
                    throw IllegalArgumentException("intent.cycle_rejected: Parent hierarchy cycle involving $cursor")
                }
                cursor = if (cursor in batchParentMap) {
                    batchParentMap[cursor]
                } else {
                    loadParentId(cursor, projectId)
                }
            }
        }

        // Phase 3: Validate ModifyNode, AddDependency (against DB + batch nodes + pending edges), and ArchiveNode.
        val pendingEdges = mutableListOf<Pair<String, String>>()
        for (op in operations) {
            when (op) {
                is IntentOperation.AddNode -> Unit

                is IntentOperation.ModifyNode -> {
                    if (op.id !in batchNodesById && !nodeExists(op.id, projectId)) {
                        throw NoSuchElementException("intent.node_not_found: Node ${op.id} does not exist")
                    }
                }

                is IntentOperation.AddDependency -> {
                    if (op.fromNodeId !in batchNodesById && !nodeExists(op.fromNodeId, projectId)) {
                        throw NoSuchElementException("intent.node_not_found: Node ${op.fromNodeId} does not exist")
                    }
                    if (op.toNodeId !in batchNodesById && !nodeExists(op.toNodeId, projectId)) {
                        throw NoSuchElementException("intent.node_not_found: Node ${op.toNodeId} does not exist")
                    }
                    if (op.fromNodeId == op.toNodeId) {
                        throw IllegalArgumentException("intent.self_dependency: A node cannot depend on itself")
                    }
                    val cycleError = cycleDetector.checkEdgeForCycle(
                        projectId = projectId,
                        fromNodeId = op.fromNodeId,
                        toNodeId = op.toNodeId,
                        actor = actor,
                        pendingEdges = pendingEdges,
                    )
                    if (cycleError != null) {
                        throw IllegalArgumentException("${cycleError.code}: ${cycleError.message}")
                    }
                    pendingEdges.add(op.fromNodeId to op.toNodeId)
                }

                is IntentOperation.ArchiveNode -> {
                    if (op.id !in batchNodesById && !nodeExists(op.id, projectId)) {
                        throw NoSuchElementException("intent.node_not_found: Node ${op.id} does not exist")
                    }
                }
            }
        }
    }

    /**
     * Applies validated operations atomically in topological/dependency order:
     * 1. `AddNode` sorted parent-before-child
     * 2. `ModifyNode`
     * 3. `AddDependency` (nodes already exist, re-checked for cycles against live DB)
     * 4. `ArchiveNode`
     */
    private fun applyOperations(
        projectId: String,
        operations: List<IntentOperation>,
        actor: ActorRef,
        now: String,
    ) {
        val addNodes = topologicalSortAddNodes(operations.filterIsInstance<IntentOperation.AddNode>())
        val modifyNodes = operations.filterIsInstance<IntentOperation.ModifyNode>()
        val addDependencies = operations.filterIsInstance<IntentOperation.AddDependency>()
        val archiveNodes = operations.filterIsInstance<IntentOperation.ArchiveNode>()

        for (op in addNodes) {
            driver.execute(
                identifier = null,
                sql = "INSERT INTO intent_nodes " +
                    "(id, project_id, type, title, description, priority, lifecycle, parent_id, " +
                    "created_at, created_by_kind, created_by_id, " +
                    "modified_at, modified_by_kind, modified_by_id, tags, row_version) " +
                    "VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, '[]', 1)",
                parameters = 13,
            ) {
                bindString(0, op.id)
                bindString(1, projectId)
                bindString(2, op.type)
                bindString(3, op.title)
                bindString(4, op.description)
                bindLong(5, op.priority.toLong())
                bindString(6, op.parentId)
                bindString(7, now)
                bindString(8, actor.kind.name)
                bindString(9, actor.id)
                bindString(10, now)
                bindString(11, actor.kind.name)
                bindString(12, actor.id)
            }
        }

        for (op in modifyNodes) {
            driver.execute(
                identifier = null,
                sql = "UPDATE intent_nodes SET " +
                    "title = COALESCE(?, title), " +
                    "description = COALESCE(?, description), " +
                    "priority = COALESCE(?, priority), " +
                    "modified_at = ?, " +
                    "modified_by_kind = ?, " +
                    "modified_by_id = ?, " +
                    "row_version = row_version + 1 " +
                    "WHERE id = ? AND project_id = ?",
                parameters = 8,
            ) {
                bindString(0, op.title)
                bindString(1, op.description)
                bindLong(2, op.priority?.toLong())
                bindString(3, now)
                bindString(4, actor.kind.name)
                bindString(5, actor.id)
                bindString(6, op.id)
                bindString(7, projectId)
            }
        }

        for (op in addDependencies) {
            val cycleError = cycleDetector.checkEdgeForCycle(
                projectId = projectId,
                fromNodeId = op.fromNodeId,
                toNodeId = op.toNodeId,
                actor = actor,
            )
            if (cycleError != null) {
                throw IllegalArgumentException("${cycleError.code}: ${cycleError.message}")
            }
            driver.execute(
                identifier = null,
                sql = "INSERT OR IGNORE INTO intent_edges " +
                    "(id, project_id, from_node_id, to_node_id, edge_kind, created_at) " +
                    "VALUES (?, ?, ?, ?, 'DEPENDS_ON', ?)",
                parameters = 5,
            ) {
                bindString(0, idGen())
                bindString(1, projectId)
                bindString(2, op.fromNodeId)
                bindString(3, op.toNodeId)
                bindString(4, now)
            }
        }

        for (op in archiveNodes) {
            driver.execute(
                identifier = null,
                sql = "UPDATE intent_nodes SET " +
                    "lifecycle = 'ARCHIVED', " +
                    "modified_at = ?, " +
                    "modified_by_kind = ?, " +
                    "modified_by_id = ?, " +
                    "row_version = row_version + 1 " +
                    "WHERE id = ? AND project_id = ?",
                parameters = 5,
            ) {
                bindString(0, now)
                bindString(1, actor.kind.name)
                bindString(2, actor.id)
                bindString(3, op.id)
                bindString(4, projectId)
            }
        }
    }

    private fun topologicalSortAddNodes(
        addNodes: List<IntentOperation.AddNode>,
    ): List<IntentOperation.AddNode> {
        if (addNodes.size <= 1) return addNodes
        val byId = addNodes.associateBy { it.id }
        val ordered = mutableListOf<IntentOperation.AddNode>()
        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()

        fun visit(op: IntentOperation.AddNode) {
            if (op.id in visited) return
            if (!visiting.add(op.id)) {
                throw IllegalArgumentException("intent.cycle_rejected: Parent hierarchy cycle involving ${op.id}")
            }
            val parentId = op.parentId
            if (parentId != null) {
                byId[parentId]?.let { parentOp ->
                    visit(parentOp)
                }
            }
            visiting.remove(op.id)
            visited.add(op.id)
            ordered.add(op)
        }

        for (op in addNodes) {
            visit(op)
        }
        return ordered
    }

    private fun encodeOperationsJson(operations: List<IntentOperation>): String {
        val operationsJson = operations.map { json.encodeToString(IntentOperation.serializer(), it) }
        return buildJsonObject {
            putJsonArray("ops") {
                for (opJson in operationsJson) {
                    add(json.parseToJsonElement(opJson))
                }
            }
        }.toString()
    }

    private fun decodeOperationsJson(operationsJson: String): List<IntentOperation> {
        val root = json.parseToJsonElement(operationsJson).jsonObject
        val opsArray = root["ops"]?.jsonArray ?: return emptyList()
        return opsArray.map { element ->
            json.decodeFromJsonElement(IntentOperation.serializer(), element)
        }
    }

    private fun decodeOperationStrings(operationsJson: String): List<String> {
        val root = json.parseToJsonElement(operationsJson).jsonObject
        val opsArray = root["ops"]?.jsonArray ?: return emptyList()
        return opsArray.map { it.toString() }
    }

    private fun isExpired(now: String, expiresAt: String): Boolean {
        val nowInstant = runCatching { Instant.parse(now) }.getOrNull()
        val expiresInstant = runCatching { Instant.parse(expiresAt) }.getOrNull()
        return if (nowInstant != null && expiresInstant != null) {
            nowInstant > expiresInstant
        } else {
            now > expiresAt
        }
    }

    private fun nodeExists(nodeId: String, projectId: String): Boolean {
        var exists = false
        driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM intent_nodes WHERE id = ? AND project_id = ?",
            parameters = 2,
            binders = { bindString(0, nodeId); bindString(1, projectId) },
            mapper = { resultSet ->
                if (resultSet.next().value) {
                    exists = (resultSet.getLong(0) ?: 0L) > 0
                }
                QueryResult.Value(Unit)
            },
        )
        return exists
    }

    private fun loadParentId(nodeId: String, projectId: String): String? {
        var parentId: String? = null
        driver.executeQuery(
            identifier = null,
            sql = "SELECT parent_id FROM intent_nodes WHERE id = ? AND project_id = ?",
            parameters = 2,
            binders = { bindString(0, nodeId); bindString(1, projectId) },
            mapper = { resultSet ->
                if (resultSet.next().value) {
                    parentId = resultSet.getString(0)
                }
                QueryResult.Value(Unit)
            },
        )
        return parentId
    }

    private fun loadProposalRow(proposalId: String, projectId: String): ProposalRow? {
        var row: ProposalRow? = null
        driver.executeQuery(
            identifier = null,
            sql = "SELECT id, project_id, operations_json, rationale, proposed_by_run_id, " +
                "proposed_at, run_taint, state, resolved_by_user_id, resolved_at, expires_at " +
                "FROM intent_proposals WHERE id = ? AND project_id = ?",
            parameters = 2,
            binders = {
                bindString(0, proposalId)
                bindString(1, projectId)
            },
            mapper = { resultSet ->
                if (resultSet.next().value) {
                    row = readProposalRow(resultSet)
                }
                QueryResult.Value(Unit)
            },
        )
        return row
    }

    private fun readProposalRow(resultSet: app.cash.sqldelight.db.SqlCursor): ProposalRow =
        ProposalRow(
            id = resultSet.getString(0)!!,
            projectId = resultSet.getString(1)!!,
            operationsJson = resultSet.getString(2)!!,
            rationale = resultSet.getString(3)!!,
            proposedByRunId = resultSet.getString(4)!!,
            proposedAt = resultSet.getString(5)!!,
            runTaint = resultSet.getString(6)!!,
            state = resultSet.getString(7)!!,
            resolvedByUserId = resultSet.getString(8),
            resolvedAt = resultSet.getString(9),
            expiresAt = resultSet.getString(10)!!,
        )

    private data class ProposalRow(
        val id: String,
        val projectId: String,
        val operationsJson: String,
        val rationale: String,
        val proposedByRunId: String,
        val proposedAt: String,
        val runTaint: String,
        val state: String,
        val resolvedByUserId: String?,
        val resolvedAt: String?,
        val expiresAt: String,
    )

    private fun ProposalRow.toModel(): IntentProposal = IntentProposal(
        id = id,
        projectId = projectId,
        operations = decodeOperationStrings(operationsJson),
        rationale = rationale,
        proposedByRunId = proposedByRunId,
        proposedAt = proposedAt,
        runTaint = runTaint,
        state = state,
        resolvedByUserId = resolvedByUserId,
        resolvedAt = resolvedAt,
        expiresAt = expiresAt,
    )
}
