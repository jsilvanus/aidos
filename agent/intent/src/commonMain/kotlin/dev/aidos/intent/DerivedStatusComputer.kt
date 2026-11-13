package dev.aidos.intent

import app.cash.sqldelight.db.SqlDriver

/**
 * Computes derived status for intent nodes based on graph state (RFC-0012).
 *
 * An intent node has no stored `status` field. Progress is derived from:
 * - TARGETED/IMPLEMENTS edges and their Run outcomes
 * - Acceptance criteria evaluation
 * - Status of dependencies
 *
 * Derived status values (RFC-0012, §Status is derived):
 * - NOT_STARTED: no TARGETED or IMPLEMENTS edges
 * - IN_PROGRESS: a TARGETED Run is non-terminal (RUNNING/SLEEPING)
 * - NEEDS_REVIEW: IMPLEMENTS edges exist but are unconfirmed (verified_by_kind != USER)
 * - DONE: confirmed IMPLEMENTS + all acceptance criteria met
 * - BLOCKED: a dependency is not DONE, or last Run failed unrecoverably
 * - STALE: was DONE, but content it produced is DANGLING or was reverted (RFC-0053)
 */
class DerivedStatusComputer(
    private val driver: SqlDriver,
) {

    fun derive(nodeId: String, projectId: String): String {
        // Check if this node has a parent (is a sub-goal).
        // If so and the parent is BLOCKED/STALE, inherit that status.

        // 1. Check for unmet dependencies (BLOCKED status)
        if (hasUnmetDependency(nodeId, projectId)) {
            return "BLOCKED"
        }

        // 2. Check if content is STALE (RFC-0053)
        if (isStale(nodeId, projectId)) {
            return "STALE"
        }

        // 3. Check if a TARGETED Run is active (IN_PROGRESS)
        if (hasActiveTargetedRun(nodeId)) {
            return "IN_PROGRESS"
        }

        // 4. Check if IMPLEMENTS edges exist but are unconfirmed (NEEDS_REVIEW)
        if (hasUnconfirmedImplements(nodeId)) {
            return "NEEDS_REVIEW"
        }

        // 5. Check if IMPLEMENTS + acceptance criteria met (DONE)
        if (hasConfirmedImplementsAndMeetsAcceptance(nodeId, projectId)) {
            return "DONE"
        }

        // 6. Default: NOT_STARTED
        return "NOT_STARTED"
    }

    /**
     * Returns true if this node has any DEPENDS_ON edges to nodes that are not DONE.
     */
    private fun hasUnmetDependency(nodeId: String, projectId: String): Boolean {
        var count = 0
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT COUNT(*) FROM intent_edges ie
                WHERE ie.project_id = ? AND ie.from_node_id = ? 
                  AND ie.edge_kind = 'DEPENDS_ON'
                  AND (
                    -- Dependency target does not exist (edge dangling)
                    NOT EXISTS (SELECT 1 FROM intent_nodes WHERE id = ie.to_node_id)
                    -- OR dependency target is not DONE
                    OR ie.to_node_id IN (
                      SELECT id FROM intent_nodes
                      WHERE project_id = ? 
                        AND lifecycle = 'ACTIVE'
                        AND (asserted_status IS NULL OR asserted_status != 'DONE')
                    )
                  )
            """,
            parameters = 3,
            binders = { bindString(0, projectId); bindString(1, nodeId); bindString(2, projectId) },
            mapper = { resultSet ->
                if (resultSet.next()) {
                    count = resultSet.getLong(0).toInt()
                }
            },
        )
        return count > 0
    }

    /**
     * Returns true if this node was DONE but content is now STALE (dangling/reverted).
     * RFC-0053 defines STALE. For now, returns false (RFC-0053 implementation is future work).
     */
    private fun isStale(nodeId: String, projectId: String): Boolean {
        // RFC-0053 reconciliation not yet implemented in this MVP.
        // Return false for now.
        return false
    }

    /**
     * Returns true if a TARGETED Run exists and is in a non-terminal state (RUNNING/SLEEPING).
     */
    private fun hasActiveTargetedRun(nodeId: String): Boolean {
        var count = 0
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT COUNT(*) FROM execution_graph eg
                JOIN runs r ON r.id = eg.run_id
                WHERE eg.target_intent_node_id = ?
                  AND eg.edge_kind = 'TARGETED'
                  AND r.state IN ('RUNNING', 'SLEEPING')
            """,
            parameters = 1,
            binders = { bindString(0, nodeId) },
            mapper = { resultSet ->
                if (resultSet.next()) {
                    count = resultSet.getLong(0).toInt()
                }
            },
        )
        return count > 0
    }

    /**
     * Returns true if IMPLEMENTS edges exist but are unconfirmed.
     *
     * An IMPLEMENTS edge is confirmed if the acceptance criterion's verified_by_kind is 'USER'
     * (or a mechanical check has evaluated it). A session can run a check, but cannot confirm it.
     */
    private fun hasUnconfirmedImplements(nodeId: String): Boolean {
        var count = 0
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT COUNT(*) FROM execution_graph eg
                WHERE eg.target_intent_node_id = ?
                  AND eg.edge_kind = 'IMPLEMENTS'
                  AND NOT EXISTS (
                    -- Check if there's a confirmed verification
                    SELECT 1 FROM intent_nodes
                    WHERE id = ?
                      AND verified_by_kind IN ('USER', 'CHECK')
                      AND verification_met = 1
                  )
            """,
            parameters = 2,
            binders = { bindString(0, nodeId); bindString(1, nodeId) },
            mapper = { resultSet ->
                if (resultSet.next()) {
                    count = resultSet.getLong(0).toInt()
                }
            },
        )
        return count > 0
    }

    /**
     * Returns true if IMPLEMENTS edges exist, are confirmed, and all acceptance criteria are met.
     */
    private fun hasConfirmedImplementsAndMeetsAcceptance(nodeId: String, projectId: String): Boolean {
        // Check for IMPLEMENTS edges that are confirmed
        var hasConfirmedImpl = false
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT COUNT(*) FROM execution_graph eg
                WHERE eg.target_intent_node_id = ?
                  AND eg.edge_kind = 'IMPLEMENTS'
                  AND EXISTS (
                    SELECT 1 FROM intent_nodes
                    WHERE id = ?
                      AND verified_by_kind IN ('USER', 'CHECK')
                      AND verification_met = 1
                  )
            """,
            parameters = 2,
            binders = { bindString(0, nodeId); bindString(1, nodeId) },
            mapper = { resultSet ->
                if (resultSet.next()) {
                    val count = resultSet.getLong(0).toInt()
                    hasConfirmedImpl = count > 0
                }
            },
        )

        if (!hasConfirmedImpl) {
            return false
        }

        // Check if all acceptance criteria are met
        var unmetCount = 0
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT COUNT(*) FROM intent_edges ie
                WHERE ie.project_id = ? AND ie.from_node_id = ?
                  AND ie.edge_kind = 'ACCEPTS'
                  AND EXISTS (
                    SELECT 1 FROM intent_nodes
                    WHERE id = ie.to_node_id
                      AND (verification_met IS NULL OR verification_met = 0)
                  )
            """,
            parameters = 2,
            binders = { bindString(0, projectId); bindString(1, nodeId) },
            mapper = { resultSet ->
                if (resultSet.next()) {
                    unmetCount = resultSet.getLong(0).toInt()
                }
            },
        )

        return unmetCount == 0
    }
}
