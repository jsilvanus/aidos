package dev.aidos.prompt

import dev.aidos.kernel.ContentNode
import dev.aidos.kernel.ContentNodeId
import dev.aidos.kernel.EgressEligibility
import dev.aidos.kernel.SensitivityLevel

/** Target model execution boundary for privacy filtering (RFC-0025). */
enum class EgressTarget { LOCAL_MODEL, REMOTE_MODEL }

/** Decision returned by [PrivacyFilter.filter] for a [ContentNode] (RFC-0025, RFC-0024). */
sealed interface FilterResult {
    data object Include : FilterResult
    data class Redact(val pattern: String, val replacement: String) : FilterResult
    data object Exclude : FilterResult
    data object RequiresUserApproval : FilterResult
}

/**
 * Evaluates whether a [ContentNode] may be included in a prompt for [targetEgress] (RFC-0025,
 * RFC-0024 Security).
 */
interface PrivacyFilter {
    fun filter(contentNode: ContentNode, targetEgress: EgressTarget): FilterResult
}

/**
 * Default RFC-0024 / RFC-0025 privacy filter:
 * - `SECRET` nodes are excluded from all prompts (local and remote).
 * - For `REMOTE_MODEL`: `SENSITIVE` and `BLOCKED` nodes are excluded; `REQUIRES_APPROVAL` nodes
 *   require explicit user approval; `INTERNAL` nodes are excluded from remote models unless
 *   explicitly approved/promoted; `PUBLIC` + `ELIGIBLE` nodes are included.
 * - For `LOCAL_MODEL`: non-`SECRET` nodes are included.
 */
class DefaultPrivacyFilter : PrivacyFilter {
    override fun filter(contentNode: ContentNode, targetEgress: EgressTarget): FilterResult {
        if (contentNode.sensitivityLevel == SensitivityLevel.SECRET) {
            return FilterResult.Exclude
        }
        if (targetEgress == EgressTarget.LOCAL_MODEL) {
            return FilterResult.Include
        }
        if (contentNode.sensitivityLevel == SensitivityLevel.SENSITIVE) {
            return FilterResult.Exclude
        }
        return when (contentNode.egressEligibility) {
            EgressEligibility.BLOCKED -> FilterResult.Exclude
            EgressEligibility.REQUIRES_APPROVAL -> FilterResult.RequiresUserApproval
            EgressEligibility.ELIGIBLE -> when (contentNode.sensitivityLevel) {
                SensitivityLevel.PUBLIC -> FilterResult.Include
                SensitivityLevel.INTERNAL -> FilterResult.Exclude
                SensitivityLevel.SENSITIVE, SensitivityLevel.SECRET -> FilterResult.Exclude
            }
        }
    }
}

/**
 * Live SQLite lookup for [ContentNode] records during prompt assembly (RFC-0024 Security:
 * must reference the current record in SQLite, not a cached copy).
 */
fun interface ContentNodeLookup {
    fun findById(id: ContentNodeId): ContentNode?
}

/** One provenance record for `prompt_provenance` (RFC-0025). */
data class PromptProvenanceEntry(
    val contentNodeId: String,
    val included: Boolean,
    val role: String, // "context" | "instruction" | "tool_result" | "history"
    val tokenCount: Int,
    val relevanceScore: Float? = null,
)

/** Provenance metadata attached to every [PromptPackage] (RFC-0025). */
data class PromptProvenance(
    val contextNodeIds: List<String> = emptyList(),
    val instructionNodeIds: List<String> = emptyList(),
    val droppedNodeIds: List<String> = emptyList(),
    val sessionMemoryVersion: Long = 0L,
    val intentNodeId: String? = null,
    val modelSelectionRationale: String? = null,
    val entries: List<PromptProvenanceEntry> = emptyList(),
)
