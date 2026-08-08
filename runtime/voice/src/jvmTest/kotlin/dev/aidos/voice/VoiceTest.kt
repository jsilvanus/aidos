package dev.aidos.voice

import dev.aidos.androidapp.runsummary.ExecutionGraphRow
import dev.aidos.androidapp.runsummary.RunSummaryComputer
import dev.aidos.androidapp.runsummary.RunStatus
import dev.aidos.androidapp.runsummary.StepOutcome
import dev.aidos.settings.VoiceApprovalsLevel
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * Tests for M33 voice support (RFC-0057).
 */
class VoiceTest {

    @Test
    fun `SpokenSummaryGenerator creates deterministic output`() {
        val rows = listOf(
            ExecutionGraphRow(
                stepIndex = 0,
                toolName = "fs_read",
                outcome = StepOutcome.SUCCESS,
                isEgress = false,
                isOutOfProjectMutation = false,
                pendingApproval = false,
            )
        )
        val summary = RunSummaryComputer.compute(rows, RunStatus.COMPLETED)
        
        val spoken = SpokenSummaryGenerator.generate(summary, "test-project", "test-session")
        
        // Verify it is deterministic (same input = same output)
        val spoken2 = SpokenSummaryGenerator.generate(summary, "test-project", "test-session")
        assertEquals(spoken, spoken2, "Spoken summary must be deterministic")
        
        // Verify it contains runtime-owned fields, no model prose
        assertTrue(spoken.contains("test-project"), "Should contain project name")
        assertFalse(spoken.contains("model-generated"), "Should not contain model prose")
    }

    @Test
    fun `SpokenSummaryGenerator handles pending approvals`() {
        val rows = listOf(
            ExecutionGraphRow(
                stepIndex = 0,
                toolName = "fs_write",
                outcome = StepOutcome.SUCCESS,
                isEgress = false,
                isOutOfProjectMutation = false,
                pendingApproval = true,
                recoveryClass = "IDEMPOTENT",
                runTaintIsTrusted = true,
                capabilityAlreadyGranted = true,
            )
        )
        val summary = RunSummaryComputer.compute(rows, RunStatus.RUNNING)
        
        val spoken = SpokenSummaryGenerator.generate(summary, "test-project", "test-session")
        
        assertTrue(spoken.contains("thing") || spoken.contains("need"), "Should mention pending items")
        assertTrue(spoken.contains("approve") || spoken.contains("skip"), "Should prompt for response")
    }

    @Test
    fun `VoiceApprovalHandler parses voice responses`() {
        assertEquals(
            VoiceApprovalHandler.VoiceResponse.APPROVE,
            VoiceApprovalHandler.VoiceResponse.parse("approve"),
            "Should parse 'approve'"
        )
        assertEquals(
            VoiceApprovalHandler.VoiceResponse.SKIP,
            VoiceApprovalHandler.VoiceResponse.parse("skip"),
            "Should parse 'skip'"
        )
        assertEquals(
            VoiceApprovalHandler.VoiceResponse.DETAILS,
            VoiceApprovalHandler.VoiceResponse.parse("details"),
            "Should parse 'details'"
        )
        assertEquals(
            VoiceApprovalHandler.VoiceResponse.UNKNOWN,
            VoiceApprovalHandler.VoiceResponse.parse("yesterday"),
            "Should not misclassify unrelated words"
        )
        assertEquals(
            VoiceApprovalHandler.VoiceResponse.UNKNOWN,
            VoiceApprovalHandler.VoiceResponse.parse("disapprove"),
            "Should not match substrings inside other words"
        )
    }

    @Test
    fun `VoiceApprovalHandler respects OFF setting`() {
        val benignRow = ExecutionGraphRow(
            stepIndex = 0,
            toolName = "fs_read",
            outcome = StepOutcome.SUCCESS,
            isEgress = false,
            isOutOfProjectMutation = false,
            pendingApproval = true,
            recoveryClass = "PURE",
            runTaintIsTrusted = true,
            capabilityAlreadyGranted = true,
        )

        // When voice approvals are OFF, no voice approval is allowed
        assertFalse(
            VoiceApprovalHandler.isVoiceApprovableForTier(VoiceApprovalsLevel.OFF, benignRow),
            "Voice approval must be disabled when setting is OFF"
        )
    }

    @Test
    fun `VoiceApprovalHandler allows TIER1 benign approvals`() {
        val benignRow = ExecutionGraphRow(
            stepIndex = 0,
            toolName = "fs_read",
            outcome = StepOutcome.SUCCESS,
            isEgress = false,
            isOutOfProjectMutation = false,
            pendingApproval = true,
            recoveryClass = "PURE",
            runTaintIsTrusted = true,
            capabilityAlreadyGranted = true,
        )

        // Benign read-only operation with trusted run and granted capability
        assertTrue(
            VoiceApprovalHandler.isVoiceApprovableForTier(VoiceApprovalsLevel.TIER1, benignRow),
            "TIER1 should allow benign approvals"
        )
    }

    @Test
    fun `VoiceApprovalHandler denies voice for non-benign approvals`() {
        // A mutation outside the project (not benign per D26)
        val nonBenignRow = ExecutionGraphRow(
            stepIndex = 0,
            toolName = "fs_write",
            outcome = StepOutcome.SUCCESS,
            isEgress = false,
            isOutOfProjectMutation = true,  // ← not benign
            pendingApproval = true,
            recoveryClass = "IDEMPOTENT",
            runTaintIsTrusted = true,
            capabilityAlreadyGranted = true,
        )

        assertFalse(
            VoiceApprovalHandler.isVoiceApprovableForTier(VoiceApprovalsLevel.TIER1, nonBenignRow),
            "Voice approval must be denied for non-benign (out-of-project) operations"
        )
    }

    @Test
    fun `VoiceApprovalHandler denies voice for UNSAFE operations`() {
        val unsafeRow = ExecutionGraphRow(
            stepIndex = 0,
            toolName = "fs_write",
            outcome = StepOutcome.SUCCESS,
            isEgress = false,
            isOutOfProjectMutation = false,
            pendingApproval = true,
            recoveryClass = "UNSAFE",  // ← not benign per D26
            runTaintIsTrusted = true,
            capabilityAlreadyGranted = true,
        )

        assertFalse(
            VoiceApprovalHandler.isVoiceApprovableForTier(VoiceApprovalsLevel.TIER1, unsafeRow),
            "Voice approval must be denied for UNSAFE operations"
        )
    }

    @Test
    fun `VoiceApprovalHandler denies voice for untrusted runs`() {
        val untrustedRow = ExecutionGraphRow(
            stepIndex = 0,
            toolName = "fs_read",
            outcome = StepOutcome.SUCCESS,
            isEgress = false,
            isOutOfProjectMutation = false,
            pendingApproval = true,
            recoveryClass = "PURE",
            runTaintIsTrusted = false,  // ← not benign per D26
            capabilityAlreadyGranted = true,
        )

        assertFalse(
            VoiceApprovalHandler.isVoiceApprovableForTier(VoiceApprovalsLevel.TIER1, untrustedRow),
            "Voice approval must be denied for untrusted runs"
        )
    }

    @Test
    fun `VoiceApprovalHandler denies voice for new capability grants`() {
        val newGrantRow = ExecutionGraphRow(
            stepIndex = 0,
            toolName = "fs_read",
            outcome = StepOutcome.SUCCESS,
            isEgress = false,
            isOutOfProjectMutation = false,
            pendingApproval = true,
            recoveryClass = "PURE",
            runTaintIsTrusted = true,
            capabilityAlreadyGranted = false,  // ← not benign per D26
        )

        assertFalse(
            VoiceApprovalHandler.isVoiceApprovableForTier(VoiceApprovalsLevel.TIER1, newGrantRow),
            "Voice approval must be denied for new capability grants"
        )
    }

    @Test
    fun `VoiceApprovalHandler processes voice responses correctly`() {
        val benignRow = ExecutionGraphRow(
            stepIndex = 0,
            toolName = "fs_read",
            outcome = StepOutcome.SUCCESS,
            isEgress = false,
            isOutOfProjectMutation = false,
            pendingApproval = true,
            recoveryClass = "PURE",
            runTaintIsTrusted = true,
            capabilityAlreadyGranted = true,
        )

        // Approve response
        val approveDecision = VoiceApprovalHandler.processVoiceResponse(
            VoiceApprovalHandler.VoiceResponse.APPROVE,
            benignRow,
            VoiceApprovalsLevel.TIER1
        )
        assertTrue(
            approveDecision is VoiceApprovalHandler.ApprovalDecision.Approved,
            "Should approve when response is APPROVE"
        )

        // Skip response
        val skipDecision = VoiceApprovalHandler.processVoiceResponse(
            VoiceApprovalHandler.VoiceResponse.SKIP,
            benignRow,
            VoiceApprovalsLevel.TIER1
        )
        assertTrue(
            skipDecision is VoiceApprovalHandler.ApprovalDecision.Skipped,
            "Should skip when response is SKIP"
        )

        // Details response (requires eyes-on)
        val detailsDecision = VoiceApprovalHandler.processVoiceResponse(
            VoiceApprovalHandler.VoiceResponse.DETAILS,
            benignRow,
            VoiceApprovalsLevel.TIER1
        )
        assertTrue(
            detailsDecision is VoiceApprovalHandler.ApprovalDecision.RequiresEyesOn,
            "Should require eyes-on for DETAILS"
        )
    }

    @Test
    fun `VoiceApprovalHandler generates safe approval prompts`() {
        val row = ExecutionGraphRow(
            stepIndex = 0,
            toolName = "fs_write",
            outcome = StepOutcome.SUCCESS,
            isEgress = false,
            isOutOfProjectMutation = false,
            pendingApproval = true,
        )

        val prompt = VoiceApprovalHandler.generateVoiceApprovalPrompt(row)
        
        // Should contain only runtime-owned fields, not file content or model output
        assertTrue(prompt.isNotEmpty(), "Prompt should not be empty")
        assertFalse(prompt.contains("../"), "Prompt should not contain file paths from repository")
        assertTrue(prompt.contains("approve") || prompt.contains("skip"), "Should give voice options")
    }

    @Test
    fun `STT and TTS providers exist (even if no-op for MVP)`() {
        val stt = NoOpSttProvider()
        val tts = NoOpTtsProvider()
        
        runBlocking {
            assertFalse(stt.isAvailable(), "No-op STT provider should report not available")
            assertFalse(tts.isAvailable(), "No-op TTS provider should report not available")
        }
    }
}
