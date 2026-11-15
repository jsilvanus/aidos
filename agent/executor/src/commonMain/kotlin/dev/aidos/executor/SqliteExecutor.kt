package dev.aidos.executor

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.broker.AuditLog
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ActorRef
import dev.aidos.kernel.AidosError
import dev.aidos.kernel.BackoffStrategy
import dev.aidos.kernel.BudgetLedger
import dev.aidos.kernel.BudgetScope
import dev.aidos.kernel.CapabilityManager
import dev.aidos.kernel.ErrorClass
import dev.aidos.kernel.ErrorCodes
import dev.aidos.kernel.EventId
import dev.aidos.kernel.ExecutionWindow
import dev.aidos.kernel.Executor
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RecoveryClass
import dev.aidos.kernel.RecoveryReport
import dev.aidos.kernel.ReservationId
import dev.aidos.kernel.RetryPolicy
import dev.aidos.kernel.RunId
import dev.aidos.kernel.RunState
import dev.aidos.kernel.SessionId
import dev.aidos.kernel.SuspendedOperation
import dev.aidos.kernel.Task
import dev.aidos.kernel.TaskId
import dev.aidos.kernel.TaskKind
import dev.aidos.kernel.TaskState
import dev.aidos.storage.RowVersionCas
import dev.aidos.storage.StateModelRecovery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    /** RFC-0053's before-a-Run-starts gate. Unset preserves the pre-reconciliation behavior. */
    private val reconciler: RunReconciler? = null,
    /** RFC-0011's worker spawner for COMPOSITE tasks. */
    private val workerSpawner: WorkerSpawner? = null,
    /** RFC-0011's worker committer seam. */
    private val workerCommitter: WorkerCommitter? = null,
    /** RFC-0028's budget ledger for production budget enforcement. */
    private val budgetLedger: BudgetLedger? = null,
    /** RFC-0009's deadline budget window. Null means unbounded (desktop/tests). */
    private val executionWindow: ExecutionWindow? = null,
    /** Minimum remaining window (ms) required to start a step (RFC-0009). */
    private val estimatedStepMillis: Long = 1_000L,
    /** RFC-0011: capability manager for recursive revocation on orphan worker cancellation. */
    private val capabilityManager: CapabilityManager? = null,
) : Executor {

    private val cancelledRuns = mutableSetOf<String>()

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

        // RFC-0053: "Reconciliation runs before any Run may start on a repository with a
        // mismatched fingerprint." A mismatch can terminate Runs other than this one too (a
        // project-wide fact, not specific to runId) — those just find themselves already
        // terminal on their own next drive() call, which is already a no-op per the guard above.
        if (run.state == RunState.PENDING || run.state == RunState.INTERRUPTED) {
            val terminated = reconciler?.reconcileBeforeRun(driver, run.projectId, runId) ?: emptySet()
            if (runId in terminated) return
        }

        // Advance PENDING or INTERRUPTED → RUNNING.
        if (run.state == RunState.PENDING || run.state == RunState.INTERRUPTED) {
            updateRunState(runId, RunState.RUNNING)
        }

        // Step loop.
        var stepIndex = run.stepIndex
        while (true) {
            val currentRun = loadRun(runId) ?: break
            if (currentRun.state.isTerminal) break
            if (isCancelRequested(runId)) {
                finalizeCancellation(runId, currentRun.projectId)
                break
            }
            val remainingMs = executionWindow?.remainingMillis()
            if (remainingMs != null && remainingMs < estimatedStepMillis) {
                // RFC-0009 deadline budget: stop cleanly at a checkpoint without starting work
                // that cannot finish in the remaining window.
                return
            }
            if (stepIndex >= currentRun.maxSteps) {
                // Budget ceiling: terminate with FAILED.
                updateRunState(runId, RunState.FAILED,
                    AidosError(ErrorCodes.RUN_STEP_LIMIT, ErrorClass.EXHAUSTED,
                        "Run exceeded max steps (${currentRun.maxSteps})"))
                resumeAwaitingParent(runId)
                break
            }

            val runnables = nextRunnableTasks(runId)
            if (runnables.isEmpty()) {
                // No pending tasks → Run is done.
                val allDone = allTasksTerminal(runId)
                updateRunState(runId, if (allDone) RunState.COMPLETED else RunState.YIELDED)
                if (allDone) {
                    resumeAwaitingParent(runId)
                }
                break
            }

            // Execute each runnable.
            for (task in runnables) {
                val exhausted = checkBudgetExhausted(runId, currentRun.sessionId, currentRun.projectId, task.kind)
                if (exhausted != null) {
                    updateRunState(
                        runId,
                        RunState.FAILED,
                        AidosError("budget.exhausted", ErrorClass.EXHAUSTED, "Budget exhausted for $exhausted"),
                    )
                    resumeAwaitingParent(runId)
                    return
                }

                stepIndex++
                val (intentAttemptId, attemptNumber) = recordTaskIntent(
                    task = task,
                    runId = runId,
                    projectId = currentRun.projectId,
                    newStepIndex = stepIndex,
                )

                val result = if (task.kind == TaskKind.COMPOSITE && workerSpawner != null) {
                    workerSpawner.spawn(task)
                } else {
                    taskRunner.execute(task)
                }

                if (isCancelRequested(runId)) {
                    markAttemptCancelledIfRunning(intentAttemptId)
                    updateTaskState(task.id, TaskState.CANCELLED)
                    finalizeCancellation(runId, currentRun.projectId)
                    return
                }

                // RFC-0008 step 8d: park rather than complete/fail.
                if (result.park != null) {
                    val park = result.park
                    inTransaction {
                        driver.execute(
                            identifier = null,
                            sql = "DELETE FROM attempts WHERE id = ? AND state = 'RUNNING'",
                            parameters = 1,
                        ) { bindString(0, intentAttemptId) }
                        updateTaskState(task.id, park.taskState)
                        writeContinuation(runId, task.id, park)
                    }
                    updateRunState(runId, RunState.YIELDED)

                    val auditId = idGen()
                    val actor = resolveSessionActor(task.sessionId.value)
                    audit.write(
                        id = auditId,
                        projectId = currentRun.projectId.value,
                        kind = EventTypes.PERMISSION_REQUESTED,
                        actor = actor,
                        subjectRef = task.toolName ?: task.kind.name,
                        detailJson = park.escalationMessage?.let {
                            buildJsonObject { put("reason", "taint_escalation"); put("message", it) }.toString()
                        } ?: "{}",
                        nowIso = nowIso(),
                    )
                    if (park.escalationMessage != null) {
                        // RFC-0027 "Escalation": a CapabilityRequested event carrying the taint reason.
                        events.publish(
                            id = idGen(),
                            projectId = currentRun.projectId.value,
                            type = "CapabilityRequested",
                            source = "executor",
                            payload = buildJsonObject {
                                put("run_id", runId.value)
                                put("task_id", task.id.value)
                                put("reason", "taint_escalation")
                                put("message", park.escalationMessage)
                            }.toString(),
                            causedBy = currentRun.triggerEventId.value,
                            causalDepth = 1,
                            nowIso = nowIso(),
                        )
                    }
                    events.publish(
                        id = idGen(),
                        projectId = currentRun.projectId.value,
                        type = "RunStepCompleted",
                        source = "executor",
                        payload = buildJsonObject {
                            put("run_id", runId.value)
                            put("task_id", task.id.value)
                            put("step_index", stepIndex)
                            put("state", park.taskState.name)
                        }.toString(),
                        causedBy = currentRun.triggerEventId.value,
                        causalDepth = 1,
                        nowIso = nowIso(),
                    )

                    if (park.suspendedOperation is SuspendedOperation.ChildRun) {
                        // RFC-0011: Drive the child Run synchronously on the same executor instance.
                        drive(park.suspendedOperation.childRunId)
                    }
                    return
                }

                val error = if (result.success) {
                    null
                } else {
                    result.error ?: AidosError(
                        "task.failed",
                        ErrorClass.TRANSIENT,
                        "Task ${task.id.value} failed: ${result.errorMessage}",
                    )
                }
                val willRetry = !result.success &&
                    error != null &&
                    task.retryPolicy.permits(error, result.recoveryClass, attemptNumber)

                val newState = when {
                    result.success -> TaskState.COMPLETED
                    willRetry -> TaskState.PENDING
                    else -> TaskState.FAILED
                }

                inTransaction {
                    if (result.success) {
                        markAttemptCompletedIfRunning(intentAttemptId)
                    } else {
                        markAttemptFailedIfRunning(
                            attemptId = intentAttemptId,
                            errorCode = error!!.code,
                            errorClass = error.errorClass.name,
                            errorDetail = """{"message":${json.encodeToString(kotlinx.serialization.serializer(), error.message)}}""",
                        )
                    }
                    if (willRetry) {
                        resetTaskToPending(task.id.value)
                    } else {
                        updateTaskState(task.id, newState)
                    }
                    if (result.success && result.appendTasks.isNotEmpty()) {
                        appendTasks(runId, currentRun, task.ordinal, result.appendTasks)
                    }
                }

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

                if (result.success) {
                    val operation = task.toolName ?: task.kind.name
                    events.publish(
                        id = idGen(),
                        projectId = currentRun.projectId.value,
                        type = EventTypes.TOOL_COMPLETED,
                        category = "FACT",
                        source = "tool:$operation",
                        topic = "tool:$operation:${task.id.value}",
                        payload = buildJsonObject {
                            put("run_id", runId.value)
                            put("task_id", task.id.value)
                            put("operation", operation)
                        }.toString(),
                        causedBy = currentRun.triggerEventId.value,
                        causalDepth = 1,
                        nowIso = nowIso(),
                    )
                }

                if (!result.success && !willRetry) {
                    // Task failed unrecoverably (or retries exhausted) — fail the Run.
                    updateRunState(runId, RunState.FAILED, error)
                    resumeAwaitingParent(runId)
                    return
                }
            }
        }
    }

    /**
     * Cooperative Run cancellation (RFC-0006 "Cancellation").
     *
     * Sets the cancellation flag for an actively-driven Run and immediately transitions
     * `PENDING` or `YIELDED` Runs to `CANCELLED`, marking parked tasks `CANCELLED` and unstarted
     * `PENDING` tasks `SKIPPED`.
     */
    suspend fun cancelRun(
        runId: RunId,
        reason: String? = null,
        actor: ActorRef = ActorRef(ActorKind.USER, "user"),
    ): Boolean {
        val run = loadRun(runId) ?: return false
        if (run.state.isTerminal) return false
        cancelledRuns.add(runId.value)
        if (run.state != RunState.RUNNING) {
            finalizeCancellation(runId, run.projectId, reason, actor)
        }
        return true
    }

    private fun isCancelRequested(runId: RunId): Boolean =
        runId.value in cancelledRuns || loadRun(runId)?.state == RunState.CANCELLED

    private suspend fun finalizeCancellation(
        runId: RunId,
        projectId: ProjectId,
        reason: String? = null,
        actor: ActorRef = ActorRef(ActorKind.USER, "user"),
    ) {
        val now = nowIso()
        inTransaction {
            driver.execute(
                identifier = null,
                sql = "UPDATE tasks SET state = 'CANCELLED', ended_at = ?, row_version = row_version + 1 " +
                    "WHERE run_id = ? AND state IN ('AWAITING_APPROVAL', 'AWAITING_INPUT')",
                parameters = 2,
            ) { bindString(0, now); bindString(1, runId.value) }
            driver.execute(
                identifier = null,
                sql = "UPDATE tasks SET state = 'SKIPPED', ended_at = ?, row_version = row_version + 1 " +
                    "WHERE run_id = ? AND state = 'PENDING'",
                parameters = 2,
            ) { bindString(0, now); bindString(1, runId.value) }
            deleteContinuation(runId)
        }
        updateRunState(runId, RunState.CANCELLED)
        audit.write(
            id = idGen(),
            projectId = projectId.value,
            kind = "RunCancelled",
            actor = actor,
            subjectRef = runId.value,
            detailJson = reason?.let { buildJsonObject { put("reason", it) }.toString() } ?: "{}",
            nowIso = now,
        )
        val cancelledRun = loadRun(runId)
        if (cancelledRun != null) {
            cancelOrphanedWorkers(cancelledRun.sessionId.value)
        }
        resumeAwaitingParent(runId)
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
        // RFC-0017: run open-time state-model recovery pass before session/run recovery.
        StateModelRecovery.recoverOnOpen(driver, nowIso, idGen)

        // Find all RUNNING attempts for this project.
        val runningAttempts = runningAttemptsForProject(projectId.value)

        var runsResumed = 0
        var runsFailed = 0
        val indeterminateEffects = mutableListOf<dev.aidos.kernel.AttemptId>()
        var reservationsReleased = 0

        for (attempt in runningAttempts) {
            if (budgetLedger != null) {
                for (resId in activeReservationsForAttempt(attempt.id)) {
                    budgetLedger.release(ReservationId(resId))
                    reservationsReleased++
                }
            }
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

        if (runningAttempts.isNotEmpty() || interruptedRuns > 0) {
            val auditId = idGen()
            audit.write(
                id = auditId,
                projectId = projectId.value,
                kind = "CrashRecoveryExecuted",
                actor = ActorRef(ActorKind.RUNTIME, "crash-recovery"),
                detailJson = buildJsonObject {
                    put("runs_examined", runningAttempts.size)
                    put("runs_resumed", runsResumed)
                    put("runs_interrupted", interruptedRuns)
                }.toString(),
                nowIso = nowIso(),
            )
        }

        return RecoveryReport(
            runsExamined = runningAttempts.size,
            runsResumed = runsResumed,
            runsFailed = runsFailed,
            indeterminateEffects = indeterminateEffects,
            reservationsReleased = reservationsReleased,
        )
    }

    private suspend fun checkBudgetExhausted(
        runId: RunId,
        sessionId: SessionId,
        projectId: ProjectId,
        taskKind: TaskKind,
    ): String? {
        val ledger = budgetLedger ?: return null
        val scopes = listOf(
            BudgetScope.RUN to runId.value,
            BudgetScope.SESSION to sessionId.value,
            BudgetScope.PROJECT to projectId.value,
        )
        for ((scope, scopeId) in scopes) {
            val rem = ledger.remaining(scope, scopeId) ?: continue
            val remSteps = rem.steps
            val remCostUnits = rem.costUnits
            val remInputTokens = rem.inputTokens
            val remOutputTokens = rem.outputTokens
            val remModelCalls = rem.modelCalls
            val remToolInvocations = rem.toolInvocations
            val exhaustedDim = when {
                remSteps != null && remSteps <= 0 -> "steps"
                remCostUnits != null && remCostUnits <= 0L -> "cost_units"
                remInputTokens != null && remInputTokens <= 0L -> "input_tokens"
                remOutputTokens != null && remOutputTokens <= 0L -> "output_tokens"
                taskKind == TaskKind.MODEL_CALL && remModelCalls != null && remModelCalls <= 0 -> "model_calls"
                taskKind == TaskKind.TOOL_CALL && remToolInvocations != null && remToolInvocations <= 0 -> "tool_invocations"
                else -> null
            }
            if (exhaustedDim != null) {
                return "${scope.name}:$scopeId ($exhaustedDim)"
            }
        }
        return null
    }

    private fun activeReservationsForAttempt(attemptId: String): List<String> {
        val ids = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT id, reserved_json FROM budget_reservations WHERE attempt_id = ?",
            mapper = { c ->
                while (c.next().value) {
                    val id = c.getString(0) ?: continue
                    val raw = c.getString(1) ?: continue
                    val status = runCatching {
                        Json.parseToJsonElement(raw).jsonObject["status"]?.toString()?.trim('"')
                    }.getOrNull()
                    if (status == null || status == "RESERVED") {
                        ids.add(id)
                    }
                }
                QueryResult.Value(Unit)
            },
            parameters = 1,
        ) { bindString(0, attemptId) }
        return ids
    }

    private fun resolveSessionActor(sessionId: String): ActorRef {
        val role = driver.executeQuery(
            identifier = null,
            sql = "SELECT role FROM sessions WHERE id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) },
            parameters = 1,
        ) { bindString(0, sessionId) }.value
        val kind = if (role.equals("WORKER", ignoreCase = true) || sessionId.startsWith("worker")) {
            ActorKind.WORKER
        } else {
            ActorKind.SESSION
        }
        return ActorRef(kind, sessionId)
    }

    // ─── Internal helpers ────────────────────────────────────────────────────

    private fun loadRun(runId: RunId): RunSnapshot? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT state, step_index, max_steps, project_id, trigger_event_id, session_id " +
                "FROM runs WHERE id = ?",
            mapper = { c ->
                QueryResult.Value(
                    if (c.next().value) RunSnapshot(
                        state = RunState.valueOf(c.getString(0)!!),
                        stepIndex = c.getLong(1)?.toInt() ?: 0,
                        maxSteps = c.getLong(2)?.toInt() ?: 24,
                        projectId = ProjectId(c.getString(3)!!),
                        triggerEventId = EventId(c.getString(4)!!),
                        sessionId = SessionId(c.getString(5)!!),
                    ) else null
                )
            },
            parameters = 1,
        ) { bindString(0, runId.value) }.value

    private fun updateRunState(runId: RunId, state: RunState, error: AidosError? = null) {
        val isTerminal = state.isTerminal
        RowVersionCas.withRetry {
            val rowVer = RowVersionCas.readRowVersion(driver, "runs", runId.value) ?: return@withRetry true
            RowVersionCas.updateWithCas(
                driver = driver,
                table = "runs",
                id = runId.value,
                expectedRowVersion = rowVer,
                setClause = "state = ?, ended_at = CASE WHEN ? = 1 THEN ? ELSE ended_at END, " +
                    "error_code = ?, error_class = ?, error_detail_json = ?",
                setParameterCount = 6,
            ) {
                bindString(0, state.name)
                bindLong(1, if (isTerminal) 1L else 0L)
                bindString(2, if (isTerminal) nowIso() else null)
                bindString(3, error?.code)
                bindString(4, error?.errorClass?.name)
                bindString(5, error?.detail?.toString())
            }
        }
        if (isTerminal) {
            val sessionId = driver.executeQuery(
                identifier = null,
                sql = "SELECT session_id FROM runs WHERE id = ?",
                mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) },
                parameters = 1,
            ) { bindString(0, runId.value) }.value
            if (sessionId != null) {
                updateSessionFailureBudget(sessionId, state)
                cancelOrphanedWorkerRunsSync(sessionId)
            }
        }
    }

    /** RFC-0011: Track consecutive FAILED Runs; 3 consecutive failures parks the session in SLEEPING. */
    private fun updateSessionFailureBudget(sessionId: String, state: RunState) {
        val now = nowIso()
        when (state) {
            RunState.FAILED -> {
                driver.execute(
                    identifier = null,
                    sql = "UPDATE sessions SET consecutive_failures = consecutive_failures + 1, " +
                        "state_updated_at = ? WHERE id = ?",
                    parameters = 2,
                ) {
                    bindString(0, now)
                    bindString(1, sessionId)
                }
                driver.execute(
                    identifier = null,
                    sql = "UPDATE sessions SET state = 'SLEEPING', state_updated_at = ?, " +
                        "row_version = row_version + 1 " +
                        "WHERE id = ? AND consecutive_failures >= 3 AND state = 'RUNNING'",
                    parameters = 2,
                ) {
                    bindString(0, now)
                    bindString(1, sessionId)
                }
            }
            RunState.COMPLETED -> {
                driver.execute(
                    identifier = null,
                    sql = "UPDATE sessions SET consecutive_failures = 0, state_updated_at = ? WHERE id = ?",
                    parameters = 2,
                ) {
                    bindString(0, now)
                    bindString(1, sessionId)
                }
            }
            else -> {}
        }
    }

    /**
     * RFC-0011: When a driver session reaches a terminal state (Run terminated or session archived),
     * every worker whose `parent_session_id` is that driver and whose Run is non-terminal is
     * cancelled with `run.parent_terminated`, and delegated capabilities are revoked recursively.
     */
    suspend fun cancelOrphanedWorkers(driverSessionId: String) {
        val workerIds = childWorkerSessionIds(driverSessionId)
        cancelOrphanedWorkerRunsSync(driverSessionId)
        for (workerId in workerIds) {
            if (capabilityManager != null) {
                val activeCaps = capabilityManager.loadForSubject(workerId).filter { it.revokedAt == null }
                for (cap in activeCaps) {
                    capabilityManager.revoke(cap.id, "parent_terminated:$driverSessionId")
                }
            }
            cancelOrphanedWorkers(workerId)
        }
    }

    private fun childWorkerSessionIds(driverSessionId: String): List<String> {
        val ids = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT id FROM sessions WHERE parent_session_id = ?",
            mapper = { c ->
                while (c.next().value) ids.add(c.getString(0)!!)
                QueryResult.Value(Unit)
            },
            parameters = 1,
        ) { bindString(0, driverSessionId) }
        return ids
    }

    private fun cancelOrphanedWorkerRunsSync(driverSessionId: String) {
        val workerIds = childWorkerSessionIds(driverSessionId)
        if (workerIds.isEmpty()) return
        val now = nowIso()
        for (workerId in workerIds) {
            val activeRuns = mutableListOf<Pair<String, String>>()
            driver.executeQuery(
                identifier = null,
                sql = "SELECT id, project_id FROM runs WHERE session_id = ? " +
                    "AND state IN ('PENDING', 'RUNNING', 'YIELDED')",
                mapper = { c ->
                    while (c.next().value) {
                        activeRuns.add(c.getString(0)!! to c.getString(1)!!)
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 1,
            ) { bindString(0, workerId) }

            for ((orphanRunId, projectId) in activeRuns) {
                cancelledRuns.add(orphanRunId)
                inTransactionSync {
                    driver.execute(
                        identifier = null,
                        sql = "UPDATE tasks SET state = 'CANCELLED', ended_at = ?, row_version = row_version + 1 " +
                            "WHERE run_id = ? AND state IN ('AWAITING_APPROVAL', 'AWAITING_INPUT')",
                        parameters = 2,
                    ) { bindString(0, now); bindString(1, orphanRunId) }
                    driver.execute(
                        identifier = null,
                        sql = "UPDATE tasks SET state = 'SKIPPED', ended_at = ?, row_version = row_version + 1 " +
                            "WHERE run_id = ? AND state = 'PENDING'",
                        parameters = 2,
                    ) { bindString(0, now); bindString(1, orphanRunId) }
                    driver.execute(
                        identifier = null,
                        sql = "DELETE FROM continuations WHERE run_id = ?",
                        parameters = 1,
                    ) { bindString(0, orphanRunId) }
                    driver.execute(
                        identifier = null,
                        sql = "UPDATE runs SET state = 'CANCELLED', ended_at = ?, " +
                            "error_code = ?, error_class = ?, row_version = row_version + 1 " +
                            "WHERE id = ? AND state IN ('PENDING', 'RUNNING', 'YIELDED')",
                        parameters = 4,
                    ) {
                        bindString(0, now)
                        bindString(1, ErrorCodes.RUN_PARENT_TERMINATED)
                        bindString(2, ErrorClass.EXHAUSTED.name)
                        bindString(3, orphanRunId)
                    }
                }
                audit.write(
                    id = idGen(),
                    projectId = projectId,
                    kind = "RunCancelled",
                    actor = ActorRef(ActorKind.RUNTIME, "orphan-canceller"),
                    subjectRef = orphanRunId,
                    detailJson = buildJsonObject {
                        put("reason", ErrorCodes.RUN_PARENT_TERMINATED)
                        put("parent_session_id", driverSessionId)
                    }.toString(),
                    nowIso = now,
                )
            }
            // Revoke active capabilities on the orphaned worker if not already revoked.
            driver.execute(
                identifier = null,
                sql = "UPDATE capabilities SET revoked_at = ?, revoked_by = ? " +
                    "WHERE subject_id = ? AND revoked_at IS NULL",
                parameters = 3,
            ) {
                bindString(0, now)
                bindString(1, "parent_terminated:$driverSessionId")
                bindString(2, workerId)
            }
            cancelOrphanedWorkerRunsSync(workerId)
        }
    }

    private fun inTransactionSync(block: () -> Unit) {
        object : app.cash.sqldelight.TransacterImpl(driver) {}.transaction {
            block()
        }
    }

    private fun recordApprovalChannel(
        taskId: TaskId,
        approvalChannel: String,
        approvalPhrase: String?,
    ) {
        driver.execute(
            identifier = null,
            sql = "UPDATE tasks SET approval_channel = ?, approval_phrase = ? WHERE id = ?",
            parameters = 3,
        ) {
            bindString(0, approvalChannel)
            bindString(1, approvalPhrase)
            bindString(2, taskId.value)
        }
    }

    private fun updateTaskState(taskId: TaskId, state: TaskState) {
        val isTerminal = state.isTerminal
        RowVersionCas.withRetry {
            val rowVer = RowVersionCas.readRowVersion(driver, "tasks", taskId.value) ?: return@withRetry true
            RowVersionCas.updateWithCas(
                driver = driver,
                table = "tasks",
                id = taskId.value,
                expectedRowVersion = rowVer,
                setClause = "state = ?, started_at = CASE WHEN state = 'PENDING' AND ? = 0 THEN ? ELSE started_at END, " +
                    "ended_at = CASE WHEN ? = 1 THEN ? ELSE ended_at END",
                setParameterCount = 5,
            ) {
                bindString(0, state.name)
                bindLong(1, if (isTerminal) 1L else 0L)
                bindString(2, nowIso())
                bindLong(3, if (isTerminal) 1L else 0L)
                bindString(4, if (isTerminal) nowIso() else null)
            }
        }
    }

    /**
     * RFC-0009: Writes the `INTENT` row (`attempts` in `RUNNING` state with `idempotency_key`)
     * atomically with `tasks.state = 'RUNNING'` and `runs.step_index` before any effect executes.
     * If a prior failed attempt exists for this task, also writes a `RETRY_OF` edge (RFC-0019).
     */
    private fun recordTaskIntent(
        task: Task,
        runId: RunId,
        projectId: ProjectId,
        newStepIndex: Int,
    ): Pair<String, Int> {
        val auditId = idGen()
        val actor = resolveSessionActor(task.sessionId.value)
        audit.write(
            id = auditId,
            projectId = projectId.value,
            kind = "TaskIntentRecorded",
            actor = actor,
            subjectRef = task.toolName ?: task.kind.name,
            nowIso = nowIso(),
        )
        val attemptId = idGen()
        var attemptNumber = 1
        inTransaction {
            updateTaskState(task.id, TaskState.RUNNING)
            incrementStepIndex(runId, newStepIndex)

            val priorAttempt = driver.executeQuery(
                identifier = null,
                sql = "SELECT id, attempt_number FROM attempts WHERE task_id = ? ORDER BY attempt_number DESC LIMIT 1",
                mapper = { c ->
                    QueryResult.Value(
                        if (c.next().value) c.getString(0)!! to (c.getLong(1)?.toInt() ?: 1) else null
                    )
                },
                parameters = 1,
            ) { bindString(0, task.id.value) }.value

            attemptNumber = (priorAttempt?.second ?: 0) + 1
            val idempotencyKey = IdempotencyKey.compute(
                taskId = task.id.value,
                attemptNumber = attemptNumber,
                canonicalArguments = task.toolName ?: task.kind.name,
            )

            driver.execute(
                identifier = null,
                sql = "INSERT INTO attempts (id, task_id, attempt_number, started_at, state, " +
                    "idempotency_key, recovery_class, audit_ref) VALUES (?, ?, ?, ?, 'RUNNING', ?, 'PURE', ?)",
                parameters = 7,
            ) {
                bindString(0, attemptId)
                bindString(1, task.id.value)
                bindLong(2, attemptNumber.toLong())
                bindString(3, nowIso())
                bindString(4, idempotencyKey)
                bindString(5, auditId)
            }

            if (priorAttempt != null) {
                writeRetryOfEdge(projectId, attemptId, priorAttempt.first)
            }
        }
        return attemptId to attemptNumber
    }

    private fun markAttemptCompletedIfRunning(attemptId: String) {
        driver.execute(
            identifier = null,
            sql = "UPDATE attempts SET state = 'COMPLETED', ended_at = ? WHERE id = ? AND state = 'RUNNING'",
            parameters = 2,
        ) {
            bindString(0, nowIso())
            bindString(1, attemptId)
        }
    }

    private fun markAttemptFailedIfRunning(
        attemptId: String,
        errorCode: String,
        errorClass: String,
        errorDetail: String,
    ) {
        driver.execute(
            identifier = null,
            sql = "UPDATE attempts SET state = 'FAILED', ended_at = ?, error_code = ?, " +
                "error_class = ?, error_detail_json = ? WHERE id = ? AND state = 'RUNNING'",
            parameters = 5,
        ) {
            bindString(0, nowIso())
            bindString(1, errorCode)
            bindString(2, errorClass)
            bindString(3, errorDetail)
            bindString(4, attemptId)
        }
    }

    private fun markAttemptCancelledIfRunning(attemptId: String) {
        driver.execute(
            identifier = null,
            sql = "UPDATE attempts SET state = 'CANCELLED', ended_at = ? WHERE id = ? AND state = 'RUNNING'",
            parameters = 2,
        ) {
            bindString(0, nowIso())
            bindString(1, attemptId)
        }
    }

    /**
     * A bare [SqlDriver] has no public transaction entry point of its own — `newTransaction()`
     * pairs with `Transaction.endTransaction()`, which is `protected`, reachable only through a
     * `Transacter` subclass. This one exists solely to reach it.
     */
    private val transacter = object : TransacterImpl(driver) {}

    /**
     * Runs [block] inside one SQLite transaction. `JdbcSqliteDriver` opens a connection per call
     * unless a transaction is active (`storage/JvmSqlDriver.kt`'s own doc comment); a live
     * transaction is what makes the statements inside [block] land on the same connection and
     * commit or roll back together, which is the property `appendTasks` below relies on.
     * `Transacter.transaction` already rolls back and rethrows on an exception from [block].
     */
    private fun inTransaction(block: () -> Unit) {
        transacter.transaction {
            block()
        }
    }

    /** Appends [specs] as new PENDING tasks starting right after [afterOrdinal]. */
    private fun appendTasks(runId: RunId, run: RunSnapshot, afterOrdinal: Int, specs: List<NewTaskSpec>) {
        specs.forEachIndexed { i, spec ->
            driver.execute(
                identifier = null,
                sql = "INSERT INTO tasks (id, run_id, session_id, project_id, ordinal, kind, " +
                    "description, tool_name, state, retry_policy_json) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', '{}')",
                parameters = 8,
            ) {
                bindString(0, spec.id)
                bindString(1, runId.value)
                bindString(2, run.sessionId.value)
                bindString(3, run.projectId.value)
                bindLong(4, (afterOrdinal + 1 + i).toLong())
                bindString(5, spec.kind.name)
                bindString(6, spec.description)
                bindString(7, spec.toolName)
            }
            // Runs inside this same transaction, after the row above — the hook a TaskRunner
            // needs to write rows with a foreign key onto the task that did not exist a
            // statement ago (AgentLoopTaskRunner's tool_calls.tool_task_id is exactly this: it
            // cannot be written at MODEL_CALL execution time because the TOOL_CALL task it
            // references is only created here).
            spec.dependsOnTaskIds.forEach { depId ->
                addDependency(run.projectId, TaskId(spec.id), depId)
            }
            spec.afterInsert()
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
        val candidates = mutableListOf<Task>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT t.id, t.run_id, t.session_id, r.project_id, t.ordinal, t.kind, t.description, " +
                "t.tool_name, t.state, t.retry_policy_json, t.approval_channel, t.approval_phrase FROM tasks t JOIN runs r ON r.id = t.run_id " +
                "WHERE t.run_id = ? AND t.state = 'PENDING' ORDER BY t.ordinal",
            mapper = { cursor ->
                while (cursor.next().value) {
                    candidates.add(
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
                            retryPolicy = parseRetryPolicy(cursor.getString(9)),
                            approvalChannel = cursor.getString(10),
                            approvalPhrase = cursor.getString(11),
                        )
                    )
                }
                QueryResult.Value(Unit)
            },
            parameters = 1,
        ) { bindString(0, runId.value) }

        for (candidate in candidates) {
            val depStates = dependencyStatesFor(candidate.id)
            val anyFailedOrSkipped = depStates.any { it == TaskState.FAILED || it == TaskState.SKIPPED }
            if (anyFailedOrSkipped) {
                // Dependency failed or was skipped -> cascade to SKIPPED (RFC-0019, PIPELINE.md §4)
                updateTaskState(candidate.id, TaskState.SKIPPED)
                val auditId = idGen()
                audit.write(
                    id = auditId,
                    projectId = candidate.projectId.value,
                    kind = "TaskSkipped",
                    actorKind = "SESSION",
                    actorId = candidate.sessionId.value,
                    subjectRef = candidate.toolName ?: candidate.kind.name,
                    nowIso = nowIso(),
                )
                events.publish(
                    id = idGen(),
                    projectId = candidate.projectId.value,
                    type = "RunStepCompleted",
                    source = "executor",
                    payload = buildJsonObject {
                        put("run_id", runId.value)
                        put("task_id", candidate.id.value)
                        put("state", TaskState.SKIPPED.name)
                        put("reason", "dependency_failed_or_skipped")
                    }.toString(),
                    causedBy = candidate.runId.value,
                    causalDepth = 1,
                    nowIso = nowIso(),
                )
                continue
            }

            val allCompleted = depStates.all { it == TaskState.COMPLETED }
            if (allCompleted) {
                // Either no dependencies or all dependencies completed -> runnable!
                return listOf(candidate)
            }
            // Otherwise, non-terminal dependency: keep scanning later siblings
        }

        return emptyList()
    }

    private fun dependencyStatesFor(taskId: TaskId): List<TaskState> {
        val states = mutableListOf<TaskState>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT t.state FROM tasks t " +
                "JOIN execution_edges e ON e.to_node_id = t.id " +
                "WHERE e.from_node_id = ? AND e.from_node_kind = 'TASK' " +
                "AND e.to_node_kind = 'TASK' AND e.edge_kind = 'DEPENDS_ON'",
            mapper = { c ->
                while (c.next().value) {
                    states.add(TaskState.valueOf(c.getString(0)!!))
                }
                QueryResult.Value(Unit)
            },
            parameters = 1,
        ) { bindString(0, taskId.value) }
        return states
    }

    private fun parseRetryPolicy(rawJson: String?): RetryPolicy {
        if (rawJson.isNullOrBlank() || rawJson == "{}") {
            return RetryPolicy(maxAttempts = 1, retryOn = emptySet(), backoff = BackoffStrategy.None)
        }
        return runCatching {
            val obj = json.parseToJsonElement(rawJson).jsonObject
            val maxAttempts = obj["maxAttempts"]?.jsonPrimitive?.intOrNull ?: 1
            val retryOn = obj["retryOn"]?.jsonArray?.mapNotNull { el ->
                runCatching { ErrorClass.valueOf(el.jsonPrimitive.content) }.getOrNull()
            }?.toSet() ?: setOf(ErrorClass.TRANSIENT, ErrorClass.RATE_LIMITED)
            RetryPolicy(maxAttempts = maxAttempts, retryOn = retryOn, backoff = BackoffStrategy.None)
        }.getOrElse {
            RetryPolicy(maxAttempts = 1, retryOn = emptySet(), backoff = BackoffStrategy.None)
        }
    }

    /**
     * Adds a `DEPENDS_ON` edge `[taskId] -> [dependsOnTaskId]` after verifying acyclicity
     * (RFC-0019 "Acyclicity"). Rejects cycles with [ExecutionCycleException] (`execution.cycle_rejected`).
     */
    fun addDependency(projectId: ProjectId, taskId: TaskId, dependsOnTaskId: TaskId) {
        if (taskId == dependsOnTaskId || isReachableViaDependsOn(dependsOnTaskId.value, taskId.value)) {
            throw ExecutionCycleException(
                "Adding DEPENDS_ON from ${taskId.value} to ${dependsOnTaskId.value} creates a cycle"
            )
        }
        driver.execute(
            identifier = null,
            sql = "INSERT INTO execution_edges (id, project_id, from_node_id, from_node_kind, to_node_id, to_node_kind, edge_kind) " +
                "VALUES (?, ?, ?, 'TASK', ?, 'TASK', 'DEPENDS_ON')",
            parameters = 5,
        ) {
            bindString(0, idGen())
            bindString(1, projectId.value)
            bindString(2, taskId.value)
            bindString(3, dependsOnTaskId.value)
        }
    }

    private fun isReachableViaDependsOn(startTaskId: String, targetTaskId: String): Boolean {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.addLast(startTaskId)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current == targetTaskId) return true
            if (!visited.add(current)) continue
            driver.executeQuery(
                identifier = null,
                sql = "SELECT to_node_id FROM execution_edges WHERE from_node_id = ? " +
                    "AND from_node_kind = 'TASK' AND to_node_kind = 'TASK' AND edge_kind = 'DEPENDS_ON'",
                mapper = { c ->
                    while (c.next().value) {
                        val next = c.getString(0) ?: continue
                        if (next !in visited) queue.addLast(next)
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 1,
            ) { bindString(0, current) }
        }
        return false
    }

    /** Writes a `TARGETED` edge from [runId] to [intentNodeId] (RFC-0019). */
    fun writeTargetedEdge(projectId: ProjectId, runId: RunId, intentNodeId: String) {
        driver.execute(
            identifier = null,
            sql = "INSERT OR IGNORE INTO execution_edges " +
                "(id, project_id, from_node_id, from_node_kind, to_node_id, to_node_kind, edge_kind, confirmed) " +
                "VALUES (?, ?, ?, 'RUN', ?, 'INTENT_NODE', 'TARGETED', 0)",
            parameters = 4,
        ) {
            bindString(0, idGen())
            bindString(1, projectId.value)
            bindString(2, runId.value)
            bindString(3, intentNodeId)
        }
    }

    /** Writes an `IMPLEMENTS` edge from [runId] to [intentNodeId] (RFC-0019). */
    fun writeImplementsEdge(
        projectId: ProjectId,
        runId: RunId,
        intentNodeId: String,
        confirmed: Boolean = false,
        confirmedByKind: String? = null,
        confirmedById: String? = null,
    ) {
        driver.execute(
            identifier = null,
            sql = "INSERT OR REPLACE INTO execution_edges " +
                "(id, project_id, from_node_id, from_node_kind, to_node_id, to_node_kind, edge_kind, " +
                "confirmed, confirmed_by_kind, confirmed_by_id, confirmed_at) " +
                "VALUES (?, ?, ?, 'RUN', ?, 'INTENT_NODE', 'IMPLEMENTS', ?, ?, ?, ?)",
            parameters = 8,
        ) {
            bindString(0, idGen())
            bindString(1, projectId.value)
            bindString(2, runId.value)
            bindString(3, intentNodeId)
            bindLong(4, if (confirmed) 1L else 0L)
            bindString(5, confirmedByKind)
            bindString(6, confirmedById)
            bindString(7, if (confirmed) nowIso() else null)
        }
    }

    /** Writes a `RETRY_OF` edge from [retryAttemptId] to [priorAttemptId] (RFC-0019). */
    fun writeRetryOfEdge(projectId: ProjectId, retryAttemptId: String, priorAttemptId: String) {
        driver.execute(
            identifier = null,
            sql = "INSERT OR IGNORE INTO execution_edges " +
                "(id, project_id, from_node_id, from_node_kind, to_node_id, to_node_kind, edge_kind) " +
                "VALUES (?, ?, ?, 'ATTEMPT', ?, 'ATTEMPT', 'RETRY_OF')",
            parameters = 4,
        ) {
            bindString(0, idGen())
            bindString(1, projectId.value)
            bindString(2, retryAttemptId)
            bindString(3, priorAttemptId)
        }
    }

    suspend fun resumeAwaitingParent(childRunId: RunId): Boolean {
        var parentRunId: RunId? = null
        var parentTaskId: TaskId? = null

        driver.executeQuery(
            identifier = null,
            sql = "SELECT run_id, task_id FROM continuations " +
                "WHERE correlation_id = ? AND suspended_operation = 'CHILD_RUN'",
            mapper = { c ->
                if (c.next().value) {
                    parentRunId = RunId(c.getString(0)!!)
                    parentTaskId = TaskId(c.getString(1)!!)
                }
                QueryResult.Value(Unit)
            },
            parameters = 1,
        ) { bindString(0, childRunId.value) }

        val pRunId = parentRunId ?: return false
        val pTaskId = parentTaskId ?: return false

        val childRun = loadRun(childRunId) ?: return false
        val workerSessionId = childRun.sessionId

        workerCommitter?.let { committer ->
            try {
                committer.commitWorkerOutcome(
                    projectId = childRun.projectId,
                    workerSessionId = workerSessionId,
                    childRunId = childRunId,
                    outcomeSummary = "Child run ${childRunId.value} finished with state ${childRun.state}",
                )
            } catch (_: Exception) {
            }
        }

        val childSucceeded = childRun.state == RunState.COMPLETED

        inTransaction {
            deleteContinuation(pRunId)
            if (childSucceeded) {
                updateTaskState(pTaskId, TaskState.COMPLETED)
                updateRunState(pRunId, RunState.RUNNING)
            } else {
                updateTaskState(pTaskId, TaskState.FAILED)
                updateRunState(
                    pRunId,
                    RunState.FAILED,
                    AidosError(
                        "child_run.failed",
                        ErrorClass.TRANSIENT,
                        "Child run ${childRunId.value} failed with state ${childRun.state}",
                    ),
                )
            }
        }

        if (childSucceeded) {
            drive(pRunId)
        } else {
            resumeAwaitingParent(pRunId)
        }

        return true
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
        val actor = resolveSessionActor(task.sessionId.value)
        audit.write(
            id = auditId,
            projectId = projectId,
            kind = kind,
            actor = actor,
            subjectRef = task.toolName ?: task.kind.name,
            nowIso = nowIso(),
        )
    }

    // ─── Continuations (RFC-0008 step 8d, RFC-0006) ────────────────────────────

    private val json = Json { ignoreUnknownKeys = true }

    private fun suspendedOperationKind(op: SuspendedOperation): String = when (op) {
        is SuspendedOperation.AiCall -> "AI_CALL"
        is SuspendedOperation.ToolCall -> "TOOL_CALL"
        is SuspendedOperation.UserPrompt -> "USER_PROMPT"
        is SuspendedOperation.CapabilityApproval -> "CAPABILITY_APPROVAL"
        is SuspendedOperation.ChildRun -> "CHILD_RUN"
        is SuspendedOperation.ForegroundRequired -> "FOREGROUND_REQUIRED"
    }

    private fun correlationIdFor(op: SuspendedOperation): String? = when (op) {
        is SuspendedOperation.AiCall -> op.requestId
        is SuspendedOperation.ToolCall -> op.callId
        is SuspendedOperation.UserPrompt -> op.promptId
        is SuspendedOperation.CapabilityApproval -> op.requestId
        is SuspendedOperation.ChildRun -> op.childRunId.value
        is SuspendedOperation.ForegroundRequired -> null
    }

    private fun writeContinuation(runId: RunId, taskId: TaskId, park: ParkRequest) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO continuations (run_id, task_id, suspended_operation, " +
                "operation_detail_json, correlation_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            parameters = 6,
        ) {
            bindString(0, runId.value)
            bindString(1, taskId.value)
            bindString(2, suspendedOperationKind(park.suspendedOperation))
            bindString(3, park.operationDetailJson)
            bindString(4, correlationIdFor(park.suspendedOperation))
            bindString(5, nowIso())
        }
    }

    private data class ContinuationRow(
        val taskId: TaskId,
        val suspendedOperation: String,
        val operationDetailJson: String,
    )

    private fun loadContinuation(runId: RunId): ContinuationRow? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT task_id, suspended_operation, operation_detail_json FROM continuations WHERE run_id = ?",
            mapper = { c ->
                QueryResult.Value(
                    if (c.next().value) {
                        ContinuationRow(
                            taskId = TaskId(c.getString(0)!!),
                            suspendedOperation = c.getString(1)!!,
                            operationDetailJson = c.getString(2)!!,
                        )
                    } else {
                        null
                    }
                )
            },
            parameters = 1,
        ) { bindString(0, runId.value) }.value

    private fun deleteContinuation(runId: RunId) {
        driver.execute(
            identifier = null,
            sql = "DELETE FROM continuations WHERE run_id = ?",
            parameters = 1,
        ) { bindString(0, runId.value) }
    }

    /** Returns [detailJson] with its `resolution` key set to [resolution], every other key kept. */
    private fun withResolution(detailJson: String, resolution: String): String {
        val original = json.parseToJsonElement(detailJson).jsonObject
        return buildJsonObject {
            original.forEach { (key, value) -> put(key, value) }
            put("resolution", resolution)
        }.toString()
    }

    /**
     * Resolves a Run parked on [SuspendedOperation.CapabilityApproval] (RFC-0008 step 8d): the
     * `RemotePendingApproval`/M23 gap this method exists to close.
     *
     * **Approve** flips `operation_detail_json.resolution` to `"approved"`, resets the parked task
     * to `PENDING`, moves the Run back to `RUNNING`, and calls [drive] again. The re-executed task
     * (`AgentLoopTaskRunner.executeModelCall`) reads the resolution back out of this same
     * continuations row — recovery is a query, not a restored coroutine (D3) — and uses the
     * adapter it names instead of asking [InferenceRouter] again, which would reproduce the
     * identical `RemotePendingApproval` a second time ([RoutingPolicy] does not change mid-Run).
     *
     * **Deny** deletes the continuation and fails the task and the Run outright with
     * [denialReason]; there is nothing to resume.
     */
    suspend fun resolveCapabilityApproval(
        runId: RunId,
        approved: Boolean,
        denialReason: String? = null,
        approvalChannel: String = "tap",
        approvalPhrase: String? = null,
    ): CapabilityApprovalResolution {
        val continuation = loadContinuation(runId) ?: return CapabilityApprovalResolution.NotFound(runId)
        if (continuation.suspendedOperation != "CAPABILITY_APPROVAL") {
            return CapabilityApprovalResolution.WrongKind(continuation.suspendedOperation)
        }
        val projectId = loadRun(runId)?.projectId?.value ?: ""

        if (!approved) {
            inTransaction {
                deleteContinuation(runId)
                updateTaskState(continuation.taskId, TaskState.FAILED)
            }
            updateRunState(
                runId, RunState.FAILED,
                AidosError(
                    "capability.denied", ErrorClass.DENIED,
                    "Remote approval denied by user" + (denialReason?.let { ": $it" } ?: ""),
                ),
            )
            audit.write(
                id = idGen(), projectId = projectId, kind = EventTypes.PERMISSION_DENIED,
                actorKind = "USER", actorId = "user", subjectRef = continuation.taskId.value,
                nowIso = nowIso(),
            )
            return CapabilityApprovalResolution.Denied
        }

        inTransaction {
            driver.execute(
                identifier = null,
                sql = "UPDATE continuations SET operation_detail_json = ? WHERE run_id = ?",
                parameters = 2,
            ) {
                bindString(0, withResolution(continuation.operationDetailJson, "approved"))
                bindString(1, runId.value)
            }
            recordApprovalChannel(continuation.taskId, approvalChannel, approvalPhrase)
            updateTaskState(continuation.taskId, TaskState.PENDING)
        }
        updateRunState(runId, RunState.RUNNING)
        audit.write(
            id = idGen(), projectId = projectId, kind = EventTypes.PERMISSION_GRANTED,
            actorKind = "USER", actorId = "user", subjectRef = continuation.taskId.value,
            nowIso = nowIso(),
        )
        drive(runId)
        return CapabilityApprovalResolution.Resumed
    }

    /**
     * Resolves a Run parked on [SuspendedOperation.ToolCall] (RFC-0008 step 8d): a tool call
     * denied with `DenialReason.REQUIRES_APPROVAL`.
     *
     * Unlike [resolveCapabilityApproval], approval here does not flip a resolution flag this
     * class reads back later — this class has no [dev.aidos.kernel.CapabilityManager] to grant a
     * fresh capability with, so [onApprove] is the caller's chance to do that (using the parked
     * continuation's `operation_detail_json`, passed through unread) **before** the task resets to
     * `PENDING` and [drive] re-executes it. `AgentLoopTaskRunner.executeToolCall`'s existing
     * `resolveCapability()` call already re-resolves fresh on every execution, so once the caller's
     * grant lands, the resumed attempt finds it without this class needing to know anything about
     * capabilities at all. Deny mirrors [resolveCapabilityApproval]'s own deny path exactly.
     */
    suspend fun resolveToolCallApproval(
        runId: RunId,
        approved: Boolean,
        denialReason: String? = null,
        approvalChannel: String = "tap",
        approvalPhrase: String? = null,
        onApprove: suspend (operationDetailJson: String) -> Unit = {},
    ): CapabilityApprovalResolution {
        val continuation = loadContinuation(runId) ?: return CapabilityApprovalResolution.NotFound(runId)
        if (continuation.suspendedOperation != "TOOL_CALL") {
            return CapabilityApprovalResolution.WrongKind(continuation.suspendedOperation)
        }
        val projectId = loadRun(runId)?.projectId?.value ?: ""

        if (!approved) {
            inTransaction {
                deleteContinuation(runId)
                updateTaskState(continuation.taskId, TaskState.FAILED)
            }
            updateRunState(
                runId, RunState.FAILED,
                AidosError(
                    "tool_call.denied", ErrorClass.DENIED,
                    "Tool call denied by user" + (denialReason?.let { ": $it" } ?: ""),
                ),
            )
            audit.write(
                id = idGen(), projectId = projectId, kind = EventTypes.PERMISSION_DENIED,
                actorKind = "USER", actorId = "user", subjectRef = continuation.taskId.value,
                nowIso = nowIso(),
            )
            return CapabilityApprovalResolution.Denied
        }

        // RFC-0027: a taint escalation is a single-use approval, not a grant. Keep the row with
        // resolution=approved so the re-executed call consumes it (and only it); no capability is
        // issued, and the Run stays tainted.
        val isTaintEscalation = runCatching {
            json.parseToJsonElement(continuation.operationDetailJson).jsonObject["taintMessage"]
        }.getOrNull().let { it != null && it !is kotlinx.serialization.json.JsonNull }
        if (isTaintEscalation) {
            inTransaction {
                driver.execute(
                    identifier = null,
                    sql = "UPDATE continuations SET operation_detail_json = ? WHERE run_id = ?",
                    parameters = 2,
                ) {
                    bindString(0, withResolution(continuation.operationDetailJson, "approved"))
                    bindString(1, runId.value)
                }
                recordApprovalChannel(continuation.taskId, approvalChannel, approvalPhrase)
                updateTaskState(continuation.taskId, TaskState.PENDING)
            }
        } else {
            onApprove(continuation.operationDetailJson)
            inTransaction {
                deleteContinuation(runId)
                recordApprovalChannel(continuation.taskId, approvalChannel, approvalPhrase)
                updateTaskState(continuation.taskId, TaskState.PENDING)
            }
        }
        updateRunState(runId, RunState.RUNNING)
        audit.write(
            id = idGen(), projectId = projectId, kind = EventTypes.PERMISSION_GRANTED,
            actorKind = "USER", actorId = "user", subjectRef = continuation.taskId.value,
            nowIso = nowIso(),
        )
        drive(runId)
        return CapabilityApprovalResolution.Resumed
    }

    /**
     * Resolves a Run parked on [SuspendedOperation.UserPrompt] (RFC-0008 step 8d): the model
     * called the built-in `ask_user` tool and is waiting for a reply.
     *
     * There is no capability to grant and no "wrong kind of denial" here — a question either gets
     * [answer]ed (any non-null string, however short) or is declined ([answer] `null`, mirroring
     * [resolveCapabilityApproval]/[resolveToolCallApproval]'s deny path exactly: delete the
     * continuation, fail the task and the Run outright). A small duplication of that deny logic
     * rather than a forced shared abstraction across three call sites — the same tradeoff
     * `AgentLoopTaskRunner`'s own class doc already accepts for `AgentLoop.kt`.
     */
    suspend fun resolveUserPrompt(
        runId: RunId,
        answer: String?,
        denialReason: String? = null,
    ): CapabilityApprovalResolution {
        val continuation = loadContinuation(runId) ?: return CapabilityApprovalResolution.NotFound(runId)
        if (continuation.suspendedOperation != "USER_PROMPT") {
            return CapabilityApprovalResolution.WrongKind(continuation.suspendedOperation)
        }
        val projectId = loadRun(runId)?.projectId?.value ?: ""

        if (answer == null) {
            inTransaction {
                deleteContinuation(runId)
                updateTaskState(continuation.taskId, TaskState.FAILED)
            }
            updateRunState(
                runId, RunState.FAILED,
                AidosError(
                    "user_prompt.declined", ErrorClass.DENIED,
                    "Question declined by user" + (denialReason?.let { ": $it" } ?: ""),
                ),
            )
            audit.write(
                id = idGen(), projectId = projectId, kind = EventTypes.PERMISSION_DENIED,
                actorKind = "USER", actorId = "user", subjectRef = continuation.taskId.value,
                nowIso = nowIso(),
            )
            return CapabilityApprovalResolution.Denied
        }

        inTransaction {
            driver.execute(
                identifier = null,
                sql = "UPDATE continuations SET operation_detail_json = ? WHERE run_id = ?",
                parameters = 2,
            ) {
                bindString(0, withAnswer(continuation.operationDetailJson, answer))
                bindString(1, runId.value)
            }
            updateTaskState(continuation.taskId, TaskState.PENDING)
        }
        updateRunState(runId, RunState.RUNNING)
        audit.write(
            id = idGen(), projectId = projectId, kind = EventTypes.PERMISSION_GRANTED,
            actorKind = "USER", actorId = "user", subjectRef = continuation.taskId.value,
            nowIso = nowIso(),
        )
        drive(runId)
        return CapabilityApprovalResolution.Resumed
    }

    /** Returns [detailJson] with its `answer` key set to [answer], every other key kept. */
    private fun withAnswer(detailJson: String, answer: String): String {
        val original = json.parseToJsonElement(detailJson).jsonObject
        return buildJsonObject {
            original.forEach { (key, value) -> put(key, value) }
            put("answer", answer)
        }.toString()
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
    val sessionId: SessionId,
)

/**
 * A follow-on Task a [TaskRunner] wants appended to the Run once its own task completes
 * (RFC-0009's `execute(task) // may append new Tasks`; RFC-0008's `MODEL_CALL` → `TOOL_CALL`
 * fan-out).
 *
 * The runner assigns [id] itself (via its own `idGen`) rather than letting the executor mint
 * one, because a runner that also writes rows referencing the future task — [AgentLoopTaskRunner]
 * writes `tool_calls.tool_task_id` pointing at the `TOOL_CALL` task it is about to append — needs
 * to know that id before the task row exists.
 */
data class NewTaskSpec(
    val id: String,
    val kind: TaskKind,
    val description: String,
    val toolName: String? = null,
    val dependsOnTaskIds: List<TaskId> = emptyList(),
    /**
     * Runs immediately after this task's row is inserted, still inside `appendTasks`'s
     * transaction. For a runner that needs to write a row referencing the new task's id via a
     * foreign key — the id exists (it was minted by the runner itself) but the row doesn't,
     * until this point.
     */
    val afterInsert: () -> Unit = {},
)

/** Thrown when adding a `DEPENDS_ON` edge would create a cycle (RFC-0019). */
class ExecutionCycleException(
    message: String,
    val code: String = ErrorCodes.EXECUTION_CYCLE_REJECTED,
) : IllegalArgumentException(message)

/** Result of running a single task. */
data class TaskResult(
    val success: Boolean,
    val errorMessage: String? = null,
    /** Ignored when [success] is false — a failed task fails its Run (see `drive()`). */
    val appendTasks: List<NewTaskSpec> = emptyList(),
    /**
     * RFC-0008 step 8d: non-null means "park, not fail" — `drive()` writes a `continuations` row
     * and moves the task to [ParkRequest.taskState] and the Run to `YIELDED`, instead of treating
     * this task as either COMPLETED or FAILED. Ignored when [success] is true and non-null (a
     * [TaskRunner] should never set both; `drive()` checks `park` first).
     */
    val park: ParkRequest? = null,
    /** Structured error for RFC-0019 retry policy evaluation. */
    val error: AidosError? = null,
    /** Effect recovery class for RFC-0019 retry safety gating. */
    val recoveryClass: RecoveryClass = RecoveryClass.PURE,
)

/**
 * What a [TaskRunner] hands back to park a task instead of completing or failing it.
 *
 * [operationDetailJson] is the durable record a later [SqliteExecutor.resolveCapabilityApproval]
 * (or a future equivalent for other [SuspendedOperation] kinds) reads back to resume — recovery is
 * a query, not a restored coroutine (D3), so everything the resumed attempt needs to act
 * differently the second time must already be in this JSON, not held anywhere in memory.
 */
data class ParkRequest(
    val suspendedOperation: SuspendedOperation,
    val operationDetailJson: String,
    val taskState: TaskState,
    /** RFC-0027: the human-readable escalation (names the tainting content); published as `CapabilityRequested`. */
    val escalationMessage: String? = null,
)

/** Pluggable task runner — replaced by a stub in tests. */
interface TaskRunner {
    suspend fun execute(task: Task): TaskResult
}

/** Outcome of resolving a parked Run's [SuspendedOperation.CapabilityApproval] continuation. */
sealed interface CapabilityApprovalResolution {
    /** Approved: the task was reset to `PENDING` and the Run re-driven. Its outcome (further
     *  progress, completion, or a later failure) is whatever that re-drive produced — this result
     *  only confirms the resume itself happened. */
    data object Resumed : CapabilityApprovalResolution

    /** Denied: the parked task and its Run are now `FAILED`. No re-drive occurs. */
    data object Denied : CapabilityApprovalResolution

    /** No `continuations` row exists for this Run — nothing to resolve (already resolved, the
     *  Run never parked, or [runId] is wrong). */
    data class NotFound(val runId: RunId) : CapabilityApprovalResolution

    /** A continuation exists but is parked on a different [SuspendedOperation] kind. */
    data class WrongKind(val actual: String) : CapabilityApprovalResolution
}
