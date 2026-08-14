package dev.aidos.executor

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.EventId
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RunId
import dev.aidos.kernel.SessionId
import dev.aidos.kernel.TaskKind

/**
 * Creates a Run for a user message (RFC-0008, RFC-0009, RFC-0019).
 *
 * Before this, nothing in `executor` created `runs` rows: `SqliteExecutor.drive()` only steps an
 * *existing* Run to completion, and `AgentLoop.kt` (in `agentloop`) has no relationship to
 * `runs`/`tasks` at all. This is "how a Run comes to exist" — the first of the two pieces the
 * AgentLoop↔executor bridge needed; the second is the per-step `MODEL_CALL`/`TOOL_CALL`
 * [TaskRunner] in `AgentLoopTaskRunner.kt`.
 *
 * Per RFC-0008's mapping table ("One step's model call → `Task(kind = MODEL_CALL)`"; "The whole
 * loop → `Run`"), a new Run for a user message is a `runs` row in `PENDING` plus exactly one
 * `Task(kind = MODEL_CALL, ordinal = 0)`. `drive()` picks it up from there: [AgentLoopTaskRunner]
 * executes that Task and appends whatever comes next.
 *
 * Both rows are one transaction — a crash between "the Run exists" and "its first Task exists"
 * would otherwise leave a Run with zero tasks, and `drive()`'s existing "no runnable tasks →
 * allTasksTerminal (vacuously true) → COMPLETED" path would mark it done without ever calling
 * the model.
 */
class RunCreator(
    private val driver: SqlDriver,
    private val idGen: () -> String,
    private val nowIso: () -> String,
) {
    private val transacter = object : TransacterImpl(driver) {}

    fun createForUserMessage(
        sessionId: SessionId,
        projectId: ProjectId,
        triggerEventId: EventId,
        userMessageSummary: String,
        platformProfile: PlatformProfile,
        deviceId: String,
        networkAvailable: Boolean,
        maxSteps: Int = 24,
    ): RunId = create(
        sessionId, projectId, triggerEventId, userMessageSummary,
        platformProfile, deviceId, networkAvailable, maxSteps,
    )

    /**
     * Creates a Run for a session woken by an event (RFC-0005), not a direct user message.
     * [contextSummary] fills the exact same `runs.user_message_summary` column
     * [createForUserMessage] does — there is no separate "why did this Run start" column, and
     * inventing one for exactly one caller would be a bigger, unreviewed schema decision than
     * this slice should make. Named separately anyway: calling `createForUserMessage` with a
     * synthesized wake description read as misleading at the [Scheduler] call site, even though
     * the row shape underneath is identical.
     */
    fun createForEvent(
        sessionId: SessionId,
        projectId: ProjectId,
        triggerEventId: EventId,
        contextSummary: String,
        platformProfile: PlatformProfile,
        deviceId: String,
        networkAvailable: Boolean,
        maxSteps: Int = 24,
    ): RunId = create(
        sessionId, projectId, triggerEventId, contextSummary,
        platformProfile, deviceId, networkAvailable, maxSteps,
    )

    private fun create(
        sessionId: SessionId,
        projectId: ProjectId,
        triggerEventId: EventId,
        summary: String,
        platformProfile: PlatformProfile,
        deviceId: String,
        networkAvailable: Boolean,
        maxSteps: Int,
    ): RunId {
        val runId = RunId(idGen())
        transacter.transaction {
            driver.execute(
                identifier = null,
                sql = "INSERT INTO runs (id, session_id, project_id, trigger_event_id, started_at, " +
                    "state, user_message_summary, retry_policy_json, max_steps, platform_profile, " +
                    "device_id, network_available) VALUES (?, ?, ?, ?, ?, 'PENDING', ?, '{}', ?, ?, ?, ?)",
                parameters = 10,
            ) {
                bindString(0, runId.value)
                bindString(1, sessionId.value)
                bindString(2, projectId.value)
                bindString(3, triggerEventId.value)
                bindString(4, nowIso())
                bindString(5, summary)
                bindLong(6, maxSteps.toLong())
                bindString(7, platformProfile.name)
                bindString(8, deviceId)
                bindLong(9, if (networkAvailable) 1L else 0L)
            }
            driver.execute(
                identifier = null,
                sql = "INSERT INTO tasks (id, run_id, session_id, project_id, ordinal, kind, " +
                    "description, state, retry_policy_json) VALUES (?, ?, ?, ?, 0, ?, ?, 'PENDING', '{}')",
                parameters = 6,
            ) {
                bindString(0, idGen())
                bindString(1, runId.value)
                bindString(2, sessionId.value)
                bindString(3, projectId.value)
                bindString(4, TaskKind.MODEL_CALL.name)
                bindString(5, "Model call for user message")
            }
        }
        return runId
    }
}
