package dev.aidos.androidapp.intent

/**
 * Intent as a task list, with the proposal gate (M32c, RFC-0012, RFC-0019, D6, D10, D20).
 *
 * Built last and small (D20). Rules:
 *
 * 1. **Flat goals** — title, description, priority. No hierarchy, no dependencies.
 * 2. **Status is derived**, never stored — so reverted or partially-failed runs cannot
 *    leave a stale status field. A user override is a timestamped claim shown alongside
 *    the derived value.
 * 3. **TARGETED edges** from Runs — so the list knows what is being worked on.
 * 4. **The proposal gate** — a session may only PROPOSE; a user RESOLVES.
 *    There is no SESSION variant of "resolved" by construction.
 * 5. Both derived status and the gate are NON-DEFERRABLE (D20 rationale explains why).
 */

enum class IntentPriority { HIGH, MEDIUM, LOW }

/** A flat intent item. Status is always derived (never stored). */
data class IntentItem(
    val id: String,
    val title: String,
    val description: String,
    val priority: IntentPriority,
    /** Which run is currently targeting this intent, if any. */
    val targetedByRunId: String? = null,
    /** User override — a timestamped claim shown alongside derived status (D20). */
    val userOverride: UserStatusOverride? = null,
)

data class UserStatusOverride(
    val claimedStatus: DerivedIntentStatus,
    val overriddenAt: String,  // ISO timestamp
    val overriddenByUserId: String,
)

/**
 * Derived intent status. Computed from run history — never stored (D20).
 *
 * Computing this from stored status would mean reverted runs could leave stale data.
 * Computing from run history means a revert automatically reflects in the status.
 */
enum class DerivedIntentStatus {
    /** No run has targeted this intent. */
    NOT_STARTED,
    /** A run is currently targeting this intent. */
    IN_PROGRESS,
    /** A run targeting this intent completed successfully. */
    COMPLETED,
    /** A run targeting this intent failed or was reverted. */
    FAILED,
}

/** A proposal from a session (the proposal gate — M32c). */
data class IntentProposal(
    val proposalId: String,
    val proposedBySessionId: String,
    val title: String,
    val description: String,
    val priority: IntentPriority,
    val proposedAt: String,  // ISO timestamp
)

/** A resolved proposal — only users can resolve (the proposal gate). */
data class ResolvedProposal(
    val proposalId: String,
    val resolvedByUserId: String,
    val resolvedAt: String,
    val accepted: Boolean,
    /** If accepted, the created intent item. */
    val createdItem: IntentItem?,
)

/**
 * Derives the current status of an intent from run history.
 *
 * This function is pure — given the same inputs it always returns the same output.
 * It is never stored to the database (D20).
 */
fun deriveIntentStatus(
    intentId: String,
    runHistory: List<RunHistoryEntry>,
): DerivedIntentStatus {
    val targeting = runHistory.filter { it.targetedIntentId == intentId }
    if (targeting.isEmpty()) return DerivedIntentStatus.NOT_STARTED
    val running = targeting.any { it.isRunning }
    if (running) return DerivedIntentStatus.IN_PROGRESS
    val lastCompleted = targeting.filter { it.completedSuccessfully }.maxByOrNull { it.completedAt ?: "" }
    val lastFailed = targeting.filter { !it.completedSuccessfully }.maxByOrNull { it.completedAt ?: "" }
    return when {
        lastCompleted != null && (lastFailed == null || lastCompleted.completedAt!! > lastFailed.completedAt!!) ->
            DerivedIntentStatus.COMPLETED
        lastFailed != null -> DerivedIntentStatus.FAILED
        else -> DerivedIntentStatus.NOT_STARTED
    }
}

data class RunHistoryEntry(
    val runId: String,
    val targetedIntentId: String?,
    val isRunning: Boolean,
    val completedSuccessfully: Boolean,
    val completedAt: String?,  // ISO timestamp, null if still running
)
