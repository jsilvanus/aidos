package dev.aidos.androidapp.voice

import dev.aidos.androidapp.runsummary.ExecutionGraphRow
import dev.aidos.androidapp.runsummary.RunStatus
import dev.aidos.androidapp.runsummary.StepOutcome
import dev.aidos.api.MockRuntimeClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlanceVoicePresenterTest {

    private class FakeTtsEngine(var available: Boolean = true) : LocalTtsEngine {
        val spoken = mutableListOf<String>()
        override fun isAvailable(): Boolean = available
        override suspend fun speak(text: String): Result<Unit> {
            spoken += text
            return Result.success(Unit)
        }
        override fun stop() {}
    }

    private fun benignRow(step: Int) = ExecutionGraphRow(
        stepIndex = step,
        toolName = "fs_read",
        outcome = StepOutcome.SUCCESS,
        isEgress = false,
        isOutOfProjectMutation = false,
        pendingApproval = false,
        recoveryClass = "PURE",
        runTaintIsTrusted = true,
        capabilityAlreadyGranted = true,
    )

    private fun mutateRow(step: Int) = ExecutionGraphRow(
        stepIndex = step,
        toolName = "write_file",
        outcome = StepOutcome.RUNNING,
        isEgress = false,
        isOutOfProjectMutation = false,
        pendingApproval = true,
        recoveryClass = "IDEMPOTENT",
        runTaintIsTrusted = true,
        capabilityAlreadyGranted = false,
    )

    @Test
    fun `renders strip and page densities and speaks via local tts`() = runTest {
        val client = MockRuntimeClient()
        val tts = FakeTtsEngine()
        val presenter = GlanceVoicePresenter(runtimeClient = client, ttsEngine = tts)
        val rows = listOf(benignRow(0), benignRow(1), mutateRow(2))

        val strip = presenter.renderSummary(rows, RunStatus.RUNNING, GlanceDensity.STRIP)
        assertTrue(strip.spokenText.contains("Running so far: 3 steps, 1 needing attention"))

        val page = presenter.renderSummary(rows, RunStatus.COMPLETED, GlanceDensity.PAGE)
        assertTrue(page.spokenText.contains("Completed: 2 routine steps completed, 1 pending approval"))

        assertTrue(presenter.speakSummary().isSuccess)
        assertEquals(listOf(page.spokenText), tts.spoken)
    }

    @Test
    fun `three-tier voice approval enforces tier rules and records voice channel`() = runTest {
        val client = MockRuntimeClient()
        val presenter = GlanceVoicePresenter(runtimeClient = client)

        // Tier 1 benign accepts "Approve"
        val tier1 = presenter.handleVoiceApproval("run-1", "task-1", benignRow(0), "Approve")
        assertTrue(tier1.isSuccess)
        assertEquals("voice", presenter.state.value.lastApprovalChannel)
        assertEquals("Approve", presenter.state.value.lastApprovalPhrase)

        // Tier 2 mutation rejects bare "Approve", requires "Confirm ..."
        val tier2Rejected = presenter.handleVoiceApproval("run-1", "task-2", mutateRow(1), "Approve")
        assertTrue(tier2Rejected.isFailure)

        val tier2Accepted = presenter.handleVoiceApproval(
            "run-1",
            "task-2",
            mutateRow(1),
            "Confirm write file",
        )
        assertTrue(tier2Accepted.isSuccess)
        assertEquals("voice", presenter.state.value.lastApprovalChannel)
        assertEquals("Confirm write file", presenter.state.value.lastApprovalPhrase)

        // Tier 3 high-risk rejects all voice approvals
        val tier3 = presenter.handleVoiceApproval(
            "run-1",
            "task-3",
            mutateRow(2),
            "Confirm delete",
            isHighRisk = true,
        )
        assertTrue(tier3.isFailure)
        assertEquals(VoiceApprovalTier.TIER3_HIGH_RISK, presenter.state.value.approvalTier)
    }
}
