package fi.italeino.aidos.engine.desktop

import dev.aidos.aidin.AidinNode
import dev.aidos.aidin.FileAidinStateStore
import dev.aidos.aidin.FileNodeKeyStore
import dev.aidos.aidin.InMemoryAidinStateStore
import dev.aidos.aidin.PairingStatus
import dev.aidos.aidin.PeerTracker
import dev.aidos.aidin.Scope
import dev.aidos.aidin.SigningKey
import dev.aidos.tcr.engine.PeerRouteUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Peers tab's presenter (RFC-0107, RFC-0108) against a real host and a real paired AIDIN peer. */
class DesktopRemotePeersModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val root = Files.createTempDirectory("aidos-peers-model").toFile()
    private val dir = root.resolve("aidin")
    private val host = DesktopEngineHost(
        modelsDir = root.resolve("models").apply { mkdirs() },
        stateDir = root,
        serverPort = { 0 },
        discoveryFile = null,
        groupSharing = { true },
        aidinFactory = { s ->
            AidinNode(FileNodeKeyStore(dir.resolve("node-key")).loadOrCreate(), FileAidinStateStore(dir.resolve("state.json")), s, "test",
                tracker = PeerTracker(heartbeatIntervalMillis = 100, leaveAfterMillis = 2_000))
        },
        aidinPort = 0,
    )
    private val other = AidinNode(SigningKey.generate(), InMemoryAidinStateStore(), scope, "test",
        tracker = PeerTracker(heartbeatIntervalMillis = 100, leaveAfterMillis = 2_000)).also { it.start("127.0.0.1") }

    @AfterTest
    fun tearDown() {
        runCatching { other.stop() }
        runBlocking { host.close() }
        scope.cancel()
        root.deleteRecursively()
    }

    private fun eventually(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(20) }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun pairedModel(): Pair<DesktopRemotePeersModel, dev.aidos.aidin.NodeId> {
        runBlocking { host.start() }
        val node = assertNotNull(host.aidin.value, "aidin failed: ${host.aidinError.value}")
        val invite = node.createInvite(listOf("127.0.0.1"))
        other.pairWith(invite)
        eventually("pairing views") { node.pairingViews.value.isNotEmpty() && other.pairingViews.value.isNotEmpty() }
        node.confirmPairing(node.pairingViews.value.single().id, "other device")
        other.confirmPairing(other.pairingViews.value.single().id, "desktop")
        eventually("paired and linked") {
            node.pairingViews.value.single().status == PairingStatus.DONE && node.peers.value.any { it.connected }
        }
        return DesktopRemotePeersModel(host, scope) to other.nodeId
    }

    @Test
    fun `with group sharing off the tab says there are no peers`() {
        val model = DesktopRemotePeersModel(host, scope)
        assertFalse(model.state.value.aidinRunning)
        assertTrue(model.state.value.peers.isEmpty())
    }

    @Test
    fun `every switch is off for a freshly paired peer and each action changes exactly what it names`() {
        val (model, peerId) = pairedModel()
        eventually("peer listed") { model.state.value.peers.any { it.id == peerId.value } }
        var peer = model.state.value.peers.single { it.id == peerId.value }
        assertEquals("other device", peer.label)
        assertTrue(peer.connected)
        assertEquals(PeerRouteUi.LAN, peer.route)
        assertFalse(peer.hostsAgentLoops)
        assertNull(peer.preferredRank)
        assertFalse(peer.remoteAccessAllowed)
        assertTrue(peer.hints.isEmpty() && peer.toolAllowlists.isEmpty())

        model.setHostsAgentLoops(peerId.value, true)
        model.setPreferred(peerId.value, true)
        model.setRemoteAccess(peerId.value, true)
        model.addHint(peerId.value, "100.64.0.7", 47100)
        model.setToolAllowlist(peerId.value, "dictator", listOf("search", "read_note"))

        eventually("actions reflected") { model.state.value.peers.singleOrNull { it.id == peerId.value }?.let { it.hostsAgentLoops && it.remoteAccessAllowed && it.hints.isNotEmpty() && it.toolAllowlists.isNotEmpty() } == true }
        peer = model.state.value.peers.single { it.id == peerId.value }
        assertTrue(peer.hostsAgentLoops)
        assertTrue(host.aidin.value!!.pairScopes(peerId)!!.contains(Scope.AGENT))
        assertEquals(1, peer.preferredRank)
        assertTrue(peer.remoteAccessAllowed)
        assertEquals(listOf("100.64.0.7:47100"), peer.hints)
        assertEquals(listOf("read_note", "search"), peer.toolAllowlists.single().tools) // stored sorted
        assertTrue(model.state.value.auditLines.any { it.contains("switch") && it.contains("allowed=true") }, "the switch is audited")

        model.setHostsAgentLoops(peerId.value, false)
        model.setPreferred(peerId.value, false)
        model.removeHint(peerId.value, "100.64.0.7:47100")
        eventually("withdrawals reflected") { model.state.value.peers.singleOrNull { it.id == peerId.value }?.let { !it.hostsAgentLoops && it.preferredRank == null && it.hints.isEmpty() } == true }
        peer = model.state.value.peers.single { it.id == peerId.value }
        assertFalse(peer.hostsAgentLoops)
        assertNull(peer.preferredRank)
        assertTrue(peer.hints.isEmpty())
        assertFalse(host.aidin.value!!.pairScopes(peerId)!!.contains(Scope.AGENT))
    }

    @Test
    fun `an effectful tool call on a tainted remote run shows as a prompt and the answer reaches the run`(): Unit = runBlocking {
        val (model, peerId) = pairedModel()
        val agent = assertNotNull(host.remoteAgent)
        val answer = CoroutineScope(Dispatchers.Default).async { agent.approvals.request(peerId, "dictator", "write_note") }
        eventually("prompt shown") { model.state.value.pendingApprovals.isNotEmpty() }
        val prompt = model.state.value.pendingApprovals.single()
        assertEquals("write_note", prompt.tool)
        assertEquals("other device", prompt.peerLabel)
        model.decideToolApproval(prompt.id, true)
        assertTrue(answer.await())
        eventually("prompt cleared") { model.state.value.pendingApprovals.isEmpty() }
    }
}
