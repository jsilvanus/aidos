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
 */

/** A row in the execution graph (subset needed for run summary). */
data class ExecutionGraphRow(
    val stepIndex: Int,
    val toolName: String?,
    val outcome: StepOutcome,
    val isEgress: Boolean,
    val isOutOfProjectMutation: Boolean,
    val pendingApproval: Boolean,
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
     */
    fun compute(rows: List<ExecutionGraphRow>, status: RunStatus): RunSummaryReport {
        val pending = rows.filter { it.pendingApproval }
        val errors = rows.filter { it.outcome == StepOutcome.ERROR }
        val egress = rows.filter { it.isEgress }
        val outOfProject = rows.filter { it.isOutOfProjectMutation }
        val indeterminate = rows.filter { it.outcome == StepOutcome.INDETERMINATE }

        // The non-collapsible set is the union of all the above.
        val nonCollapsible = (pending + errors + egress + outOfProject + indeterminate)
            .map { it.stepIndex }.toSet()

        val collapsedCount = rows.count { it.stepIndex !in nonCollapsible }

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
     * Benign-approval classifier (M32b security boundary).
     *
     * Returns true ONLY if the step is definitively safe to auto-approve:
     * - No egress
     * - No out-of-project mutation
     * - No INDETERMINATE outcome
     * - No pending approval already flagged
     * - Tool is a known read-only operation
     *
     * When in doubt, returns false — the approval card must show.
     */
    fun isBenign(row: ExecutionGraphRow): Boolean {
        if (row.isEgress) return false
        if (row.isOutOfProjectMutation) return false
        if (row.outcome == StepOutcome.INDETERMINATE) return false
        if (row.pendingApproval) return false
        // Only known read-only tools are benign.
        val readOnlyTools = setOf("fs_read", "git_status", "git_log", "git_diff", "list_dir")
        return row.toolName in readOnlyTools
    }
}
