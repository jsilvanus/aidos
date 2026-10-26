package dev.aidos.aidin

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Two real nodes over loopback TLS: admission by pairing, by group, and refusal. */
class PeerLinkTest {
    private val now = System.currentTimeMillis()
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterTest
    fun closeAll() = closeables.reversed().forEach { runCatching { it.close() } }

    private class Node(val key: SigningKey = SigningKey.generate()) {
        val trust = TrustStore(key.nodeId)
        val own = mutableListOf<MembershipCertificate>()
        fun endpoint() = PeerLinkEndpoint(
            key,
            admit = { creds -> synchronized(trust) { trust.authorizeAny(creds, System.currentTimeMillis()) } },
            ownCertificates = { own.toList() },
            handshakeTimeoutMillis = 5_000,
        )
    }

    private fun serve(node: Node, links: LinkedBlockingQueue<PeerLink>, rejects: LinkedBlockingQueue<String>): Int {
        val server = node.endpoint().listen(onReject = { rejects.add(it) }) { links.add(it) }
        closeables += server
        return server.port
    }

    @Test
    fun pairedNodesLinkAndExchangeHeartbeats() {
        val a = Node()
        val b = Node()
        a.trust.addPair(PairRecord(b.key.publicKey, "b", now))
        b.trust.addPair(PairRecord(a.key.publicKey, "a", now))
        val accepted = LinkedBlockingQueue<PeerLink>()
        val port = serve(b, accepted, LinkedBlockingQueue())

        val fromA = a.endpoint().connect("127.0.0.1", port).also { closeables += it }
        val atB = assertNotNull(accepted.poll(10, TimeUnit.SECONDS))
        closeables += atB

        assertEquals(b.key.nodeId, fromA.nodeId)
        assertEquals(a.key.nodeId, atB.nodeId)
        assertEquals(setOf(TrustDomain.Pairing), fromA.domains)

        val beat = Heartbeat(AIDIN_PROTOCOL_VERSION, "a-1.0", contributionRamBytes = 42)
        fromA.send(LinkMessage.Beat(beat))
        assertEquals(LinkMessage.Beat(beat), atB.receive())

        fromA.close()
        assertIs<LinkMessage.Bye>(atB.receive())
        assertNull(atB.receive())
    }

    @Test
    fun groupMembersLinkWithoutPairingAndCarryRevocations() {
        val authority = GroupAuthority(SigningKey.generate())
        val a = Node()
        val b = Node()
        for (n in listOf(a, b)) {
            val cert = authority.issue(n.key.publicKey, Scope.ALL, now)
            n.trust.join(GroupMembership(authority.groupPublicKey, cert), now)
            n.own += cert
        }
        val accepted = LinkedBlockingQueue<PeerLink>()
        val port = serve(b, accepted, LinkedBlockingQueue())

        val fromA = a.endpoint().connect("127.0.0.1", port).also { closeables += it }
        val atB = assertNotNull(accepted.poll(10, TimeUnit.SECONDS)).also { closeables += it }
        assertEquals(setOf(TrustDomain.Group(authority.groupId)), atB.domains)

        val list = authority.revocations(1, emptySet(), now)
        fromA.send(LinkMessage.Revocations(list.toWire()))
        val received = assertIs<LinkMessage.Revocations>(atB.receive())
        assertTrue(b.trust.acceptRevocations(assertNotNull(received.list.toRevocationList())))
    }

    @Test
    fun strangerIsRefusedByTheAcceptingSide() {
        val a = Node()
        val b = Node()
        // a trusts b, but b has never heard of a.
        a.trust.addPair(PairRecord(b.key.publicKey, "b", now))
        val accepted = LinkedBlockingQueue<PeerLink>()
        val rejects = LinkedBlockingQueue<String>()
        val port = serve(b, accepted, rejects)

        val e = assertFailsWith<PeerRejectedException> { a.endpoint().connect("127.0.0.1", port) }
        assertTrue(e.message!!.contains("not trusted"))
        assertEquals("not trusted", rejects.poll(10, TimeUnit.SECONDS))
        assertNull(accepted.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun connectingSideRefusesAServerItDoesNotTrust() {
        val a = Node()
        val b = Node()
        b.trust.addPair(PairRecord(a.key.publicKey, "a", now))
        val accepted = LinkedBlockingQueue<PeerLink>()
        val port = serve(b, accepted, LinkedBlockingQueue())

        assertFailsWith<PeerRejectedException> { a.endpoint().connect("127.0.0.1", port) }
    }

    @Test
    fun revokedMemberIsRefused() {
        val authority = GroupAuthority(SigningKey.generate())
        val a = Node()
        val b = Node()
        for (n in listOf(a, b)) {
            val cert = authority.issue(n.key.publicKey, Scope.ALL, now)
            n.trust.join(GroupMembership(authority.groupPublicKey, cert), now)
            n.own += cert
        }
        b.trust.acceptRevocations(authority.revocations(1, setOf(a.key.nodeId), now))
        val port = serve(b, LinkedBlockingQueue(), LinkedBlockingQueue())
        assertFailsWith<PeerRejectedException> { a.endpoint().connect("127.0.0.1", port) }
    }
}
