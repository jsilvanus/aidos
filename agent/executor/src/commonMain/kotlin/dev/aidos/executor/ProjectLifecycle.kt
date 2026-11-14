package dev.aidos.executor

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.ProjectId

/**
 * Project lifecycle operations (RFC-0002, RFC-0017).
 *
 * Manages project archival and deletion with safety checks:
 * - Archive: soft close, sets state='CLOSED' (per project.sql CREATING|OPEN|CLOSING|CLOSED), preserves Git repo & history
 * - Delete: hard delete inside a single SQLite transaction, removes project and all dependent rows in FK order
 */
class ProjectLifecycle(
    private val driver: SqlDriver,
    private val nowIso: () -> String,
) {

    private val transacter = object : TransacterImpl(driver) {}

    private fun <T> inTransaction(block: () -> T): T =
        transacter.transactionWithResult { block() }

    /**
     * Archive a project (soft close).
     *
     * Safety checks:
     * - No active (RUNNING) sessions
     * - No open (non-terminal) runs
     *
     * Atomicity:
     * - Sets project state to CLOSED (schema valid: CREATING|OPEN|CLOSING|CLOSED)
     * - Leaves Git repository intact
     * - Preserves all data for recovery
     */
    suspend fun archiveProject(projectId: ProjectId): Result<Unit> {
        return try {
            inTransaction {
                val activeSessions = countActiveSessions(projectId)
                if (activeSessions > 0) {
                    return@inTransaction Result.failure(
                        IllegalStateException(
                            "Cannot archive project ${projectId.value}: " +
                                "$activeSessions active session(s) still running"
                        )
                    )
                }

                val openRuns = countOpenRuns(projectId)
                if (openRuns > 0) {
                    return@inTransaction Result.failure(
                        IllegalStateException(
                            "Cannot archive project ${projectId.value}: " +
                                "$openRuns open run(s) still in progress"
                        )
                    )
                }

                val now = nowIso()
                driver.execute(
                    identifier = null,
                    sql = """
                    UPDATE projects
                    SET state = 'CLOSED', updated_at = ?, state_updated_at = ?, row_version = row_version + 1
                    WHERE id = ?
                    """.trimIndent(),
                    parameters = 3,
                ) {
                    bindString(0, now)
                    bindString(1, now)
                    bindString(2, projectId.value)
                }

                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Delete a project (hard delete) atomically inside one SQLite transaction.
     *
     * Safety checks:
     * - No active (RUNNING) sessions
     * - No open (non-terminal) runs
     *
     * Deletes every dependent child/grandchild table referencing `projects(id)`, `sessions(id)`,
     * `runs(id)`, `tasks(id)`, `attempts(id)`, `capabilities(id)`, `content_nodes(id)`,
     * `intent_nodes(id)`, and `audit_log(id)` in topological FK order, along with project-,
     * session-, run-, task-, attempt-, and capability-scoped `budget_ledger`, `settings`, and
     * `retention_policy` rows, before removing `projects`.
     *
     * Does NOT delete the Git repository on disk (caller must remove separately).
     */
    suspend fun deleteProject(projectId: ProjectId): Result<Unit> {
        return try {
            val pid = projectId.value
            inTransaction {
                val activeSessions = countActiveSessions(projectId)
                if (activeSessions > 0) {
                    return@inTransaction Result.failure(
                        IllegalStateException(
                            "Cannot delete project $pid: " +
                                "$activeSessions active session(s) still running"
                        )
                    )
                }

                val openRuns = countOpenRuns(projectId)
                if (openRuns > 0) {
                    return@inTransaction Result.failure(
                        IllegalStateException(
                            "Cannot delete project $pid: " +
                                "$openRuns open run(s) still in progress"
                        )
                    )
                }

                val attemptsSubquery = """
                    SELECT id FROM attempts
                    WHERE task_id IN (
                        SELECT id FROM tasks
                        WHERE project_id = ?
                           OR run_id IN (SELECT id FROM runs WHERE project_id = ?)
                           OR session_id IN (SELECT id FROM sessions WHERE project_id = ?)
                    )
                       OR capability_id IN (SELECT id FROM capabilities WHERE project_id = ?)
                       OR audit_ref IN (SELECT id FROM audit_log WHERE project_id = ?)
                """.trimIndent()

                val tasksSubquery = """
                    SELECT id FROM tasks
                    WHERE project_id = ?
                       OR run_id IN (SELECT id FROM runs WHERE project_id = ?)
                       OR session_id IN (SELECT id FROM sessions WHERE project_id = ?)
                       OR awaiting_run_id IN (SELECT id FROM runs WHERE project_id = ?)
                """.trimIndent()

                val statements = listOf(
                    // 1. Non-FK / leaf budget rows BEFORE runs/sessions/capabilities/tasks/attempts are removed
                    "DELETE FROM budget_reservations WHERE attempt_id IN ($attemptsSubquery)",
                    """
                    DELETE FROM budget_ledger
                    WHERE scope_id = ?
                       OR (UPPER(scope) = 'SESSION' AND scope_id IN (SELECT id FROM sessions WHERE project_id = ?))
                       OR (UPPER(scope) = 'RUN' AND scope_id IN (SELECT id FROM runs WHERE project_id = ?))
                       OR (UPPER(scope) = 'CAPABILITY' AND scope_id IN (SELECT id FROM capabilities WHERE project_id = ?))
                       OR scope_id IN (SELECT id FROM sessions WHERE project_id = ?)
                       OR scope_id IN (SELECT id FROM runs WHERE project_id = ?)
                       OR scope_id IN (SELECT id FROM capabilities WHERE project_id = ?)
                       OR scope_id IN (SELECT id FROM tasks WHERE project_id = ?)
                       OR scope_id IN ($attemptsSubquery)
                    """.trimIndent(),

                    // 2. Attempt-, task-, and run-referencing leaf tables
                    """
                    DELETE FROM prompt_provenance
                    WHERE attempt_id IN ($attemptsSubquery)
                       OR content_node_id IN (SELECT id FROM content_nodes WHERE project_id = ?)
                    """.trimIndent(),
                    """
                    DELETE FROM secret_accesses
                    WHERE attempt_id IN ($attemptsSubquery)
                       OR capability_id IN (SELECT id FROM capabilities WHERE project_id = ?)
                    """.trimIndent(),
                    """
                    DELETE FROM egress_records
                    WHERE project_id = ?
                       OR attempt_id IN ($attemptsSubquery)
                    """.trimIndent(),
                    """
                    DELETE FROM continuations
                    WHERE run_id IN (SELECT id FROM runs WHERE project_id = ?)
                       OR task_id IN ($tasksSubquery)
                    """.trimIndent(),
                    """
                    DELETE FROM worker_spawn_requests
                    WHERE task_id IN ($tasksSubquery)
                       OR worker_session_id IN (SELECT id FROM sessions WHERE project_id = ?)
                       OR child_run_id IN (SELECT id FROM runs WHERE project_id = ?)
                    """.trimIndent(),
                    """
                    DELETE FROM tool_calls
                    WHERE run_id IN (SELECT id FROM runs WHERE project_id = ?)
                       OR model_task_id IN ($tasksSubquery)
                       OR tool_task_id IN ($tasksSubquery)
                    """.trimIndent(),
                    """
                    DELETE FROM intent_proposals
                    WHERE project_id = ?
                       OR proposed_by_run_id IN (SELECT id FROM runs WHERE project_id = ?)
                       OR audit_ref IN (SELECT id FROM audit_log WHERE project_id = ?)
                    """.trimIndent(),

                    // 3. Attempts, tasks, execution plans/edges, runs
                    "DELETE FROM attempts WHERE id IN ($attemptsSubquery)",
                    "DELETE FROM tasks WHERE id IN ($tasksSubquery)",
                    "DELETE FROM execution_plans WHERE run_id IN (SELECT id FROM runs WHERE project_id = ?)",
                    "DELETE FROM execution_edges WHERE project_id = ?",
                    "DELETE FROM runs WHERE project_id = ? OR session_id IN (SELECT id FROM sessions WHERE project_id = ?)",

                    // 4. Content graph
                    """
                    DELETE FROM redaction_events
                    WHERE project_id = ?
                       OR content_node_id IN (SELECT id FROM content_nodes WHERE project_id = ?)
                    """.trimIndent(),
                    """
                    DELETE FROM provenance_edges
                    WHERE from_node_id IN (SELECT id FROM content_nodes WHERE project_id = ?)
                       OR to_node_id IN (SELECT id FROM content_nodes WHERE project_id = ?)
                    """.trimIndent(),
                    "DELETE FROM content_nodes WHERE project_id = ?",

                    // 5. Intent graph (break self-FK before delete)
                    """
                    DELETE FROM intent_edges
                    WHERE project_id = ?
                       OR from_node_id IN (SELECT id FROM intent_nodes WHERE project_id = ?)
                       OR to_node_id IN (SELECT id FROM intent_nodes WHERE project_id = ?)
                    """.trimIndent(),
                    "UPDATE intent_nodes SET parent_id = NULL WHERE project_id = ?",
                    "DELETE FROM intent_nodes WHERE project_id = ?",

                    // 6. Memory, scheduling, non-FK settings/retention, sessions (break self-FK before delete)
                    """
                    UPDATE memory_entries SET superseded_by = NULL
                    WHERE project_id = ?
                       OR session_id IN (SELECT id FROM sessions WHERE project_id = ?)
                    """.trimIndent(),
                    """
                    DELETE FROM memory_entries
                    WHERE project_id = ?
                       OR session_id IN (SELECT id FROM sessions WHERE project_id = ?)
                    """.trimIndent(),
                    """
                    DELETE FROM scheduled_jobs
                    WHERE project_id = ?
                       OR session_id IN (SELECT id FROM sessions WHERE project_id = ?)
                    """.trimIndent(),
                    "DELETE FROM session_subscriptions WHERE session_id IN (SELECT id FROM sessions WHERE project_id = ?)",
                    """
                    DELETE FROM settings
                    WHERE (scope = 'project' AND scope_id = ?)
                       OR (scope = 'session' AND scope_id IN (SELECT id FROM sessions WHERE project_id = ?))
                    """.trimIndent(),
                    "DELETE FROM retention_policy WHERE scope = 'project' AND scope_id = ?",
                    "UPDATE sessions SET parent_session_id = NULL WHERE project_id = ?",
                    "DELETE FROM sessions WHERE project_id = ?",

                    // 7. Capabilities (break self-FK before delete), audit-referencing tables, and audit_log
                    "DELETE FROM capability_usage WHERE capability_id IN (SELECT id FROM capabilities WHERE project_id = ?)",
                    "UPDATE capabilities SET parent_capability_id = NULL WHERE project_id = ?",
                    "DELETE FROM capabilities WHERE project_id = ?",
                    "DELETE FROM reconciliations WHERE project_id = ?",
                    "DELETE FROM lock_breaks WHERE project_id = ?",
                    "DELETE FROM audit_log WHERE project_id = ?",

                    // 8. Remaining direct project children
                    "DELETE FROM events WHERE project_id = ?",
                    "DELETE FROM notifications WHERE project_id = ?",
                    "DELETE FROM repo_fingerprints WHERE project_id = ?",
                    "DELETE FROM metric_samples WHERE project_id = ?",
                    "DELETE FROM degradation_events WHERE project_id = ?",
                    "DELETE FROM project_revocation_epoch WHERE project_id = ?",
                    "DELETE FROM instruction_adoptions WHERE project_id = ?",
                    "DELETE FROM mcp_operation_adoptions WHERE project_id = ?",

                    // 9. Project row itself
                    "DELETE FROM projects WHERE id = ?",
                )

                for (sql in statements) {
                    executeWithRepeatedProjectId(sql, pid)
                }

                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun executeWithRepeatedProjectId(sql: String, projectId: String) {
        val paramCount = sql.count { it == '?' }
        driver.execute(identifier = null, sql = sql, parameters = paramCount) {
            for (i in 0 until paramCount) {
                bindString(i, projectId)
            }
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
            """.trimIndent(),
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
