package dev.aidos.voice

import dev.aidos.androidapp.runsummary.ExecutionGraphRow
import dev.aidos.androidapp.runsummary.RunSummaryReport
import dev.aidos.androidapp.runsummary.RunStatus

/**
 * Generates spoken summaries from RunSummaryReport (M33, RFC-0057).
 *
 * The spoken form is templated from the same fields the RunSummaryReport uses,
 * with no model call. Numbers are spoken as quantities, paths as paths.
 * Nothing in the spoken form is free text from a model or from a file.
 *
 * RFC-0057 Design: The spoken form is deterministic and its slots are structured values,
 * which keeps it auditable: if the user asks "why did it say that", the slot values are
 * in the execution graph.
 */
object SpokenSummaryGenerator {

    /**
     * Generate a spoken summary for a Run (RFC-0057).
     *
     * Example: "Aidos is paused on the aidos project, six steps in. It changed three files
     * in the HTTP client — forty-seven lines added, twelve removed. One thing needs you:
     * writing to build.gradle.kts, which is outside the source tree. Say approve, say skip,
     * or say details."
     *
     * Rules:
     * - No model-generated prose, only runtime-owned fields
     * - Project name from context (not shown here)
     * - Step count (totalSteps)
     * - File changes (collapsed counts only, never individual files unless pending)
     * - Pending approvals (shown as "things that need you")
     * - Errors (if any)
     * - Status (RUNNING → "so far", COMPLETED → implicit, FAILED → name the error)
     *
     * @param summary the RunSummaryReport computed from execution graph
     * @param projectName the project being worked on (runtime-owned)
     * @param sessionName the session name (runtime-owned)
     * @return the spoken form ready for TTS
     */
    fun generate(
        summary: RunSummaryReport,
        projectName: String,
        sessionName: String,
    ): String {
        val parts = mutableListOf<String>()

        // Opening: "Aidos is [status] on the [project], [step] steps in."
        val statusWord = when (summary.status) {
            RunStatus.RUNNING -> "running"
            RunStatus.COMPLETED -> "done"
            RunStatus.FAILED -> "failed"
        }
        parts.add("Aidos is $statusWord on the $projectName project for session $sessionName")
        
        if (summary.status != RunStatus.COMPLETED || summary.totalSteps > 0) {
            parts.add("${summarizeNumber(summary.totalSteps)} steps")
            if (summary.status == RunStatus.RUNNING) {
                parts[parts.lastIndex] += " so far"
            }
        }

        // Changes: "It changed [N] files — [adds] added, [removes] removed."
        // Only if there are changes (not shown for read-only runs).
        // TODO: integrate with file change tracking from Preview.Diff aggregation

        // Pending approvals: "One thing needs you: [what]."
        if (summary.pendingApprovals.isNotEmpty()) {
            val pendingText = summarizePendingApprovals(summary.pendingApprovals)
            parts.add(pendingText)
        }

        // Errors: "Something went wrong: [error class]."
        if (summary.errors.isNotEmpty()) {
            val errorText = summarizeErrors(summary.errors)
            parts.add(errorText)
        }

        // Egress: "One request left the device: [destination/reason]."
        if (summary.egressSteps.isNotEmpty()) {
            val egressText = summarizeEgress(summary.egressSteps)
            parts.add(egressText)
        }

        // Out-of-project mutations: "One change is outside the project: [path]."
        if (summary.outOfProjectMutations.isNotEmpty()) {
            val outText = summarizeOutOfProject(summary.outOfProjectMutations)
            parts.add(outText)
        }

        // INDETERMINATE outcomes: "One result is unknown: [what tool]."
        if (summary.indeterminateSteps.isNotEmpty()) {
            val indeterminateText = summarizeIndeterminate(summary.indeterminateSteps)
            parts.add(indeterminateText)
        }

        // Closing instruction: "Say approve, say skip, or say details."
        if (summary.pendingApprovals.isNotEmpty()) {
            parts.add("Say approve to proceed, say skip to defer, or say details to see more")
        }

        return parts.joinToString(". ") + "."
    }

    private fun summarizeNumber(count: Int): String = when (count) {
        1 -> "one"
        2 -> "two"
        3 -> "three"
        4 -> "four"
        5 -> "five"
        6 -> "six"
        7 -> "seven"
        8 -> "eight"
        9 -> "nine"
        10 -> "ten"
        else -> count.toString()
    }

    private fun summarizePendingApprovals(pending: List<ExecutionGraphRow>): String {
        return when {
            pending.isEmpty() -> ""
            pending.size == 1 -> "One thing needs you"
            else -> "${summarizeNumber(pending.size)} things need you"
        }
    }

    private fun summarizeErrors(errors: List<ExecutionGraphRow>): String {
        return when {
            errors.isEmpty() -> ""
            errors.size == 1 -> "One step failed"
            else -> "${summarizeNumber(errors.size)} steps failed"
        }
    }

    private fun summarizeEgress(egress: List<ExecutionGraphRow>): String {
        return when {
            egress.isEmpty() -> ""
            egress.size == 1 -> "One request left the device"
            else -> "${summarizeNumber(egress.size)} requests left the device"
        }
    }

    private fun summarizeOutOfProject(outOfProject: List<ExecutionGraphRow>): String {
        return when {
            outOfProject.isEmpty() -> ""
            outOfProject.size == 1 -> "One change is outside the project"
            else -> "${summarizeNumber(outOfProject.size)} changes are outside the project"
        }
    }

    private fun summarizeIndeterminate(indeterminate: List<ExecutionGraphRow>): String {
        return when {
            indeterminate.isEmpty() -> ""
            indeterminate.size == 1 -> "One result is unknown"
            else -> "${summarizeNumber(indeterminate.size)} results are unknown"
        }
    }
}
