package dev.aidos.api

import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.EffectiveDispatchPolicy
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentCheckKind
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentEdgeRecord
import dev.aidos.intent.IntentGranularity
import dev.aidos.intent.IntentGraphRepository
import dev.aidos.intent.IntentGraphSnapshot
import dev.aidos.intent.IntentLifecycle
import dev.aidos.intent.IntentMinLocality
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.intent.IntentNodeType
import dev.aidos.intent.IntentOperation
import dev.aidos.intent.IntentProposal
import dev.aidos.intent.IntentTargetProfile
import dev.aidos.intent.NodeDispatchPolicy
import dev.aidos.intent.ProposalResolutionDecision
import dev.aidos.intent.SnapshotReconcileResult
import dev.aidos.intent.isLocalityCompatible
import dev.aidos.intent.isProfileCompatible
import dev.aidos.kernel.PlatformProfile
import kotlinx.serialization.json.Json

/**
 * Shared in-memory [IntentCommands] implementation used by [MockRuntimeClient] and by
 * [RealRuntimeClient] when running without a wired SQLite project database.
 */
internal class InMemoryIntentCommands(
    private val nextId: () -> String,
    private val nowIso: () -> String,
) : IntentCommands {

    private val nodes = mutableMapOf<String, MutableMap<String, IntentNodeRecord>>()
    private val edges = mutableMapOf<String, MutableList<IntentEdgeRecord>>()
    private val proposals = mutableMapOf<String, MutableMap<String, IntentProposal>>()
    private val opJson = Json {
        classDiscriminator = "_kind"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val snapshotJsonCodec = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override suspend fun createNode(request: CreateIntentNodeRequest): IntentNodeRecord {
        require(request.title.isNotBlank()) { "Intent node title must not be blank." }
        val projectNodes = nodes.getOrPut(request.projectId) { mutableMapOf() }
        val id = request.id ?: nextId()
        require(id !in projectNodes) { "Intent node '$id' already exists." }
        if (request.parentId != null) {
            require(request.parentId in projectNodes) {
                "intent.node_not_found: Parent '${request.parentId}' not found."
            }
        }
        val now = nowIso()
        val record = IntentNodeRecord(
            id = id,
            projectId = request.projectId,
            type = request.type,
            title = request.title,
            description = request.description,
            priority = request.priority,
            lifecycle = IntentLifecycle.ACTIVE,
            parentId = request.parentId,
            actorKind = request.actorKind,
            dispatchPolicy = request.dispatchPolicy,
            granularity = request.granularity,
            targetProfile = request.targetProfile,
            minLocality = request.minLocality,
            estimatedSteps = request.estimatedSteps,
            scopePaths = request.scopePaths,
            dueAt = request.dueAt,
            recurrenceJson = request.recurrenceJson,
            checkKind = request.checkKind,
            checkSpec = request.checkSpec,
            createdAt = now,
            createdByKind = "USER",
            createdById = "local-user",
            modifiedAt = now,
            modifiedByKind = "USER",
            modifiedById = "local-user",
            tags = request.tags,
            rowVersion = 1,
        )
        projectNodes[id] = record
        return record
    }

    override suspend fun updateNode(request: UpdateIntentNodeRequest): IntentNodeRecord {
        val projectNodes = nodes[request.projectId]
            ?: throw IllegalArgumentException("Intent node '${request.nodeId}' not found.")
        val existing = projectNodes[request.nodeId]
            ?: throw IllegalArgumentException("Intent node '${request.nodeId}' not found.")
        val nextTitle = request.title ?: existing.title
        require(nextTitle.isNotBlank()) { "Intent node title must not be blank." }
        val updated = existing.copy(
            title = nextTitle,
            description = if (request.clearDescription) null else (request.description ?: existing.description),
            priority = request.priority ?: existing.priority,
            parentId = if (request.clearParentId) null else (request.parentId ?: existing.parentId),
            actorKind = request.actorKind ?: existing.actorKind,
            dispatchPolicy = request.dispatchPolicy ?: existing.dispatchPolicy,
            granularity = request.granularity ?: existing.granularity,
            targetProfile = if (request.clearTargetProfile) null else (request.targetProfile ?: existing.targetProfile),
            minLocality = if (request.clearMinLocality) null else (request.minLocality ?: existing.minLocality),
            estimatedSteps = if (request.clearEstimatedSteps) null else (request.estimatedSteps ?: existing.estimatedSteps),
            scopePaths = request.scopePaths ?: existing.scopePaths,
            dueAt = if (request.clearDueAt) null else (request.dueAt ?: existing.dueAt),
            recurrenceJson = if (request.clearRecurrenceJson) null else (request.recurrenceJson ?: existing.recurrenceJson),
            checkKind = request.checkKind ?: existing.checkKind,
            checkSpec = request.checkSpec ?: existing.checkSpec,
            tags = request.tags ?: existing.tags,
            modifiedAt = nowIso(),
            rowVersion = existing.rowVersion + 1,
        )
        projectNodes[updated.id] = updated
        return updated
    }

    override suspend fun archiveNode(projectId: String, nodeId: String): IntentNodeRecord {
        val projectNodes = nodes[projectId]
            ?: throw IllegalArgumentException("Intent node '$nodeId' not found.")
        val existing = projectNodes[nodeId]
            ?: throw IllegalArgumentException("Intent node '$nodeId' not found.")
        val archived = existing.copy(
            lifecycle = IntentLifecycle.ARCHIVED,
            modifiedAt = nowIso(),
            rowVersion = existing.rowVersion + 1,
        )
        projectNodes[nodeId] = archived
        return archived
    }

    override suspend fun getNodeStatus(
        projectId: String,
        nodeId: String,
        projectDefaultPolicy: EffectiveDispatchPolicy,
    ): IntentNodeStatusView? {
        val node = nodes[projectId]?.get(nodeId) ?: return null
        return buildStatusView(projectId, node, projectDefaultPolicy)
    }

    override suspend fun listNodes(
        projectId: String,
        includeArchived: Boolean,
        actorKind: IntentActorKind?,
        parentId: String?,
        filterByParent: Boolean,
    ): List<IntentNodeRecord> = nodes[projectId]?.values
        .orEmpty()
        .filter { node ->
            (includeArchived || node.lifecycle == IntentLifecycle.ACTIVE) &&
                (actorKind == null || node.actorKind == actorKind) &&
                (!filterByParent || node.parentId == parentId)
        }
        .sortedWith(compareBy({ it.priority }, { it.createdAt }, { it.id }))

    override suspend fun listActionableForHuman(
        projectId: String,
        projectDefaultPolicy: EffectiveDispatchPolicy,
    ): List<IntentNodeStatusView> = listNodes(projectId)
        .map { buildStatusView(projectId, it, projectDefaultPolicy) }
        .filter { view ->
            when (view.node.actorKind) {
                IntentActorKind.HUMAN -> view.derivedStatus in setOf(
                    DerivedStatus.NOT_STARTED,
                    DerivedStatus.IN_PROGRESS,
                    DerivedStatus.NEEDS_REVIEW,
                )
                IntentActorKind.HYBRID, IntentActorKind.AGENT ->
                    view.derivedStatus == DerivedStatus.NEEDS_REVIEW
            }
        }
        .sortedWith(
            compareBy<IntentNodeStatusView>(
                { it.node.dueAt == null },
                { it.node.dueAt ?: "" },
                { it.node.priority },
                { it.node.createdAt },
            ),
        )

    override suspend fun listReadyForAgent(
        projectId: String,
        profile: PlatformProfile,
        locality: IntentMinLocality,
        projectDefaultPolicy: EffectiveDispatchPolicy,
        onlyAutoDispatch: Boolean,
    ): List<IntentNodeStatusView> = listNodes(projectId)
        .filter { node ->
            node.actorKind != IntentActorKind.HUMAN &&
                node.type in setOf(IntentNodeType.GOAL, IntentNodeType.SUB_GOAL, IntentNodeType.ATOMIC_TASK) &&
                isProfileCompatible(node.targetProfile, profile) &&
                isLocalityCompatible(node.minLocality, locality)
        }
        .map { buildStatusView(projectId, it, projectDefaultPolicy) }
        .filter { view ->
            view.derivedStatus == DerivedStatus.NOT_STARTED &&
                (!onlyAutoDispatch || view.effectiveDispatchPolicy == EffectiveDispatchPolicy.AUTO_WHEN_READY)
        }
        .sortedWith(compareBy({ it.node.priority }, { it.node.createdAt }))

    override suspend fun completeHumanNode(
        projectId: String,
        nodeId: String,
        userId: String,
        note: String?,
    ): IntentNodeRecord {
        val projectNodes = nodes[projectId]
            ?: throw IllegalArgumentException("Intent node '$nodeId' not found.")
        val existing = projectNodes[nodeId]
            ?: throw IllegalArgumentException("Intent node '$nodeId' not found.")
        require(existing.actorKind == IntentActorKind.HUMAN || existing.actorKind == IntentActorKind.HYBRID) {
            "completeHumanNode applies to HUMAN or HYBRID nodes."
        }
        val now = nowIso()
        val updated = existing.copy(
            assertedStatus = DerivedStatus.DONE,
            assertedAt = now,
            assertedByUserId = userId,
            assertionNote = note,
            verificationMet = true,
            verifiedByKind = "USER",
            verifiedById = userId,
            verifiedAt = now,
            modifiedAt = now,
            modifiedByKind = "USER",
            modifiedById = userId,
            rowVersion = existing.rowVersion + 1,
        )
        projectNodes[nodeId] = updated
        return updated
    }

    override suspend fun addEdge(
        projectId: String,
        fromNodeId: String,
        toNodeId: String,
        edgeKind: IntentEdgeKind,
    ): IntentEdgeRecord {
        if (fromNodeId == toNodeId) {
            throw IllegalArgumentException("intent.self_dependency: Self-referential edge '$fromNodeId'.")
        }
        val projectNodes = nodes[projectId].orEmpty()
        if (fromNodeId !in projectNodes || toNodeId !in projectNodes) {
            throw IllegalArgumentException("intent.node_not_found: Edge endpoint missing.")
        }
        val projectEdges = edges.getOrPut(projectId) { mutableListOf() }
        projectEdges.firstOrNull {
            it.fromNodeId == fromNodeId && it.toNodeId == toNodeId && it.edgeKind == edgeKind
        }?.let { return it }

        if (edgeKind == IntentEdgeKind.DEPENDS_ON && reaches(projectId, toNodeId, fromNodeId)) {
            throw IllegalArgumentException("intent.cycle_rejected: Adding $fromNodeId -> $toNodeId creates a cycle.")
        }
        val record = IntentEdgeRecord(
            id = nextId(),
            projectId = projectId,
            fromNodeId = fromNodeId,
            toNodeId = toNodeId,
            edgeKind = edgeKind,
            createdAt = nowIso(),
        )
        projectEdges.add(record)
        return record
    }

    override suspend fun removeEdge(
        projectId: String,
        fromNodeId: String,
        toNodeId: String,
        edgeKind: IntentEdgeKind,
    ): Boolean = edges[projectId]?.removeAll {
        it.fromNodeId == fromNodeId && it.toNodeId == toNodeId && it.edgeKind == edgeKind
    } ?: false

    override suspend fun listEdges(
        projectId: String,
        fromNodeId: String?,
        toNodeId: String?,
        edgeKind: IntentEdgeKind?,
    ): List<IntentEdgeRecord> = edgesMatching(projectId, fromNodeId, toNodeId, edgeKind)

    private fun edgesMatching(
        projectId: String,
        fromNodeId: String? = null,
        toNodeId: String? = null,
        edgeKind: IntentEdgeKind? = null,
    ): List<IntentEdgeRecord> = edges[projectId].orEmpty().filter {
        (fromNodeId == null || it.fromNodeId == fromNodeId) &&
            (toNodeId == null || it.toNodeId == toNodeId) &&
            (edgeKind == null || it.edgeKind == edgeKind)
    }

    override suspend fun propose(request: CreateIntentProposalRequest): String {
        require(request.operations.isNotEmpty()) { "Proposal must contain operations." }
        require(request.rationale.isNotBlank()) { "Proposal rationale must not be blank." }
        val proposalId = nextId()
        val encodedOps = request.operations.map { opJson.encodeToString(IntentOperation.serializer(), it) }
        val proposal = IntentProposal(
            id = proposalId,
            projectId = request.projectId,
            operations = encodedOps,
            rationale = request.rationale,
            proposedByRunId = request.runId,
            proposedAt = nowIso(),
            runTaint = request.runTaint,
            state = "PENDING",
            resolvedByUserId = null,
            resolvedAt = null,
            expiresAt = request.expiresAtIso,
        )
        proposals.getOrPut(request.projectId) { mutableMapOf() }[proposalId] = proposal
        return proposalId
    }

    override suspend fun listProposals(projectId: String, state: String?): List<IntentProposal> =
        proposals[projectId]?.values.orEmpty().filter { state == null || it.state == state }

    override suspend fun resolveProposal(
        projectId: String,
        proposalId: String,
        decision: ProposalResolutionDecision,
        resolvedByUserId: String,
        editedOperations: List<IntentOperation>?,
    ): IntentProposal {
        val projectProposals = proposals[projectId]
            ?: throw IllegalArgumentException("Proposal '$proposalId' not found.")
        val existing = projectProposals[proposalId]
            ?: throw IllegalArgumentException("Proposal '$proposalId' not found.")
        require(existing.state == "PENDING") { "Proposal '$proposalId' already resolved." }

        val opsToApply: List<IntentOperation> = when (decision) {
            ProposalResolutionDecision.ACCEPTED ->
                existing.operations.map { opJson.decodeFromString(IntentOperation.serializer(), it) }
            ProposalResolutionDecision.ACCEPTED_WITH_EDITS ->
                editedOperations ?: throw IllegalArgumentException("editedOperations required.")
            ProposalResolutionDecision.REJECTED,
            ProposalResolutionDecision.SUPERSEDED,
            ProposalResolutionDecision.EXPIRED -> emptyList()
        }

        for (op in opsToApply) {
            when (op) {
                is IntentOperation.AddNode -> createNode(
                    CreateIntentNodeRequest(
                        projectId = projectId,
                        title = op.title,
                        type = IntentNodeType.valueOf(op.type),
                        id = op.id,
                        description = op.description,
                        priority = op.priority,
                        parentId = op.parentId,
                        actorKind = IntentActorKind.valueOf(op.actorKind),
                        dispatchPolicy = NodeDispatchPolicy.valueOf(op.dispatchPolicy),
                        granularity = op.granularity?.let(IntentGranularity::valueOf)
                            ?: IntentGraphRepository.defaultGranularityForType(IntentNodeType.valueOf(op.type)),
                        targetProfile = op.targetProfile?.let(IntentTargetProfile::valueOf),
                        minLocality = op.minLocality?.let(IntentMinLocality::valueOf),
                        estimatedSteps = op.estimatedSteps,
                        scopePaths = op.scopePaths,
                        dueAt = op.dueAt,
                        recurrenceJson = op.recurrenceJson,
                        checkKind = op.checkKind?.let(IntentCheckKind::valueOf),
                        checkSpec = op.checkSpec,
                        tags = op.tags,
                    ),
                )
                is IntentOperation.ModifyNode -> updateNode(
                    UpdateIntentNodeRequest(
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
                    ),
                )
                is IntentOperation.ArchiveNode -> archiveNode(projectId, op.id)
                is IntentOperation.AddDependency -> addEdge(projectId, op.fromNodeId, op.toNodeId, IntentEdgeKind.DEPENDS_ON)
                is IntentOperation.AddEdge -> addEdge(projectId, op.fromNodeId, op.toNodeId, IntentEdgeKind.valueOf(op.edgeKind))
                is IntentOperation.RemoveEdge -> removeEdge(projectId, op.fromNodeId, op.toNodeId, IntentEdgeKind.valueOf(op.edgeKind))
            }
        }

        val updated = existing.copy(
            state = decision.name,
            resolvedByUserId = resolvedByUserId,
            resolvedAt = nowIso(),
        )
        projectProposals[proposalId] = updated
        return updated
    }

    override suspend fun confirmImplementation(
        projectId: String,
        nodeId: String,
        confirmedByKind: String,
        confirmedById: String,
        criterionIds: List<String>?,
    ) {
        val projectNodes = nodes[projectId]
            ?: throw IllegalArgumentException("Intent node '$nodeId' not found.")
        val existing = projectNodes[nodeId]
            ?: throw IllegalArgumentException("Intent node '$nodeId' not found.")
        val now = nowIso()
        projectNodes[nodeId] = existing.copy(
            verificationMet = true,
            verifiedByKind = confirmedByKind,
            verifiedById = confirmedById,
            verifiedAt = now,
            modifiedAt = now,
            rowVersion = existing.rowVersion + 1,
        )
        val targets = criterionIds ?: listEdges(projectId, fromNodeId = nodeId, edgeKind = IntentEdgeKind.ACCEPTS)
            .map { it.toNodeId }
        for (cid in targets) {
            val crit = projectNodes[cid] ?: continue
            projectNodes[cid] = crit.copy(
                verificationMet = true,
                verifiedByKind = confirmedByKind,
                verifiedById = confirmedById,
                verifiedAt = now,
                modifiedAt = now,
                rowVersion = crit.rowVersion + 1,
            )
        }
    }

    override suspend fun exportSnapshotJson(projectId: String): String {
        val snapshot = IntentGraphSnapshot(
            schemaVersion = 1,
            projectId = projectId,
            exportedAt = nowIso(),
            nodes = nodes[projectId]?.values.orEmpty().sortedBy { it.id },
            edges = edges[projectId].orEmpty().sortedWith(
                compareBy<IntentEdgeRecord>(
                    { it.edgeKind.name },
                    { it.fromNodeId },
                    { it.toNodeId },
                    { it.id },
                ),
            ),
        )
        return snapshotJsonCodec.encodeToString(IntentGraphSnapshot.serializer(), snapshot)
    }

    override suspend fun importAndReconcileSnapshot(
        projectId: String,
        snapshotJson: String,
    ): SnapshotReconcileResult {
        val snapshot = snapshotJsonCodec.decodeFromString(IntentGraphSnapshot.serializer(), snapshotJson)
        require(snapshot.projectId == projectId) { "Snapshot project mismatch." }
        val projectNodes = nodes.getOrPut(projectId) { mutableMapOf() }
        val projectEdges = edges.getOrPut(projectId) { mutableListOf() }
        var addedNodes = 0
        var updatedNodes = 0
        val conflicted = mutableListOf<String>()
        for (incoming in snapshot.nodes) {
            val existing = projectNodes[incoming.id]
            if (existing == null) {
                projectNodes[incoming.id] = incoming
                addedNodes++
            } else if (existing != incoming) {
                when {
                    incoming.rowVersion > existing.rowVersion -> {
                        projectNodes[incoming.id] = incoming
                        updatedNodes++
                    }
                    incoming.rowVersion == existing.rowVersion -> {
                        projectNodes[incoming.id] = existing.copy(
                            assertedStatus = DerivedStatus.CONFLICTED,
                            assertedAt = nowIso(),
                            rowVersion = existing.rowVersion + 1,
                        )
                        conflicted.add(incoming.id)
                    }
                }
            }
        }
        var addedEdges = 0
        for (edge in snapshot.edges) {
            val exists = projectEdges.any {
                it.fromNodeId == edge.fromNodeId && it.toNodeId == edge.toNodeId && it.edgeKind == edge.edgeKind
            }
            if (!exists && edge.fromNodeId in projectNodes && edge.toNodeId in projectNodes) {
                projectEdges.add(edge)
                addedEdges++
            }
        }
        return SnapshotReconcileResult(addedNodes, updatedNodes, conflicted, addedEdges)
    }

    private fun buildStatusView(
        projectId: String,
        node: IntentNodeRecord,
        projectDefaultPolicy: EffectiveDispatchPolicy,
    ): IntentNodeStatusView {
        val status = deriveStatus(projectId, node.id, emptySet())
        val policy = resolvePolicy(projectId, node.id, projectDefaultPolicy)
        val unmet = unmetDeps(projectId, node.id, emptySet())
        val pendingPlatform = edgesMatching(projectId, fromNodeId = node.id, edgeKind = IntentEdgeKind.ACCEPTS)
            .mapNotNull { nodes[projectId]?.get(it.toNodeId) }
            .any { it.lifecycle == IntentLifecycle.ACTIVE && it.checkKind == IntentCheckKind.SHELL_COMMAND && it.verificationMet != true }
        return IntentNodeStatusView(
            node = node,
            derivedStatus = status,
            effectiveDispatchPolicy = policy,
            unmetDependencyIds = unmet,
            activeRunIds = emptyList(),
            pendingPlatformChecks = pendingPlatform,
        )
    }

    private fun deriveStatus(projectId: String, nodeId: String, visited: Set<String>): DerivedStatus {
        val projectNodes = nodes[projectId].orEmpty()
        val node = projectNodes[nodeId] ?: return DerivedStatus.NOT_STARTED
        if (node.assertedStatus == DerivedStatus.CONFLICTED) return DerivedStatus.CONFLICTED
        if (unmetDeps(projectId, nodeId, visited).isNotEmpty()) return DerivedStatus.BLOCKED
        if (node.assertedStatus == DerivedStatus.DONE || (node.verificationMet == true && node.verifiedByKind in setOf("USER", "CHECK"))) {
            return DerivedStatus.DONE
        }
        if (node.assertedStatus == DerivedStatus.IN_PROGRESS) return DerivedStatus.IN_PROGRESS
        return DerivedStatus.NOT_STARTED
    }

    private fun unmetDeps(projectId: String, nodeId: String, visited: Set<String>): List<String> {
        val projectNodes = nodes[projectId].orEmpty()
        val deps = edgesMatching(projectId, fromNodeId = nodeId, edgeKind = IntentEdgeKind.DEPENDS_ON)
        val nextVisited = visited + nodeId
        return deps.mapNotNull { edge ->
            val target = projectNodes[edge.toNodeId]
            when {
                edge.toNodeId in nextVisited -> edge.toNodeId
                target == null -> edge.toNodeId
                target.lifecycle == IntentLifecycle.ARCHIVED -> null
                deriveStatus(projectId, edge.toNodeId, nextVisited) != DerivedStatus.DONE -> edge.toNodeId
                else -> null
            }
        }
    }

    private fun resolvePolicy(
        projectId: String,
        nodeId: String,
        projectDefault: EffectiveDispatchPolicy,
    ): EffectiveDispatchPolicy {
        val projectNodes = nodes[projectId].orEmpty()
        var cursor: String? = nodeId
        val visited = mutableSetOf<String>()
        while (cursor != null && visited.add(cursor)) {
            val current = projectNodes[cursor] ?: break
            when (current.dispatchPolicy) {
                NodeDispatchPolicy.MANUAL_START -> return EffectiveDispatchPolicy.MANUAL_START
                NodeDispatchPolicy.AUTO_WHEN_READY -> return EffectiveDispatchPolicy.AUTO_WHEN_READY
                NodeDispatchPolicy.INHERIT -> cursor = current.parentId
            }
        }
        return projectDefault
    }

    private fun reaches(projectId: String, from: String, target: String, visited: MutableSet<String> = mutableSetOf()): Boolean {
        if (from == target) return true
        if (!visited.add(from)) return false
        return edgesMatching(projectId, fromNodeId = from, edgeKind = IntentEdgeKind.DEPENDS_ON)
            .any { reaches(projectId, it.toNodeId, target, visited) }
    }
}
