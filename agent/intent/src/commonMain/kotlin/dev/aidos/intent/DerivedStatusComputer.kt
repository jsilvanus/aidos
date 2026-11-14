package dev.aidos.intent

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.PlatformProfile

/**
 * Computes derived status and scheduling readiness for dual-mode intent nodes (RFC-0110,
 * superseding RFC-0012).
 *
 * Intent nodes have no stored `status` column. Progress is always derived from:
 * - Recursive `DEPENDS_ON` prerequisites in `intent_edges`
 * - `TARGETED` / `IMPLEMENTS` edges in canonical `execution_edges` and Run states in `runs`
 * - Actor ownership (`HUMAN` vs `AGENT` vs `HYBRID`) and verification (`USER` / `CHECK`)
 * - Linked `ACCEPTS` acceptance criteria in `intent_nodes`
 * - Produced `content_nodes` state (`DANGLING` -> `STALE`)
 */
class DerivedStatusComputer(
    private val driver: SqlDriver,
) {

    private data class NodeMeta(
        val type: String,
        val lifecycle: String,
        val actorKind: String,
        val assertedStatus: String?,
        val verificationMet: Long?,
        val verifiedByKind: String?,
    )

    fun derive(nodeId: String, projectId: String): String =
        deriveInternal(nodeId, projectId, emptySet())

    fun deriveStatus(nodeId: String, projectId: String): DerivedStatus =
        DerivedStatus.valueOf(derive(nodeId, projectId))

    private fun deriveInternal(nodeId: String, projectId: String, visited: Set<String>): String {
        val meta = readNodeMeta(nodeId, projectId) ?: return "NOT_STARTED"

        // 1. Explicit merge/snapshot conflict supersedes normal progression (RFC-0053).
        if (meta.assertedStatus == "CONFLICTED") {
            return "CONFLICTED"
        }

        // 2. Unmet recursive dependencies block downstream nodes regardless of actor kind.
        if (unmetDependencies(nodeId, projectId, visited).isNotEmpty()) {
            return "BLOCKED"
        }

        // 3. Stale if produced content became DANGLING (RFC-0053).
        if (isStale(nodeId, projectId)) {
            return "STALE"
        }

        // 4. GROUP containers aggregate their active child nodes when children exist.
        if (meta.type == "GROUP") {
            val childIds = activeChildIds(nodeId, projectId)
            if (childIds.isNotEmpty()) {
                val childStatuses = childIds.map { childId ->
                    if (childId in visited) "BLOCKED" else deriveInternal(childId, projectId, visited + nodeId)
                }
                return when {
                    childStatuses.all { it == "DONE" } -> "DONE"
                    childStatuses.any { it == "CONFLICTED" } -> "CONFLICTED"
                    childStatuses.any { it == "STALE" } -> "STALE"
                    childStatuses.any { it == "IN_PROGRESS" } -> "IN_PROGRESS"
                    childStatuses.any { it == "NEEDS_REVIEW" } -> "NEEDS_REVIEW"
                    childStatuses.any { it == "BLOCKED" } -> "BLOCKED"
                    else -> "NOT_STARTED"
                }
            }
        }

        // 5. Active targeting Run -> IN_PROGRESS (or explicit human in-progress assertion).
        if (hasActiveTargetedRun(nodeId, projectId) ||
            (meta.actorKind == "HUMAN" && meta.assertedStatus == "IN_PROGRESS")
        ) {
            return "IN_PROGRESS"
        }

        val criteriaMet = areAcceptanceCriteriaMet(nodeId, projectId)
        val userVerified = meta.verifiedByKind == "USER" && meta.verificationMet == 1L
        val checkVerified = meta.verifiedByKind == "CHECK" && meta.verificationMet == 1L
        val hasConfirmedImpl = hasConfirmedImplements(nodeId, projectId)
        val hasUnconfirmedImpl = hasUnconfirmedImplements(nodeId, projectId)

        return when (meta.actorKind) {
            "HUMAN" -> when {
                (userVerified || meta.assertedStatus == "DONE") && criteriaMet -> "DONE"
                meta.assertedStatus == "NEEDS_REVIEW" || (hasUnconfirmedImpl || !criteriaMet && (userVerified || meta.assertedStatus == "DONE")) ->
                    "NEEDS_REVIEW"
                else -> "NOT_STARTED"
            }

            "HYBRID" -> when {
                (userVerified || meta.assertedStatus == "DONE") &&
                    (hasConfirmedImpl || hasUnconfirmedImpl || meta.assertedStatus == "DONE") &&
                    criteriaMet -> "DONE"
                hasConfirmedImpl || hasUnconfirmedImpl || checkVerified -> "NEEDS_REVIEW"
                else -> "NOT_STARTED"
            }

            else -> when { // AGENT
                (hasConfirmedImpl || meta.assertedStatus == "DONE") && criteriaMet -> "DONE"
                hasUnconfirmedImpl || (hasConfirmedImpl && !criteriaMet) -> "NEEDS_REVIEW"
                else -> "NOT_STARTED"
            }
        }
    }

    /**
     * Returns direct `DEPENDS_ON` target node IDs whose derived status is not `DONE` (or whose
     * target row is missing). Archived dependency targets do not block.
     */
    fun unmetDependencies(
        nodeId: String,
        projectId: String,
        visited: Set<String> = emptySet(),
    ): List<String> {
        val depIds = mutableListOf<String>()
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
            mapper = { cursor ->
                while (cursor.next().value) {
                    cursor.getString(0)?.let { depIds.add(it) }
                }
                QueryResult.Value(Unit)
            },
        )
        if (depIds.isEmpty()) return emptyList()

        val unmet = mutableListOf<String>()
        val nextVisited = visited + nodeId
        for (depId in depIds) {
            if (depId in nextVisited) {
                unmet.add(depId)
                continue
            }
            val meta = readNodeMeta(depId, projectId)
            if (meta == null) {
                unmet.add(depId)
                continue
            }
            if (meta.lifecycle == "ARCHIVED") {
                continue
            }
            if (deriveInternal(depId, projectId, nextVisited) != "DONE") {
                unmet.add(depId)
            }
        }
        return unmet
    }

    fun activeTargetingRunIds(nodeId: String, projectId: String): List<String> {
        val runIds = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT r.id FROM execution_edges ee
                JOIN runs r ON r.id = ee.from_node_id AND ee.from_node_kind = 'RUN'
                WHERE ee.project_id = ?
                  AND ee.to_node_id = ?
                  AND ee.to_node_kind = 'INTENT_NODE'
                  AND ee.edge_kind = 'TARGETED'
                  AND r.state IN ('PENDING', 'RUNNING', 'SLEEPING', 'YIELDED', 'INTERRUPTED')
            """.trimIndent(),
            parameters = 2,
            binders = {
                bindString(0, projectId)
                bindString(1, nodeId)
            },
            mapper = { cursor ->
                while (cursor.next().value) {
                    cursor.getString(0)?.let { runIds.add(it) }
                }
                QueryResult.Value(Unit)
            },
        )
        return runIds
    }

    fun resolveDispatchPolicy(
        nodeId: String,
        projectId: String,
        projectDefault: EffectiveDispatchPolicy = EffectiveDispatchPolicy.MANUAL_START,
    ): EffectiveDispatchPolicy {
        var currentId: String? = nodeId
        val visited = mutableSetOf<String>()
        while (currentId != null && currentId !in visited) {
            visited.add(currentId)
            val pair = driver.executeQuery(
                identifier = null,
                sql = "SELECT parent_id, dispatch_policy FROM intent_nodes WHERE id = ? AND project_id = ?",
                mapper = { cursor ->
                    QueryResult.Value(
                        if (cursor.next().value) {
                            cursor.getString(0) to cursor.getString(1)
                        } else {
                            null
                        }
                    )
                },
                parameters = 2,
            ) {
                bindString(0, currentId!!)
                bindString(1, projectId)
            }.value ?: break

            when (pair.second) {
                "MANUAL_START" -> return EffectiveDispatchPolicy.MANUAL_START
                "AUTO_WHEN_READY" -> return EffectiveDispatchPolicy.AUTO_WHEN_READY
            }
            currentId = pair.first
        }
        return projectDefault
    }

    fun hasPendingPlatformChecks(nodeId: String, projectId: String): Boolean {
        var pendingCount = 0
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT COUNT(*) FROM intent_edges ie
                JOIN intent_nodes crit ON crit.id = ie.to_node_id
                WHERE ie.project_id = ?
                  AND ie.from_node_id = ?
                  AND ie.edge_kind = 'ACCEPTS'
                  AND crit.lifecycle = 'ACTIVE'
                  AND crit.check_kind = 'SHELL_COMMAND'
                  AND (crit.verification_met IS NULL OR crit.verification_met = 0)
            """.trimIndent(),
            parameters = 2,
            binders = {
                bindString(0, projectId)
                bindString(1, nodeId)
            },
            mapper = { cursor ->
                if (cursor.next().value) {
                    pendingCount = cursor.getLong(0)?.toInt() ?: 0
                }
                QueryResult.Value(Unit)
            },
        )
        return pendingCount > 0
    }

    fun statusView(
        node: IntentNodeRecord,
        projectDefaultPolicy: EffectiveDispatchPolicy = EffectiveDispatchPolicy.MANUAL_START,
    ): IntentNodeStatusView {
        val status = deriveStatus(node.id, node.projectId)
        val policy = resolveDispatchPolicy(node.id, node.projectId, projectDefaultPolicy)
        val unmet = unmetDependencies(node.id, node.projectId)
        val activeRuns = activeTargetingRunIds(node.id, node.projectId)
        val pendingPlatform = hasPendingPlatformChecks(node.id, node.projectId)
        return IntentNodeStatusView(
            node = node,
            derivedStatus = status,
            effectiveDispatchPolicy = policy,
            unmetDependencyIds = unmet,
            activeRunIds = activeRuns,
            pendingPlatformChecks = pendingPlatform,
        )
    }

    fun listActionableForHuman(
        projectId: String,
        projectDefaultPolicy: EffectiveDispatchPolicy = EffectiveDispatchPolicy.MANUAL_START,
    ): List<IntentNodeStatusView> {
        val nodes = loadActiveNodes(projectId)
        return nodes
            .map { statusView(it, projectDefaultPolicy) }
            .filter { view ->
                val n = view.node
                when (n.actorKind) {
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
                )
            )
    }

    fun listReadyForAgent(
        projectId: String,
        profile: PlatformProfile = PlatformProfile.DESKTOP,
        locality: IntentMinLocality = IntentMinLocality.ANY,
        projectDefaultPolicy: EffectiveDispatchPolicy = EffectiveDispatchPolicy.MANUAL_START,
        onlyAutoDispatch: Boolean = false,
    ): List<IntentNodeStatusView> {
        val nodes = loadActiveNodes(projectId)
        return nodes
            .filter { n ->
                n.actorKind != IntentActorKind.HUMAN &&
                    n.type in setOf(IntentNodeType.GOAL, IntentNodeType.SUB_GOAL, IntentNodeType.ATOMIC_TASK) &&
                    isProfileCompatible(n.targetProfile, profile) &&
                    isLocalityCompatible(n.minLocality, locality)
            }
            .map { statusView(it, projectDefaultPolicy) }
            .filter { view ->
                view.derivedStatus == DerivedStatus.NOT_STARTED &&
                    (!onlyAutoDispatch || view.effectiveDispatchPolicy == EffectiveDispatchPolicy.AUTO_WHEN_READY)
            }
            .sortedWith(compareBy({ it.node.priority }, { it.node.createdAt }))
    }

    private fun hasActiveTargetedRun(nodeId: String, projectId: String): Boolean =
        activeTargetingRunIds(nodeId, projectId).isNotEmpty()

    private fun hasUnconfirmedImplements(nodeId: String, projectId: String): Boolean {
        var count = 0
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT COUNT(*) FROM execution_edges ee
                WHERE ee.project_id = ?
                  AND ee.to_node_id = ?
                  AND ee.to_node_kind = 'INTENT_NODE'
                  AND ee.edge_kind = 'IMPLEMENTS'
                  AND ee.confirmed = 0
                  AND NOT EXISTS (
                    SELECT 1 FROM intent_nodes n
                    WHERE n.id = ?
                      AND n.project_id = ?
                      AND n.verified_by_kind IN ('USER', 'CHECK')
                      AND n.verification_met = 1
                  )
            """.trimIndent(),
            parameters = 4,
            binders = {
                bindString(0, projectId)
                bindString(1, nodeId)
                bindString(2, nodeId)
                bindString(3, projectId)
            },
            mapper = { cursor ->
                if (cursor.next().value) {
                    count = cursor.getLong(0)?.toInt() ?: 0
                }
                QueryResult.Value(Unit)
            },
        )
        return count > 0
    }

    private fun hasConfirmedImplements(nodeId: String, projectId: String): Boolean {
        var count = 0
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT COUNT(*) FROM execution_edges ee
                WHERE ee.project_id = ?
                  AND ee.to_node_id = ?
                  AND ee.to_node_kind = 'INTENT_NODE'
                  AND ee.edge_kind = 'IMPLEMENTS'
                  AND (
                    ee.confirmed = 1
                    OR EXISTS (
                      SELECT 1 FROM intent_nodes n
                      WHERE n.id = ?
                        AND n.project_id = ?
                        AND n.verified_by_kind IN ('USER', 'CHECK')
                        AND n.verification_met = 1
                    )
                  )
            """.trimIndent(),
            parameters = 4,
            binders = {
                bindString(0, projectId)
                bindString(1, nodeId)
                bindString(2, nodeId)
                bindString(3, projectId)
            },
            mapper = { cursor ->
                if (cursor.next().value) {
                    count = cursor.getLong(0)?.toInt() ?: 0
                }
                QueryResult.Value(Unit)
            },
        )
        return count > 0
    }

    private fun areAcceptanceCriteriaMet(nodeId: String, projectId: String): Boolean {
        var unmetCount = 0
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT COUNT(*) FROM intent_edges ie
                JOIN intent_nodes crit ON crit.id = ie.to_node_id
                WHERE ie.project_id = ?
                  AND ie.from_node_id = ?
                  AND ie.edge_kind = 'ACCEPTS'
                  AND crit.lifecycle = 'ACTIVE'
                  AND (crit.verification_met IS NULL OR crit.verification_met = 0)
            """.trimIndent(),
            parameters = 2,
            binders = {
                bindString(0, projectId)
                bindString(1, nodeId)
            },
            mapper = { cursor ->
                if (cursor.next().value) {
                    unmetCount = cursor.getLong(0)?.toInt() ?: 0
                }
                QueryResult.Value(Unit)
            },
        )
        return unmetCount == 0
    }

    private fun isStale(nodeId: String, projectId: String): Boolean {
        val directStale = runCatching {
            var count = 0
            driver.executeQuery(
                identifier = null,
                sql = """
                    SELECT COUNT(*) FROM execution_edges impl
                    JOIN execution_edges prod
                      ON prod.project_id = impl.project_id
                     AND prod.from_node_kind = 'RUN'
                     AND prod.from_node_id = impl.from_node_id
                     AND prod.edge_kind = 'PRODUCED'
                     AND prod.to_node_kind = 'CONTENT_NODE'
                    JOIN content_nodes cn ON cn.id = prod.to_node_id
                    WHERE impl.project_id = ?
                      AND impl.to_node_id = ?
                      AND impl.to_node_kind = 'INTENT_NODE'
                      AND impl.edge_kind = 'IMPLEMENTS'
                      AND cn.state = 'DANGLING'
                """.trimIndent(),
                parameters = 2,
                binders = {
                    bindString(0, projectId)
                    bindString(1, nodeId)
                },
                mapper = { cursor ->
                    if (cursor.next().value) {
                        count = cursor.getLong(0)?.toInt() ?: 0
                    }
                    QueryResult.Value(Unit)
                },
            )
            count > 0
        }.getOrDefault(false)
        if (directStale) return true

        return runCatching {
            var count = 0
            driver.executeQuery(
                identifier = null,
                sql = """
                    SELECT COUNT(*) FROM execution_edges impl
                    JOIN tasks t ON t.run_id = impl.from_node_id
                    JOIN attempts a ON a.task_id = t.id
                    JOIN execution_edges prod
                      ON prod.project_id = impl.project_id
                     AND prod.from_node_kind = 'ATTEMPT'
                     AND prod.from_node_id = a.id
                     AND prod.edge_kind = 'PRODUCED'
                     AND prod.to_node_kind = 'CONTENT_NODE'
                    JOIN content_nodes cn ON cn.id = prod.to_node_id
                    WHERE impl.project_id = ?
                      AND impl.to_node_id = ?
                      AND impl.to_node_kind = 'INTENT_NODE'
                      AND impl.edge_kind = 'IMPLEMENTS'
                      AND cn.state = 'DANGLING'
                """.trimIndent(),
                parameters = 2,
                binders = {
                    bindString(0, projectId)
                    bindString(1, nodeId)
                },
                mapper = { cursor ->
                    if (cursor.next().value) {
                        count = cursor.getLong(0)?.toInt() ?: 0
                    }
                    QueryResult.Value(Unit)
                },
            )
            count > 0
        }.getOrDefault(false)
    }

    private fun readNodeMeta(nodeId: String, projectId: String): NodeMeta? =
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT type, lifecycle, actor_kind, asserted_status, verification_met, verified_by_kind
                FROM intent_nodes
                WHERE id = ? AND project_id = ?
            """.trimIndent(),
            mapper = { cursor ->
                QueryResult.Value(
                    if (cursor.next().value) {
                        NodeMeta(
                            type = cursor.getString(0) ?: "GOAL",
                            lifecycle = cursor.getString(1) ?: "ACTIVE",
                            actorKind = cursor.getString(2) ?: "AGENT",
                            assertedStatus = cursor.getString(3),
                            verificationMet = cursor.getLong(4),
                            verifiedByKind = cursor.getString(5),
                        )
                    } else {
                        null
                    }
                )
            },
            parameters = 2,
        ) {
            bindString(0, nodeId)
            bindString(1, projectId)
        }.value

    private fun activeChildIds(parentId: String, projectId: String): List<String> {
        val ids = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT id FROM intent_nodes WHERE project_id = ? AND parent_id = ? AND lifecycle = 'ACTIVE'",
            parameters = 2,
            binders = {
                bindString(0, projectId)
                bindString(1, parentId)
            },
            mapper = { cursor ->
                while (cursor.next().value) {
                    cursor.getString(0)?.let { ids.add(it) }
                }
                QueryResult.Value(Unit)
            },
        )
        return ids
    }

    private fun loadActiveNodes(projectId: String): List<IntentNodeRecord> =
        IntentGraphRepository.readNodes(driver, projectId, includeArchived = false)
}
