package dev.aidos.executor

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.QueryResult
import dev.aidos.kernel.ProjectId

/**
 * Project lifecycle operations (RFC-0002).
 *
 * Manages project deletion and archival with safety checks:
 * - Archive: soft delete, marks state=ARCHIVED, preserves Git repo
 * - Delete: hard delete, removes database entries (Git repo must be removed separately)
 */
class ProjectLifecycle(
    private val driver: SqlDriver,
    private val nowIso: () -> String,
) {

    /**
     * Archive a project (soft delete).
     *
     * Safety checks:
     * - No active (RUNNING) sessions
     * - No open (non-terminal) runs
     *
     * Atomicity:
     * - Sets project state to ARCHIVED
     * - Leaves Git repository intact
     * - Preserves all data for recovery
     */
    suspend fun archiveProject(projectId: ProjectId): Result<Unit> {
        return try {
            // Check for active sessions
            val activeSessions = countActiveSessions(projectId)
            if (activeSessions > 0) {
                return Result.failure(
                    IllegalStateException(
                        "Cannot archive project ${projectId.value}: " +
                            "$activeSessions active session(s) still running"
                    )
                )
            }

            // Check for open runs
            val openRuns = countOpenRuns(projectId)
            if (openRuns > 0) {
                return Result.failure(
                    IllegalStateException(
                        "Cannot archive project ${projectId.value}: " +
                            "$openRuns open run(s) still in progress"
                    )
                )
            }

            // Safe to archive: mark state as ARCHIVED
            driver.execute(
                null,
                """
                UPDATE projects
                SET state = 'ARCHIVED', updated_at = ?, state_updated_at = ?
                WHERE id = ?
                """,
                3
            ) {
                bindString(0, nowIso())
                bindString(1, nowIso())
                bindString(2, projectId.value)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Delete a project (hard delete).
     *
     * Safety checks:
     * - No active (RUNNING) sessions
     * - No open (non-terminal) runs
     *
     * Deletes:
     * - All sessions and their state
     * - All runs, attempts, tasks
     * - All content nodes and provenance
     * - All budget ledger entries
     * - All events
     * - Project record itself
     *
     * Does NOT delete:
     * - Git repository (caller must remove separately)
     * - Audit logs (permanent record)
     */
    suspend fun deleteProject(projectId: ProjectId): Result<Unit> {
        return try {
            // Check for active sessions
            val activeSessions = countActiveSessions(projectId)
            if (activeSessions > 0) {
                return Result.failure(
                    IllegalStateException(
                        "Cannot delete project ${projectId.value}: " +
                            "$activeSessions active session(s) still running"
                    )
                )
            }

            // Check for open runs
            val openRuns = countOpenRuns(projectId)
            if (openRuns > 0) {
                return Result.failure(
                    IllegalStateException(
                        "Cannot delete project ${projectId.value}: " +
                            "$openRuns open run(s) still in progress"
                    )
                )
            }

            // Delete in correct order (respecting FK constraints)
            // Note: audit_log entries for this project are preserved (permanent record)

            // Delete runs and their associated data
            driver.execute(null, "DELETE FROM execution_edges WHERE project_id = ?", 1) {
                bindString(0, projectId.value)
            }
            driver.execute(
                null,
                "DELETE FROM budget_reservations WHERE attempt_id IN (" +
                    "SELECT a.id FROM attempts a " +
                    "JOIN tasks t ON a.task_id = t.id " +
                    "JOIN runs r ON t.run_id = r.id " +
                    "WHERE r.project_id = ?" +
                    ")",
                1
            ) {
                bindString(0, projectId.value)
            }
            driver.execute(
                null,
                "DELETE FROM attempts WHERE task_id IN (" +
                    "SELECT t.id FROM tasks t " +
                    "JOIN runs r ON t.run_id = r.id " +
                    "WHERE r.project_id = ?" +
                    ")",
                1
            ) {
                bindString(0, projectId.value)
            }
            driver.execute(null, "DELETE FROM tasks WHERE run_id IN (SELECT id FROM runs WHERE project_id = ?)", 1) {
                bindString(0, projectId.value)
            }
            driver.execute(null, "DELETE FROM execution_plans WHERE run_id IN (SELECT id FROM runs WHERE project_id = ?)", 1) {
                bindString(0, projectId.value)
            }
            driver.execute(null, "DELETE FROM runs WHERE project_id = ?", 1) {
                bindString(0, projectId.value)
            }

            // Delete content graph
            driver.execute(null, "DELETE FROM provenance_edges WHERE from_node_id IN (SELECT id FROM content_nodes WHERE project_id = ?) OR to_node_id IN (SELECT id FROM content_nodes WHERE project_id = ?)", 2) {
                bindString(0, projectId.value)
                bindString(1, projectId.value)
            }
            driver.execute(null, "DELETE FROM content_nodes WHERE project_id = ?", 1) {
                bindString(0, projectId.value)
            }

            // Delete intent graph
            driver.execute(null, "DELETE FROM intent_edges WHERE project_id = ?", 1) {
                bindString(0, projectId.value)
            }
            driver.execute(null, "DELETE FROM intent_proposals WHERE project_id = ?", 1) {
                bindString(0, projectId.value)
            }
            driver.execute(null, "DELETE FROM intent_nodes WHERE project_id = ?", 1) {
                bindString(0, projectId.value)
            }

            // Delete budget ledger
            driver.execute(null, "DELETE FROM budget_ledger WHERE scope_id = ?", 1) {
                bindString(0, projectId.value)
            }

            // Delete events
            driver.execute(null, "DELETE FROM events WHERE project_id = ?", 1) {
                bindString(0, projectId.value)
            }

            // Delete sessions and subscriptions
            driver.execute(null, "DELETE FROM session_subscriptions WHERE session_id IN (SELECT id FROM sessions WHERE project_id = ?)", 1) {
                bindString(0, projectId.value)
            }
            driver.execute(null, "DELETE FROM sessions WHERE project_id = ?", 1) {
                bindString(0, projectId.value)
            }

            // Delete capabilities
            driver.execute(null, "DELETE FROM capability_usage WHERE capability_id IN (SELECT id FROM capabilities WHERE project_id = ?)", 1) {
                bindString(0, projectId.value)
            }
            driver.execute(null, "DELETE FROM capabilities WHERE project_id = ?", 1) {
                bindString(0, projectId.value)
            }

            // Delete revocation epoch
            driver.execute(null, "DELETE FROM project_revocation_epoch WHERE project_id = ?", 1) {
                bindString(0, projectId.value)
            }

            // Finally delete the project itself
            driver.execute(null, "DELETE FROM projects WHERE id = ?", 1) {
                bindString(0, projectId.value)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun countActiveSessions(projectId: ProjectId): Long {
        var count = 0L
        driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM sessions WHERE project_id = ? AND state = 'RUNNING'",
            mapper = { cursor ->
                if (cursor.next().value) {
                    count = cursor.getLong(0) ?: 0
                }
                QueryResult.Value(Unit)
            },
            parameters = 1,
            binders = {
                bindString(0, projectId.value)
            }
        )
        return count
    }

    private fun countOpenRuns(projectId: ProjectId): Long {
        var count = 0L
        driver.executeQuery(
            identifier = null,
            sql = """
            SELECT COUNT(*) FROM runs 
            WHERE project_id = ? AND state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
            """,
            mapper = { cursor ->
                if (cursor.next().value) {
                    count = cursor.getLong(0) ?: 0
                }
                QueryResult.Value(Unit)
            },
            parameters = 1,
            binders = {
                bindString(0, projectId.value)
            }
        )
        return count
    }
}
