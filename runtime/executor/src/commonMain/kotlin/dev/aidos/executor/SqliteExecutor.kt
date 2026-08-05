package dev.aidos.executor

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.broker.AuditLog
import dev.aidos.kernel.AidosError
import dev.aidos.kernel.BackoffStrategy
import dev.aidos.kernel.ErrorClass
import dev.aidos.kernel.EventId
import dev.aidos.kernel.Executor
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RecoveryReport
import dev.aidos.kernel.RetryPolicy
import dev.aidos.kernel.RunId
import dev.aidos.kernel.RunState
import dev.aidos.kernel.SessionId
import dev.aidos.kernel.Task
import dev.aidos.kernel.TaskId
import dev.aidos.kernel.TaskKind
import dev.aidos.kernel.TaskState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * SQLite-backed executor (RFC-0009, M5).
 *
 * `drive()` is re-entrant and idempotent — calling it on a complete Run is a no-op; calling it
 * after a crash resumes exactly where the rows say execution was. There is no in-memory state
 * that must survive a restart.
 *
 * Concurrency invariant (D14): at most one effectful Task is RUNNING per Run. Read tasks
 * (EffectKind.Read) may overlap. This is enforced in [nextRunnableTasks] before any task
 * is dispatched — no lock required because the invariant is structural: the executor checks
 * the DB before dispatching, and SQLite serialises all writes.
 *
 * For MVP, "execution" means running hard-coded Tasks supplied at Run creation time. The model
 * call loop (RFC-0020) is out of scope until M9.
 */
class SqliteExecutor(
    private val driver: SqlDriver,
    private val audit: AuditLog,
    private val events: EventStore,
    private val idGen: () -> String,
    private val nowIso: () -> String,
    /** Pluggable task runner — real tools in integration, hard-coded tasks in tests. */
    private val taskRunner: TaskRunner,
) : Executor {

    /**
     * Drives a Run to completion.
     *
     * Re-entrant: a Run in a terminal state is returned immediately without touching the DB.
     * A Run in PENDING is moved to RUNNING. Then we loop: find runnable tasks, execute them,
     * record their outcomes, advance the step index, and repeat until no tasks remain or the
     * Run is terminal.
     */
    override suspend fun drive(runId: RunId) {
        val run = loadRun(runId) ?: error("Run ${runId.value} not found")

        // Re-entrancy: already terminal → no-op.
        if (run.state.isTerminal) return

        // Advance PENDING or INTERRUPTED → RUNNING.
        if (run.state == RunState.PENDING || run.state == RunState.INTERRUPTED) {
            updateRunState(runId, RunState.RUNNING)
        }

        // Step loop.
        var stepIndex = run.stepIndex
        while (true) {
            val currentRun = loadRun(runId) ?: break
            if (currentRun.state.isTerminal) break
            if (stepIndex >= currentRun.maxSteps) {
                // Budget ceiling: terminate with FAILED.
                updateRunState(runId, RunState.FAILED,
                    AidosError("run.max_steps_exceeded", ErrorClass.EXHAUSTED,
                        "Run exceeded max steps (${currentRun.maxSteps})"))
                break
            }

            val runnables = nextRunnableTasks(runId)
            if (runnables.isEmpty()) {
                // No pending tasks → Run is done.
                val allDone = allTasksTerminal(runId)
                updateRunState(runId, if (allDone) RunState.COMPLETED else RunState.YIELDED)
                break
            }

            // Execute each runnable.
            for (task in runnables) {
                stepIndex++
                updateTaskState(task.id, TaskState.RUNNING)
                incrementStepIndex(runId, stepIndex)

                val result = taskRunner.execute(task)
                val newState = if (result.success) TaskState.COMPLETED else TaskState.FAILED
                updateTaskState(task.id, newState)

                // Write audit row for this task execution.
                val auditId = idGen()
                writeAuditRow(auditId, currentRun.projectId.value, task, result)

                // Publish RunStepCompleted event with causality.
                events.publish(
                    id = idGen(),
                    projectId = currentRun.projectId.value,
                    type = "RunStepCompleted",
                    source = "executor",
                    payload = buildJsonObject {
                        put("run_id", runId.value)
                        put("task_id", task.id.value)
                        put("step_index", stepIndex)
                        put("state", newState.name)
                    }.toString(),
                    causedBy = currentRun.triggerEventId.value,
                    causalDepth = 1,
                    nowIso = nowIso(),
                )

                if (!result.success) {
                    // Task failed — fail the Run.
                    updateRunState(runId, RunState.FAILED,
                        AidosError("task.failed", ErrorClass.TRANSIENT, "Task ${task.id.value} failed: ${result.errorMessage}"))
                    return
                }
            }
        }
    }

    /**
     * Returns the next set of runnable tasks for a Run.
     *
     * Concurrency rule (D14): if any effectful task is RUNNING, returns empty. Read-only
     * tasks may run concurrently, so multiple Read tasks may be returned together.
     */
    override suspend fun nextRunnableTasks(runId: RunId): List<Task> {
        val effectfulRunning = countEffectfulRunning(runId)
        if (effectfulRunning > 0) return emptyList()

        return pendingTasksFor(runId)
    }

    override suspend fun recover(projectId: ProjectId): RecoveryReport {
        // Find all RUNNING attempts for this project.
        val runningAttempts = runningAttemptsForProject(projectId.value)

        var runsResumed = 0
        var runsFailed = 0
        val indeterminateEffects = mutableListOf<dev.aidos.kernel.AttemptId>()
        var reservationsReleased = 0

        for (attempt in runningAttempts) {
            when (attempt.recoveryClass) {
                "UNSAFE" -> {
                    // Never retried. Reported as INDETERMINATE. (RFC-0009, RFC-0029)
                    markAttemptFailed(
                        attemptId = attempt.id,
                        errorCode = "effect.indeterminate",
                        errorClass = ErrorClass.INDETERMINATE.name,
                        errorDetail = "{\"recovery_class\":\"UNSAFE\"}",
                    )
                    indeterminateEffects.add(dev.aidos.kernel.AttemptId(attempt.id))
                }
                "CHECKABLE" -> {
                    // Probe then re-execute — for MVP, mark failed so the run can be retried.
                    markAttemptFailed(
                        attemptId = attempt.id,
                        errorCode = "effect.checkable_unresolved",
                        errorClass = ErrorClass.INDETERMINATE.name,
                        errorDetail = "{\"recovery_class\":\"CHECKABLE\"}",
                    )
                }
                else -> {
                    // PURE or IDEMPOTENT — safe to re-execute. Reset task to PENDING.
                    markAttemptFailed(
                        attemptId = attempt.id,
                        errorCode = "effect.interrupted",
                        errorClass = ErrorClass.TRANSIENT.name,
                        errorDetail = "{\"recovery_class\":\"${attempt.recoveryClass}\"}",
                    )
                    resetTaskToPending(attempt.taskId)
                    runsResumed++
                }
            }
        }

        // Mark all RUNNING runs as INTERRUPTED.
        val interruptedRuns = interruptRunningRuns(projectId.value)
        runsFailed = interruptedRuns

        // Reset any tasks that are RUNNING but have no RUNNING attempt (crashed between
        // task state update and attempt insert). These are PURE by default — re-execute.
        resetOrphanRunningTasks(projectId.value)

        return RecoveryReport(
            runsExamined = runningAttempts.size,
            runsResumed = runsResumed,
            runsFailed = runsFailed,
            indeterminateEffects = indeterminateEffects,
            reservationsReleased = reservationsReleased,
        )
    }

    // ─── Internal helpers ────────────────────────────────────────────────────

    private fun loadRun(runId: RunId): RunSnapshot? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT state, step_index, max_steps, project_id, trigger_event_id " +
                "FROM runs WHERE id = ?",
            mapper = { c ->
                QueryResult.Value(
                    if (c.next().value) RunSnapshot(
                        state = RunState.valueOf(c.getString(0)!!),
                        stepIndex = c.getLong(1)?.toInt() ?: 0,
                        maxSteps = c.getLong(2)?.toInt() ?: 24,
                        projectId = ProjectId(c.getString(3)!!),
                        triggerEventId = EventId(c.getString(4)!!),
                    ) else null
                )
            },
            parameters = 1,
        ) { bindString(0, runId.value) }.value

    private fun updateRunState(runId: RunId, state: RunState, error: AidosError? = null) {
        val isTerminal = state.isTerminal
        driver.execute(
            identifier = null,
            sql = "UPDATE runs SET state = ?, " +
                "ended_at = CASE WHEN ? = 1 THEN ? ELSE ended_at END, " +
                "error_code = ?, error_class = ?, error_detail_json = ? " +
                "WHERE id = ?",
            parameters = 7,
        ) {
            bindString(0, state.name)
            bindLong(1, if (isTerminal) 1L else 0L)
            bindString(2, if (isTerminal) nowIso() else null)
            bindString(3, error?.code)
            bindString(4, error?.errorClass?.name)
            bindString(5, error?.detail?.toString())
            bindString(6, runId.value)
        }
    }

    private fun updateTaskState(taskId: TaskId, state: TaskState) {
        val isTerminal = state.isTerminal
        driver.execute(
            identifier = null,
            sql = "UPDATE tasks SET state = ?, " +
                "started_at = CASE WHEN state = 'PENDING' AND ? = 0 THEN ? ELSE started_at END, " +
                "ended_at = CASE WHEN ? = 1 THEN ? ELSE ended_at END " +
                "WHERE id = ?",
            parameters = 6,
        ) {
            bindString(0, state.name)
            bindLong(1, if (isTerminal) 1L else 0L)
            bindString(2, nowIso())
            bindLong(3, if (isTerminal) 1L else 0L)
            bindString(4, if (isTerminal) nowIso() else null)
            bindString(5, taskId.value)
        }
    }

    private fun incrementStepIndex(runId: RunId, newIndex: Int) {
        driver.execute(
            identifier = null,
            sql = "UPDATE runs SET step_index = ? WHERE id = ?",
            parameters = 2,
        ) {
            bindLong(0, newIndex.toLong())
            bindString(1, runId.value)
        }
    }

    private fun countEffectfulRunning(runId: RunId): Long =
        driver.executeQuery(
            identifier = null,
            // COMPOSITE and TOOL_CALL tasks may have Write/Egress effects; MODEL_CALL is Read.
            // For MVP: TOOL_CALL counts as effectful. MODEL_CALL does not.
            sql = "SELECT COUNT(*) FROM tasks WHERE run_id = ? AND state = 'RUNNING' AND kind != 'MODEL_CALL'",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) },
            parameters = 1,
        ) { bindString(0, runId.value) }.value

    private fun pendingTasksFor(runId: RunId): List<Task> {
        val rows = mutableListOf<Task>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT t.id, t.run_id, t.session_id, r.project_id, t.ordinal, t.kind, t.description, " +
                "t.tool_name, t.state FROM tasks t JOIN runs r ON r.id = t.run_id " +
                "WHERE t.run_id = ? AND t.state = 'PENDING' ORDER BY t.ordinal LIMIT 1",
            mapper = { cursor ->
                while (cursor.next().value) {
                    rows.add(
                        Task(
                            id = TaskId(cursor.getString(0)!!),
                            runId = RunId(cursor.getString(1)!!),
                            planId = null,
                            sessionId = SessionId(cursor.getString(2)!!),
                            projectId = ProjectId(cursor.getString(3)!!),
                            ordinal = cursor.getLong(4)?.toInt() ?: 0,
                            kind = TaskKind.valueOf(cursor.getString(5)!!),
                            description = cursor.getString(6)!!,
                            toolName = cursor.getString(7),
                            modelKind = null,
                            state = TaskState.valueOf(cursor.getString(8)!!),
                            startedAt = null,
                            endedAt = null,
                            awaitingRunId = null,
                            retryPolicy = RetryPolicy(
                                maxAttempts = 1,
                                retryOn = emptySet(),
                                backoff = BackoffStrategy.None,
                            ),
                        )
                    )
                }
                QueryResult.Value(Unit)
            },
            parameters = 1,
        ) { bindString(0, runId.value) }
        return rows
    }

    private fun allTasksTerminal(runId: RunId): Boolean {
        val nonTerminalCount = driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM tasks WHERE run_id = ? AND state NOT IN ('COMPLETED','FAILED','CANCELLED','SKIPPED')",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) },
            parameters = 1,
        ) { bindString(0, runId.value) }.value
        return nonTerminalCount == 0L
    }

    private fun writeAuditRow(auditId: String, projectId: String, task: Task, result: TaskResult) {
        val kind = if (result.success) "ToolCompleted" else "ToolFailed"
        audit.write(
            id = auditId,
            projectId = projectId,
            kind = kind,
            actorKind = "SESSION",
            actorId = task.sessionId.value,
            subjectRef = task.toolName ?: task.kind.name,
            nowIso = nowIso(),
        )
    }

    // ─── Recovery helpers (M6) ────────────────────────────────────────────────

    private data class AttemptSnapshot(
        val id: String,
        val taskId: String,
        val recoveryClass: String,
    )

    private fun runningAttemptsForProject(projectId: String): List<AttemptSnapshot> {
        val rows = mutableListOf<AttemptSnapshot>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT a.id, a.task_id, a.recovery_class FROM attempts a " +
                "JOIN tasks t ON t.id = a.task_id " +
                "JOIN runs r ON r.id = t.run_id " +
                "WHERE a.state = 'RUNNING' AND r.project_id = ?",
            mapper = { c ->
                while (c.next().value) {
                    rows.add(AttemptSnapshot(c.getString(0)!!, c.getString(1)!!, c.getString(2)!!))
                }
                QueryResult.Value(Unit)
            },
            parameters = 1,
        ) { bindString(0, projectId) }
        return rows
    }

    private fun markAttemptFailed(
        attemptId: String,
        errorCode: String,
        errorClass: String,
        errorDetail: String,
    ) {
        driver.execute(
            identifier = null,
            sql = "UPDATE attempts SET state = 'FAILED', ended_at = ?, error_code = ?, " +
                "error_class = ?, error_detail_json = ? WHERE id = ?",
            parameters = 5,
        ) {
            bindString(0, nowIso())
            bindString(1, errorCode)
            bindString(2, errorClass)
            bindString(3, errorDetail)
            bindString(4, attemptId)
        }
    }

    private fun resetTaskToPending(taskId: String) {
        driver.execute(
            identifier = null,
            sql = "UPDATE tasks SET state = 'PENDING', started_at = NULL WHERE id = ?",
            parameters = 1,
        ) { bindString(0, taskId) }
    }

    /** Sets all RUNNING runs for a project to INTERRUPTED. Returns count affected. */
    private fun interruptRunningRuns(projectId: String): Int {
        // Count before updating
        val count = driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM runs WHERE project_id = ? AND state = 'RUNNING'",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getLong(0)?.toInt() ?: 0 else 0) },
            parameters = 1,
        ) { bindString(0, projectId) }.value

        driver.execute(
            identifier = null,
            sql = "UPDATE runs SET state = 'INTERRUPTED', ended_at = ? WHERE project_id = ? AND state = 'RUNNING'",
            parameters = 2,
        ) {
            bindString(0, nowIso())
            bindString(1, projectId)
        }
        return count
    }

    /**
     * Resets tasks that are stuck in RUNNING with no corresponding RUNNING attempt.
     * This occurs when a crash happens between `updateTaskState(RUNNING)` and the attempt insert.
     * Such tasks are safe to re-run (no external effect was recorded), so reset to PENDING.
     */
    private fun resetOrphanRunningTasks(projectId: String) {
        driver.execute(
            identifier = null,
            sql = "UPDATE tasks SET state = 'PENDING', started_at = NULL " +
                "WHERE state = 'RUNNING' AND project_id = ? " +
                "AND NOT EXISTS (SELECT 1 FROM attempts a WHERE a.task_id = tasks.id AND a.state = 'RUNNING')",
            parameters = 1,
        ) { bindString(0, projectId) }
    }
}

private data class RunSnapshot(
    val state: RunState,
    val stepIndex: Int,
    val maxSteps: Int,
    val projectId: ProjectId,
    val triggerEventId: EventId,
)

/** Result of running a single task. */
data class TaskResult(
    val success: Boolean,
    val errorMessage: String? = null,
)

/** Pluggable task runner — replaced by a stub in tests. */
interface TaskRunner {
    suspend fun execute(task: Task): TaskResult
}
