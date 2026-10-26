package dev.aidos.aidin

/**
 * RFC-0105 "(b) Group membership": an admin-signed statement that [nodeId] (holding
 * [nodePublicKey]) is a member of [groupId] with [roles] between [notBeforeMillis] and
 * [notAfterMillis].
 *
 * [groupPublicKey] travels with the certificate, and [groupId] is derived from it, so a group id
 * cannot be claimed by another key. Verifiers still check the signature against the group key
 * they stored when they themselves were enrolled.
 */
class MembershipCertificate(
    val groupPublicKey: ByteArray,
    val nodePublicKey: ByteArray,
    val roles: Set<Scope>,
    val notBeforeMillis: Long,
    val notAfterMillis: Long,
    val signature: ByteArray,
) {
    val groupId: GroupId get() = Keys.groupIdOf(groupPublicKey)
    val nodeId: NodeId get() = Keys.nodeIdOf(nodePublicKey)

    fun signedBytes(): ByteArray =
        signedBytes(groupPublicKey, nodePublicKey, roles, notBeforeMillis, notAfterMillis)

    /** Signature by the group key, and the validity window contains [nowMillis]. */
    fun isValidAt(nowMillis: Long): Boolean =
        nowMillis in notBeforeMillis..notAfterMillis && Keys.verify(groupPublicKey, signedBytes(), signature)

    companion object {
        internal fun signedBytes(
            groupPublicKey: ByteArray,
            nodePublicKey: ByteArray,
            roles: Set<Scope>,
            notBeforeMillis: Long,
            notAfterMillis: Long,
        ): ByteArray = Canonical("aidin-membership-v1")
            .bytes(groupPublicKey)
            .bytes(nodePublicKey)
            .strings(roles.map { it.wire })
            .long(notBeforeMillis)
            .long(notAfterMillis)
            .toByteArray()
    }
}

/**
 * Admin-signed list of revoked members. Members exchange it in heartbeats and only ever accept
 * one with a higher [serial] (RFC-0105), so an old list cannot un-revoke anybody.
 */
class RevocationList(
    val groupPublicKey: ByteArray,
    val serial: Long,
    val revoked: Set<NodeId>,
    val issuedAtMillis: Long,
    val signature: ByteArray,
) {
    val groupId: GroupId get() = Keys.groupIdOf(groupPublicKey)

    fun signedBytes(): ByteArray = signedBytes(groupPublicKey, serial, revoked, issuedAtMillis)

    fun isSignedBy(groupPublicKey: ByteArray): Boolean =
        this.groupPublicKey.contentEquals(groupPublicKey) && Keys.verify(groupPublicKey, signedBytes(), signature)

    companion object {
        internal fun signedBytes(groupPublicKey: ByteArray, serial: Long, revoked: Set<NodeId>, issuedAtMillis: Long) =
            Canonical("aidin-revocations-v1")
                .bytes(groupPublicKey)
                .long(serial)
                .strings(revoked.map { it.value })
                .long(issuedAtMillis)
                .toByteArray()
    }
}

/**
 * The admin side of a group: holds the group signing key and issues certificates and revocation
 * lists. Any Engine can create a group and is then its admin.
 */
class GroupAuthority(private val groupKey: SigningKey) {
    val groupId: GroupId get() = Keys.groupIdOf(groupKey.publicKey)
    val groupPublicKey: ByteArray get() = groupKey.publicKey

    fun issue(
        nodePublicKey: ByteArray,
        roles: Set<Scope>,
        nowMillis: Long,
        validityMillis: Long = DEFAULT_VALIDITY_MILLIS,
    ): MembershipCertificate {
        require(roles.isNotEmpty()) { "a member needs at least one role" }
        require(validityMillis > 0) { "validity must be positive" }
        val notAfter = nowMillis + validityMillis
        val bytes = MembershipCertificate.signedBytes(groupKey.publicKey, nodePublicKey, roles, nowMillis, notAfter)
        return MembershipCertificate(groupKey.publicKey, nodePublicKey, roles, nowMillis, notAfter, groupKey.sign(bytes))
    }

    /** The caller keeps [serial] increasing across restarts; members ignore a list that does not. */
    fun revocations(serial: Long, revoked: Set<NodeId>, nowMillis: Long): RevocationList {
        val bytes = RevocationList.signedBytes(groupKey.publicKey, serial, revoked, nowMillis)
        return RevocationList(groupKey.publicKey, serial, revoked, nowMillis, groupKey.sign(bytes))
    }

    companion object {
        /** RFC-0105: 30 days, renewed while the device is seen, so a lost phone ages out. */
        const val DEFAULT_VALIDITY_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
    }
}

/** What a member stores about a group it belongs to. */
data class GroupMembership(
    val groupPublicKey: ByteArray,
    val ownCertificate: MembershipCertificate,
    val revocations: RevocationList? = null,
) {
    val groupId: GroupId get() = Keys.groupIdOf(groupPublicKey)

    init {
        require(ownCertificate.groupPublicKey.contentEquals(groupPublicKey)) { "certificate is for another group" }
        require(revocations == null || revocations.isSignedBy(groupPublicKey)) { "revocation list is not signed by this group" }
    }

    override fun equals(other: Any?): Boolean =
        other is GroupMembership && groupPublicKey.contentEquals(other.groupPublicKey) &&
            ownCertificate === other.ownCertificate && revocations === other.revocations

    override fun hashCode(): Int = groupPublicKey.contentHashCode()
}
