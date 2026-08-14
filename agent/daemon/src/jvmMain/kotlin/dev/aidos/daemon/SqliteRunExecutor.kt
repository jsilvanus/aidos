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
 * **Drives the sender's own Run inline, when [compositionRoot] is wired.** RFC-0044's own
 * "Background work classes" table classifies "a Run the user just started" as **Interactive**,
 * and Interactive's mechanism is **inline** on desktop (a foreground service on MOBILE exists
 * precisely to keep that same inline call alive, per D24 — it is not a different execution
 * model). `sessions.send()` is exactly that case, so once a real composition exists to drive
 * through, `send()` is where RFC-0044 says the driving belongs — not a background dispatcher.
 * [compositionRoot] is nullable (unset preserves the old PENDING-only behavior) following the
 * same seam idiom as `RealRuntimeClient`'s own `projectDbFactory`/`runExecutor` fields: a
 * composition root is substantial enough to build and inject separately, not force through as a
 * side effect of this class's own construction.
 *
 * **Sessions woken by [Scheduler.wake] on the same event are deliberately left un-driven.** An
 * event-driven wake is not "a Run the user just started" — RFC-0044 classifies it Deferred /
 * Scheduled / Opportunistic, whose mechanism is `WorkManager` / a background dispatcher, not
 * inline. Driving those here would be answering a different RFC-0044 row with this one's
 * mechanism; that dispatch is separate, already-partially-built infrastructure
 * (`androidapp/scheduling/`), not this class's job.
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
    private val compositionRoot: RuntimeCompositionRoot? = null,
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

        compositionRoot?.drive(
            projectDriver = projectDriver,
            runId = runId,
            projectId = ProjectId(projectId),
            sessionId = sessionId,
            deviceId = deviceId,
            platformProfile = platformProfile,
            networkAvailable = networkAvailable,
            idGen = idGen,
            nowIso = nowIso,
        )

        return RunResult.Accepted(runId.value)
    }
}
