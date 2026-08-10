package dev.aidos.daemon

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.api.RunExecutor
import dev.aidos.api.RunResult
import dev.aidos.api.UserMessage
import dev.aidos.broker.AuditLog
import dev.aidos.executor.EventStore
import dev.aidos.executor.EventTypes
import dev.aidos.executor.RunCreator
import dev.aidos.executor.Scheduler
import dev.aidos.executor.SessionSubscriptionStore
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.EventId
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.SessionId

/**
 * The real [RunExecutor] (RFC-0008/0009): publishes the triggering event, then creates the durable
 * Run via [RunCreator]. Composed here rather than living in `api` because `api` cannot depend on
 * `executor` without a module cycle — see [RunExecutor]'s own doc comment.
 *
 * **Deliberately does not call `drive()`.** Driving a Run to a model response needs a real
 * `InferenceRouter` + `PromptAssembler` + `EffectBroker` (a `CapabilityManager` with actual tools
 * registered) — none of which exist anywhere in this composition root yet. That is separate,
 * substantial follow-up work (real tool registration, real model routing), not a side effect of
 * wiring `sessions.send()`. The Run this creates is durable and `PENDING`, ready for whoever
 * builds that composition to call `SqliteExecutor.drive()` on it — nothing is lost by not driving
 * it immediately, since D3 already requires every step to be reconstructable from rows alone.
 *
 * After the sending session's own Run is created, [Scheduler.wake] is called on the same
 * `UserCommand` event (RFC-0005) so any *other* session subscribed to it — a driver watching for
 * a particular user command, say — wakes too. `sourceSessionId = sessionId` because the sending
 * session is who caused the publish; that's what lets [Scheduler] refuse a self-wake correctly if
 * the sender happens to also be subscribed to its own `UserCommand`.
 */
class SqliteRunExecutor(
    private val idGen: () -> String = { UuidV7Generator().next() },
    private val nowIso: () -> String,
) : RunExecutor {

    override suspend fun send(
        projectDriver: SqlDriver,
        projectId: String,
        sessionId: String,
        message: UserMessage,
        platformProfile: PlatformProfile,
        deviceId: String,
        networkAvailable: Boolean,
    ): RunResult {
        val events = EventStore(projectDriver)
        val eventId = idGen()
        val sequence = events.publish(
            id = eventId,
            projectId = projectId,
            type = EventTypes.USER_COMMAND,
            source = "session:$sessionId",
            payload = "{}",
            nowIso = nowIso(),
        )
        if (sequence == null) {
            // MAX_CAUSAL_DEPTH refusal cannot apply to a depth-0 publish (no causedBy) -- this
            // branch exists only because publish()'s return type says it's possible, not because
            // it's reachable from here today.
            return RunResult.Error("run.event_publish_failed", "Could not publish trigger event for session $sessionId")
        }

        val runCreator = RunCreator(projectDriver, idGen, nowIso)
        val runId = runCreator.createForUserMessage(
            sessionId = SessionId(sessionId),
            projectId = ProjectId(projectId),
            triggerEventId = EventId(eventId),
            userMessageSummary = message.content,
            platformProfile = platformProfile,
            deviceId = deviceId,
            networkAvailable = networkAvailable,
        )

        val eventRow = events.eventsForProject(projectId, type = EventTypes.USER_COMMAND)
            .last { it.id == eventId }
        Scheduler(
            driver = projectDriver,
            events = events,
            subscriptions = SessionSubscriptionStore(projectDriver),
            runCreator = runCreator,
            audit = AuditLog(projectDriver, deviceId),
            idGen = idGen,
            nowIso = nowIso,
        ).wake(
            event = eventRow,
            sourceSessionId = sessionId,
            projectId = ProjectId(projectId),
            platformProfile = platformProfile,
            deviceId = deviceId,
            networkAvailable = networkAvailable,
        )

        return RunResult.Accepted(runId.value)
    }
}
