package dev.aidos.androidapp.voice

import dev.aidos.androidapp.runsummary.ExecutionGraphRow
import dev.aidos.androidapp.runsummary.RunStatus
import dev.aidos.androidapp.runsummary.RunSummaryComputer
import dev.aidos.androidapp.runsummary.RunSummaryReport
import dev.aidos.api.CapabilityResult
import dev.aidos.api.RuntimeClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class GlanceDensity {
    STRIP,
    PAGE,
    SPOKEN,
}

enum class VoiceApprovalTier {
    TIER1_BENIGN,
    TIER2_MUTATE_OR_SPEND,
    TIER3_HIGH_RISK,
}

data class GlanceVoiceUiState(
    val density: GlanceDensity = GlanceDensity.PAGE,
    val runSummary: RunSummaryReport? = null,
    val spokenText: String = "",
    val approvalTier: VoiceApprovalTier? = null,
    val lastApprovalChannel: String? = null,
    val lastApprovalPhrase: String? = null,
    val error: String? = null,
)

/**
 * Glanceable Run Summary & Hands-Free Voice Approval presenter (RFC-0057).
 *
 * Computes deterministic summaries via [RunSummaryComputer] (no model call), formats them for
 * Strip / Page / Spoken densities, speaks via [LocalTtsEngine], and routes 3-tier voice
 * approvals with `approvalChannel = "voice"` and `approvalPhrase` to [RuntimeClient].
 */
class GlanceVoicePresenter(
    private val runtimeClient: RuntimeClient,
    val sttRouter: HybridSttRouter? = null,
    private val ttsEngine: LocalTtsEngine? = null,
) {
    private val _state = MutableStateFlow(GlanceVoiceUiState())
    val state: StateFlow<GlanceVoiceUiState> = _state.asStateFlow()

    fun renderSummary(
        rows: List<ExecutionGraphRow>,
        status: RunStatus,
        density: GlanceDensity = _state.value.density,
    ): GlanceVoiceUiState {
        val report = RunSummaryComputer.compute(rows, status)
        val spoken = formatSpokenSummary(report, density)
        val next = _state.value.copy(
            density = density,
            runSummary = report,
            spokenText = spoken,
            error = null,
        )
        _state.value = next
        return next
    }

    suspend fun speakSummary(): Result<Unit> {
        val text = _state.value.spokenText
        if (text.isBlank()) return Result.success(Unit)
        val tts = ttsEngine ?: return Result.failure(IllegalStateException("TTS engine unavailable"))
        if (!tts.isAvailable()) return Result.failure(IllegalStateException("TTS engine unavailable"))
        return tts.speak(text)
    }

    fun stopSpeaking() {
        ttsEngine?.stop()
    }

    fun classifyApprovalTier(
        row: ExecutionGraphRow,
        isHighRisk: Boolean = false,
    ): VoiceApprovalTier {
        if (isHighRisk) return VoiceApprovalTier.TIER3_HIGH_RISK
        return if (RunSummaryComputer.isBenign(row)) {
            VoiceApprovalTier.TIER1_BENIGN
        } else {
            VoiceApprovalTier.TIER2_MUTATE_OR_SPEND
        }
    }

    suspend fun handleVoiceApproval(
        runId: String,
        taskId: String,
        row: ExecutionGraphRow,
        spokenPhrase: String,
        isHighRisk: Boolean = false,
    ): Result<Unit> {
        val normalized = spokenPhrase.trim()
        val tier = classifyApprovalTier(row, isHighRisk)
        _state.update { it.copy(approvalTier = tier, error = null) }

        if (tier == VoiceApprovalTier.TIER3_HIGH_RISK) {
            val msg = "High-risk approvals require touch confirmation"
            _state.update { it.copy(error = msg) }
            return Result.failure(IllegalStateException(msg))
        }

        val lower = normalized.lowercase()
        val accepted = when (tier) {
            VoiceApprovalTier.TIER1_BENIGN ->
                lower in setOf("approve", "allow", "yes", "ok", "confirm")
            VoiceApprovalTier.TIER2_MUTATE_OR_SPEND ->
                lower.startsWith("confirm") || lower.startsWith("yes confirm")
            VoiceApprovalTier.TIER3_HIGH_RISK -> false
        }
        if (!accepted) {
            val msg = if (tier == VoiceApprovalTier.TIER2_MUTATE_OR_SPEND) {
                "Say 'Confirm ...' to approve this mutation or egress"
            } else {
                "Unrecognized approval phrase: $normalized"
            }
            _state.update { it.copy(error = msg) }
            return Result.failure(IllegalArgumentException(msg))
        }

        return when (
            val result = runtimeClient.capabilities.approveEffect(
                runId = runId,
                taskId = taskId,
                approvalChannel = "voice",
                approvalPhrase = normalized,
            )
        ) {
            is CapabilityResult.Success -> {
                _state.update {
                    it.copy(
                        lastApprovalChannel = "voice",
                        lastApprovalPhrase = normalized,
                        error = null,
                    )
                }
                Result.success(Unit)
            }
            is CapabilityResult.Error -> {
                _state.update { it.copy(error = result.message) }
                Result.failure(IllegalStateException("${result.code}: ${result.message}"))
            }
        }
    }

    companion object {
        fun formatSpokenSummary(report: RunSummaryReport, density: GlanceDensity): String {
            val prefix = when (report.status) {
                RunStatus.RUNNING -> "Running so far"
                RunStatus.COMPLETED -> "Completed"
                RunStatus.FAILED -> "Failed"
            }
            val attentionCount = report.pendingApprovals.size +
                report.errors.size +
                report.egressSteps.size +
                report.outOfProjectMutations.size +
                report.indeterminateSteps.size

            return when (density) {
                GlanceDensity.STRIP ->
                    "$prefix: ${report.totalSteps} steps, $attentionCount needing attention."
                GlanceDensity.PAGE, GlanceDensity.SPOKEN -> buildString {
                    append("$prefix: ${report.collapsedStepCount} routine steps completed")
                    if (report.pendingApprovals.isNotEmpty()) {
                        append(", ${report.pendingApprovals.size} pending approval")
                    }
                    if (report.errors.isNotEmpty()) {
                        append(", ${report.errors.size} errors")
                    }
                    if (report.egressSteps.isNotEmpty()) {
                        append(", ${report.egressSteps.size} egress")
                    }
                    if (report.outOfProjectMutations.isNotEmpty()) {
                        append(", ${report.outOfProjectMutations.size} out-of-project mutations")
                    }
                    if (report.indeterminateSteps.isNotEmpty()) {
                        append(", ${report.indeterminateSteps.size} indeterminate")
                    }
                    append(".")
                }
            }
        }
    }
}
