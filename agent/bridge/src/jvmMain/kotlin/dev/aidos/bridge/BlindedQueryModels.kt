package dev.aidos.bridge

import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.TrustLevel
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Base sealed interface for all strongly-typed, schema-bounded query responses (RFC-0113).
 *
 * Invariant: Blinded responses MUST NOT contain sensitive life data (e.g., event titles,
 * locations, attendee lists, personal notes, contact details, or full lists).
 */
@Serializable
sealed interface BlindedResponse {
    val isSuccess: Boolean
}

/**
 * Boolean/enum availability response indicating whether a time window is busy.
 *
 * Under no circumstances does this disclose event titles, descriptions, or locations.
 */
@Serializable
data class AvailabilityResponse(
    override val isSuccess: Boolean,
    val isBusy: Boolean,
    val nextAvailableSlot: Instant? = null,
) : BlindedResponse

/**
 * Circle membership response indicating whether a contact belongs to an approved circle.
 *
 * Discloses only membership; never exposes personal contact names, notes, or full circle rosters.
 */
@Serializable
data class ContactCircleResponse(
    override val isSuccess: Boolean,
    val isMember: Boolean,
) : BlindedResponse

/**
 * List query response indicating whether a specific normalized item is on a list.
 *
 * Discloses only existence; never returns other list items or sensitive notes.
 */
@Serializable
data class ListContainsResponse(
    override val isSuccess: Boolean,
    val alreadyPresent: Boolean,
) : BlindedResponse

/**
 * Strongly-typed blinded query interface (RFC-0113 §4.2).
 *
 * External messaging sessions cannot read Life project state directly. They may only submit
 * strongly-typed queries that are evaluated locally by the Life project evaluator.
 */
@Serializable
sealed interface BlindedQuery<R : BlindedResponse> {
    val targetProjectId: String

    @Serializable
    data class CheckAvailability(
        override val targetProjectId: String,
        val startTime: Instant,
        val endTime: Instant,
    ) : BlindedQuery<AvailabilityResponse> {
        companion object {
            fun forProject(targetProjectId: ProjectId, startTime: Instant, endTime: Instant): CheckAvailability =
                CheckAvailability(targetProjectId.value, startTime, endTime)
        }
    }

    @Serializable
    data class VerifyContactCircle(
        override val targetProjectId: String,
        val contactHandle: String,
        val requiredCircle: String,
    ) : BlindedQuery<ContactCircleResponse> {
        companion object {
            fun forProject(targetProjectId: ProjectId, contactHandle: String, requiredCircle: String): VerifyContactCircle =
                VerifyContactCircle(targetProjectId.value, contactHandle, requiredCircle)
        }
    }

    @Serializable
    data class CheckListContains(
        override val targetProjectId: String,
        val listTag: String,
        val normalizedItemName: String,
    ) : BlindedQuery<ListContainsResponse> {
        companion object {
            fun forProject(targetProjectId: ProjectId, listTag: String, normalizedItemName: String): CheckListContains =
                CheckListContains(targetProjectId.value, listTag, normalizedItemName)
        }
    }
}

/**
 * Ingress intent proposal (RFC-0113 §4.1).
 *
 * External incoming messages safely propose goals and tasks into Life projects (writing inward).
 * Sandboxed as UNTRUSTED external data, writing inward into a more confidential domain does not breach
 * confidentiality (Biba/Bell-LaPadula).
 */
@Serializable
data class IngressIntentProposal(
    val sourceChannel: String,
    val senderId: String,
    val targetProjectId: String,
    val summary: String,
    val suggestedParentGoalId: String? = null,
) {
    companion object {
        fun forProject(
            sourceChannel: String,
            senderId: String,
            targetProjectId: ProjectId,
            summary: String,
            suggestedParentGoalId: String? = null,
        ): IngressIntentProposal =
            IngressIntentProposal(sourceChannel, senderId, targetProjectId.value, summary, suggestedParentGoalId)
    }
}

/**
 * Classification of taint level for cross-domain query evaluation (RFC-0027, RFC-0113).
 */
enum class EgressTaintLevel {
    /** Taint-neutral (TRUSTED / un-tainted). */
    CLEAN,

    /** Contained within the project boundary. */
    PROJECT,

    /** High-sensitivity confidential personal data. */
    CONFIDENTIAL,

    /** External untrusted data. */
    UNTRUSTED,
}

/**
 * Wrapper for the result of a blinded query execution.
 *
 * Verifies that the returned data is taint-neutral (clean) and does not transfer
 * CONFIDENTIAL or PROJECT taint to the caller's session.
 */
data class BlindedQueryResult<out R : BlindedResponse>(
    val response: R,
    val trustLevel: TrustLevel = TrustLevel.TRUSTED,
    val egressTaint: EgressTaintLevel = EgressTaintLevel.CLEAN,
    val auditEventLogged: Boolean = true,
) {
    /**
     * Confirms that this result is taint-neutral: it carries no CONFIDENTIAL or PROJECT taint.
     */
    val isTaintNeutral: Boolean
        get() = trustLevel == TrustLevel.TRUSTED && egressTaint == EgressTaintLevel.CLEAN
}
