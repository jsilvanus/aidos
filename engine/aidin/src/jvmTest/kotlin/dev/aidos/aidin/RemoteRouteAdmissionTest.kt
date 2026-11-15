package dev.aidos.aidin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RFC-0108: links that arrive over a tunnel need the per-peer remote-access switch, and never carry split roles. */
class RemoteRouteAdmissionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nodes = mutableListOf<AidinNode>()

    @AfterTest
    fun tearDown() {
        nodes.forEach { runCatching { it.stop() } }
        scope.cancel()
    }

    private fun node() = AidinNode(
        SigningKey.generate(), InMemoryAidinStateStore(), scope, engineVersion = "test",
        tracker = PeerTracker(heartbeatIntervalMillis = 100, leaveAfterMillis = 2_000),
    ).also { it.start("127.0.0.1"); nodes += it }

    private fun eventually(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(20) }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun paired(): Pair<AidinNode, AidinNode> {
        val a = node()
        val b = node()
        a.pairWith(b.createInvite(listOf("127.0.0.1")))
        eventually("pairings") { a.pairingViews.value.isNotEmpty() && b.pairingViews.value.isNotEmpty() }
        a.confirmPairing(a.pairingViews.value.single().id, "b")
        b.confirmPairing(b.pairingViews.value.single().id, "a")
        eventually("linked") {
            a.pairingViews.value.single().status == PairingStatus.DONE && b.pairingViews.value.single().status == PairingStatus.DONE &&
                a.peers.value.any { it.connected } && b.peers.value.any { it.connected }
        }
        return a to b
    }

    @Test
    fun `a normal LAN link is unaffected and is classed lan`() {
        val (a, b) = paired()
        assertEquals(LinkRoute.LAN, b.routeOf(a.nodeId))
    }

    @Test
    fun `a link delivered by a tunnel is refused until the peer is allowed remote access, and every decision is audited`() {
        val (a, b) = paired()
        val decisions = CopyOnWriteArrayList<Triple<NodeId, LinkRoute, Boolean>>()
        b.onRemoteLinkDecision = { peer, route, ok -> decisions += Triple(peer, route, ok) }
        val ingress = b.startRemoteIngress()

        // Off by default: refused. (The dialling side replaces its own earlier link, so B ends with none.)
        a.connect("127.0.0.1", ingress)
        eventually("refusal audited") { decisions.isNotEmpty() }
        assertEquals(Triple(a.nodeId, LinkRoute.SSH_TUNNEL, false), decisions.first())
        assertTrue(b.routeOf(a.nodeId) != LinkRoute.SSH_TUNNEL)

        // The user allows remote access for that peer: the same dial is now admitted and classed as a tunnel.
        b.remoteAccessAllowed = { it == a.nodeId }
        a.connect("127.0.0.1", ingress)
        eventually("admitted as tunnel") { b.routeOf(a.nodeId) == LinkRoute.SSH_TUNNEL }
        assertEquals(Triple(a.nodeId, LinkRoute.SSH_TUNNEL, true), decisions.last())
    }

    @Test
    fun `split roles are refused over a remote link even for a fully trusted peer`() {
        val (a, b) = paired()
        b.remoteAccessAllowed = { true }
        val ingress = b.startRemoteIngress()
        a.connect("127.0.0.1", ingress)
        eventually("tunnel link") { b.routeOf(a.nodeId) == LinkRoute.SSH_TUNNEL }

        assertTrue(b.authorize(a.nodeId, Scope.DELEGATE).isAllowed, "delegation may travel over a remote route")
        assertFalse(b.authorize(a.nodeId, Scope.HELP).isAllowed)
        assertFalse(b.authorize(a.nodeId, Scope.ANCHOR).isAllowed)
        assertFalse(b.isSplitRouteTrustedByOrigin(a.nodeId, listOf(a.nodeId)), "a split plan never runs over a remote link")
    }
}
