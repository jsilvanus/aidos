package dev.aidos.executor

/**
 * Pure decision function for RFC-0005's "Matching" and "Cycles and amplification" sections
 * (MVP items 1 and 3).
 *
 * This is deliberately **not** wired into event publication or the `drive()` loop yet: matching
 * is pure and cheap by RFC-0005's own rule ("never reads the filesystem and never calls a
 * model"), but *acting* on a match — transitioning a session out of `SLEEPING` and creating a
 * Run for it — touches the step-machine invariants D3/D14 guard, and that wiring is its own,
 * separate, more careful piece of work. This function only answers "which sessions would wake,
 * and which self-wakes were refused" for a caller to act on.
 *
 * [sourceSessionId] is the session that caused the event's publication, if any — the caller
 * knows this because they are the one publishing on the session's behalf. It is **not** derived
 * from [EventRow.source] here: there is no established convention yet for encoding a session's
 * identity in that field (grepping the codebase for one turned up nothing), and inventing one as
 * a side effect of this function would be a bigger, unreviewed decision than this slice should
 * make. Callers that don't yet track a source session pass `null`, which disables self-wake
 * refusal for that call (nothing to refuse) rather than silently guessing wrong.
 */
object SchedulerMatcher {

    /** The outcome of matching one published event against a project's subscriptions. */
    data class MatchResult(
        /** Session IDs whose subscription matched and were not refused. */
        val woken: List<String>,
        /** Session IDs whose subscription matched but were refused because it was self-sourced. */
        val selfWakeRefused: List<String>,
    )

    fun match(
        event: EventRow,
        sourceSessionId: String?,
        subscriptions: List<SessionSubscriptionRow>,
    ): MatchResult {
        val woken = mutableListOf<String>()
        val refused = mutableListOf<String>()

        for (sub in subscriptions) {
            val topicMatches = TopicMatcher.matchesAny(sub.topicPatterns, event.topic)
            val typeMatches = sub.eventTypes == null || sub.eventTypes.isEmpty() || event.type in sub.eventTypes
            if (!topicMatches || !typeMatches) continue

            val isSelfWake = sourceSessionId != null && sourceSessionId == sub.sessionId
            if (isSelfWake && !sub.selfWake) {
                refused.add(sub.sessionId)
            } else {
                woken.add(sub.sessionId)
            }
        }

        return MatchResult(woken = woken.distinct(), selfWakeRefused = refused.distinct())
    }
}
