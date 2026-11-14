package dev.aidos.bridge

import dev.aidos.settings.EgressPolicy
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Proposed outbound network message originating from an agent session.
 */
data class OutboundMessageRequest(
    val id: String,
    val projectId: String,
    val projectType: String = "personal", // e.g. "personal" / "LIFE" / "coding"
    val channel: String, // e.g. "SMS", "Matrix", "Email", "HTTP"
    val recipient: String, // e.g. "Alice (+358 40 123 4567)"
    val payload: String, // Proposed message body
    val reason: String? = null,
    val taintLevel: String = "HIGH",
    val runId: String? = null,
)

/**
 * Status of a parked outbound message in the approval queue.
 */
enum class ParkedMessageStatus {
    PARKED,
    APPROVED,
    REJECTED,
    TRANSMITTED,
}

/**
 * An outbound message parked for explicit human declassification approval (RFC-0113 §4.3).
 */
data class ParkedOutboundMessage(
    val id: String,
    val request: OutboundMessageRequest,
    val status: ParkedMessageStatus = ParkedMessageStatus.PARKED,
    val createdAt: Instant,
    val approvalDecisionAt: Instant? = null,
    val approverUserId: String? = null,
    val rejectionReason: String? = null,
)

/**
 * Single-use cryptographically signed declassification grant token (RFC-0113 §4.3).
 *
 * Required to authorize network delivery of outbound content originating from an
 * air-gapped Life project.
 */
data class EgressGrant(
    val tokenId: String,
    val messageId: String,
    val payloadHash: String, // Lowercase hex SHA-256 of payload
    val recipient: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val signature: String, // HMAC-SHA256 signature
)

/**
 * Outcome of attempting to park or evaluate an outbound message.
 */
sealed interface ParkResult {
    data class AllowedDirectly(val message: OutboundMessageRequest) : ParkResult
    data class Parked(val parkedMessage: ParkedOutboundMessage, val reason: String) : ParkResult
}

/**
 * Outcome of attempting network transmission.
 */
sealed interface TransmissionResult {
    data class Transmitted(
        val transmissionId: String,
        val messageId: String,
        val recipient: String,
        val channel: String,
        val payload: String,
        val deliveredAt: Instant,
    ) : TransmissionResult

    data class Blocked(val reason: String) : TransmissionResult
    data class Rejected(val reason: String) : TransmissionResult
}

/**
 * Project policy resolver determining whether remote egress requires explicit gating.
 */
interface ProjectPolicyResolver {
    fun isEgressAirGapped(projectId: String, projectType: String): Boolean
}

class DefaultProjectPolicyResolver(
    private val projectEgressPolicies: Map<String, EgressPolicy> = emptyMap(),
) : ProjectPolicyResolver {
    override fun isEgressAirGapped(projectId: String, projectType: String): Boolean {
        val normalizedType = projectType.lowercase()
        if (normalizedType == "personal" || normalizedType == "life") return true

        val policy = projectEgressPolicies[projectId]
        return policy == EgressPolicy.NEVER || policy == EgressPolicy.ASK
    }
}

/**
 * Manages PARKED outbound network messages from personal/LIFE projects, creating
 * one-time cryptographic declassification grants upon user approval (RFC-0113 §4.3).
 */
class EgressGateCoordinator(
    private val policyResolver: ProjectPolicyResolver = DefaultProjectPolicyResolver(),
    private val hmacSecretKey: ByteArray = generateSecretKey(),
    private val clock: () -> Instant = { Clock.System.now() },
) {
    private val parkedMessages = mutableMapOf<String, ParkedOutboundMessage>()
    private val usedGrantTokens = mutableSetOf<String>()
    private val transmittedMessages = mutableListOf<TransmissionResult.Transmitted>()

    companion object {
        fun generateSecretKey(): ByteArray {
            val key = ByteArray(32)
            SecureRandom().nextBytes(key)
            return key
        }

        fun sha256Hex(text: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * Intercepts an outbound message attempt.
     *
     * If the originating project has zero-egress policy (e.g. personal / LIFE),
     * automatically halts and parks the message for human approval.
     */
    fun interceptAndPark(request: OutboundMessageRequest): ParkResult {
        if (!policyResolver.isEgressAirGapped(request.projectId, request.projectType)) {
            return ParkResult.AllowedDirectly(request)
        }

        val parked = ParkedOutboundMessage(
            id = request.id,
            request = request,
            status = ParkedMessageStatus.PARKED,
            createdAt = clock(),
        )
        parkedMessages[request.id] = parked

        return ParkResult.Parked(
            parkedMessage = parked,
            reason = "EGRESS_POLICY_PROHIBITED: Outbound message from air-gapped Life project " +
                "requires explicit human declassification approval.",
        )
    }

    /**
     * Lists currently parked messages awaiting human approval.
     */
    fun getPendingApprovals(projectId: String? = null): List<ParkedOutboundMessage> {
        return parkedMessages.values
            .filter { it.status == ParkedMessageStatus.PARKED }
            .filter { projectId == null || it.request.projectId == projectId }
            .toList()
    }

    /**
     * Retrieves a parked message by ID.
     */
    fun getParkedMessage(messageId: String): ParkedOutboundMessage? = parkedMessages[messageId]

    /**
     * Rejects a parked message.
     *
     * Zero bytes leave the device. No declassification grant is issued.
     */
    fun reject(messageId: String, reason: String = "Rejected by user"): ParkedOutboundMessage {
        val existing = parkedMessages[messageId]
            ?: throw IllegalArgumentException("No parked message with ID '$messageId' found.")

        require(existing.status == ParkedMessageStatus.PARKED) {
            "Cannot reject message in status '${existing.status}'."
        }

        val updated = existing.copy(
            status = ParkedMessageStatus.REJECTED,
            approvalDecisionAt = clock(),
            rejectionReason = reason,
        )
        parkedMessages[messageId] = updated
        return updated
    }

    /**
     * Approves a parked message and issues a single-use cryptographic declassification grant.
     */
    fun approve(
        messageId: String,
        approverUserId: String = "user",
        ttl: Duration = 60.seconds,
    ): EgressGrant {
        val existing = parkedMessages[messageId]
            ?: throw IllegalArgumentException("No parked message with ID '$messageId' found.")

        require(existing.status == ParkedMessageStatus.PARKED) {
            "Cannot approve message in status '${existing.status}'."
        }

        val now = clock()
        val expiresAt = now + ttl
        val payloadHash = sha256Hex(existing.request.payload)
        val tokenId = UUID.randomUUID().toString()

        val signature = computeHmac(
            tokenId = tokenId,
            payloadHash = payloadHash,
            recipient = existing.request.recipient,
            expiresAtMs = expiresAt.toEpochMilliseconds(),
        )

        val updated = existing.copy(
            status = ParkedMessageStatus.APPROVED,
            approvalDecisionAt = now,
            approverUserId = approverUserId,
        )
        parkedMessages[messageId] = updated

        return EgressGrant(
            tokenId = tokenId,
            messageId = messageId,
            payloadHash = payloadHash,
            recipient = existing.request.recipient,
            issuedAt = now,
            expiresAt = expiresAt,
            signature = signature,
        )
    }

    /**
     * Validates and executes transmission of an outbound message.
     *
     * If originating from a Life project:
     * - Blocks if grant is absent.
     * - Rejects if grant is expired, altered, reused, or signature invalid.
     * - Transmits once verified and marks the grant as used (single-use guarantee).
     */
    fun transmit(
        request: OutboundMessageRequest,
        grant: EgressGrant? = null,
    ): TransmissionResult {
        val isAirGapped = policyResolver.isEgressAirGapped(request.projectId, request.projectType)

        if (isAirGapped) {
            if (grant == null) {
                return TransmissionResult.Blocked(
                    "Unapproved Life message blocked: remote network egress prohibited without declassification grant.",
                )
            }

            // 1. Single-use check: grants cannot be reused.
            if (grant.tokenId in usedGrantTokens) {
                return TransmissionResult.Rejected(
                    "Grant already used: declassification grants are strictly single-use.",
                )
            }

            // 2. Expiration check.
            val now = clock()
            if (now > grant.expiresAt) {
                return TransmissionResult.Rejected("Grant expired at ${grant.expiresAt} (current time: $now).")
            }

            // 3. Cryptographic signature check.
            val expectedSig = computeHmac(
                tokenId = grant.tokenId,
                payloadHash = grant.payloadHash,
                recipient = grant.recipient,
                expiresAtMs = grant.expiresAt.toEpochMilliseconds(),
            )
            if (!MessageDigest.isEqual(expectedSig.toByteArray(), grant.signature.toByteArray())) {
                return TransmissionResult.Rejected("Invalid cryptographic signature on declassification grant.")
            }

            // 4. Payload integrity check: ensure message was not tampered with.
            val currentPayloadHash = sha256Hex(request.payload)
            if (currentPayloadHash != grant.payloadHash) {
                return TransmissionResult.Rejected(
                    "Payload hash mismatch: message content does not match approved declassification grant.",
                )
            }

            // 5. Recipient integrity check.
            if (request.recipient != grant.recipient) {
                return TransmissionResult.Rejected(
                    "Recipient mismatch: grant is for '${grant.recipient}', requested for '${request.recipient}'.",
                )
            }

            // Mark grant as used so it cannot be replayed.
            usedGrantTokens.add(grant.tokenId)

            // Update parked message state if tracked.
            val parked = parkedMessages[request.id]
            if (parked != null) {
                parkedMessages[request.id] = parked.copy(status = ParkedMessageStatus.TRANSMITTED)
            }
        }

        val transmitted = TransmissionResult.Transmitted(
            transmissionId = UUID.randomUUID().toString(),
            messageId = request.id,
            recipient = request.recipient,
            channel = request.channel,
            payload = request.payload,
            deliveredAt = clock(),
        )
        transmittedMessages.add(transmitted)
        return transmitted
    }

    fun getTransmittedMessages(): List<TransmissionResult.Transmitted> = transmittedMessages.toList()

    private fun computeHmac(
        tokenId: String,
        payloadHash: String,
        recipient: String,
        expiresAtMs: Long,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(hmacSecretKey, "HmacSHA256")
        mac.init(secretKeySpec)
        val dataToSign = "$tokenId:$payloadHash:$recipient:$expiresAtMs"
        val bytes = mac.doFinal(dataToSign.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
