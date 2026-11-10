package dev.aidos.aidin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** RFC-0105 phase 1 end to end: two (or three) real nodes over loopback TLS. */
class AidinNodeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nodes = mutableListOf<AidinNode>()

    @AfterTest
    fun tearDown() {
        nodes.forEach { runCatching { it.stop() } }
        scope.cancel()
    }

    private fun node(store: AidinStateStore = InMemoryAidinStateStore()): AidinNode =
        AidinNode(
            SigningKey.generate(),
            store,
            scope,
            engineVersion = "test",
            tracker = PeerTracker(heartbeatIntervalMillis = 100, leaveAfterMillis = 2_000),
        ).also {
            it.start("127.0.0.1")
            nodes += it
        }

    private fun eventually(what: String, timeoutMillis: Long = 10_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun AidinNode.pairing(): PairingView {
        eventually("a pairing on $nodeId") { pairingViews.value.isNotEmpty() }
        return pairingViews.value.single()
    }

    private fun AidinNode.connectedTo(other: AidinNode) =
        peers.value.any { it.nodeId == other.nodeId && it.connected }

    @Test
    fun twoDevicesPairAfterBothConfirmTheSameCode() {
        val a = node()
        val b = node()
        val invite = b.createInvite(listOf("127.0.0.1"))

        a.pairWith(invite)
        val atA = a.pairing()
        val atB = b.pairing()
        assertEquals(atA.code, atB.code)
        assertEquals(PairingKind.PAIR, atB.kind)
        // Nothing is trusted before both confirm.
        assertIs<TrustDecision.Denied>(a.authorize(b.nodeId, Scope.DELEGATE))

        a.confirmPairing(atA.id, "laptop B")
        b.confirmPairing(atB.id, "phone A")

        eventually("both pairings done") {
            a.pairingViews.value.single().status == PairingStatus.DONE &&
                b.pairingViews.value.single().status == PairingStatus.DONE
        }
        eventually("normal link after pairing") { a.connectedTo(b) && b.connectedTo(a) }
        assertIs<TrustDecision.Allowed>(a.authorize(b.nodeId, Scope.DELEGATE))
        assertIs<TrustDecision.Allowed>(b.authorize(a.nodeId, Scope.SERVE))
        assertEquals("laptop B", a.peers.value.single { it.nodeId == b.nodeId }.label)
        eventually("heartbeats flow") { a.peers.value.single().lastHeartbeat != null }
    }

    @Test
    fun rejectingOnOneSideStoresNothingOnEither() {
        val a = node()
        val b = node()
        a.pairWith(b.createInvite(listOf("127.0.0.1")))
        val atA = a.pairing()
        val atB = b.pairing()
        a.confirmPairing(atA.id, "B")
        b.rejectPairing(atB.id)

        eventually("a sees the failure") { a.pairingViews.value.single().status == PairingStatus.FAILED }
        assertTrue(a.peers.value.none { it.label != null })
        assertTrue(b.peers.value.isEmpty())
    }

    @Test
    fun anInviteWorksOnceAndOnlyWhileShown() {
        val a = node()
        val b = node()
        val invite = b.createInvite(listOf("127.0.0.1"))
        b.cancelInvites()
        assertFailsWith<PeerRejectedException> { a.pairWith(invite) }

        val fresh = b.createInvite(listOf("127.0.0.1"))
        a.pairWith(fresh)
        b.pairing()
        // Consumed by the first pairing: a second device cannot reuse it.
        val c = node()
        assertFailsWith<PeerRejectedException> { c.pairWith(fresh) }
    }

    @Test
    fun anInviteFromAnotherDeviceAtThatAddressIsRefused() {
        val a = node()
        val b = node()
        val c = node()
        // C's invite, but B answers at that port.
        val forged = c.createInvite(listOf("127.0.0.1")).copy(port = b.port!!)
        assertFailsWith<PeerRejectedException> { a.pairWith(forged) }
    }

    /**
     * Regression for the flaky renewal test on CI: the admin used to open the normal link the moment
     * it had sent Enrolment. A device that had not stored the enrolment yet (its reader thread not
     * scheduled, a busy phone) refused that link as untrusted, and with no discovery nothing
     * retried. Here the enrolled device is a bare endpoint that, like a real node, admits only what
     * its trust store holds, and takes half a second to store before closing the pairing link.
     */
    @Test
    fun adminOpensTheNormalLinkOnlyAfterTheEnrolledDeviceStored() {
        val admin = node()
        val group = admin.createGroup("Office")
        val memberKey = SigningKey.generate()
        val trust = TrustStore(memberKey.nodeId)
        val ownCert = AtomicReference<MembershipCertificate?>(null)
        val pairingLink = CompletableFuture<PeerLink>()
        val normalLinks = CopyOnWriteArrayList<PeerLink>()
        val refusals = AtomicInteger()
        var invite: PairingInvite? = null
        val endpoint = PeerLinkEndpoint(
            memberKey,
            admit = { creds -> synchronized(trust) { trust.authorizeAny(creds, System.currentTimeMillis()) } },
            ownCertificates = { listOfNotNull(ownCert.get()) },
            pendingInviteSecrets = { listOfNotNull(invite?.secret) },
        )
        val server = endpoint.listen("127.0.0.1", onReject = { refusals.incrementAndGet() }) { link ->
            if (link.isPairing) pairingLink.complete(link) else normalLinks += link
        }
        try {
            invite = PairingInvite.create(memberKey, listOf("127.0.0.1"), server.port)
            admin.enrol(invite!!, group)
            val link = pairingLink.get(10, TimeUnit.SECONDS)
            assertEquals(LinkMessage.PairOffer(group.value), link.receive())

            admin.confirmPairing(admin.pairingViews.value.single().id, "member")
            link.send(LinkMessage.PairConfirm(Scope.ALL.map { it.wire }, group.value))
            val enrolment = assertIs<LinkMessage.Enrolment>(generateSequence { link.receive() }.first { it !is LinkMessage.PairConfirm })

            Thread.sleep(500)
            val cert = assertNotNull(enrolment.certificate.toCertificate())
            synchronized(trust) {
                trust.join(GroupMembership(cert.groupPublicKey, cert, enrolment.revocations?.toRevocationList()), System.currentTimeMillis())
            }
            ownCert.set(cert)
            link.close()

            eventually("admin links the member") { admin.peers.value.any { it.nodeId == memberKey.nodeId && it.connected } }
            assertEquals(0, refusals.get(), "the admin connected before the member had stored its enrolment")
        } finally {
            normalLinks.forEach { runCatching { it.close() } }
            server.close()
        }
    }

    @Test
    fun adminEnrolsMembersWhoThenTrustEachOtherWithoutPairing() {
        val admin = node()
        val m1 = node()
        val m2 = node()
        val group = admin.createGroup("Office")

        for (m in listOf(m1, m2)) {
            admin.enrol(m.createInvite(listOf("127.0.0.1")), group)
            val here = admin.pairingViews.value.last()
            eventually("member sees an enrolment") { m.pairingViews.value.singleOrNull()?.kind == PairingKind.ENROLLED_BY_OTHER }
            val there = m.pairingViews.value.single()
            assertEquals(group, there.groupId)
            admin.confirmPairing(here.id, "member")
            m.confirmPairing(there.id, "Office")
            eventually("member joined") { m.groups().any { it.first == group } }
            eventually("admin links the member") { admin.connectedTo(m) }
        }
        assertEquals("Office", m1.groups().single().second)

        // Members never paired, but the group admits them to each other.
        m1.connect("127.0.0.1", m2.port!!)
        eventually("members linked") { m1.connectedTo(m2) && m2.connectedTo(m1) }
        val decision = assertIs<TrustDecision.Allowed>(m1.authorize(m2.nodeId, Scope.HELP))
        assertTrue(decision.domains.any { it is TrustDomain.Group })
    }

    @Test
    fun revocationReachesMembersAndDropsTheRevokedLink() {
        val admin = node()
        val m1 = node()
        val m2 = node()
        val group = admin.createGroup("Office")
        for (m in listOf(m1, m2)) {
            admin.enrol(m.createInvite(listOf("127.0.0.1")), group)
            val here = admin.pairingViews.value.last()
            eventually("enrolment offered") { m.pairingViews.value.isNotEmpty() }
            admin.confirmPairing(here.id, "m")
            m.confirmPairing(m.pairingViews.value.single().id, "Office")
            eventually("admin links the member") { admin.connectedTo(m) }
        }
        m1.connect("127.0.0.1", m2.port!!)
        eventually("members linked") { m1.connectedTo(m2) }

        admin.revoke(group, m2.nodeId)

        eventually("admin dropped the revoked member") { !admin.connectedTo(m2) }
        eventually("m1 learned the revocation and dropped m2") { !m1.connectedTo(m2) }
        assertIs<TrustDecision.Denied>(m1.authorize(m2.nodeId, Scope.HELP))
        assertTrue(m1.connectedTo(admin))
    }

    @Test
    fun stateSurvivesARestartThroughTheFileStore() {
        val dir = Files.createTempDirectory("aidin-state").toFile()
        try {
            val file = File(dir, "aidin.json")
            val store = FileAidinStateStore(file)
            val now = System.currentTimeMillis()
            val groupKey = SigningKey.generate()
            val authority = GroupAuthority(groupKey)
            val me = SigningKey.generate()
            val peer = SigningKey.generate()
            val state = AidinState(
                pairs = listOf(PairRecord(peer.publicKey, "phone", now, setOf(Scope.DELEGATE))),
                memberships = listOf(
                    GroupMembership(authority.groupPublicKey, authority.issue(me.publicKey, Scope.ALL, now), authority.revocations(3, setOf(peer.nodeId), now)),
                ),
                groupLabels = mapOf(authority.groupId to "Office"),
                administered = listOf(AdministeredGroup("Office", authority, 3, setOf(peer.nodeId), groupKey)),
            )
            store.save(state)
            val loaded = FileAidinStateStore(file).load()

            assertEquals(state.pairs, loaded.pairs)
            assertEquals(state.groupLabels, loaded.groupLabels)
            assertEquals(3, loaded.memberships.single().revocations?.serial)
            val admin = loaded.administered.single()
            assertEquals(authority.groupId, admin.groupId)
            assertEquals(setOf(peer.nodeId), admin.revoked)
            // The restored group key still signs certificates members accept.
            val cert = admin.authority.issue(peer.publicKey, Scope.ALL, now)
            assertTrue(cert.isValidAt(now) && cert.groupId == authority.groupId)
            if (File("/").toPath().fileSystem.supportedFileAttributeViews().contains("posix")) {
                assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
