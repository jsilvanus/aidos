package dev.aidos.intent

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * Canonical derived status values for intent nodes (RFC-0012, §Status is derived).
 */
enum class DerivedStatus {
    NOT_STARTED,
    IN_PROGRESS,
    NEEDS_REVIEW,
    DONE,
    BLOCKED,
    STALE,
    ABANDONED,
}

/**
 * Computes derived status for intent nodes based on graph state (RFC-0012).
 *
 * An intent node has no stored `status` field. Progress is derived from:
 * - Explicit user `asserted_status` overrides when set
 * - Parent hierarchy (`parent_id`) blocking/stale/abandoned inheritance and child rollup
 * - Recursive `DEPENDS_ON` dependency satisfaction (cycle-safe via visiting path tracking)
 * - `TARGETED` and `IMPLEMENTS` rows in `execution_edges` joined with `runs`
 * - `execution_edges.confirmed` and acceptance criteria verification (`verification_met`, `verified_by_kind`)
 * - Produced `content_nodes` state (`DANGLING` -> `STALE`, RFC-0053)
 */
class DerivedStatusComputer(
    private val driver: SqlDriver,
) {

    fun derive(nodeId: String, projectId: String): String {
        val memo = mutableMapOf<String, String>()
        val inheritedMemo = mutableMapOf<String, String?>()
        val visiting = mutableSetOf<String>()
        return deriveNode(nodeId, projectId, visiting, memo, inheritedMemo)
    }

    private fun deriveNode(
        nodeId: String,
        projectId: String,
        visiting: MutableSet<String>,
        memo: MutableMap<String, String>,
        inheritedMemo: MutableMap<String, String?>,
    ): String {
        memo[nodeId]?.let { return it }
        if (!visiting.add(nodeId)) {
            // Break dependency or parent cycles safely rather than recursing infinitely.
            return DerivedStatus.BLOCKED.name
        }
        try {
            val node = loadNode(nodeId, projectId)
                ?: return DerivedStatus.NOT_STARTED.name

            // 1. Respect explicit user assertion if present.
            val asserted = node.assertedStatus?.takeIf { it.isNotBlank() }
            if (asserted != null) {
                inheritedMemo[nodeId] = if (asserted in INHERITED_PARENT_STATUSES) asserted else null
                memo[nodeId] = asserted
                return asserted
            }

            // 2. Inherit blocking / stale / abandoned state from parent hierarchy.
            if (node.parentId != null) {
                val inherited = deriveInheritedParentStatus(node.parentId, projectId, visiting, memo, inheritedMemo)
                if (inherited != null) {
                    inheritedMemo[nodeId] = inherited
                    memo[nodeId] = inherited
                    return inherited
                }
            }

            // 3. Evaluate DEPENDS_ON dependencies recursively (BLOCKED if any dependency is not DONE).
            if (hasUnmetDependency(nodeId, projectId, visiting, memo, inheritedMemo)) {
                val blocked = DerivedStatus.BLOCKED.name
                inheritedMemo[nodeId] = blocked
                memo[nodeId] = blocked
                return blocked
            }

            // 4. Check if a TARGETED Run is in a non-terminal state (IN_PROGRESS).
            if (hasActiveTargetedRun(nodeId, projectId)) {
                val inProgress = DerivedStatus.IN_PROGRESS.name
                inheritedMemo[nodeId] = null
                memo[nodeId] = inProgress
                return inProgress
            }

            // 5. Check if confirmed work became STALE because produced content is DANGLING (RFC-0053).
            if (isStale(nodeId, projectId)) {
                val stale = DerivedStatus.STALE.name
                inheritedMemo[nodeId] = stale
                memo[nodeId] = stale
                return stale
            }

            // 6. Acceptance criteria nodes derive completion directly from their verification fields.
            if (node.type == "ACCEPTANCE_CRITERION") {
                val criterionStatus = if (node.verificationMet == 1L && node.verifiedByKind in VALID_NODE_VERIFIERS) {
                    DerivedStatus.DONE.name
                } else {
                    DerivedStatus.NOT_STARTED.name
                }
                inheritedMemo[nodeId] = null
                memo[nodeId] = criterionStatus
                return criterionStatus
            }

            // Record intrinsic parent-inheritance status BEFORE rolling up children so child -> parent
            // inheritance queries do not misinterpret parent -> child rollup traversal as a cycle.
            val propagatesFailure = implementsEdgeCounts(nodeId, projectId).first == 0 &&
                latestTargetedRunFailed(nodeId, projectId)
            inheritedMemo[nodeId] = if (propagatesFailure) DerivedStatus.BLOCKED.name else null

            // 7. Derive completion from execution_edges (IMPLEMENTS.confirmed), acceptance criteria,
            //    and child sub-goal rollup.
            val status = computeCompletionOrRollup(nodeId, projectId, visiting, memo, inheritedMemo)
            memo[nodeId] = status
            return status
        } finally {
            visiting.remove(nodeId)
        }
    }

    /**
     * Evaluates intrinsic blocking/stale/abandoned state along the ancestor chain without rolling up
     * sibling children, preventing one blocked child from falsely blocking unrelated siblings.
     */
    private fun deriveInheritedParentStatus(
        parentId: String,
        projectId: String,
        visiting: MutableSet<String>,
        memo: MutableMap<String, String>,
        inheritedMemo: MutableMap<String, String?>,
    ): String? {
        if (inheritedMemo.containsKey(parentId)) {
            return inheritedMemo[parentId]
        }
        if (!visiting.add(parentId)) {
            return DerivedStatus.BLOCKED.name
        }
        try {
            val parent = loadNode(parentId, projectId) ?: run {
                inheritedMemo[parentId] = null
                return null
            }
            val asserted = parent.assertedStatus?.takeIf { it.isNotBlank() }
            if (asserted != null) {
                val inherited = if (asserted in INHERITED_PARENT_STATUSES) asserted else null
                inheritedMemo[parentId] = inherited
                return inherited
            }

            if (parent.parentId != null) {
                val ancestorStatus = deriveInheritedParentStatus(parent.parentId, projectId, visiting, memo, inheritedMemo)
                if (ancestorStatus != null) {
                    inheritedMemo[parentId] = ancestorStatus
                    return ancestorStatus
                }
            }

            if (hasUnmetDependency(parentId, projectId, visiting, memo, inheritedMemo)) {
                val blocked = DerivedStatus.BLOCKED.name
                inheritedMemo[parentId] = blocked
                return blocked
            }

            val inherited = if (!hasActiveTargetedRun(parentId, projectId)) {
                when {
                    isStale(parentId, projectId) -> DerivedStatus.STALE.name
                    implementsEdgeCounts(parentId, projectId).first == 0 &&
                        latestTargetedRunFailed(parentId, projectId) -> DerivedStatus.BLOCKED.name
                    else -> null
                }
            } else {
                null
            }
            inheritedMemo[parentId] = inherited
            return inherited
        } finally {
            visiting.remove(parentId)
        }
    }

    private fun computeCompletionOrRollup(
        nodeId: String,
        projectId: String,
        visiting: MutableSet<String>,
        memo: MutableMap<String, String>,
        inheritedMemo: MutableMap<String, String?>,
    ): String {
        val (totalImpl, confirmedImpl) = implementsEdgeCounts(nodeId, projectId)
        val criteriaMet = meetsAllAcceptanceCriteria(nodeId, projectId)
        val childIds = childSubGoalIds(nodeId, projectId)

        if (childIds.isNotEmpty()) {
            val childStatuses = childIds.map { childId ->
                deriveNode(childId, projectId, visiting, memo, inheritedMemo)
            }
            if (childStatuses.all { it == DerivedStatus.ABANDONED.name }) {
                return DerivedStatus.ABANDONED.name
            }
            val activeChildren = childStatuses.filter { it != DerivedStatus.ABANDONED.name }
            return when {
                activeChildren.any { it == DerivedStatus.STALE.name } -> DerivedStatus.STALE.name
                activeChildren.any { it == DerivedStatus.IN_PROGRESS.name } -> DerivedStatus.IN_PROGRESS.name
                activeChildren.any { it == DerivedStatus.BLOCKED.name } -> DerivedStatus.BLOCKED.name
                activeChildren.all { it == DerivedStatus.DONE.name } -> {
                    if ((totalImpl > 0 && confirmedImpl == 0) || !criteriaMet) {
                        DerivedStatus.NEEDS_REVIEW.name
                    } else {
                        DerivedStatus.DONE.name
                    }
                }
                activeChildren.any {
                    it == DerivedStatus.DONE.name || it == DerivedStatus.NEEDS_REVIEW.name
                } -> {
                    if (activeChildren.any { it == DerivedStatus.NOT_STARTED.name }) {
                        DerivedStatus.IN_PROGRESS.name
                    } else {
                        DerivedStatus.NEEDS_REVIEW.name
                    }
                }
                else -> directCompletionStatus(nodeId, projectId, totalImpl, confirmedImpl, criteriaMet)
            }
        }

        return directCompletionStatus(nodeId, projectId, totalImpl, confirmedImpl, criteriaMet)
    }

    private fun directCompletionStatus(
        nodeId: String,
        projectId: String,
        totalImpl: Int,
        confirmedImpl: Int,
        criteriaMet: Boolean,
    ): String {
        if (confirmedImpl > 0) {
            return if (criteriaMet) {
                DerivedStatus.DONE.name
            } else {
                DerivedStatus.NEEDS_REVIEW.name
            }
        }
        if (totalImpl > 0) {
            return DerivedStatus.NEEDS_REVIEW.name
        }
        if (latestTargetedRunFailed(nodeId, projectId)) {
            return DerivedStatus.BLOCKED.name
        }
        return DerivedStatus.NOT_STARTED.name
    }

    /**
     * Returns true if this node has any DEPENDS_ON edge whose target does not exist or whose
     * recursively derived status is not DONE.
     */
    private fun hasUnmetDependency(
        nodeId: String,
        projectId: String,
        visiting: MutableSet<String>,
        memo: MutableMap<String, String>,
        inheritedMemo: MutableMap<String, String?>,
    ): Boolean {
        val depIds = dependencyIds(nodeId, projectId)
        for (depId in depIds) {
            if (loadNode(depId, projectId) == null) {
                return true
            }
            if (depId in visiting) {
                return true
            }
            val depStatus = deriveNode(depId, projectId, visiting, memo, inheritedMemo)
            if (depStatus != DerivedStatus.DONE.name) {
                return true
            }
        }
        return false
    }

    /**
     * Returns true if a confirmed IMPLEMENTS run produced a content_node now in DANGLING state.
     */
    private fun isStale(nodeId: String, projectId: String): Boolean {
        var count = 0
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT COUNT(*) FROM execution_edges impl
                JOIN tasks t ON t.run_id = impl.from_node_id
                JOIN attempts a ON a.task_id = t.id
                JOIN execution_edges prod ON prod.from_node_id = a.id
                JOIN content_nodes cn ON cn.id = prod.to_node_id
                WHERE impl.project_id = ?
                  AND impl.to_node_id = ?
                  AND impl.to_node_kind = 'INTENT_NODE'
                  AND impl.from_node_kind = 'RUN'
                  AND impl.edge_kind = 'IMPLEMENTS'
                  AND impl.confirmed = 1
                  AND prod.project_id = ?
                  AND prod.from_node_kind = 'ATTEMPT'
                  AND prod.to_node_kind = 'CONTENT_NODE'
                  AND prod.edge_kind = 'PRODUCED'
                  AND cn.project_id = ?
                  AND cn.state = 'DANGLING'
            """.trimIndent(),
            parameters = 4,
            binders = {
                bindString(0, projectId)
                bindString(1, nodeId)
                bindString(2, projectId)
                bindString(3, projectId)
            },
            mapper = { resultSet ->
                if (resultSet.next().value) {
                    count = resultSet.getLong(0)?.toInt() ?: 0
                }
                QueryResult.Value(Unit)
            },
        )
        return count > 0
    }

    /**
     * Returns true if a TARGETED Run exists in a non-terminal state
     * (`PENDING`, `RUNNING`, `YIELDED`, `INTERRUPTED`).
     */
    private fun hasActiveTargetedRun(nodeId: String, projectId: String): Boolean {
        var count = 0
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT COUNT(*) FROM execution_edges ee
                JOIN runs r ON r.id = ee.from_node_id
                WHERE ee.project_id = ?
                  AND ee.to_node_id = ?
                  AND ee.to_node_kind = 'INTENT_NODE'
                  AND ee.from_node_kind = 'RUN'
                  AND ee.edge_kind = 'TARGETED'
                  AND r.project_id = ?
                  AND r.state IN ('PENDING', 'RUNNING', 'YIELDED', 'INTERRUPTED')
            """.trimIndent(),
            parameters = 3,
            binders = {
                bindString(0, projectId)
                bindString(1, nodeId)
                bindString(2, projectId)
            },
            mapper = { resultSet ->
                if (resultSet.next().value) {
                    count = resultSet.getLong(0)?.toInt() ?: 0
                }
                QueryResult.Value(Unit)
            },
        )
        return count > 0
    }

    private fun latestTargetedRunFailed(nodeId: String, projectId: String): Boolean {
        var latestState: String? = null
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT r.state FROM execution_edges ee
                JOIN runs r ON r.id = ee.from_node_id
                WHERE ee.project_id = ?
                  AND ee.to_node_id = ?
                  AND ee.to_node_kind = 'INTENT_NODE'
                  AND ee.from_node_kind = 'RUN'
                  AND ee.edge_kind = 'TARGETED'
                  AND r.project_id = ?
                ORDER BY r.started_at DESC, r.id DESC
                LIMIT 1
            """.trimIndent(),
            parameters = 3,
            binders = {
                bindString(0, projectId)
                bindString(1, nodeId)
                bindString(2, projectId)
            },
            mapper = { resultSet ->
                if (resultSet.next().value) {
                    latestState = resultSet.getString(0)
                }
                QueryResult.Value(Unit)
            },
        )
        return latestState == "FAILED"
    }

    /**
     * Returns `(totalImplementsCount, confirmedImplementsCount)` from `execution_edges`.
     *
     * Per RFC-0019 and `schema/project.sql`, an `IMPLEMENTS` edge is confirmed when
     * `confirmed = 1` and confirmed by `USER` or `ACCEPTANCE_CRITERIA` (never `SESSION`).
     */
    private fun implementsEdgeCounts(nodeId: String, projectId: String): Pair<Int, Int> {
        var total = 0
        var confirmed = 0
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT
                    COUNT(*),
                    COALESCE(SUM(CASE
                        WHEN confirmed = 1
                         AND (confirmed_by_kind IS NULL OR confirmed_by_kind IN ('USER', 'ACCEPTANCE_CRITERIA'))
                        THEN 1 ELSE 0
                    END), 0)
                FROM execution_edges
                WHERE project_id = ?
                  AND to_node_id = ?
                  AND to_node_kind = 'INTENT_NODE'
                  AND from_node_kind = 'RUN'
                  AND edge_kind = 'IMPLEMENTS'
            """.trimIndent(),
            parameters = 2,
            binders = {
                bindString(0, projectId)
                bindString(1, nodeId)
            },
            mapper = { resultSet ->
                if (resultSet.next().value) {
                    total = resultSet.getLong(0)?.toInt() ?: 0
                    confirmed = resultSet.getLong(1)?.toInt() ?: 0
                }
                QueryResult.Value(Unit)
            },
        )
        return total to confirmed
    }

    /**
     * Checks whether all acceptance criteria linked via `ACCEPTS` edges or `parent_id`
     * (`type = 'ACCEPTANCE_CRITERION'`) are met (`verification_met = 1` and `verified_by_kind`
     * in `USER` / `CHECK`).
     */
    private fun meetsAllAcceptanceCriteria(nodeId: String, projectId: String): Boolean {
        var unmetEdgeCriteria = 0
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT COUNT(*) FROM intent_edges ie
                LEFT JOIN intent_nodes ac ON ac.id = ie.to_node_id AND ac.project_id = ie.project_id
                WHERE ie.project_id = ?
                  AND ie.from_node_id = ?
                  AND ie.edge_kind = 'ACCEPTS'
                  AND (
                    ac.id IS NULL
                    OR (
                      ac.lifecycle = 'ACTIVE'
                      AND (
                        COALESCE(ac.verification_met, 0) != 1
                        OR COALESCE(ac.verified_by_kind, '') NOT IN ('USER', 'CHECK')
                      )
                    )
                  )
            """.trimIndent(),
            parameters = 2,
            binders = {
                bindString(0, projectId)
                bindString(1, nodeId)
            },
            mapper = { resultSet ->
                if (resultSet.next().value) {
                    unmetEdgeCriteria = resultSet.getLong(0)?.toInt() ?: 0
                }
                QueryResult.Value(Unit)
            },
        )
        if (unmetEdgeCriteria > 0) {
            return false
        }

        var unmetChildCriteria = 0
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT COUNT(*) FROM intent_nodes
                WHERE project_id = ?
                  AND parent_id = ?
                  AND type = 'ACCEPTANCE_CRITERION'
                  AND lifecycle = 'ACTIVE'
                  AND (
                    COALESCE(verification_met, 0) != 1
                    OR COALESCE(verified_by_kind, '') NOT IN ('USER', 'CHECK')
                  )
            """.trimIndent(),
            parameters = 2,
            binders = {
                bindString(0, projectId)
                bindString(1, nodeId)
            },
            mapper = { resultSet ->
                if (resultSet.next().value) {
                    unmetChildCriteria = resultSet.getLong(0)?.toInt() ?: 0
                }
                QueryResult.Value(Unit)
            },
        )
        return unmetChildCriteria == 0
    }

    private fun loadNode(nodeId: String, projectId: String): IntentNodeRecord? {
        var record: IntentNodeRecord? = null
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT id, type, lifecycle, parent_id, asserted_status, verification_met, verified_by_kind
                FROM intent_nodes
                WHERE id = ? AND project_id = ?
            """.trimIndent(),
            parameters = 2,
            binders = {
                bindString(0, nodeId)
                bindString(1, projectId)
            },
            mapper = { resultSet ->
                if (resultSet.next().value) {
                    record = IntentNodeRecord(
                        id = resultSet.getString(0)!!,
                        type = resultSet.getString(1)!!,
                        lifecycle = resultSet.getString(2)!!,
                        parentId = resultSet.getString(3),
                        assertedStatus = resultSet.getString(4),
                        verificationMet = resultSet.getLong(5),
                        verifiedByKind = resultSet.getString(6),
                    )
                }
                QueryResult.Value(Unit)
            },
        )
        return record
    }

    private fun dependencyIds(nodeId: String, projectId: String): List<String> {
        val deps = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT to_node_id FROM intent_edges
                WHERE project_id = ? AND from_node_id = ? AND edge_kind = 'DEPENDS_ON'
            """.trimIndent(),
            parameters = 2,
            binders = {
                bindString(0, projectId)
                bindString(1, nodeId)
            },
            mapper = { resultSet ->
                while (resultSet.next().value) {
                    resultSet.getString(0)?.let { deps.add(it) }
                }
                QueryResult.Value(Unit)
            },
        )
        return deps
    }

    private fun childSubGoalIds(nodeId: String, projectId: String): List<String> {
        val children = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT id FROM intent_nodes
                WHERE project_id = ?
                  AND parent_id = ?
                  AND lifecycle = 'ACTIVE'
                  AND type IN ('GOAL', 'SUB_GOAL')
                ORDER BY priority ASC, created_at ASC, id ASC
            """.trimIndent(),
            parameters = 2,
            binders = {
                bindString(0, projectId)
                bindString(1, nodeId)
            },
            mapper = { resultSet ->
                while (resultSet.next().value) {
                    resultSet.getString(0)?.let { children.add(it) }
                }
                QueryResult.Value(Unit)
            },
        )
        return children
    }

    private data class IntentNodeRecord(
        val id: String,
        val type: String,
        val lifecycle: String,
        val parentId: String?,
        val assertedStatus: String?,
        val verificationMet: Long?,
        val verifiedByKind: String?,
    )

    private companion object {
        val VALID_NODE_VERIFIERS = setOf("USER", "CHECK")
        val INHERITED_PARENT_STATUSES = setOf(
            DerivedStatus.BLOCKED.name,
            DerivedStatus.STALE.name,
            DerivedStatus.ABANDONED.name,
        )
    }
}
