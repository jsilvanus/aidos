package dev.aidos.executor

import app.cash.sqldelight.TransacterImpl
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
import dev.aidos.kernel.SuspendedOperation
import dev.aidos.kernel.Task
import dev.aidos.kernel.TaskId
import dev.aidos.kernel.TaskKind
import dev.aidos.kernel.TaskState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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

                // RFC-0008 step 8d: park rather than complete/fail. The task row and the
                // continuations row land in the same transaction as the RUNNING→parked
                // transition, so a crash between them leaves the task RUNNING with no attempt —
                // exactly the case `recover()` already resets to PENDING and retries safely.
                if (result.park != null) {
                    val park = result.park
                    inTransaction {
                        updateTaskState(task.id, park.taskState)
                        writeContinuation(runId, task.id, park)
                    }
                    updateRunState(runId, RunState.YIELDED)

                    val auditId = idGen()
                    audit.write(
                        id = auditId,
                        projectId = currentRun.projectId.value,
                        kind = EventTypes.PERMISSION_REQUESTED,
                        actorKind = "SESSION",
                        actorId = task.sessionId.value,
                        subjectRef = task.toolName ?: task.kind.name,
                        nowIso = nowIso(),
                    )
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
                    return
                }

                val newState = if (result.success) TaskState.COMPLETED else TaskState.FAILED

                // The task's own completion and any follow-on Tasks it produces are one
                // checkpoint (RFC-0009). A crash between "this task is done" and "here is what
                // comes next" must never be observable: if it were, drive() would see an
                // all-terminal task set on resume and complete the Run one step early, silently
                // truncating a Run that still had a next model step to take (RFC-0008's
                // MODEL_CALL → TOOL_CALL → MODEL_CALL fan-out).
                inTransaction {
                    updateTaskState(task.id, newState)
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

                // Publish RFC-0004's ToolCompleted (MVP item 2) for a successful task — a FACT,
                // not a SIGNAL: the tool result is a durable outcome, not lossy progress. Topic
                // follows the RFC's own worked example (tool:<name>:<id>); failures publish no
                // event here since "ToolFailed" is outside the RFC's MVP-scoped type list
                // (EventTypes doc comment) and inventing one wasn't this slice's call to make.
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
                            approvalChannel = null,
                            approvalPhrase = null,
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

        onApprove(continuation.operationDetailJson)
        inTransaction {
            deleteContinuation(runId)
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
    /**
     * Runs immediately after this task's row is inserted, still inside `appendTasks`'s
     * transaction. For a runner that needs to write a row referencing the new task's id via a
     * foreign key — the id exists (it was minted by the runner itself) but the row doesn't,
     * until this point.
     */
    val afterInsert: () -> Unit = {},
)

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
