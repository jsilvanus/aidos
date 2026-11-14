package dev.aidos.intent

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.broker.AuditLog
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ActorRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

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
        val actorKind: String = IntentActorKind.AGENT.name,
        val dispatchPolicy: String = NodeDispatchPolicy.INHERIT.name,
        val granularity: String? = null,
        val targetProfile: String? = null,
        val minLocality: String? = null,
        val estimatedSteps: Int? = null,
        val scopePaths: List<String> = emptyList(),
        val dueAt: String? = null,
        val recurrenceJson: String? = null,
        val checkKind: String? = null,
        val checkSpec: String? = null,
        val tags: List<String> = emptyList(),
    ) : IntentOperation()

    @Serializable
    data class ModifyNode(
        val id: String,
        val title: String? = null,
        val description: String? = null,
        val priority: Int? = null,
        val parentId: String? = null,
        val actorKind: String? = null,
        val dispatchPolicy: String? = null,
        val granularity: String? = null,
        val targetProfile: String? = null,
        val minLocality: String? = null,
        val estimatedSteps: Int? = null,
        val scopePaths: List<String>? = null,
        val dueAt: String? = null,
        val recurrenceJson: String? = null,
        val checkKind: String? = null,
        val checkSpec: String? = null,
        val tags: List<String>? = null,
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

    @Serializable
    data class AddEdge(
        val fromNodeId: String,
        val toNodeId: String,
        val edgeKind: String,
    ) : IntentOperation()

    @Serializable
    data class RemoveEdge(
        val fromNodeId: String,
        val toNodeId: String,
        val edgeKind: String,
    ) : IntentOperation()
}

@Serializable
enum class ProposalResolutionDecision {
    ACCEPTED,
    ACCEPTED_WITH_EDITS,
    REJECTED,
    SUPERSEDED,
    EXPIRED,
}

@Serializable
data class IntentProposal(
    val id: String,
    val projectId: String,
    val operations: List<String>,
    val rationale: String,
    val proposedByRunId: String,
    val proposedAt: String,
    val runTaint: String,
    val state: String,
    val resolvedByUserId: String?,
    val resolvedAt: String?,
    val expiresAt: String?,
)

/**
 * Proposal gate for AI-driven changes to the Intent Graph (RFC-0012, RFC-0110).
 *
 * Sessions propose atomic batches of [IntentOperation]s via [propose]; only human users
 * resolve them via [resolve]. Both intra-batch node references and DB+batch DAG acyclicity
 * are validated before persisting a pending proposal.
 */
class IntentProposalEngine(
    private val driver: SqlDriver,
    private val audit: AuditLog,
    private val cycleDetector: IntentCycleDetector,
    private val idGen: () -> String,
    private val nowIso: () -> String,
) {
    private val json = Json {
        classDiscriminator = "_kind"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val stringListSerializer = ListSerializer(String.serializer())

    fun propose(
        projectId: String,
        runId: String,
        operations: List<IntentOperation>,
        rationale: String,
        runTaint: String,
        actor: ActorRef,
        expiresAtIso: String,
    ): String {
        require(operations.isNotEmpty()) { "Proposal must include at least one operation." }
        require(rationale.isNotBlank()) { "Proposal rationale must not be blank." }

        validateBatch(projectId, operations, actor)

        val proposalId = idGen()
        val auditId = idGen()
        val now = nowIso()

        val encodedOps = operations.map { json.encodeToString(IntentOperation.serializer(), it) }
        val operationsJson = json.encodeToString(stringListSerializer, encodedOps)

        // Write audit entry BEFORE inserting intent_proposals so FOREIGN KEY (audit_ref) succeeds.
        audit.write(
            id = auditId,
            projectId = projectId,
            kind = "IntentProposalCreated",
            actor = actor,
            subjectRef = proposalId,
            detailJson = """{"run_id":"${escapeJson(runId)}","operation_count":${operations.size},"run_taint":"${escapeJson(runTaint)}"}""",
            nowIso = now,
        )

        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO intent_proposals (
                    id, project_id, operations_json, rationale, proposed_by_run_id,
                    proposed_at, run_taint, state, resolved_by_user_id, resolved_at,
                    expires_at, audit_ref
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', NULL, NULL, ?, ?)
            """.trimIndent(),
            parameters = 9,
        ) {
            bindString(0, proposalId)
            bindString(1, projectId)
            bindString(2, operationsJson)
            bindString(3, rationale)
            bindString(4, runId)
            bindString(5, now)
            bindString(6, runTaint)
            bindString(7, expiresAtIso)
            bindString(8, auditId)
        }

        return proposalId
    }

    fun getProposal(projectId: String, proposalId: String): IntentProposal? = driver.executeQuery(
        identifier = null,
        sql = """
            SELECT id, project_id, operations_json, rationale, proposed_by_run_id,
                   proposed_at, run_taint, state, resolved_by_user_id, resolved_at, expires_at
            FROM intent_proposals
            WHERE project_id = ? AND id = ?
        """.trimIndent(),
        mapper = { cursor ->
            val item = if (cursor.next().value) readProposalRow(cursor) else null
            QueryResult.Value(item)
        },
        parameters = 2,
    ) {
        bindString(0, projectId)
        bindString(1, proposalId)
    }.value

    fun listProposals(projectId: String, state: String? = null): List<IntentProposal> {
        val sql = if (state != null) {
            """
            SELECT id, project_id, operations_json, rationale, proposed_by_run_id,
                   proposed_at, run_taint, state, resolved_by_user_id, resolved_at, expires_at
            FROM intent_proposals
            WHERE project_id = ? AND state = ?
            ORDER BY proposed_at ASC, id ASC
            """.trimIndent()
        } else {
            """
            SELECT id, project_id, operations_json, rationale, proposed_by_run_id,
                   proposed_at, run_taint, state, resolved_by_user_id, resolved_at, expires_at
            FROM intent_proposals
            WHERE project_id = ?
            ORDER BY proposed_at ASC, id ASC
            """.trimIndent()
        }
        return driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                val list = mutableListOf<IntentProposal>()
                while (cursor.next().value) {
                    list.add(readProposalRow(cursor))
                }
                QueryResult.Value(list)
            },
            parameters = if (state != null) 2 else 1,
        ) {
            bindString(0, projectId)
            if (state != null) {
                bindString(1, state)
            }
        }.value
    }

    fun resolve(
        projectId: String,
        proposalId: String,
        decision: ProposalResolutionDecision,
        resolvedByUserId: String,
        editedOperations: List<IntentOperation>? = null,
    ): IntentProposal {
        require(resolvedByUserId.isNotBlank()) { "Resolving user ID must not be blank." }
        val existing = getProposal(projectId, proposalId)
            ?: throw IllegalArgumentException("Intent proposal '$proposalId' not found in project '$projectId'.")
        require(existing.state == "PENDING") {
            "Intent proposal '$proposalId' is already in state '${existing.state}'."
        }

        val operationsToApply: List<IntentOperation> = when (decision) {
            ProposalResolutionDecision.ACCEPTED -> {
                existing.operations.map { json.decodeFromString(IntentOperation.serializer(), it) }
            }
            ProposalResolutionDecision.ACCEPTED_WITH_EDITS -> {
                val edits = editedOperations
                    ?: throw IllegalArgumentException("ACCEPTED_WITH_EDITS requires editedOperations.")
                require(edits.isNotEmpty()) { "editedOperations must not be empty." }
                edits
            }
            ProposalResolutionDecision.REJECTED,
            ProposalResolutionDecision.SUPERSEDED,
            ProposalResolutionDecision.EXPIRED -> emptyList()
        }

        val userActor = ActorRef(ActorKind.USER, resolvedByUserId)
        if (operationsToApply.isNotEmpty()) {
            validateBatch(projectId, operationsToApply, userActor)
            val repository = IntentGraphRepository(
                driver = driver,
                cycleDetector = cycleDetector,
                idGen = idGen,
                nowIso = nowIso,
            )
            applyOperations(projectId, repository, userActor, operationsToApply)
        }

        val resolvedAt = nowIso()
        audit.write(
            id = idGen(),
            projectId = projectId,
            kind = "IntentProposalResolved",
            actor = ActorRef(ActorKind.USER, resolvedByUserId),
            subjectRef = proposalId,
            detailJson = """{"decision":"${decision.name}","applied_count":${operationsToApply.size}}""",
            nowIso = resolvedAt,
        )

        driver.execute(
            identifier = null,
            sql = """
                UPDATE intent_proposals
                SET state = ?, resolved_by_user_id = ?, resolved_at = ?
                WHERE project_id = ? AND id = ?
            """.trimIndent(),
            parameters = 5,
        ) {
            bindString(0, decision.name)
            bindString(1, resolvedByUserId)
            bindString(2, resolvedAt)
            bindString(3, projectId)
            bindString(4, proposalId)
        }

        return getProposal(projectId, proposalId)!!
    }

    private fun validateBatch(projectId: String, operations: List<IntentOperation>, actor: ActorRef) {
        val existingNodes = IntentGraphRepository.readNodes(driver, projectId, includeArchived = true)
        val knownNodeIds = existingNodes.map { it.id }.toMutableSet()
        val archivedNodeIds = existingNodes
            .filter { it.lifecycle == IntentLifecycle.ARCHIVED }
            .map { it.id }
            .toMutableSet()
        val batchAddedDeps = mutableListOf<Pair<String, String>>()
        val batchRemovedDeps = mutableSetOf<Pair<String, String>>()

        for (op in operations) {
            when (op) {
                is IntentOperation.AddNode -> {
                    require(op.id !in knownNodeIds) {
                        "Intent node '${op.id}' already exists in project '$projectId' or earlier in batch."
                    }
                    require(op.title.isNotBlank()) { "AddNode title must not be blank." }
                    val nodeType = IntentNodeType.valueOf(op.type)
                    IntentActorKind.valueOf(op.actorKind)
                    NodeDispatchPolicy.valueOf(op.dispatchPolicy)
                    op.granularity?.let(IntentGranularity::valueOf)
                    op.targetProfile?.let(IntentTargetProfile::valueOf)
                    op.minLocality?.let(IntentMinLocality::valueOf)
                    op.checkKind?.let(IntentCheckKind::valueOf)
                    if (op.estimatedSteps != null) {
                        require(op.estimatedSteps > 0) { "estimatedSteps must be positive." }
                    }
                    if (op.parentId != null) {
                        if (op.parentId !in knownNodeIds || op.parentId in archivedNodeIds) {
                            throw IllegalArgumentException(
                                "intent.node_not_found: Parent node '${op.parentId}' not found or archived in project '$projectId'.",
                            )
                        }
                    }
                    check(nodeType.name == op.type)
                    knownNodeIds.add(op.id)
                }
                is IntentOperation.ModifyNode -> {
                    if (op.id !in knownNodeIds || op.id in archivedNodeIds) {
                        throw IllegalArgumentException(
                            "intent.node_not_found: Cannot modify missing or archived intent node '${op.id}'.",
                        )
                    }
                    if (op.title != null) {
                        require(op.title.isNotBlank()) { "ModifyNode title must not be blank." }
                    }
                    op.actorKind?.let(IntentActorKind::valueOf)
                    op.dispatchPolicy?.let(NodeDispatchPolicy::valueOf)
                    op.granularity?.let(IntentGranularity::valueOf)
                    op.targetProfile?.let(IntentTargetProfile::valueOf)
                    op.minLocality?.let(IntentMinLocality::valueOf)
                    op.checkKind?.let(IntentCheckKind::valueOf)
                    if (op.estimatedSteps != null) {
                        require(op.estimatedSteps > 0) { "estimatedSteps must be positive." }
                    }
                    if (op.parentId != null) {
                        require(op.parentId != op.id) { "Node cannot be its own parent: '${op.id}'." }
                        if (op.parentId !in knownNodeIds || op.parentId in archivedNodeIds) {
                            throw IllegalArgumentException(
                                "intent.node_not_found: Parent node '${op.parentId}' not found or archived.",
                            )
                        }
                    }
                }
                is IntentOperation.ArchiveNode -> {
                    if (op.id !in knownNodeIds) {
                        throw IllegalArgumentException(
                            "intent.node_not_found: Cannot archive unknown intent node '${op.id}'.",
                        )
                    }
                    archivedNodeIds.add(op.id)
                }
                is IntentOperation.AddDependency -> {
                    validateDependencyEdge(
                        projectId = projectId,
                        fromNodeId = op.fromNodeId,
                        toNodeId = op.toNodeId,
                        actor = actor,
                        knownNodeIds = knownNodeIds,
                        archivedNodeIds = archivedNodeIds,
                        batchAddedDeps = batchAddedDeps,
                        batchRemovedDeps = batchRemovedDeps,
                    )
                }
                is IntentOperation.AddEdge -> {
                    val kind = IntentEdgeKind.valueOf(op.edgeKind)
                    if (kind == IntentEdgeKind.DEPENDS_ON) {
                        validateDependencyEdge(
                            projectId = projectId,
                            fromNodeId = op.fromNodeId,
                            toNodeId = op.toNodeId,
                            actor = actor,
                            knownNodeIds = knownNodeIds,
                            archivedNodeIds = archivedNodeIds,
                            batchAddedDeps = batchAddedDeps,
                            batchRemovedDeps = batchRemovedDeps,
                        )
                    } else {
                        if (op.fromNodeId == op.toNodeId) {
                            throw IllegalArgumentException("intent.self_dependency: Self-edge is not allowed: ${op.fromNodeId}")
                        }
                        if (op.fromNodeId !in knownNodeIds || op.fromNodeId in archivedNodeIds) {
                            throw IllegalArgumentException("intent.node_not_found: Source node '${op.fromNodeId}' not found or archived.")
                        }
                        if (op.toNodeId !in knownNodeIds || op.toNodeId in archivedNodeIds) {
                            throw IllegalArgumentException("intent.node_not_found: Target node '${op.toNodeId}' not found or archived.")
                        }
                    }
                }
                is IntentOperation.RemoveEdge -> {
                    val kind = IntentEdgeKind.valueOf(op.edgeKind)
                    if (op.fromNodeId !in knownNodeIds || op.toNodeId !in knownNodeIds) {
                        throw IllegalArgumentException(
                            "intent.node_not_found: Edge endpoints not found for RemoveEdge(${op.fromNodeId} -> ${op.toNodeId}).",
                        )
                    }
                    if (kind == IntentEdgeKind.DEPENDS_ON) {
                        batchRemovedDeps.add(op.fromNodeId to op.toNodeId)
                        batchAddedDeps.removeAll { it == (op.fromNodeId to op.toNodeId) }
                    }
                }
            }
        }
    }

    private fun validateDependencyEdge(
        projectId: String,
        fromNodeId: String,
        toNodeId: String,
        actor: ActorRef,
        knownNodeIds: Set<String>,
        archivedNodeIds: Set<String>,
        batchAddedDeps: MutableList<Pair<String, String>>,
        batchRemovedDeps: MutableSet<Pair<String, String>>,
    ) {
        if (fromNodeId == toNodeId) {
            throw IllegalArgumentException("intent.self_dependency: Self-dependency is not allowed: $fromNodeId")
        }
        if (fromNodeId !in knownNodeIds || fromNodeId in archivedNodeIds) {
            throw IllegalArgumentException("intent.node_not_found: Source node '$fromNodeId' not found or archived.")
        }
        if (toNodeId !in knownNodeIds || toNodeId in archivedNodeIds) {
            throw IllegalArgumentException("intent.node_not_found: Target node '$toNodeId' not found or archived.")
        }
        val cycleErr = cycleDetector.checkEdgeForCycle(
            projectId = projectId,
            fromNodeId = fromNodeId,
            toNodeId = toNodeId,
            actor = actor,
            additionalEdges = batchAddedDeps,
            removedEdges = batchRemovedDeps,
        )
        if (cycleErr != null) {
            throw IllegalArgumentException("${cycleErr.code}: ${cycleErr.message}")
        }
        batchRemovedDeps.remove(fromNodeId to toNodeId)
        batchAddedDeps.add(fromNodeId to toNodeId)
    }

    private fun applyOperations(
        projectId: String,
        repository: IntentGraphRepository,
        userActor: ActorRef,
        operations: List<IntentOperation>,
    ) {
        for (op in operations) {
            when (op) {
                is IntentOperation.AddNode -> {
                    val nodeType = IntentNodeType.valueOf(op.type)
                    repository.createNode(
                        projectId = projectId,
                        id = op.id,
                        type = nodeType,
                        title = op.title,
                        description = op.description,
                        priority = op.priority,
                        parentId = op.parentId,
                        actorKind = IntentActorKind.valueOf(op.actorKind),
                        dispatchPolicy = NodeDispatchPolicy.valueOf(op.dispatchPolicy),
                        granularity = op.granularity?.let(IntentGranularity::valueOf)
                            ?: IntentGraphRepository.defaultGranularityForType(nodeType),
                        targetProfile = op.targetProfile?.let(IntentTargetProfile::valueOf),
                        minLocality = op.minLocality?.let(IntentMinLocality::valueOf),
                        estimatedSteps = op.estimatedSteps,
                        scopePaths = op.scopePaths,
                        dueAt = op.dueAt,
                        recurrenceJson = op.recurrenceJson,
                        checkKind = op.checkKind?.let(IntentCheckKind::valueOf),
                        checkSpec = op.checkSpec,
                        tags = op.tags,
                        createdBy = userActor,
                    )
                }
                is IntentOperation.ModifyNode -> {
                    repository.updateNode(
                        projectId = projectId,
                        nodeId = op.id,
                        title = op.title,
                        description = op.description,
                        priority = op.priority,
                        parentId = op.parentId,
                        actorKind = op.actorKind?.let(IntentActorKind::valueOf),
                        dispatchPolicy = op.dispatchPolicy?.let(NodeDispatchPolicy::valueOf),
                        granularity = op.granularity?.let(IntentGranularity::valueOf),
                        targetProfile = op.targetProfile?.let(IntentTargetProfile::valueOf),
                        minLocality = op.minLocality?.let(IntentMinLocality::valueOf),
                        estimatedSteps = op.estimatedSteps,
                        scopePaths = op.scopePaths,
                        dueAt = op.dueAt,
                        recurrenceJson = op.recurrenceJson,
                        checkKind = op.checkKind?.let(IntentCheckKind::valueOf),
                        checkSpec = op.checkSpec,
                        tags = op.tags,
                        modifiedBy = userActor,
                    )
                }
                is IntentOperation.ArchiveNode -> {
                    repository.archiveNode(projectId, op.id, modifiedBy = userActor)
                }
                is IntentOperation.AddDependency -> {
                    repository.addEdge(
                        projectId = projectId,
                        fromNodeId = op.fromNodeId,
                        toNodeId = op.toNodeId,
                        edgeKind = IntentEdgeKind.DEPENDS_ON,
                        actor = userActor,
                    )
                }
                is IntentOperation.AddEdge -> {
                    repository.addEdge(
                        projectId = projectId,
                        fromNodeId = op.fromNodeId,
                        toNodeId = op.toNodeId,
                        edgeKind = IntentEdgeKind.valueOf(op.edgeKind),
                        actor = userActor,
                    )
                }
                is IntentOperation.RemoveEdge -> {
                    repository.removeEdge(projectId, op.fromNodeId, op.toNodeId, IntentEdgeKind.valueOf(op.edgeKind))
                }
            }
        }
    }

    private fun readProposalRow(cursor: SqlCursor): IntentProposal {
        val rawOpsJson = cursor.getString(2)!!
        val ops = json.decodeFromString(stringListSerializer, rawOpsJson)
        return IntentProposal(
            id = cursor.getString(0)!!,
            projectId = cursor.getString(1)!!,
            operations = ops,
            rationale = cursor.getString(3)!!,
            proposedByRunId = cursor.getString(4)!!,
            proposedAt = cursor.getString(5)!!,
            runTaint = cursor.getString(6)!!,
            state = cursor.getString(7)!!,
            resolvedByUserId = cursor.getString(8),
            resolvedAt = cursor.getString(9),
            expiresAt = cursor.getString(10),
        )
    }

    private fun escapeJson(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
