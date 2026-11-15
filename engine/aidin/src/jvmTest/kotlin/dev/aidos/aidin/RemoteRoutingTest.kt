package dev.aidos.aidin

import dev.aidos.routes.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RFC-0108 integration: hints, the per-peer switch, the redial loop and its audit, over real AIDIN nodes. */
class RemoteRoutingTest {
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

    private fun connected(a: AidinNode, b: AidinNode) = a.peers.value.any { it.nodeId == b.nodeId && it.connected }

    @Test
    fun `remote access is off by default and the switch is audited`() {
        val (a, b) = paired()
        val audit = CopyOnWriteArrayList<RemoteRoutingAudit>()
        val routing = RemoteRouting(a, scope, audit = { audit += it })
        assertFalse(routing.isRemoteAccessAllowed(b.nodeId))
        routing.setRemoteAccess(b.nodeId, true, "desktop")
        routing.setRemoteAccess(b.nodeId, false, "desktop")
        val events = audit.filterIsInstance<RemoteRoutingAudit.SwitchChanged>().map { it.event.allowed }
        assertEquals(listOf(true, false), events)
    }

    @Test
    fun `the redial loop reconnects an allowed peer through a hint and leaves a peer that is not allowed alone`() = runBlocking {
        val (a, b) = paired()
        val audit = CopyOnWriteArrayList<RemoteRoutingAudit>()
        val routing = RemoteRouting(a, scope, audit = { audit += it })
        routing.addHint(b.nodeId, "127.0.0.1", b.port!!)

        a.markPeerLost(b.nodeId)
        eventually("link gone") { !connected(a, b) }

        routing.redialOnce()
        assertFalse(connected(a, b), "not allowed, so not dialled")

        routing.setRemoteAccess(b.nodeId, true)
        routing.redialOnce()
        eventually("reconnected through the hint") { connected(a, b) }
        assertTrue(audit.any { it is RemoteRoutingAudit.Dialed })
    }

    @Test
    fun `a hint that points at some other node is not trusted`() = runBlocking {
        val (a, b) = paired()
        val c = node()
        val routing = RemoteRouting(a, scope)
        routing.addHint(b.nodeId, "127.0.0.1", c.port!!) // wrong node behind the address
        routing.setRemoteAccess(b.nodeId, true)
        a.markPeerLost(b.nodeId)
        eventually("link gone") { !connected(a, b) }
        routing.redialOnce()
        assertFalse(connected(a, b))
        assertFalse(a.peers.value.any { it.nodeId == c.nodeId && it.connected }, "the stranger is not trusted either")
    }

    @Test
    fun `hints are validated and persisted with the switch`() {
        val (a, b) = paired()
        val file = Files.createTempDirectory("routing").toFile().resolve("remote-routing.json")
        val routing = RemoteRouting(a, scope, file)
        assertFailsWith<IllegalArgumentException> { routing.addHint(b.nodeId, " ", 99) }
        assertFailsWith<IllegalArgumentException> { routing.addHint(b.nodeId, "host.example", 0) }
        routing.addHint(b.nodeId, "100.64.0.7", 47100)
        routing.setRemoteAccess(b.nodeId, true)

        val reloaded = RemoteRouting(a, scope, file)
        assertEquals(listOf(Route.Direct("100.64.0.7", 47100)), reloaded.hintsFor(b.nodeId))
        assertTrue(reloaded.isRemoteAccessAllowed(b.nodeId))
        reloaded.removeHint(b.nodeId, "100.64.0.7", 47100)
        assertTrue(RemoteRouting(a, scope, file).hintsFor(b.nodeId).isEmpty())
    }

    @Test
    fun `turning the switch off closes a live remote link`() {
        val (a, b) = paired()
        val routingB = RemoteRouting(b, scope)
        routingB.setRemoteAccess(a.nodeId, true)
        val ingress = b.startRemoteIngress()
        a.connect("127.0.0.1", ingress)
        eventually("tunnel link admitted") { b.routeOf(a.nodeId) == LinkRoute.SSH_TUNNEL }
        routingB.setRemoteAccess(a.nodeId, false)
        eventually("remote link closed") { b.routeOf(a.nodeId) != LinkRoute.SSH_TUNNEL }
    }
}
