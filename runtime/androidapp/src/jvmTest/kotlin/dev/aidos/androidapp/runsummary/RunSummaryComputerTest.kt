package dev.aidos.androidapp.runsummary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for RunSummaryComputer benign classifier (M32b, RFC-0057 D26).
 *
 * RFC-0057 D26 specifies four conditions for benign approval (TIER1 voice):
 * 1. Effect is Read or Mutate(IN_PROJECT) — tool is read-only or in-project mutation
 * 2. Recovery is not UNSAFE — the operation can be safely retried or undone
 * 3. Run.taint is TRUSTED — no adversary in execution context
 * 4. Capability already granted — exercise of existing authority, not a new grant
 */
class RunSummaryComputerTest {

    @Test
    fun `benign classifier requires all four conditions`() {
        val baseRow = ExecutionGraphRow(
            stepIndex = 1,
            toolName = "git_status",
            outcome = StepOutcome.SUCCESS,
            isEgress = false,
            isOutOfProjectMutation = false,
            pendingApproval = false,
            recoveryClass = "PURE",
            runTaintIsTrusted = true,
            capabilityAlreadyGranted = true,
        )

        // All conditions met: benign
        assertTrue(RunSummaryComputer.isBenign(baseRow), "all 4 conditions met should be benign")

        // Condition 1 fails: egress
        assertFalse(
            RunSummaryComputer.isBenign(baseRow.copy(isEgress = true)),
            "egress should not be benign"
        )

        // Condition 1 fails: out-of-project mutation
        assertFalse(
            RunSummaryComputer.isBenign(baseRow.copy(isOutOfProjectMutation = true)),
            "out-of-project mutation should not be benign"
        )

        // Condition 1 fails: tool not in read-only set (no egress, no out-of-project, but unknown tool)
        assertFalse(
            RunSummaryComputer.isBenign(baseRow.copy(toolName = "unknown_tool")),
            "unknown tool should not be benign"
        )

        // Condition 2 fails: recovery is UNSAFE
        assertFalse(
            RunSummaryComputer.isBenign(baseRow.copy(recoveryClass = "UNSAFE")),
            "UNSAFE recovery should not be benign"
        )

        // Condition 2 fails: recovery is unknown (null)
        assertFalse(
            RunSummaryComputer.isBenign(baseRow.copy(recoveryClass = null)),
            "unknown recovery class should not be benign (be conservative)"
        )

        // Condition 3 fails: run taint is not trusted
        assertFalse(
            RunSummaryComputer.isBenign(baseRow.copy(runTaintIsTrusted = false)),
            "untrusted run should not be benign"
        )

        // Condition 3 fails: run taint is unknown (null)
        assertFalse(
            RunSummaryComputer.isBenign(baseRow.copy(runTaintIsTrusted = null)),
            "unknown taint level should not be benign (be conservative)"
        )

        // Condition 4 fails: capability not granted (new capability grant)
        assertFalse(
            RunSummaryComputer.isBenign(baseRow.copy(capabilityAlreadyGranted = false)),
            "new capability grant should not be benign"
        )

        // Condition 4 fails: capability status unknown (null)
        assertFalse(
            RunSummaryComputer.isBenign(baseRow.copy(capabilityAlreadyGranted = null)),
            "unknown capability status should not be benign (be conservative)"
        )
    }

    @Test
    fun `known read-only tools pass condition 1`() {
        val readOnlyTools = listOf("fs_read", "git_status", "git_log", "git_diff", "list_dir")
        val baseRow = ExecutionGraphRow(
            stepIndex = 1,
            toolName = "fs_read",
            outcome = StepOutcome.SUCCESS,
            isEgress = false,
            isOutOfProjectMutation = false,
            pendingApproval = false,
            recoveryClass = "IDEMPOTENT",
            runTaintIsTrusted = true,
            capabilityAlreadyGranted = true,
        )

        for (toolName in readOnlyTools) {
            assertTrue(
                RunSummaryComputer.isBenign(baseRow.copy(toolName = toolName)),
                "read-only tool '$toolName' should pass condition 1 (with all other conditions met)"
            )
        }
    }

    @Test
    fun `recovery classes affect benign decision`() {
        val baseRow = ExecutionGraphRow(
            stepIndex = 1,
            toolName = "git_status",
            outcome = StepOutcome.SUCCESS,
            isEgress = false,
            isOutOfProjectMutation = false,
            pendingApproval = false,
            runTaintIsTrusted = true,
            capabilityAlreadyGranted = true,
        )

        // PURE, IDEMPOTENT, CHECKABLE are safe
        assertTrue(
            RunSummaryComputer.isBenign(baseRow.copy(recoveryClass = "PURE")),
            "PURE recovery should be benign"
        )
        assertTrue(
            RunSummaryComputer.isBenign(baseRow.copy(recoveryClass = "IDEMPOTENT")),
            "IDEMPOTENT recovery should be benign"
        )
        assertTrue(
            RunSummaryComputer.isBenign(baseRow.copy(recoveryClass = "CHECKABLE")),
            "CHECKABLE recovery should be benign"
        )

        // UNSAFE is never benign
        assertFalse(
            RunSummaryComputer.isBenign(baseRow.copy(recoveryClass = "UNSAFE")),
            "UNSAFE recovery should not be benign"
        )
    }

    @Test
    fun `run summary computation separates collapsible from non-collapsible rows`() {
        val rows = listOf(
            ExecutionGraphRow(1, "fs_read", StepOutcome.SUCCESS, false, false, false),
            ExecutionGraphRow(2, "git_log", StepOutcome.SUCCESS, false, false, false),
            ExecutionGraphRow(3, "fs_write", StepOutcome.SUCCESS, false, true, false),  // out-of-project
            ExecutionGraphRow(4, "fs_read", StepOutcome.ERROR, false, false, false),     // error
            ExecutionGraphRow(5, "git_commit", StepOutcome.SUCCESS, false, false, true), // pending approval
        )

        val report = RunSummaryComputer.compute(rows, RunStatus.COMPLETED)

        assertEquals(5, report.totalSteps)
        assertEquals(2, report.collapsedStepCount, "steps 1-2 should collapse")
        assertEquals(1, report.outOfProjectMutations.size)
        assertEquals(1, report.errors.size)
        assertEquals(1, report.pendingApprovals.size)
    }

    @Test
    fun `never-collapse rule for INDETERMINATE outcomes`() {
        val rows = listOf(
            ExecutionGraphRow(1, "tool", StepOutcome.INDETERMINATE, false, false, false),
            ExecutionGraphRow(2, "tool", StepOutcome.SUCCESS, false, false, false),
        )

        val report = RunSummaryComputer.compute(rows, RunStatus.RUNNING)

        assertEquals(1, report.indeterminateSteps.size)
        assertEquals(0, report.collapsedStepCount, "no steps should collapse with INDETERMINATE present")
    }
}
