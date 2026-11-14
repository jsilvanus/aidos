package dev.aidos.intent

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.broker.AuditLog
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ActorRef

/**
 * Persistence and validation repository for the Dual-Mode Intent Graph (RFC-0110).
 *
 * Manages [IntentNodeRecord] CRUD, typed [IntentEdgeRecord] operations guarded by
 * [IntentCycleDetector], human task completion, and `execution_edges` tracking (`TARGETED`,
 * `IMPLEMENTS`) while preserving the `verified_by_kind IN ('USER', 'CHECK')` security invariant.
 */
class IntentGraphRepository(
    private val driver: SqlDriver,
    private val cycleDetector: IntentCycleDetector? = null,
    private val idGen: () -> String,
    private val nowIso: () -> String,
) {
    private val effectiveCycleDetector: IntentCycleDetector by lazy {
        cycleDetector ?: IntentCycleDetector(
            driver = driver,
            audit = AuditLog(driver, "local-device"),
            idGen = idGen,
            nowIso = nowIso,
        )
    }
    companion object {
        private const val NODE_SELECT_COLUMNS = """
            id, project_id, type, title, description, priority, lifecycle, parent_id,
            actor_kind, dispatch_policy, granularity, target_profile, min_locality,
            estimated_steps, scope_paths_json, due_at, recurrence_json,
            asserted_status, asserted_at, asserted_by_user_id, assertion_note,
            check_kind, check_spec, verification_met, verified_by_kind, verified_by_id, verified_at,
            created_at, created_by_kind, created_by_id, modified_at, modified_by_kind, modified_by_id,
            tags, row_version
        """

        internal fun readNodes(
            driver: SqlDriver,
            projectId: String,
            includeArchived: Boolean = false,
        ): List<IntentNodeRecord> {
            val sql = if (includeArchived) {
                """
                SELECT $NODE_SELECT_COLUMNS
                FROM intent_nodes
                WHERE project_id = ?
                ORDER BY priority ASC, created_at ASC, id ASC
                """.trimIndent()
            } else {
                """
                SELECT $NODE_SELECT_COLUMNS
                FROM intent_nodes
                WHERE project_id = ? AND lifecycle = 'ACTIVE'
                ORDER BY priority ASC, created_at ASC, id ASC
                """.trimIndent()
            }
            return driver.executeQuery(
                identifier = null,
                sql = sql,
                mapper = { cursor ->
                    val rows = mutableListOf<IntentNodeRecord>()
                    while (cursor.next().value) {
                        rows.add(readNodeRow(cursor))
                    }
                    QueryResult.Value(rows)
                },
                parameters = 1,
            ) {
                bindString(0, projectId)
            }.value
        }

        internal fun readEdges(
            driver: SqlDriver,
            projectId: String,
        ): List<IntentEdgeRecord> = driver.executeQuery(
            identifier = null,
            sql = """
                SELECT id, project_id, from_node_id, to_node_id, edge_kind, created_at
                FROM intent_edges
                WHERE project_id = ?
                ORDER BY created_at ASC, id ASC
            """.trimIndent(),
            mapper = { cursor ->
                val rows = mutableListOf<IntentEdgeRecord>()
                while (cursor.next().value) {
                    rows.add(
                        IntentEdgeRecord(
                            id = cursor.getString(0)!!,
                            projectId = cursor.getString(1)!!,
                            fromNodeId = cursor.getString(2)!!,
                            toNodeId = cursor.getString(3)!!,
                            edgeKind = IntentEdgeKind.valueOf(cursor.getString(4)!!),
                            createdAt = cursor.getString(5)!!,
                        ),
                    )
                }
                QueryResult.Value(rows)
            },
            parameters = 1,
        ) {
            bindString(0, projectId)
        }.value

        internal fun readNodeRow(cursor: SqlCursor): IntentNodeRecord {
            val verificationLong = cursor.getLong(23)
            return IntentNodeRecord(
                id = cursor.getString(0)!!,
                projectId = cursor.getString(1)!!,
                type = IntentNodeType.valueOf(cursor.getString(2)!!),
                title = cursor.getString(3)!!,
                description = cursor.getString(4),
                priority = cursor.getLong(5)!!.toInt(),
                lifecycle = IntentLifecycle.valueOf(cursor.getString(6)!!),
                parentId = cursor.getString(7),
                actorKind = IntentActorKind.valueOf(cursor.getString(8)!!),
                dispatchPolicy = NodeDispatchPolicy.valueOf(cursor.getString(9)!!),
                granularity = IntentGranularity.valueOf(cursor.getString(10)!!),
                targetProfile = cursor.getString(11)?.let(IntentTargetProfile::valueOf),
                minLocality = cursor.getString(12)?.let(IntentMinLocality::valueOf),
                estimatedSteps = cursor.getLong(13)?.toInt(),
                scopePaths = IntentJson.decodeStringList(cursor.getString(14)),
                dueAt = cursor.getString(15),
                recurrenceJson = cursor.getString(16),
                assertedStatus = cursor.getString(17)?.let(DerivedStatus::valueOf),
                assertedAt = cursor.getString(18),
                assertedByUserId = cursor.getString(19),
                assertionNote = cursor.getString(20),
                checkKind = cursor.getString(21)?.let(IntentCheckKind::valueOf),
                checkSpec = cursor.getString(22),
                verificationMet = verificationLong?.let { it != 0L },
                verifiedByKind = cursor.getString(24),
                verifiedById = cursor.getString(25),
                verifiedAt = cursor.getString(26),
                createdAt = cursor.getString(27)!!,
                createdByKind = cursor.getString(28)!!,
                createdById = cursor.getString(29)!!,
                modifiedAt = cursor.getString(30)!!,
                modifiedByKind = cursor.getString(31)!!,
                modifiedById = cursor.getString(32)!!,
                tags = IntentJson.decodeStringList(cursor.getString(33)),
                rowVersion = cursor.getLong(34)!!.toInt(),
            )
        }

        fun defaultGranularityForType(type: IntentNodeType): IntentGranularity = when (type) {
            IntentNodeType.GROUP -> IntentGranularity.GROUP
            IntentNodeType.GOAL -> IntentGranularity.GOAL
            IntentNodeType.SUB_GOAL -> IntentGranularity.SUB_GOAL
            IntentNodeType.ATOMIC_TASK -> IntentGranularity.ATOMIC
            IntentNodeType.CONSTRAINT -> IntentGranularity.GOAL
            IntentNodeType.ACCEPTANCE_CRITERION -> IntentGranularity.ATOMIC
        }
    }

    fun getNode(projectId: String, nodeId: String): IntentNodeRecord? = driver.executeQuery(
        identifier = null,
        sql = "SELECT $NODE_SELECT_COLUMNS FROM intent_nodes WHERE project_id = ? AND id = ?",
        mapper = { cursor ->
            val value = if (cursor.next().value) readNodeRow(cursor) else null
            QueryResult.Value(value)
        },
        parameters = 2,
    ) {
        bindString(0, projectId)
        bindString(1, nodeId)
    }.value

    fun listNodes(
        projectId: String,
        includeArchived: Boolean = false,
        actorKind: IntentActorKind? = null,
        parentId: String? = null,
        filterByParent: Boolean = false,
    ): List<IntentNodeRecord> = readNodes(driver, projectId, includeArchived).filter { node ->
        (actorKind == null || node.actorKind == actorKind) &&
            (!filterByParent || node.parentId == parentId)
    }

    fun listEdges(
        projectId: String,
        fromNodeId: String? = null,
        toNodeId: String? = null,
        edgeKind: IntentEdgeKind? = null,
    ): List<IntentEdgeRecord> = readEdges(driver, projectId).filter { edge ->
        (fromNodeId == null || edge.fromNodeId == fromNodeId) &&
            (toNodeId == null || edge.toNodeId == toNodeId) &&
            (edgeKind == null || edge.edgeKind == edgeKind)
    }

    fun createNode(
        projectId: String,
        title: String,
        type: IntentNodeType = IntentNodeType.GOAL,
        id: String = idGen(),
        description: String? = null,
        priority: Int = 100,
        parentId: String? = null,
        actorKind: IntentActorKind = IntentActorKind.AGENT,
        dispatchPolicy: NodeDispatchPolicy = NodeDispatchPolicy.INHERIT,
        granularity: IntentGranularity = defaultGranularityForType(type),
        targetProfile: IntentTargetProfile? = null,
        minLocality: IntentMinLocality? = null,
        estimatedSteps: Int? = null,
        scopePaths: List<String> = emptyList(),
        dueAt: String? = null,
        recurrenceJson: String? = null,
        checkKind: IntentCheckKind? = null,
        checkSpec: String? = null,
        tags: List<String> = emptyList(),
        createdBy: ActorRef = ActorRef(ActorKind.USER, "local-user"),
    ): IntentNodeRecord {
        require(title.isNotBlank()) { "Intent node title must not be blank." }
        if (estimatedSteps != null) {
            require(estimatedSteps > 0) { "estimatedSteps must be positive, got $estimatedSteps." }
        }
        if (parentId != null) {
            require(parentId != id) { "Intent node cannot be its own parent: $id" }
            requireNotNull(getNode(projectId, parentId)) {
                "Parent intent node '$parentId' does not exist in project '$projectId'."
            }
        }
        val now = nowIso()
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO intent_nodes (
                    id, project_id, type, title, description, priority, lifecycle, parent_id,
                    actor_kind, dispatch_policy, granularity, target_profile, min_locality,
                    estimated_steps, scope_paths_json, due_at, recurrence_json,
                    check_kind, check_spec,
                    created_at, created_by_kind, created_by_id,
                    modified_at, modified_by_kind, modified_by_id,
                    tags, row_version
                ) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
            """.trimIndent(),
            parameters = 26,
        ) {
            bindString(0, id)
            bindString(1, projectId)
            bindString(2, type.name)
            bindString(3, title)
            bindString(4, description)
            bindLong(5, priority.toLong())
            bindString(6, parentId)
            bindString(7, actorKind.name)
            bindString(8, dispatchPolicy.name)
            bindString(9, granularity.name)
            bindString(10, targetProfile?.name)
            bindString(11, minLocality?.name)
            bindLong(12, estimatedSteps?.toLong())
            bindString(13, IntentJson.encodeStringList(scopePaths))
            bindString(14, dueAt)
            bindString(15, recurrenceJson)
            bindString(16, checkKind?.name)
            bindString(17, checkSpec)
            bindString(18, now)
            bindString(19, createdBy.kind.name)
            bindString(20, createdBy.id)
            bindString(21, now)
            bindString(22, createdBy.kind.name)
            bindString(23, createdBy.id)
            bindString(24, IntentJson.encodeStringList(tags))
        }
        return getNode(projectId, id)!!
    }

    fun updateNode(
        projectId: String,
        nodeId: String,
        title: String? = null,
        description: String? = null,
        clearDescription: Boolean = false,
        priority: Int? = null,
        parentId: String? = null,
        clearParentId: Boolean = false,
        actorKind: IntentActorKind? = null,
        dispatchPolicy: NodeDispatchPolicy? = null,
        granularity: IntentGranularity? = null,
        targetProfile: IntentTargetProfile? = null,
        clearTargetProfile: Boolean = false,
        minLocality: IntentMinLocality? = null,
        clearMinLocality: Boolean = false,
        estimatedSteps: Int? = null,
        clearEstimatedSteps: Boolean = false,
        scopePaths: List<String>? = null,
        dueAt: String? = null,
        clearDueAt: Boolean = false,
        recurrenceJson: String? = null,
        clearRecurrenceJson: Boolean = false,
        assertedStatus: DerivedStatus? = null,
        clearAssertedStatus: Boolean = false,
        assertedAt: String? = null,
        assertedByUserId: String? = null,
        assertionNote: String? = null,
        checkKind: IntentCheckKind? = null,
        checkSpec: String? = null,
        tags: List<String>? = null,
        modifiedBy: ActorRef = ActorRef(ActorKind.USER, "local-user"),
    ): IntentNodeRecord {
        val existing = getNode(projectId, nodeId)
            ?: throw IllegalArgumentException("Intent node '$nodeId' not found in project '$projectId'.")

        val nextTitle = title ?: existing.title
        require(nextTitle.isNotBlank()) { "Intent node title must not be blank." }
        val nextEstimatedSteps = if (clearEstimatedSteps) null else (estimatedSteps ?: existing.estimatedSteps)
        if (nextEstimatedSteps != null) {
            require(nextEstimatedSteps > 0) { "estimatedSteps must be positive, got $nextEstimatedSteps." }
        }
        val nextParentId = if (clearParentId) null else (parentId ?: existing.parentId)
        if (nextParentId != null) {
            validateParentAssignment(projectId, nodeId, nextParentId)
        }

        val nextDescription = if (clearDescription) null else (description ?: existing.description)
        val nextPriority = priority ?: existing.priority
        val nextActorKind = actorKind ?: existing.actorKind
        val nextDispatchPolicy = dispatchPolicy ?: existing.dispatchPolicy
        val nextGranularity = granularity ?: existing.granularity
        val nextTargetProfile = if (clearTargetProfile) null else (targetProfile ?: existing.targetProfile)
        val nextMinLocality = if (clearMinLocality) null else (minLocality ?: existing.minLocality)
        val nextScopePaths = scopePaths ?: existing.scopePaths
        val nextDueAt = if (clearDueAt) null else (dueAt ?: existing.dueAt)
        val nextRecurrence = if (clearRecurrenceJson) null else (recurrenceJson ?: existing.recurrenceJson)
        val nextAssertedStatus = if (clearAssertedStatus) null else (assertedStatus ?: existing.assertedStatus)
        val now = nowIso()
        val nextAssertedAt = when {
            clearAssertedStatus -> null
            assertedAt != null -> assertedAt
            assertedStatus != null -> now
            else -> existing.assertedAt
        }
        val nextAssertedBy = when {
            clearAssertedStatus -> null
            assertedByUserId != null -> assertedByUserId
            assertedStatus != null -> modifiedBy.id
            else -> existing.assertedByUserId
        }
        val nextAssertionNote = when {
            clearAssertedStatus -> null
            assertionNote != null -> assertionNote
            else -> existing.assertionNote
        }
        val nextCheckKind = checkKind ?: existing.checkKind
        val nextCheckSpec = checkSpec ?: existing.checkSpec
        val nextTags = tags ?: existing.tags

        driver.execute(
            identifier = null,
            sql = """
                UPDATE intent_nodes SET
                    title = ?,
                    description = ?,
                    priority = ?,
                    parent_id = ?,
                    actor_kind = ?,
                    dispatch_policy = ?,
                    granularity = ?,
                    target_profile = ?,
                    min_locality = ?,
                    estimated_steps = ?,
                    scope_paths_json = ?,
                    due_at = ?,
                    recurrence_json = ?,
                    asserted_status = ?,
                    asserted_at = ?,
                    asserted_by_user_id = ?,
                    assertion_note = ?,
                    check_kind = ?,
                    check_spec = ?,
                    modified_at = ?,
                    modified_by_kind = ?,
                    modified_by_id = ?,
                    tags = ?,
                    row_version = row_version + 1
                WHERE project_id = ? AND id = ?
            """.trimIndent(),
            parameters = 25,
        ) {
            bindString(0, nextTitle)
            bindString(1, nextDescription)
            bindLong(2, nextPriority.toLong())
            bindString(3, nextParentId)
            bindString(4, nextActorKind.name)
            bindString(5, nextDispatchPolicy.name)
            bindString(6, nextGranularity.name)
            bindString(7, nextTargetProfile?.name)
            bindString(8, nextMinLocality?.name)
            bindLong(9, nextEstimatedSteps?.toLong())
            bindString(10, IntentJson.encodeStringList(nextScopePaths))
            bindString(11, nextDueAt)
            bindString(12, nextRecurrence)
            bindString(13, nextAssertedStatus?.name)
            bindString(14, nextAssertedAt)
            bindString(15, nextAssertedBy)
            bindString(16, nextAssertionNote)
            bindString(17, nextCheckKind?.name)
            bindString(18, nextCheckSpec)
            bindString(19, now)
            bindString(20, modifiedBy.kind.name)
            bindString(21, modifiedBy.id)
            bindString(22, IntentJson.encodeStringList(nextTags))
            bindString(23, projectId)
            bindString(24, nodeId)
        }
        return getNode(projectId, nodeId)!!
    }

    fun archiveNode(
        projectId: String,
        nodeId: String,
        modifiedBy: ActorRef = ActorRef(ActorKind.USER, "local-user"),
    ): IntentNodeRecord {
        getNode(projectId, nodeId)
            ?: throw IllegalArgumentException("Intent node '$nodeId' not found in project '$projectId'.")
        val now = nowIso()
        driver.execute(
            identifier = null,
            sql = """
                UPDATE intent_nodes SET
                    lifecycle = 'ARCHIVED',
                    modified_at = ?,
                    modified_by_kind = ?,
                    modified_by_id = ?,
                    row_version = row_version + 1
                WHERE project_id = ? AND id = ?
            """.trimIndent(),
            parameters = 5,
        ) {
            bindString(0, now)
            bindString(1, modifiedBy.kind.name)
            bindString(2, modifiedBy.id)
            bindString(3, projectId)
            bindString(4, nodeId)
        }
        return getNode(projectId, nodeId)!!
    }

    fun addEdge(
        projectId: String,
        fromNodeId: String,
        toNodeId: String,
        edgeKind: IntentEdgeKind,
        edgeId: String = idGen(),
        actor: ActorRef = ActorRef(ActorKind.USER, "local-user"),
    ): IntentEdgeRecord {
        if (fromNodeId == toNodeId) {
            throw IllegalArgumentException("intent.self_dependency: Self-referential intent edge is not allowed: $fromNodeId")
        }
        getNode(projectId, fromNodeId)
            ?: throw IllegalArgumentException("intent.node_not_found: Source intent node '$fromNodeId' not found in project '$projectId'.")
        getNode(projectId, toNodeId)
            ?: throw IllegalArgumentException("intent.node_not_found: Target intent node '$toNodeId' not found in project '$projectId'.")
        val existing = listEdges(projectId, fromNodeId, toNodeId, edgeKind).firstOrNull()
        if (existing != null) {
            return existing
        }
        if (edgeKind == IntentEdgeKind.DEPENDS_ON) {
            val cycleErr = effectiveCycleDetector.checkEdgeForCycle(
                projectId = projectId,
                fromNodeId = fromNodeId,
                toNodeId = toNodeId,
                actor = actor,
            )
            if (cycleErr != null) {
                throw IllegalArgumentException("${cycleErr.code}: ${cycleErr.message}")
            }
        }
        val now = nowIso()
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO intent_edges (id, project_id, from_node_id, to_node_id, edge_kind, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            parameters = 6,
        ) {
            bindString(0, edgeId)
            bindString(1, projectId)
            bindString(2, fromNodeId)
            bindString(3, toNodeId)
            bindString(4, edgeKind.name)
            bindString(5, now)
        }
        return IntentEdgeRecord(
            id = edgeId,
            projectId = projectId,
            fromNodeId = fromNodeId,
            toNodeId = toNodeId,
            edgeKind = edgeKind,
            createdAt = now,
        )
    }

    fun removeEdge(
        projectId: String,
        fromNodeId: String,
        toNodeId: String,
        edgeKind: IntentEdgeKind,
    ): Boolean {
        val existed = listEdges(projectId, fromNodeId, toNodeId, edgeKind).isNotEmpty()
        if (!existed) return false
        driver.execute(
            identifier = null,
            sql = """
                DELETE FROM intent_edges
                WHERE project_id = ? AND from_node_id = ? AND to_node_id = ? AND edge_kind = ?
            """.trimIndent(),
            parameters = 4,
        ) {
            bindString(0, projectId)
            bindString(1, fromNodeId)
            bindString(2, toNodeId)
            bindString(3, edgeKind.name)
        }
        return true
    }

    fun completeHumanNode(
        projectId: String,
        nodeId: String,
        userId: String,
        note: String? = null,
    ): IntentNodeRecord {
        val node = getNode(projectId, nodeId)
            ?: throw IllegalArgumentException("Intent node '$nodeId' not found in project '$projectId'.")
        require(node.lifecycle == IntentLifecycle.ACTIVE) {
            "Cannot complete archived intent node '$nodeId'."
        }
        require(node.actorKind == IntentActorKind.HUMAN || node.actorKind == IntentActorKind.HYBRID) {
            "completeHumanNode applies to HUMAN or HYBRID nodes; node '$nodeId' has actor_kind=${node.actorKind}."
        }
        val now = nowIso()
        driver.execute(
            identifier = null,
            sql = """
                UPDATE intent_nodes SET
                    asserted_status = 'DONE',
                    asserted_at = ?,
                    asserted_by_user_id = ?,
                    assertion_note = ?,
                    verification_met = 1,
                    verified_by_kind = 'USER',
                    verified_by_id = ?,
                    verified_at = ?,
                    modified_at = ?,
                    modified_by_kind = 'USER',
                    modified_by_id = ?,
                    row_version = row_version + 1
                WHERE project_id = ? AND id = ?
            """.trimIndent(),
            parameters = 9,
        ) {
            bindString(0, now)
            bindString(1, userId)
            bindString(2, note)
            bindString(3, userId)
            bindString(4, now)
            bindString(5, now)
            bindString(6, userId)
            bindString(7, projectId)
            bindString(8, nodeId)
        }
        return getNode(projectId, nodeId)!!
    }

    fun recordRunTargeting(
        projectId: String,
        runId: String,
        nodeId: String,
        edgeId: String = idGen(),
    ) {
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO execution_edges (
                    id, project_id, from_node_id, from_node_kind, to_node_id, to_node_kind, edge_kind, confirmed
                ) VALUES (?, ?, ?, 'RUN', ?, 'INTENT_NODE', 'TARGETED', 0)
                ON CONFLICT(from_node_id, to_node_id, edge_kind) DO NOTHING
            """.trimIndent(),
            parameters = 4,
        ) {
            bindString(0, edgeId)
            bindString(1, projectId)
            bindString(2, runId)
            bindString(3, nodeId)
        }
    }

    fun recordRunImplements(
        projectId: String,
        runId: String,
        nodeId: String,
        confirmed: Boolean = false,
        confirmedByKind: String? = null,
        confirmedById: String? = null,
        edgeId: String = idGen(),
    ) {
        if (confirmed) {
            require(confirmedByKind == "USER" || confirmedByKind == "CHECK" || confirmedByKind == "ACCEPTANCE_CRITERIA") {
                "Confirmed IMPLEMENTS edge requires USER, CHECK, or ACCEPTANCE_CRITERIA confirmation, got '$confirmedByKind'."
            }
        }
        val confirmedAt = if (confirmed) nowIso() else null
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO execution_edges (
                    id, project_id, from_node_id, from_node_kind, to_node_id, to_node_kind,
                    edge_kind, confirmed, confirmed_by_kind, confirmed_by_id, confirmed_at
                ) VALUES (?, ?, ?, 'RUN', ?, 'INTENT_NODE', 'IMPLEMENTS', ?, ?, ?, ?)
                ON CONFLICT(from_node_id, to_node_id, edge_kind) DO UPDATE SET
                    confirmed = CASE WHEN excluded.confirmed = 1 THEN 1 ELSE execution_edges.confirmed END,
                    confirmed_by_kind = COALESCE(excluded.confirmed_by_kind, execution_edges.confirmed_by_kind),
                    confirmed_by_id = COALESCE(excluded.confirmed_by_id, execution_edges.confirmed_by_id),
                    confirmed_at = COALESCE(excluded.confirmed_at, execution_edges.confirmed_at)
            """.trimIndent(),
            parameters = 8,
        ) {
            bindString(0, edgeId)
            bindString(1, projectId)
            bindString(2, runId)
            bindString(3, nodeId)
            bindLong(4, if (confirmed) 1L else 0L)
            bindString(5, confirmedByKind)
            bindString(6, confirmedById)
            bindString(7, confirmedAt)
        }
    }

    fun confirmPendingImplementation(
        projectId: String,
        nodeId: String,
        confirmedByKind: String,
        confirmedById: String,
        criterionIds: List<String>? = null,
    ) {
        require(confirmedByKind == "USER" || confirmedByKind == "CHECK") {
            "Verification confirmation must be USER or CHECK, got '$confirmedByKind'."
        }
        requireNotNull(getNode(projectId, nodeId)) {
            "Intent node '$nodeId' not found in project '$projectId'."
        }
        val now = nowIso()
        driver.execute(
            identifier = null,
            sql = """
                UPDATE execution_edges SET
                    confirmed = 1,
                    confirmed_by_kind = ?,
                    confirmed_by_id = ?,
                    confirmed_at = ?
                WHERE project_id = ? AND to_node_id = ? AND edge_kind = 'IMPLEMENTS'
            """.trimIndent(),
            parameters = 5,
        ) {
            bindString(0, confirmedByKind)
            bindString(1, confirmedById)
            bindString(2, now)
            bindString(3, projectId)
            bindString(4, nodeId)
        }

        val targetCriteriaIds = criterionIds ?: listEdges(
            projectId = projectId,
            fromNodeId = nodeId,
            edgeKind = IntentEdgeKind.ACCEPTS,
        ).map { it.toNodeId }

        for (criterionId in targetCriteriaIds) {
            driver.execute(
                identifier = null,
                sql = """
                    UPDATE intent_nodes SET
                        verification_met = 1,
                        verified_by_kind = ?,
                        verified_by_id = ?,
                        verified_at = ?,
                        modified_at = ?,
                        modified_by_kind = ?,
                        modified_by_id = ?,
                        row_version = row_version + 1
                    WHERE project_id = ? AND id = ? AND type = 'ACCEPTANCE_CRITERION'
                """.trimIndent(),
                parameters = 8,
            ) {
                bindString(0, confirmedByKind)
                bindString(1, confirmedById)
                bindString(2, now)
                bindString(3, now)
                bindString(4, confirmedByKind)
                bindString(5, confirmedById)
                bindString(6, projectId)
                bindString(7, criterionId)
            }
        }
    }

    private fun validateParentAssignment(projectId: String, nodeId: String, parentId: String) {
        require(parentId != nodeId) { "Intent node cannot be its own parent: $nodeId" }
        val nodesById = readNodes(driver, projectId, includeArchived = true).associateBy { it.id }
        var cursor: String? = parentId
        val visited = mutableSetOf(nodeId)
        while (cursor != null) {
            require(visited.add(cursor)) {
                "Parent assignment from '$nodeId' to '$parentId' creates a parent cycle."
            }
            val parent = nodesById[cursor]
                ?: throw IllegalArgumentException("Parent intent node '$parentId' not found in project '$projectId'.")
            cursor = parent.parentId
        }
    }
}
