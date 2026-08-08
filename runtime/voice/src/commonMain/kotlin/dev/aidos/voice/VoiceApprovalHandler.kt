package dev.aidos.voice

import dev.aidos.androidapp.runsummary.RunSummaryComputer
import dev.aidos.androidapp.runsummary.ExecutionGraphRow
import dev.aidos.settings.VoiceApprovalsLevel

/**
 * Handles voice-based approval responses (M33, RFC-0057, D26).
 *
 * Voice may only approve the benign class: Read or Mutate(IN_PROJECT), not UNSAFE,
 * run is TRUSTED, and capability already granted. TIER2 currently uses the same benign-gate
 * as TIER1; a readback-verification hook is reserved for future work.
 *
 * TIER3 (egress, tainted Runs, new grants) NEVER approves by voice, regardless of setting.
 */
object VoiceApprovalHandler {

    /**
     * Approval response phrases (M33, RFC-0057).
     *
     * The user speaks one of these in response to a voice approval request.
     * These are simple, distinct, and cannot be confused with normal speech.
     */
    enum class VoiceResponse {
        APPROVE,    // "approve"
        SKIP,       // "skip" or "defer"
        DETAILS,    // "details" or "more" (asks for eyes-on review)
        UNKNOWN;    // no match

        companion object {
            private val approvePattern = Regex("\\b(approve|yes)\\b")
            private val skipPattern = Regex("\\b(skip|defer)\\b")
            private val detailsPattern = Regex("\\b(details?|more)\\b")

            fun parse(spoken: String): VoiceResponse {
                val normalized = spoken.lowercase().trim()
                return when {
                    approvePattern.containsMatchIn(normalized) -> APPROVE
                    skipPattern.containsMatchIn(normalized) -> SKIP
                    detailsPattern.containsMatchIn(normalized) -> DETAILS
                    else -> UNKNOWN
                }
            }
        }
    }

    /**
     * Whether a voice response is valid for the current voice approval setting.
     *
     * @param voiceLevel the setting (OFF, TIER1, TIER2)
     * @param row the pending approval row
     * @return true if voice approval is permitted for this row
     */
    fun isVoiceApprovableForTier(
        voiceLevel: VoiceApprovalsLevel,
        row: ExecutionGraphRow,
    ): Boolean {
        // Voice is disabled: never approve by voice
        if (voiceLevel == VoiceApprovalsLevel.OFF) return false

        // The benign classifier is the gate (RFC-0057 D26)
        val isBenign = RunSummaryComputer.isBenign(row)
        if (!isBenign) return false

        // TIER1: benign only
        if (voiceLevel == VoiceApprovalsLevel.TIER1) return true

        // TIER2 currently uses the same benign gate as TIER1.
        // Readback verification is not implemented yet and is reserved for future work.
        if (voiceLevel == VoiceApprovalsLevel.TIER2) return true

        return false
    }

    /**
     * Process a voice approval response.
     *
     * @param response the parsed voice response (APPROVE, SKIP, DETAILS, UNKNOWN)
     * @param row the pending approval row
     * @param voiceLevel the voice approval setting
     * @return the approval decision: (approved, shouldShowDetails, shouldSkip)
     */
    fun processVoiceResponse(
        response: VoiceResponse,
        row: ExecutionGraphRow,
        voiceLevel: VoiceApprovalsLevel,
    ): ApprovalDecision {
        // Check if voice approval is allowed for this row
        if (!isVoiceApprovableForTier(voiceLevel, row)) {
            return ApprovalDecision.RequiresEyesOn
        }

        return when (response) {
            VoiceResponse.APPROVE -> ApprovalDecision.Approved
            VoiceResponse.SKIP -> ApprovalDecision.Skipped
            VoiceResponse.DETAILS -> ApprovalDecision.RequiresEyesOn
            VoiceResponse.UNKNOWN -> ApprovalDecision.RequiresEyesOn
        }
    }

    sealed interface ApprovalDecision {
        data object Approved : ApprovalDecision
        data object Skipped : ApprovalDecision
        data object RequiresEyesOn : ApprovalDecision
    }

    /**
     * Generate a voice approval prompt (RFC-0057).
     *
     * The prompt is composed from runtime-owned fields only, never from file content
     * or model output, because a hostile repository could write a sentence that sounds
     * approvable to someone who cannot see the screen.
     *
     * Example: "Edit the configuration file. Say approve, skip, or details."
     *
     * @param row the pending approval row
     * @return the voice prompt
     */
    fun generateVoiceApprovalPrompt(row: ExecutionGraphRow): String {
        // Describe the action in runtime-owned terms only
        val action = when {
            row.isEgress -> "send data over the network"
            row.isOutOfProjectMutation -> "write outside the project directory"
            row.toolName == "fs_read" -> "read a file"
            row.toolName == "fs_write" -> "write to the filesystem"
            else -> "perform an operation"
        }

        return "Proceed to $action. Say approve, skip, or details."
    }
}
