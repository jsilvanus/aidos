package dev.aidos.knowledge

import dev.aidos.kernel.GuaranteeClass
import dev.aidos.kernel.ScheduledJob
import dev.aidos.kernel.ScheduledJobId
import dev.aidos.kernel.Trigger
import dev.aidos.kernel.WorkClass
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject

/**
 * Background indexing job (Phase 4: Background indexing integration, RFC-0044).
 *
 * Schedules semantic indexing as a background job with auto-resume on eviction.
 * - Runs with OPPORTUNISTIC guarantee (best-effort, no crash recovery)
 * - Updates progress via notifications when available
 * - Reports coverage improvements as blobs are embedded
 * - Auto-resumes from last checkpoint on process eviction
 *
 * Phase 4 wiring:
 * 1. When a project opens, create an IndexingJob via [createForProject]
 * 2. Submit to ScheduledJobManager with OPPORTUNISTIC class
 * 3. On scheduled trigger, call [execute] with RuntimeClient.knowledge
 * 4. Job records progress and can be resumed on next trigger
 * 5. On model load (M21), coverage improves and indexing accelerates
 */
class IndexingJob {
    /**
     * Create a scheduled indexing job for a project (Phase 4).
     *
     * Returns a ScheduledJob configured for:
     * - OPPORTUNISTIC guarantee (no crash recovery, best-effort)
     * - Repeating trigger every N minutes (configurable, default 30)
     * - BACKGROUND work class (does not block foreground)
     * - Runs until complete or preempted
     */
    companion object {
        /**
         * Factory for creating indexing ScheduledJobs (Phase 4).
         * 
         * @param projectId Project to index
         * @param sessionId Optional session for the indexing job
         * @param intervalMinutes Repeat interval (default 30 minutes for opportunistic indexing)
         */
        fun createForProject(
            projectId: String,
            sessionId: String? = null,
            intervalMinutes: Int = 30,
        ): ScheduledJob {
            val id = ScheduledJobId("indexing-$projectId-${Clock.System.now().toEpochMilliseconds()}")
            val now = Clock.System.now()
            return ScheduledJob(
                id = id,
                projectId = projectId,
                sessionId = sessionId,
                name = "Index project $projectId",
                trigger = Trigger.Every(
                    anchor = now,
                    intervalSeconds = intervalMinutes * 60,
                ),
                guaranteeClass = GuaranteeClass.OPPORTUNISTIC,  // Phase 4: best-effort, no crash recovery
                workClass = WorkClass.BACKGROUND,  // Phase 4: doesn't block foreground
                constraintsJson = "{}",  // TODO: Phase 4 - add charging/unmetered constraints
                enabled = true,
                nextRunAt = now,  // Can start immediately
                lastRunAt = null,
                lastOutcome = null,
                createdAt = now,
            )
        }

        /**
         * Create an indexing job that runs once (Phase 4: manual trigger).
         */
        fun createOnce(
            projectId: String,
            sessionId: String? = null,
        ): ScheduledJob {
            val id = ScheduledJobId("index-once-$projectId-${Clock.System.now().toEpochMilliseconds()}")
            val now = Clock.System.now()
            return ScheduledJob(
                id = id,
                projectId = projectId,
                sessionId = sessionId,
                name = "Index project $projectId (once)",
                trigger = Trigger.At(now),
                guaranteeClass = GuaranteeClass.OPPORTUNISTIC,
                workClass = WorkClass.BACKGROUND,
                enabled = true,
                nextRunAt = now,
                lastRunAt = null,
                lastOutcome = null,
                createdAt = now,
            )
        }
    }
}

