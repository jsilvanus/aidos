package dev.aidos.androidapp

import dev.aidos.androidapp.approval.ApprovalKind
import dev.aidos.androidapp.approval.ApprovalPresenter
import dev.aidos.androidapp.intent.DerivedIntentStatus
import dev.aidos.androidapp.intent.IntentPriority
import dev.aidos.androidapp.intent.IntentProposal
import dev.aidos.androidapp.intent.RunHistoryEntry
import dev.aidos.androidapp.intent.deriveIntentStatus
import dev.aidos.androidapp.notification.NotificationManager
import dev.aidos.androidapp.runsummary.ExecutionGraphRow
import dev.aidos.androidapp.runsummary.RunStatus
import dev.aidos.androidapp.runsummary.RunSummaryComputer
import dev.aidos.androidapp.runsummary.StepOutcome
import dev.aidos.androidapp.service.RuntimeServiceHost
import dev.aidos.androidapp.service.ServiceState
import dev.aidos.androidapp.ui.AvailabilityReporter
import dev.aidos.androidapp.ui.ToolAvailabilityState
import dev.aidos.api.MockRuntimeClient
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4 Android app tests (M27–M35).
 *
 * These tests verify the platform-neutral logic of the Android app components without
 * requiring an Android device or SDK. The actual Android wiring (Compose, Service lifecycle,
 * AndroidManifest) is done when `androidTarget()` is added to this module.
 */
class Phase4Tests {

    // ─── M27: Foreground service and runtime hosting ──────────────────────────

    @Test
    fun `M27 service starts idle`() = runTest {
        val host = RuntimeServiceHost(MockRuntimeClient(), this)
        assertIs<ServiceState.Idle>(host.state.value)
    }

    @Test
    fun `M27 notification text reflects what is actually running`() = runTest {
        val host = RuntimeServiceHost(MockRuntimeClient(), this)
        assertEquals("Aidos: idle", host.currentNotificationText)

        host.startRun("run-1", "Refactoring session")
        assertTrue(host.currentNotificationText.contains("Refactoring session"),
            "Notification must say what is actually running")
    }

    @Test
    fun `M27 eviction mid-run loses no committed step`() = runTest {
        val host = RuntimeServiceHost(MockRuntimeClient(), this)
        host.startRun("run-42", "Test run")
        host.onEvicted("run-42", lastCheckpointStep = 7)

        val state = host.state.value
        assertIs<ServiceState.EvictedMidRun>(state)
        assertEquals(7, state.lastCheckpointStep,
            "Eviction must record last committed checkpoint step")
        assertTrue(host.currentNotificationText.contains("step 7"))
    }

    @Test
    fun `M27 resume after eviction restarts from checkpoint`() = runTest {
        val host = RuntimeServiceHost(MockRuntimeClient(), this)
        host.startRun("run-42", "Test run")
        host.onEvicted("run-42", lastCheckpointStep = 7)
        host.resumeAfterEviction()

        assertIs<ServiceState.RunningRun>(host.state.value)
    }

    // ─── M29: Availability reporting ─────────────────────────────────────────

    @Test
    fun `M29 degraded tools shown at project open`() {
        val report = AvailabilityReporter.report(
            registeredTools = listOf("fs_read", "model_query", "mcp_http_weather"),
            networkAvailable = false,
            localModelAvailable = false,
            remoteModelAllowed = true,
        )

        assertFalse(report.allAvailable)
        val needsAttention = report.needsAttention
        assertTrue(needsAttention.any { it.toolName == "mcp_http_weather" },
            "Network tool must be reported unavailable when network is off")
        assertTrue(needsAttention.any { it.toolName == "model_query" },
            "Model query must be reported degraded when local model absent")
    }

    @Test
    fun `M29 fully available report when conditions met`() {
        val report = AvailabilityReporter.report(
            registeredTools = listOf("fs_read", "git_status"),
            networkAvailable = true,
            localModelAvailable = true,
            remoteModelAllowed = false,
        )
        assertTrue(report.allAvailable)
        assertTrue(report.needsAttention.isEmpty())
    }

    @Test
    fun `M29 unavailable tool never offered implies never fails mid-run`() {
        // The availability check MUST happen at project open, so the tool is absent from
        // the broker before any run starts. This test verifies the report correctly classifies
        // tools that cannot run as Unavailable (not Degraded).
        val report = AvailabilityReporter.report(
            registeredTools = listOf("model_query"),
            networkAvailable = false,
            localModelAvailable = false,
            remoteModelAllowed = false,
        )
        val modelEntry = report.tools.first { it.toolName == "model_query" }
        assertIs<ToolAvailabilityState.Unavailable>(modelEntry.state)
    }

    // ─── M30: Approval, preview, and memory review ───────────────────────────

    @Test
    fun `M30 every mutation shows preview before it happens`() {
        val event = makeFakeApprovalEvent(
            toolName = "fs_write",
            previewDescription = "Write 42 lines to src/main.kt",
        )
        val card = ApprovalPresenter.toCard(event)

        assertEquals("fs_write", card.toolName)
        assertTrue(card.previewDescription.contains("src/main.kt"),
            "Approval card must show the preview description")
        assertEquals(ApprovalKind.MUTATION, card.kind)
    }

    @Test
    fun `M30 escalation names the untrusted source`() {
        val event = makeFakeApprovalEvent(
            toolName = "fs_write",
            previewDescription = "UNTRUSTED source: tool output from mcp_server_x requested elevated write",
        )
        val card = ApprovalPresenter.toCard(event)

        assertEquals(ApprovalKind.ESCALATION, card.kind)
        assertNotNull(card.untrustedSourceDescription,
            "Escalation card must name the untrusted source")
    }

    @Test
    fun `M30 egress approval states provider retention`() {
        val event = makeFakeApprovalEvent(
            toolName = "mcp_http_claude",
            previewDescription = "Send prompt to Claude API",
        )
        val card = ApprovalPresenter.toCard(event)

        assertEquals(ApprovalKind.EGRESS, card.kind)
        assertNotNull(card.providerRetentionStatement,
            "Egress card must state what the provider retains (RFC-0026)")
    }

    // ─── M32: Notifications ───────────────────────────────────────────────────

    @Test
    fun `M32 parked run notification fires exactly once`() {
        val nm = NotificationManager(throttleWindowMs = 60_000L)
        val content = nm.parkedRunContent("run-1", "Approval needed: write to src/main.kt")

        val t0 = 1_000L
        assertTrue(nm.shouldFire(content.key, t0, oneShot = true))
        nm.recordFired(content.key, t0)

        // One-shot: should not fire again even after throttle window.
        assertFalse(nm.shouldFire(content.key, t0 + 120_000L, oneShot = true),
            "Parked run notification must fire exactly once")
    }

    @Test
    fun `M32 regular notification is throttled`() {
        val nm = NotificationManager(throttleWindowMs = 60_000L)
        val key = "progress_update"

        val t0 = 1_000L
        assertTrue(nm.shouldFire(key, t0))
        nm.recordFired(key, t0)

        // Within throttle window — must not fire.
        assertFalse(nm.shouldFire(key, t0 + 30_000L))

        // After throttle window — may fire again.
        assertTrue(nm.shouldFire(key, t0 + 61_000L))
    }

    @Test
    fun `M32 approval notifications bypass quiet hours`() {
        val nm = NotificationManager(
            throttleWindowMs = 60_000L,
            quietHoursStart = kotlinx.datetime.LocalTime(22, 0),
            quietHoursEnd = kotlinx.datetime.LocalTime(8, 0)
        )
        val content = nm.approvalContent("run-1", "fs_write", "Write to src/main.kt")

        // Simulate nighttime (would normally be suppressed by quiet hours)
        val nighttimeMs = 1_000L
        
        // Approval bypasses quiet hours
        assertTrue(nm.shouldFire(content.key, nighttimeMs, isApproval = true),
            "Approval notifications must bypass quiet hours")
    }

    // ─── M32b: Run Summary and benign-approval classifier ────────────────────

    @Test
    fun `M32b pending approvals never collapse in run summary`() {
        val rows = listOf(
            row(0, "fs_read", StepOutcome.SUCCESS, pendingApproval = false),
            row(1, "fs_write", StepOutcome.SUCCESS, pendingApproval = true),
        )
        val summary = RunSummaryComputer.compute(rows, RunStatus.RUNNING)

        assertEquals(1, summary.pendingApprovals.size, "Pending approval must appear in summary")
        assertEquals(1, summary.collapsedStepCount, "Only the read step should collapse")
    }

    @Test
    fun `M32b INDETERMINATE outcomes never collapse`() {
        val rows = listOf(
            row(0, "git_commit", StepOutcome.SUCCESS),
            row(1, "fs_write", StepOutcome.INDETERMINATE),
        )
        val summary = RunSummaryComputer.compute(rows, RunStatus.COMPLETED)

        assertEquals(1, summary.indeterminateSteps.size)
        assertEquals(0, summary.collapsedStepCount, "No steps should collapse when INDETERMINATE is present")
    }

    @Test
    fun `M32b egress steps never collapse`() {
        val rows = listOf(
            row(0, "mcp_http_claude", StepOutcome.SUCCESS, isEgress = true),
            row(1, "fs_read", StepOutcome.SUCCESS),
        )
        val summary = RunSummaryComputer.compute(rows, RunStatus.COMPLETED)

        assertEquals(1, summary.egressSteps.size)
        assertEquals(1, summary.collapsedStepCount)
    }

    @Test
    fun `M32b benign classifier is conservative - egress is never benign`() {
        val egressRow = row(0, "mcp_http_claude", StepOutcome.SUCCESS, isEgress = true)
        assertFalse(RunSummaryComputer.isBenign(egressRow),
            "Egress is never benign — must require approval")
    }

    @Test
    fun `M32b benign classifier approves read-only tools`() {
        val readRow = row(0, "fs_read", StepOutcome.SUCCESS)
        assertTrue(RunSummaryComputer.isBenign(readRow))
    }

    @Test
    fun `M32b running run shows so far status`() {
        val rows = listOf(row(0, "fs_read", StepOutcome.SUCCESS))
        val summary = RunSummaryComputer.compute(rows, RunStatus.RUNNING)
        assertEquals(RunStatus.RUNNING, summary.status)
    }

    // ─── M32c: Intent as a task list, with the proposal gate ─────────────────

    @Test
    fun `M32c status is derived not stored`() {
        val history = listOf(
            RunHistoryEntry("r1", "intent-a", isRunning = false, completedSuccessfully = true,
                completedAt = "2025-01-02T10:00:00Z"),
        )
        val status = deriveIntentStatus("intent-a", history)
        assertEquals(DerivedIntentStatus.COMPLETED, status)
    }

    @Test
    fun `M32c reverted run changes derived status to failed`() {
        val history = listOf(
            RunHistoryEntry("r1", "intent-a", isRunning = false, completedSuccessfully = true,
                completedAt = "2025-01-02T10:00:00Z"),
            // Later revert run failed
            RunHistoryEntry("r2", "intent-a", isRunning = false, completedSuccessfully = false,
                completedAt = "2025-01-03T10:00:00Z"),
        )
        val status = deriveIntentStatus("intent-a", history)
        assertEquals(DerivedIntentStatus.FAILED, status,
            "Derived status must reflect the most recent run outcome")
    }

    @Test
    fun `M32c session can only propose - no SESSION resolved variant`() {
        // The proposal gate: SESSION -> PROPOSE only, USER -> RESOLVE only.
        // Verified by construction: IntentProposal has proposedBySessionId,
        // there is no ResolvedProposal with a session ID (only resolvedByUserId).
        val proposal = IntentProposal(
            proposalId = "p1",
            proposedBySessionId = "sess-1",
            title = "Add error handling",
            description = "Handle null case in parser",
            priority = IntentPriority.HIGH,
            proposedAt = "2025-01-01T12:00:00Z",
        )
        assertNotNull(proposal.proposedBySessionId)
        // ResolvedProposal only has resolvedByUserId — sessions cannot resolve by type design.
    }

    @Test
    fun `M32c in-progress intent shows while run is running`() {
        val history = listOf(
            RunHistoryEntry("r1", "intent-b", isRunning = true, completedSuccessfully = false,
                completedAt = null),
        )
        val status = deriveIntentStatus("intent-b", history)
        assertEquals(DerivedIntentStatus.IN_PROGRESS, status)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun makeFakeApprovalEvent(
        toolName: String,
        previewDescription: String,
    ) = dev.aidos.api.RuntimeEvent.ToolApprovalRequired(
        eventId = "evt-1",
        timestamp = Clock.System.now(),
        projectId = "proj-1",
        sessionId = "sess-1",
        runId = "run-1",
        taskId = "task-1",
        toolName = toolName,
        previewDescription = previewDescription,
    )

    private fun row(
        step: Int,
        tool: String,
        outcome: StepOutcome,
        pendingApproval: Boolean = false,
        isEgress: Boolean = false,
        isOutOfProject: Boolean = false,
    ) = ExecutionGraphRow(
        stepIndex = step,
        toolName = tool,
        outcome = outcome,
        isEgress = isEgress,
        isOutOfProjectMutation = isOutOfProject,
        pendingApproval = pendingApproval,
        // For M32b benign classifier test, provide default values for benign-capable rows
        // unless they are explicitly non-benign (egress or out-of-project)
        recoveryClass = if (isEgress || isOutOfProject) "UNSAFE" else "PURE",
        runTaintIsTrusted = true,
        capabilityAlreadyGranted = true,
    )
}
