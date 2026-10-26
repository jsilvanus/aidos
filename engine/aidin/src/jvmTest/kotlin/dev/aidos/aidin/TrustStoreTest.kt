package dev.aidos.aidin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TrustStoreTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private val me = SigningKey.generate()
    private val peer = SigningKey.generate()

    private fun store() = TrustStore(me.nodeId)

    private fun enrol(store: TrustStore, authority: GroupAuthority, roles: Set<Scope> = Scope.ALL, at: Long = now) {
        store.join(GroupMembership(authority.groupPublicKey, authority.issue(me.publicKey, roles, at)), at)
    }

    @Test
    fun unknownPeerIsDenied() {
        val decision = store().authorize(PeerCredentials(peer.publicKey), Scope.SERVE, now)
        assertIs<TrustDecision.Denied>(decision)
    }

    @Test
    fun pairRecordGrantsItsScopesInThePairingDomain() {
        val s = store()
        s.addPair(PairRecord(peer.publicKey, "laptop", now, setOf(Scope.SERVE)))
        assertEquals(
            TrustDecision.Allowed(setOf(TrustDomain.Pairing)),
            s.authorize(PeerCredentials(peer.publicKey), Scope.SERVE, now),
        )
        assertFalse(s.authorize(PeerCredentials(peer.publicKey), Scope.HELP, now).isAllowed)
        assertTrue(s.removePair(peer.nodeId))
        assertFalse(s.authorize(PeerCredentials(peer.publicKey), Scope.SERVE, now).isAllowed)
    }

    @Test
    fun pairingWithMyselfIsRefused() {
        assertFailsWith<IllegalArgumentException> { store().addPair(PairRecord(me.publicKey, "me", now)) }
    }

    @Test
    fun groupCertificateGrantsItsRolesInThatGroupOnly() {
        val authority = GroupAuthority(SigningKey.generate())
        val s = store()
        enrol(s, authority)
        val cert = authority.issue(peer.publicKey, setOf(Scope.HELP), now)
        val creds = PeerCredentials(peer.publicKey, listOf(cert))
        assertEquals(
            TrustDecision.Allowed(setOf(TrustDomain.Group(authority.groupId))),
            s.authorize(creds, Scope.HELP, now),
        )
        assertFalse(s.authorize(creds, Scope.SERVE, now).isAllowed)
    }

    @Test
    fun certificateFromAGroupIAmNotInIsIgnored() {
        val mine = GroupAuthority(SigningKey.generate())
        val other = GroupAuthority(SigningKey.generate())
        val s = store()
        enrol(s, mine)
        val creds = PeerCredentials(peer.publicKey, listOf(other.issue(peer.publicKey, Scope.ALL, now)))
        assertFalse(s.authorize(creds, Scope.SERVE, now).isAllowed)
    }

    @Test
    fun certificateForAnotherKeyOrTamperedRolesIsDenied() {
        val authority = GroupAuthority(SigningKey.generate())
        val s = store()
        enrol(s, authority)
        val stolen = authority.issue(SigningKey.generate().publicKey, Scope.ALL, now)
        assertFalse(s.authorize(PeerCredentials(peer.publicKey, listOf(stolen)), Scope.SERVE, now).isAllowed)

        val narrow = authority.issue(peer.publicKey, setOf(Scope.DELEGATE), now)
        val widened = MembershipCertificate(
            narrow.groupPublicKey, narrow.nodePublicKey, Scope.ALL, narrow.notBeforeMillis, narrow.notAfterMillis, narrow.signature,
        )
        assertFalse(s.authorize(PeerCredentials(peer.publicKey, listOf(widened)), Scope.SERVE, now).isAllowed)
    }

    @Test
    fun certificateSignedByAnImpostorGroupKeyIsDenied() {
        val authority = GroupAuthority(SigningKey.generate())
        val s = store()
        enrol(s, authority)
        val impostor = GroupAuthority(SigningKey.generate())
        val real = impostor.issue(peer.publicKey, Scope.ALL, now)
        // Claims the real group key but carries the impostor's signature.
        val forged = MembershipCertificate(
            authority.groupPublicKey, real.nodePublicKey, real.roles, real.notBeforeMillis, real.notAfterMillis, real.signature,
        )
        assertFalse(s.authorize(PeerCredentials(peer.publicKey, listOf(forged)), Scope.SERVE, now).isAllowed)
    }

    @Test
    fun expiredPeerCertificateOrExpiredOwnMembershipDenies() {
        val authority = GroupAuthority(SigningKey.generate())
        val s = store()
        enrol(s, authority)
        val shortLived = authority.issue(peer.publicKey, Scope.ALL, now, validityMillis = day)
        val creds = PeerCredentials(peer.publicKey, listOf(shortLived))
        assertTrue(s.authorize(creds, Scope.SERVE, now + day - 1).isAllowed)
        assertFalse(s.authorize(creds, Scope.SERVE, now + day + 1).isAllowed)

        // Peer renewed, but my own 30-day certificate has run out: I am no longer a member.
        val later = now + GroupAuthority.DEFAULT_VALIDITY_MILLIS + 1
        val renewed = authority.issue(peer.publicKey, Scope.ALL, later)
        assertFalse(s.authorize(PeerCredentials(peer.publicKey, listOf(renewed)), Scope.SERVE, later).isAllowed)
    }

    @Test
    fun revocationListRevokesAndOnlyNewerListsAreAccepted() {
        val authority = GroupAuthority(SigningKey.generate())
        val s = store()
        enrol(s, authority)
        val creds = PeerCredentials(peer.publicKey, listOf(authority.issue(peer.publicKey, Scope.ALL, now)))
        assertTrue(s.authorize(creds, Scope.SERVE, now).isAllowed)

        assertTrue(s.acceptRevocations(authority.revocations(2, setOf(peer.nodeId), now)))
        assertFalse(s.authorize(creds, Scope.SERVE, now).isAllowed)

        // An older or equal list cannot un-revoke.
        assertFalse(s.acceptRevocations(authority.revocations(1, emptySet(), now)))
        assertFalse(s.acceptRevocations(authority.revocations(2, emptySet(), now)))
        assertFalse(s.authorize(creds, Scope.SERVE, now).isAllowed)

        // Only the admin can lift it, with a newer list.
        assertTrue(s.acceptRevocations(authority.revocations(3, emptySet(), now)))
        assertTrue(s.authorize(creds, Scope.SERVE, now).isAllowed)
    }

    @Test
    fun revocationListNotSignedByTheGroupIsRejected() {
        val authority = GroupAuthority(SigningKey.generate())
        val s = store()
        enrol(s, authority)
        val other = GroupAuthority(SigningKey.generate())
        val foreign = other.revocations(9, setOf(peer.nodeId), now)
        assertFalse(s.acceptRevocations(foreign))
        val forged = RevocationList(authority.groupPublicKey, 9, emptySet(), now, foreign.signature)
        assertFalse(s.acceptRevocations(forged))
        assertEquals(null, s.revocationSerial(authority.groupId))
    }

    @Test
    fun beingRevokedMyselfEndsMyMembership() {
        val authority = GroupAuthority(SigningKey.generate())
        val s = store()
        enrol(s, authority)
        val creds = PeerCredentials(peer.publicKey, listOf(authority.issue(peer.publicKey, Scope.ALL, now)))
        assertTrue(s.acceptRevocations(authority.revocations(1, setOf(me.nodeId), now)))
        assertFalse(s.authorize(creds, Scope.SERVE, now).isAllowed)
    }

    @Test
    fun renewingMyCertificateKeepsTheNewerRevocationList() {
        val authority = GroupAuthority(SigningKey.generate())
        val s = store()
        enrol(s, authority)
        s.acceptRevocations(authority.revocations(5, setOf(peer.nodeId), now))
        enrol(s, authority, at = now + day)
        assertEquals(5L, s.revocationSerial(authority.groupId))
    }

    @Test
    fun joinRefusesSomeoneElsesOrInvalidCertificate() {
        val authority = GroupAuthority(SigningKey.generate())
        val s = store()
        assertFailsWith<IllegalArgumentException> {
            s.join(GroupMembership(authority.groupPublicKey, authority.issue(peer.publicKey, Scope.ALL, now)), now)
        }
        val cert = authority.issue(me.publicKey, Scope.ALL, now, validityMillis = day)
        assertFailsWith<IllegalArgumentException> {
            s.join(GroupMembership(authority.groupPublicKey, cert), now + 2 * day)
        }
    }

    @Test
    fun pairAndGroupBothReportTheirDomains() {
        val authority = GroupAuthority(SigningKey.generate())
        val s = store()
        enrol(s, authority)
        s.addPair(PairRecord(peer.publicKey, "phone", now))
        val creds = PeerCredentials(peer.publicKey, listOf(authority.issue(peer.publicKey, Scope.ALL, now)))
        assertEquals(
            TrustDecision.Allowed(setOf(TrustDomain.Pairing, TrustDomain.Group(authority.groupId))),
            s.authorize(creds, Scope.SERVE, now),
        )
    }

    @Test
    fun pairRecordWithDifferentKeyThanPresentedIsDenied() {
        val s = store()
        s.addPair(PairRecord(peer.publicKey, "laptop", now))
        // Same node id cannot have another key (id is derived from the key), so a peer presenting
        // its own key is only trusted if the record holds exactly that key.
        assertTrue(s.authorize(PeerCredentials(peer.publicKey), Scope.SERVE, now).isAllowed)
        assertFalse(s.authorize(PeerCredentials(me.publicKey), Scope.SERVE, now).isAllowed)
    }
}
