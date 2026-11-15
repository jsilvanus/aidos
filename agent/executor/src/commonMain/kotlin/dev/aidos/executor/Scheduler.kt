package dev.aidos.executor

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.broker.AuditLog
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ActorRef
import dev.aidos.kernel.EventId
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.SessionId
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * An event queued for a session that was already `RUNNING` when the event arrived (RFC-0006
 * "Interrupt Handling"). Runs within a session execute serially — never concurrently.
 */
data class QueuedSessionEvent(
    val event: EventRow,
    val sourceSessionId: String?,
    val projectId: ProjectId,
    val platformProfile: PlatformProfile,
    val deviceId: String,
    val networkAvailable: Boolean,
)

/** Per-session pending event queue (RFC-0006). */
class SessionEventQueue {
    private val queues = mutableMapOf<String, ArrayDeque<QueuedSessionEvent>>()

    fun enqueue(sessionId: String, item: QueuedSessionEvent) {
        queues.getOrPut(sessionId) { ArrayDeque() }.addLast(item)
    }

    fun dequeue(sessionId: String): QueuedSessionEvent? {
        val q = queues[sessionId] ?: return null
        val next = if (q.isEmpty()) null else q.removeFirst()
        if (q.isEmpty()) queues.remove(sessionId)
        return next
    }

    fun clear(sessionId: String): Int {
        val q = queues.remove(sessionId) ?: return 0
        return q.size
    }

    fun pendingFor(sessionId: String): List<QueuedSessionEvent> =
        queues[sessionId]?.toList() ?: emptyList()
}

/**
 * RFC-0005's actual wake-to-Run wiring and RFC-0006's serial session event queue &
 * `PRIORITY_INTERRUPT` handling.
 */
class Scheduler(
    private val driver: SqlDriver,
    private val events: EventStore,
    private val subscriptions: SessionSubscriptionStore,
    private val runCreator: RunCreator,
    private val audit: AuditLog,
    private val idGen: () -> String,
    private val nowIso: () -> String,
    val eventQueue: SessionEventQueue = SessionEventQueue(),
) {
    private val transacter = object : TransacterImpl(driver) {}

    /** One session the Scheduler decided to wake, and what it did about it. */
    data class WokenSession(val sessionId: String, val runId: String, val sessionWokenEventId: String)

    data class WakeResult(
        /** Sessions actually woken: transitioned to `RUNNING` with a new `PENDING` Run. */
        val woken: List<WokenSession>,
        /** Matched, but refused because the event's own subscription is self-sourced (RFC-0005 "Cycles"). */
        val selfWakeRefused: List<String>,
        /** Matched and eligible, but the `SessionWoken` event itself hit `EventStore.MAX_CAUSAL_DEPTH`. */
        val depthCeilingRefused: List<String>,
        /** Matched, but the session was not `SLEEPING` (RFC-0017: only a sleeping session wakes). */
        val alreadyRunning: List<String>,
        /** Sessions whose event was queued because the session was already `RUNNING` (RFC-0006). */
        val queued: List<String> = emptyList(),
        /** Sessions interrupted by a `PRIORITY_INTERRUPT` event (RFC-0006). */
        val interrupted: List<String> = emptyList(),
        /** Matched, but parked in `SLEEPING` after 3 consecutive `FAILED` Runs (RFC-0011). */
        val failureBudgetParked: List<String> = emptyList(),
    )

    /**
     * Reacts to one already-published event: matches it against [projectId]'s subscriptions and
     * wakes whichever sessions RFC-0005/RFC-0004/RFC-0006 admit.
     */
    fun wake(
        event: EventRow,
        sourceSessionId: String?,
        projectId: ProjectId,
        platformProfile: PlatformProfile,
        deviceId: String,
        networkAvailable: Boolean,
    ): WakeResult {
        val match = SchedulerMatcher.match(
            event = event,
            sourceSessionId = sourceSessionId,
            subscriptions = subscriptions.forProject(projectId.value),
        )

        for (sessionId in match.selfWakeRefused) {
            val actor = resolveSessionActor(sessionId)
            audit.write(
                id = idGen(),
                projectId = projectId.value,
                kind = "WakeRefused",
                actor = actor,
                subjectRef = event.type,
                detailJson = buildJsonObject {
                    put("reason", "self_wake_not_opted_in")
                    put("event_id", event.id)
                }.toString(),
                nowIso = nowIso(),
            )
        }

        // RFC-0006: PRIORITY_INTERRUPT cancels active Runs for targeted/matched sessions and clears queue.
        if (event.type == EventTypes.PRIORITY_INTERRUPT) {
            val targets = (match.woken + targetSessionFromTopic(event.topic)).filterNotNull().distinct()
            val interrupted = mutableListOf<String>()
            for (sessionId in targets) {
                if (cancelActiveSessionRuns(projectId.value, sessionId, event.id)) {
                    interrupted.add(sessionId)
                }
            }
            return WakeResult(
                woken = emptyList(),
                selfWakeRefused = match.selfWakeRefused,
                depthCeilingRefused = emptyList(),
                alreadyRunning = emptyList(),
                interrupted = interrupted,
            )
        }

        val woken = mutableListOf<WokenSession>()
        val depthCeilingRefused = mutableListOf<String>()
        val alreadyRunning = mutableListOf<String>()
        val queued = mutableListOf<String>()
        val failureBudgetParked = mutableListOf<String>()

        for (sessionId in match.woken) {
            // RFC-0004: Visibility check — never deliver PRIVILEGED or other-session events.
            if (!events.canDeliver(event, ActorKind.SESSION, sessionId)) {
                continue
            }

            // RFC-0011: A session with >= 3 consecutive FAILED Runs is parked in SLEEPING and
            // stops responding to non-user events until the user intervenes.
            val userEvent = event.type == EventTypes.USER_COMMAND || event.source == "user"
            if (isFailureBudgetExhausted(sessionId)) {
                if (!userEvent) {
                    audit.write(
                        id = idGen(),
                        projectId = projectId.value,
                        kind = "WakeRefused",
                        actor = resolveSessionActor(sessionId),
                        subjectRef = event.type,
                        detailJson = buildJsonObject {
                            put("reason", "failure_budget_exhausted")
                            put("event_id", event.id)
                            put("session_id", sessionId)
                        }.toString(),
                        nowIso = nowIso(),
                    )
                    failureBudgetParked.add(sessionId)
                    continue
                } else {
                    resetFailureBudget(sessionId)
                }
            }

            val wokenEventId = idGen()
            val seq = events.publish(
                id = wokenEventId,
                projectId = projectId.value,
                type = EventTypes.SESSION_WOKEN,
                source = "scheduler",
                topic = "session:$sessionId",
                payload = buildJsonObject { put("session_id", sessionId) }.toString(),
                causedBy = event.id,
                causalDepth = event.causalDepth + 1,
                nowIso = nowIso(),
            )
            if (seq == null) {
                audit.write(
                    id = idGen(),
                    projectId = projectId.value,
                    kind = "WakeRefused",
                    actor = ActorRef(ActorKind.RUNTIME, "scheduler"),
                    subjectRef = event.type,
                    detailJson = buildJsonObject {
                        put("reason", "causal_depth_ceiling")
                        put("event_id", event.id)
                        put("session_id", sessionId)
                    }.toString(),
                    nowIso = nowIso(),
                )
                depthCeilingRefused.add(sessionId)
                continue
            }

            // RFC-0006: If session is already RUNNING, queue the event rather than waking concurrently.
            if (isSessionRunning(sessionId)) {
                eventQueue.enqueue(
                    sessionId,
                    QueuedSessionEvent(
                        event = event,
                        sourceSessionId = sourceSessionId,
                        projectId = projectId,
                        platformProfile = platformProfile,
                        deviceId = deviceId,
                        networkAvailable = networkAvailable,
                    ),
                )
                alreadyRunning.add(sessionId)
                queued.add(sessionId)
                continue
            }

            var created: WokenSession? = null
            transacter.transaction {
                val updated = updateSessionToRunning(sessionId)
                if (updated == 1L) {
                    val runId = runCreator.createForEvent(
                        sessionId = SessionId(sessionId),
                        projectId = projectId,
                        triggerEventId = EventId(wokenEventId),
                        contextSummary = "Woken by ${event.type}" + (event.topic?.let { " ($it)" } ?: ""),
                        platformProfile = platformProfile,
                        deviceId = deviceId,
                        networkAvailable = networkAvailable,
                    )
                    created = WokenSession(sessionId, runId.value, wokenEventId)
                }
            }
            if (created != null) {
                woken.add(created)
            } else {
                alreadyRunning.add(sessionId)
            }
        }

        return WakeResult(
            woken = woken,
            selfWakeRefused = match.selfWakeRefused,
            depthCeilingRefused = depthCeilingRefused,
            alreadyRunning = alreadyRunning,
            queued = queued,
            failureBudgetParked = failureBudgetParked,
        )
    }

    /**
     * Called when a Run for [sessionId] completes, yields, or is cancelled (RFC-0006):
     * dequeues the next pending event and starts a new Run for it, or returns the session to
     * `SLEEPING` if the queue is empty.
     */
    fun onRunFinished(sessionId: String): WokenSession? {
        if (isFailureBudgetExhausted(sessionId)) {
            eventQueue.clear(sessionId)
            parkSessionSleeping(sessionId)
            return null
        }
        val next = eventQueue.dequeue(sessionId)
        if (next == null) {
            parkSessionSleeping(sessionId)
            return null
        }

        val wokenEventId = idGen()
        val seq = events.publish(
            id = wokenEventId,
            projectId = next.projectId.value,
            type = EventTypes.SESSION_WOKEN,
            source = "scheduler",
            topic = "session:$sessionId",
            payload = buildJsonObject { put("session_id", sessionId) }.toString(),
            causedBy = next.event.id,
            causalDepth = next.event.causalDepth + 1,
            nowIso = nowIso(),
        ) ?: return null

        val runId = runCreator.createForEvent(
            sessionId = SessionId(sessionId),
            projectId = next.projectId,
            triggerEventId = EventId(wokenEventId),
            contextSummary = "Woken by ${next.event.type}" + (next.event.topic?.let { " ($it)" } ?: ""),
            platformProfile = next.platformProfile,
            deviceId = next.deviceId,
            networkAvailable = next.networkAvailable,
        )
        return WokenSession(sessionId, runId.value, wokenEventId)
    }

    private fun cancelActiveSessionRuns(projectId: String, sessionId: String, eventId: String): Boolean {
        eventQueue.clear(sessionId)
        val now = nowIso()
        var cancelledAny = false
        transacter.transaction {
            val activeRunIds = mutableListOf<String>()
            driver.executeQuery(
                identifier = null,
                sql = "SELECT id FROM runs WHERE session_id = ? AND state IN ('PENDING', 'RUNNING', 'YIELDED')",
                mapper = { c ->
                    while (c.next().value) activeRunIds.add(c.getString(0)!!)
                    QueryResult.Value(Unit)
                },
                parameters = 1,
            ) { bindString(0, sessionId) }

            for (runId in activeRunIds) {
                driver.execute(
                    identifier = null,
                    sql = "UPDATE runs SET state = 'CANCELLED', ended_at = ?, row_version = row_version + 1 " +
                        "WHERE id = ? AND state IN ('PENDING', 'RUNNING', 'YIELDED')",
                    parameters = 2,
                ) { bindString(0, now); bindString(1, runId) }
                driver.execute(
                    identifier = null,
                    sql = "UPDATE tasks SET state = 'SKIPPED', ended_at = ?, row_version = row_version + 1 " +
                        "WHERE run_id = ? AND state IN ('PENDING', 'RUNNING', 'AWAITING_APPROVAL', 'AWAITING_INPUT')",
                    parameters = 2,
                ) { bindString(0, now); bindString(1, runId) }
                driver.execute(
                    identifier = null,
                    sql = "DELETE FROM continuations WHERE run_id = ?",
                    parameters = 1,
                ) { bindString(0, runId) }
                audit.write(
                    id = idGen(),
                    projectId = projectId,
                    kind = "RunCancelled",
                    actor = ActorRef(ActorKind.RUNTIME, "priority-interrupt"),
                    subjectRef = runId,
                    detailJson = buildJsonObject {
                        put("reason", "priority_interrupt")
                        put("event_id", eventId)
                        put("session_id", sessionId)
                    }.toString(),
                    nowIso = now,
                )
                cancelledAny = true
            }
            driver.execute(
                identifier = null,
                sql = "UPDATE sessions SET state = 'SLEEPING', state_updated_at = ?, row_version = row_version + 1 " +
                    "WHERE id = ? AND state = 'RUNNING'",
                parameters = 2,
            ) { bindString(0, now); bindString(1, sessionId) }
        }
        return cancelledAny
    }

    private fun targetSessionFromTopic(topic: String?): String? {
        if (topic == null || !topic.startsWith("session:")) return null
        val id = topic.removePrefix("session:").substringBefore(':')
        return id.takeIf { it.isNotBlank() && it != "*" && it != "**" }
    }

    private fun isSessionRunning(sessionId: String): Boolean =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT state FROM sessions WHERE id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0) == "RUNNING" else false) },
            parameters = 1,
        ) { bindString(0, sessionId) }.value

    private fun updateSessionToRunning(sessionId: String): Long =
        driver.execute(
            identifier = null,
            sql = "UPDATE sessions SET state = 'RUNNING', state_updated_at = ?, last_active_at = ?, " +
                "row_version = row_version + 1 WHERE id = ? AND state = 'SLEEPING'",
            parameters = 3,
        ) {
            bindString(0, nowIso())
            bindString(1, nowIso())
            bindString(2, sessionId)
        }.value

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

    private fun parkSessionSleeping(sessionId: String) {
        driver.execute(
            identifier = null,
            sql = "UPDATE sessions SET state = 'SLEEPING', state_updated_at = ?, row_version = row_version + 1 " +
                "WHERE id = ? AND state = 'RUNNING'",
            parameters = 2,
        ) {
            bindString(0, nowIso())
            bindString(1, sessionId)
        }
    }

    private fun isFailureBudgetExhausted(sessionId: String): Boolean {
        val failures = driver.executeQuery(
            identifier = null,
            sql = "SELECT consecutive_failures FROM sessions WHERE id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) (c.getLong(0) ?: 0L) else 0L) },
            parameters = 1,
        ) { bindString(0, sessionId) }.value
        return failures >= FAILURE_BUDGET
    }

    private fun resetFailureBudget(sessionId: String) {
        driver.execute(
            identifier = null,
            sql = "UPDATE sessions SET consecutive_failures = 0, state_updated_at = ? WHERE id = ?",
            parameters = 2,
        ) {
            bindString(0, nowIso())
            bindString(1, sessionId)
        }
    }

    companion object {
        /** RFC-0011: 3 consecutive FAILED Runs parks a session in SLEEPING. */
        const val FAILURE_BUDGET: Long = 3L
    }
}
