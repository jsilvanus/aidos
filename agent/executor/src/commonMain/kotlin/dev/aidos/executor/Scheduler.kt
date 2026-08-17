package dev.aidos.executor

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.broker.AuditLog
import dev.aidos.kernel.EventId
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.SessionId
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * RFC-0005's actual wake-to-Run wiring: given a published event, decides which subscribed
 * sessions wake, and for each, creates the Run the way [RunCreator] already does for a user
 * message — "it creates a Run, and RFC-0009 drives it" is this RFC's own Non-goals line, and
 * `drive()` is exactly what picks a `PENDING` Run up from here. This class does **not** call
 * `drive()` itself, for the same reason `AgentLoopTaskRunner`-driven Runs from `sessions.send()`
 * don't either (see `api/RunExecutor.kt`): actually driving a Run to a model response needs a
 * real `InferenceRouter`/`PromptAssembler`/`EffectBroker` composition that doesn't exist in this
 * runtime yet. A durable, un-driven `PENDING` Run is correct either way (D3): nothing is lost by
 * not driving it immediately.
 *
 * [SchedulerMatcher] already does the pure "which sessions match" decision (RFC-0005's own rule:
 * matching "is pure and cheap; it never reads the filesystem and never calls a model"). This
 * class is what *acts* on a match: transitioning a session out of `SLEEPING` and creating a Run
 * touches the step-machine invariants D3/D14 guard — `SchedulerMatcher`'s own doc comment named
 * this as "its own, separate, more careful piece of work," and this is that piece.
 *
 * **Deliberately not built here — RFC-0005's own MVP section excludes it explicitly**: the full
 * admission policy (project lock, availability, budget, least-recently-run fairness ordering),
 * priorities, and deadline scheduling. The one admission check this class *does* make —
 * refusing to wake a session that isn't `SLEEPING` — is basic session-state-machine correctness
 * (RFC-0017: only `SLEEPING` sessions wake), not admission policy.
 */
class Scheduler(
    private val driver: SqlDriver,
    private val events: EventStore,
    private val subscriptions: SessionSubscriptionStore,
    private val runCreator: RunCreator,
    private val audit: AuditLog,
    private val idGen: () -> String,
    private val nowIso: () -> String,
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
    )

    /**
     * Reacts to one already-published event: matches it against [projectId]'s subscriptions and
     * wakes whichever sessions RFC-0005's matching rules admit.
     *
     * [sourceSessionId] is the session that caused [event]'s publication, if any — passed through
     * unchanged to [SchedulerMatcher]; see that class's own doc comment for why this isn't derived
     * from [EventRow.source].
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

        // Self-wake refusals are recorded, not silent (RFC-0005 "Cycles and amplification",
        // RFC-0037) -- this is a decision, and "why did my subscription never fire" needs an
        // answer source the same way a causal-depth refusal does, below.
        for (sessionId in match.selfWakeRefused) {
            audit.write(
                id = idGen(),
                projectId = projectId.value,
                kind = "WakeRefused",
                actorKind = "SESSION",
                actorId = sessionId,
                subjectRef = event.type,
                detailJson = buildJsonObject {
                    put("reason", "self_wake_not_opted_in")
                    put("event_id", event.id)
                }.toString(),
                nowIso = nowIso(),
            )
        }

        val woken = mutableListOf<WokenSession>()
        val depthCeilingRefused = mutableListOf<String>()
        val alreadyRunning = mutableListOf<String>()

        for (sessionId in match.woken) {
            // Publish SessionWoken before touching session state -- if it's refused (depth
            // ceiling), there is no valid trigger_event_id to create a Run with, and nothing
            // about the session has changed yet, so there's nothing to roll back.
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
                    actorKind = "SESSION",
                    actorId = sessionId,
                    subjectRef = event.type,
                    detailJson = buildJsonObject {
                        put("reason", "causal_depth_ceiling")
                        put("event_id", event.id)
                    }.toString(),
                    nowIso = nowIso(),
                )
                depthCeilingRefused.add(sessionId)
                continue
            }

            // The session transition and the Run it's woken into must be atomic (D3): a session
            // left RUNNING with no Run to drive it would never transition back to SLEEPING. The
            // SessionWoken event above is informational (same tier as drive()'s own
            // RunStepCompleted/ToolCompleted publishes, which aren't transactional with the task
            // state change they describe either) -- in the rare case a session stops being
            // SLEEPING between the match and this point, the event stays published (an honest
            // record that a wake was attempted) but nothing else happens.
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
            if (created != null) woken.add(created) else alreadyRunning.add(sessionId)
        }

        return WakeResult(woken, match.selfWakeRefused, depthCeilingRefused, alreadyRunning)
    }

    private fun updateSessionToRunning(sessionId: String): Long =
        driver.execute(
            identifier = null,
            sql = "UPDATE sessions SET state = 'RUNNING', state_updated_at = ?, last_active_at = ? " +
                "WHERE id = ? AND state = 'SLEEPING'",
            parameters = 3,
        ) {
            bindString(0, nowIso())
            bindString(1, nowIso())
            bindString(2, sessionId)
        }.value
}
