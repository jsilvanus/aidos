package dev.aidos.aidin

/**
 * What a peer presents: the public key the peer link authenticated (the TLS handshake proves
 * possession) and any group certificates it offers.
 */
class PeerCredentials(
    val publicKey: ByteArray,
    val certificates: List<MembershipCertificate> = emptyList(),
) {
    val nodeId: NodeId get() = Keys.nodeIdOf(publicKey)
}

sealed interface TrustDecision {
    /** Trusted for the asked scope in each of [domains] (never empty). */
    data class Allowed(val domains: Set<TrustDomain>) : TrustDecision

    data class Denied(val reasons: List<String>) : TrustDecision

    val isAllowed: Boolean get() = this is Allowed
}

/**
 * RFC-0105 "Trust: two mechanisms, one check". Holds this node's pair records and group
 * memberships, and answers the only question the rest of AIDIN asks: *may this peer do this
 * now, and in which trust domains?*
 *
 * Fails closed: a credential that does not verify, has expired, names another key, is revoked, or
 * belongs to a group this node is not itself a valid member of, counts as absent. Callers that need
 * "same domain as X" (a split model's helpers and anchor) intersect the returned domains.
 *
 * Not thread-safe; the Engine owns one instance behind its own lock. Persistence is the caller's:
 * [pairs] and [memberships] are the whole state.
 */
class TrustStore(private val ownNodeId: NodeId) {
    private val pairsByNode = LinkedHashMap<NodeId, PairRecord>()
    private val membershipsByGroup = LinkedHashMap<GroupId, GroupMembership>()

    val pairs: List<PairRecord> get() = pairsByNode.values.toList()
    val memberships: List<GroupMembership> get() = membershipsByGroup.values.toList()

    fun addPair(record: PairRecord) {
        require(record.nodeId != ownNodeId) { "cannot pair with myself" }
        pairsByNode[record.nodeId] = record
    }

    fun removePair(nodeId: NodeId): Boolean = pairsByNode.remove(nodeId) != null

    /** Store a membership after enrolment. The certificate must be this node's own and verify now. */
    fun join(membership: GroupMembership, nowMillis: Long) {
        val cert = membership.ownCertificate
        require(cert.nodeId == ownNodeId) { "certificate belongs to ${cert.nodeId}, not this node" }
        require(cert.isValidAt(nowMillis)) { "certificate does not verify or is outside its validity" }
        val existing = membershipsByGroup[membership.groupId]
        // Re-enrolment (certificate renewal) must not roll back a newer revocation list.
        val keepRevocations = existing?.revocations?.let { old ->
            if (membership.revocations == null || membership.revocations.serial < old.serial) old else null
        }
        membershipsByGroup[membership.groupId] =
            if (keepRevocations != null) membership.copy(revocations = keepRevocations) else membership
    }

    fun leave(groupId: GroupId): Boolean = membershipsByGroup.remove(groupId) != null

    /**
     * Accept a revocation list a peer passed on. True only if it is for a group this node is in,
     * signed by that group's key, and newer than the one held.
     */
    fun acceptRevocations(list: RevocationList): Boolean {
        val membership = membershipsByGroup[list.groupId] ?: return false
        if (!list.isSignedBy(membership.groupPublicKey)) return false
        val current = membership.revocations?.serial ?: Long.MIN_VALUE
        if (list.serial <= current) return false
        membershipsByGroup[list.groupId] = membership.copy(revocations = list)
        return true
    }

    fun revocationSerial(groupId: GroupId): Long? = membershipsByGroup[groupId]?.revocations?.serial

    fun authorize(peer: PeerCredentials, scope: Scope, nowMillis: Long): TrustDecision {
        val reasons = mutableListOf<String>()
        val domains = LinkedHashSet<TrustDomain>()
        val peerId = peer.nodeId

        if (peerId == ownNodeId) return TrustDecision.Denied(listOf("peer presents this node's own key"))

        pairsByNode[peerId]?.let { record ->
            when {
                !record.publicKey.contentEquals(peer.publicKey) -> reasons += "pair record key differs"
                scope !in record.scopes -> reasons += "pairing does not grant ${scope.wire}"
                else -> domains += TrustDomain.Pairing
            }
        }

        for (cert in peer.certificates) {
            val groupId = cert.groupId
            val membership = membershipsByGroup[groupId]
            val problem = when {
                membership == null -> "not a member of group $groupId"
                !ownMembershipValid(membership, nowMillis) -> "own membership of $groupId expired or revoked"
                !cert.groupPublicKey.contentEquals(membership.groupPublicKey) -> "certificate group key differs"
                !cert.nodePublicKey.contentEquals(peer.publicKey) -> "certificate is for another key"
                !cert.isValidAt(nowMillis) -> "certificate for $groupId invalid or expired"
                membership.revocations?.revoked?.contains(peerId) == true -> "peer revoked in $groupId"
                scope !in cert.roles -> "group $groupId does not grant ${scope.wire}"
                else -> null
            }
            if (problem == null) domains += TrustDomain.Group(groupId) else reasons += problem
        }

        if (domains.isNotEmpty()) return TrustDecision.Allowed(domains)
        if (reasons.isEmpty()) reasons += "no pair record or group certificate"
        return TrustDecision.Denied(reasons)
    }

    private fun ownMembershipValid(membership: GroupMembership, nowMillis: Long): Boolean =
        membership.ownCertificate.isValidAt(nowMillis) &&
            membership.revocations?.revoked?.contains(ownNodeId) != true
}
