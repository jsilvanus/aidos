package dev.aidos.api

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * RFC-0004 / RFC-0052: turns what the executor durably writes to a project's database into
 * [RuntimeEvent]s for [EventSubscriptions] subscribers.
 *
 * The executor publishes FACT rows to the project's `events` table and updates `runs`, `tasks`,
 * `tool_calls` and `attempts`; nothing turned those into subscriber events, so UIs polled. This
 * bridge tails the `events` table by `sequence` (so it works whether the executor runs in this
 * process or in another one sharing the database) and reads the rows each event refers to.
 *
 * SIGNAL events are not persisted and no SIGNAL type is mapped, so the in-process signal listener
 * path is not used: it would need `:api` to depend on `:executor`.
 *
 * Payloads in `events` are redacted at write time (RFC-0035). The bridge never reads
 * `tool_calls.arguments_json`; approval previews carry the tool name only.
 *
 * Model text reaches the executor as a whole response, so one [RuntimeEvent.AiResponseDelta] with
 * `isFinal = true` is emitted per model step. Token streaming is out of scope.
 */
class RuntimeEventBridge(
    private val projectId: String,
    private val driver: SqlDriver,
    private val emit: (RuntimeEvent) -> Unit,
    private val idGen: () -> String,
    private val now: () -> Instant = { Clock.System.now() },
) {
    private var lastSequence: Long = currentMaxSequence()

    /** runId → sessionId, for runs seen in events or announced by the caller and not yet ended. */
    private val activeRuns = mutableMapOf<String, String>()
    private val announcedRuns = mutableSetOf<String>()

    /** The caller already emitted `RunStarted` for [runId] (e.g. `sessions.send`). */
    @Synchronized
    fun noteRunStarted(runId: String, sessionId: String) {
        announcedRuns.add(runId)
        activeRuns[runId] = sessionId
    }

    /** Polls on a loop: every 250 ms while a run is RUNNING in this project, every 2 s otherwise. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            val running = try {
                pollOnce()
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                false // the next tick retries; a bad row must not stop the stream
            }
            delay(if (running) FAST_POLL_MILLIS else IDLE_POLL_MILLIS)
        }
    }

    /** Reads rows after the last seen sequence and emits their events. Returns true while a run is RUNNING. */
    @Synchronized
    fun pollOnce(): Boolean {
        while (true) {
            val rows = readEventRows()
            if (rows.isEmpty()) break
            for (row in rows) {
                lastSequence = row.sequence
                map(row)
            }
            if (rows.size < PAGE) break
        }
        return reconcileRuns()
    }

    private class EventRow(val sequence: Long, val id: String, val type: String, val timestamp: String, val payload: JsonObject)

    private fun currentMaxSequence(): Long = driver.executeQuery(
        identifier = null,
        sql = "SELECT COALESCE(MAX(sequence), 0) FROM events WHERE project_id = ?",
        mapper = { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) },
        parameters = 1,
    ) { bindString(0, projectId) }.value

    private fun readEventRows(): List<EventRow> = driver.executeQuery(
        identifier = null,
        sql = "SELECT sequence, id, type, timestamp, payload FROM events " +
            "WHERE project_id = ? AND sequence > ? AND category = 'FACT' " +
            "AND type IN ('RunStepCompleted', 'ToolCompleted') ORDER BY sequence LIMIT $PAGE",
        mapper = { c ->
            val out = mutableListOf<EventRow>()
            while (c.next().value) {
                val payload = runCatching { Json.parseToJsonElement(c.getString(4) ?: "{}").jsonObject }.getOrNull()
                    ?: JsonObject(emptyMap())
                out += EventRow(c.getLong(0)!!, c.getString(1)!!, c.getString(2)!!, c.getString(3) ?: "", payload)
            }
            QueryResult.Value(out)
        },
        parameters = 2,
    ) { bindString(0, projectId); bindLong(1, lastSequence) }.value

    private class TaskInfo(val sessionId: String, val kind: String, val toolName: String?)

    private fun taskInfo(taskId: String): TaskInfo? = driver.executeQuery(
        identifier = null,
        sql = "SELECT session_id, kind, tool_name FROM tasks WHERE id = ?",
        mapper = { c -> QueryResult.Value(if (c.next().value) TaskInfo(c.getString(0)!!, c.getString(1)!!, c.getString(2)) else null) },
        parameters = 1,
    ) { bindString(0, taskId) }.value

    private fun map(row: EventRow) {
        val runId = row.payload["run_id"]?.jsonPrimitive?.contentOrNull ?: return
        val taskId = row.payload["task_id"]?.jsonPrimitive?.contentOrNull ?: return
        val task = taskInfo(taskId) ?: return
        val timestamp = runCatching { Instant.parse(row.timestamp) }.getOrDefault(now())
        val sessionId = task.sessionId
        announce(runId, sessionId, timestamp)
        when (row.type) {
            "RunStepCompleted" -> {
                val state = row.payload["state"]?.jsonPrimitive?.contentOrNull ?: return
                emit(
                    RuntimeEvent.RunStepCompleted(
                        eventId = row.id, timestamp = timestamp, projectId = projectId, sessionId = sessionId,
                        runId = runId, taskId = taskId,
                        stepIndex = row.payload["step_index"]?.jsonPrimitive?.intOrNull ?: 0,
                        taskState = state,
                    )
                )
                if (state == "COMPLETED" && task.kind == "MODEL_CALL") {
                    modelText(taskId)?.let { text ->
                        emit(
                            RuntimeEvent.AiResponseDelta(
                                eventId = idGen(), timestamp = timestamp, projectId = projectId, sessionId = sessionId,
                                runId = runId, delta = text, isFinal = true,
                            )
                        )
                    }
                }
                if (state == "AWAITING_APPROVAL" && task.kind == "TOOL_CALL") {
                    emit(
                        RuntimeEvent.ToolApprovalRequired(
                            eventId = idGen(), timestamp = timestamp, projectId = projectId, sessionId = sessionId,
                            runId = runId, taskId = taskId, toolName = task.toolName ?: "tool",
                            previewDescription = "Waiting for approval to call ${task.toolName ?: "a tool"}",
                        )
                    )
                }
            }
            "ToolCompleted" -> {
                val (callId, outcome) = toolCall(taskId)
                emit(
                    RuntimeEvent.ToolCallCompleted(
                        eventId = row.id, timestamp = timestamp, projectId = projectId, sessionId = sessionId,
                        runId = runId, callId = callId ?: taskId,
                        toolName = task.toolName ?: row.payload["operation"]?.jsonPrimitive?.contentOrNull ?: "tool",
                        outcome = outcome ?: "COMPLETED",
                    )
                )
            }
        }
    }

    private fun announce(runId: String, sessionId: String, timestamp: Instant) {
        activeRuns.putIfAbsent(runId, sessionId)
        if (announcedRuns.add(runId)) {
            emit(RuntimeEvent.RunStarted(idGen(), timestamp, projectId, sessionId, runId))
        }
    }

    private fun toolCall(taskId: String): Pair<String?, String?> = driver.executeQuery(
        identifier = null,
        sql = "SELECT call_id, outcome FROM tool_calls WHERE tool_task_id = ?",
        mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0) to c.getString(1) else null to null) },
        parameters = 1,
    ) { bindString(0, taskId) }.value

    private fun modelText(taskId: String): String? {
        val snapshot = driver.executeQuery(
            identifier = null,
            sql = "SELECT output_snapshot FROM attempts WHERE task_id = ? AND output_snapshot IS NOT NULL " +
                "ORDER BY attempt_number DESC LIMIT 1",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) },
            parameters = 1,
        ) { bindString(0, taskId) }.value ?: return null
        return runCatching { Json.parseToJsonElement(snapshot).jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            .getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Emits RunCompleted/RunFailed for tracked runs that ended. Returns true while any is RUNNING. */
    private fun reconcileRuns(): Boolean {
        var anyRunning = false
        for ((runId, sessionId) in activeRuns.entries.toList()) {
            val row = driver.executeQuery(
                identifier = null,
                sql = "SELECT state, error_code, error_detail_json FROM runs WHERE id = ?",
                mapper = { c -> QueryResult.Value(if (c.next().value) Triple(c.getString(0)!!, c.getString(1), c.getString(2)) else null) },
                parameters = 1,
            ) { bindString(0, runId) }.value
            when (row?.first) {
                null -> activeRuns.remove(runId)
                "PENDING", "RUNNING" -> anyRunning = true
                "COMPLETED" -> {
                    activeRuns.remove(runId)
                    emit(RuntimeEvent.RunCompleted(idGen(), now(), projectId, sessionId, runId, emptyList()))
                }
                "FAILED", "CANCELLED" -> {
                    activeRuns.remove(runId)
                    val message = row.third?.let {
                        runCatching { Json.parseToJsonElement(it).jsonObject["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                    } ?: row.first.lowercase()
                    emit(
                        RuntimeEvent.RunFailed(
                            idGen(), now(), projectId, sessionId, runId,
                            errorCode = row.second ?: "run.${row.first.lowercase()}", errorMessage = message,
                        )
                    )
                }
                // YIELDED, INTERRUPTED: parked or paused, stays tracked; polled slowly.
            }
        }
        return anyRunning
    }

    private companion object {
        const val FAST_POLL_MILLIS = 250L
        const val IDLE_POLL_MILLIS = 2000L
        const val PAGE = 200
    }
}
