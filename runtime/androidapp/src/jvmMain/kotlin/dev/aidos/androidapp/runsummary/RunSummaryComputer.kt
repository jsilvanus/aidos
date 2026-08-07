package dev.aidos.androidapp.runsummary

/**
 * Run Summary and the benign-approval classifier (M32b, RFC-0057, RFC-0019, D26).
 *
 * One page, no scrolling, computed from execution graph rows with no model call.
 *
 * Rules:
 * - Pending approvals, errors, egress, out-of-project mutation, and INDETERMINATE outcomes
 *   NEVER collapse — they always show individually.
 * - A RUNNING run reads "so far".
 * - The classifier is a security boundary — approval cards use it to decide what auto-approves.
 * - NOT CUTTABLE: the classifier is required by the approval card regardless.
 *
 * The `isBenign` classifier is the security boundary: it must be conservative —
 * when in doubt, return false (requires human approval).
 *
 * RFC-0057 D26 specifies four conditions for benign approval (TIER1 voice):
 * 1. Effect is Read or Mutate(IN_PROJECT) — tool is read-only or in-project mutation
 * 2. Recovery is not UNSAFE — the operation can be safely retried or undone
 * 3. Run.taint is TRUSTED — no adversary in execution context
 * 4. Capability already granted — exercise of existing authority, not a new grant
 */

/** A row in the execution graph (subset needed for run summary). */
data class ExecutionGraphRow(
    val stepIndex: Int,
    val toolName: String?,
    val outcome: StepOutcome,
    val isEgress: Boolean,
    val isOutOfProjectMutation: Boolean,
    val pendingApproval: Boolean,
    /**
     * Recovery class from attempts: PURE | IDEMPOTENT | CHECKABLE | UNSAFE.
     * Null if no attempt yet (still pending).
     */
    val recoveryClass: String? = null,
    /**
     * Whether the Run's taint level is TRUSTED (true) or UNTRUSTED/TAINTED (false).
     * Null means unknown/not set.
     */
    val runTaintIsTrusted: Boolean? = null,
    /**
     * Whether this Task's capability_id is set (true = already granted) or null (false = new grant).
     * Null means unknown.
     */
    val capabilityAlreadyGranted: Boolean? = null,
)

enum class StepOutcome {
    SUCCESS, ERROR, INDETERMINATE, RUNNING
}

/** The run summary computed from execution graph rows. */
data class RunSummaryReport(
    val status: RunStatus,
    val totalSteps: Int,
    val pendingApprovals: List<ExecutionGraphRow>,
    val errors: List<ExecutionGraphRow>,
    val egressSteps: List<ExecutionGraphRow>,
    val outOfProjectMutations: List<ExecutionGraphRow>,
    val indeterminateSteps: List<ExecutionGraphRow>,
    /** Steps that collapse safely into "N steps completed". */
    val collapsedStepCount: Int,
)

enum class RunStatus { RUNNING, COMPLETED, FAILED }

object RunSummaryComputer {

    /**
     * Computes the run summary from [rows]. This must complete with no model call (D26).
     *
     * Non-collapsible rows (always shown individually):
     * - `pendingApproval == true`
     * - `outcome == ERROR`
     * - `isEgress == true`
     * - `isOutOfProjectMutation == true`
     * - `outcome == INDETERMINATE`
     *
     * Additionally, if ANY step is INDETERMINATE, ALL steps are shown (no collapsing),
     * because INDETERMINATE indicates an uncertain state that requires full visibility.
     */
    fun compute(rows: List<ExecutionGraphRow>, status: RunStatus): RunSummaryReport {
        val pending = rows.filter { it.pendingApproval }
        val errors = rows.filter { it.outcome == StepOutcome.ERROR }
        val egress = rows.filter { it.isEgress }
        val outOfProject = rows.filter { it.isOutOfProjectMutation }
        val indeterminate = rows.filter { it.outcome == StepOutcome.INDETERMINATE }

        // If ANY step is INDETERMINATE, show everything (no collapsing).
        // INDETERMINATE means the result is unknown, so the Run's state is uncertain.
        val hasIndeterminate = indeterminate.isNotEmpty()
        
        // The non-collapsible set is the union of all the above.
        val nonCollapsible = (pending + errors + egress + outOfProject + indeterminate)
            .map { it.stepIndex }.toSet()

        // If INDETERMINATE present, all steps are non-collapsible.
        val effectiveNonCollapsible = if (hasIndeterminate) {
            rows.map { it.stepIndex }.toSet()
        } else {
            nonCollapsible
        }

        val collapsedCount = rows.count { it.stepIndex !in effectiveNonCollapsible }

        return RunSummaryReport(
            status = status,
            totalSteps = rows.size,
            pendingApprovals = pending,
            errors = errors,
            egressSteps = egress,
            outOfProjectMutations = outOfProject,
            indeterminateSteps = indeterminate,
            collapsedStepCount = collapsedCount,
        )
    }

    /**
     * Benign-approval classifier (M32b security boundary, RFC-0057 D26).
     *
     * Returns true ONLY if ALL four conditions hold:
     * 1. Effect is Read or Mutate(IN_PROJECT) — check tool name (read-only set)
     * 2. Recovery is not UNSAFE — recoveryClass must be PURE, IDEMPOTENT, or CHECKABLE
     * 3. Run.taint is TRUSTED — runTaintIsTrusted must be true
     * 4. Capability already granted — capabilityAlreadyGranted must be true
     *
     * When ANY condition fails or is unknown, returns false — the approval card must show.
     * This is intentionally conservative: we fail closed to requiring explicit approval.
     */
    fun isBenign(row: ExecutionGraphRow): Boolean {
        // Condition 1: Effect is Read or Mutate(IN_PROJECT)
        // Only known read-only tools are benign. Mutations are only benign if in-project.
        if (row.isEgress) return false  // egress is tier3, never benign
        if (row.isOutOfProjectMutation) return false  // out-of-project is tier2 at best

        // If tool is in the read-only set, condition 1 passes. Otherwise, condition 1 fails
        // (we treat unknown or mutation tools as non-benign).
        val readOnlyTools = setOf("fs_read", "git_status", "git_log", "git_diff", "list_dir")
        val condition1Passes = row.toolName in readOnlyTools

        if (!condition1Passes) return false  // Condition 1 failed

        // Condition 2: Recovery is not UNSAFE
        // Benign approvals must be reversible/retryable. UNSAFE effects can have unknown outcomes.
        val unsafeRecoveryClasses = setOf("UNSAFE", null)  // null = unknown, be conservative
        if (row.recoveryClass in unsafeRecoveryClasses) return false

        // Condition 3: Run.taint is TRUSTED
        if (row.runTaintIsTrusted != true) return false  // null or false = not trusted

        // Condition 4: Capability already granted
        // A benign approval exercises existing authority, never grants new capability.
        if (row.capabilityAlreadyGranted != true) return false

        // All four conditions passed: this is benign.
        return true
    }
}
