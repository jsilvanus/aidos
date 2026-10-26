package dev.aidos.aidin

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeerTrackerTest {
    private val peer = SigningKey.generate().nodeId
    private val beat = Heartbeat(protocolVersion = AIDIN_PROTOCOL_VERSION, engineVersion = "test")

    @Test
    fun heartbeatMakesReadyAndActiveIsTheCallersChoice() {
        val t = PeerTracker()
        t.onConnected(peer, 0)
        assertEquals(PeerState.JOINING, t.state(peer))
        t.onHeartbeat(peer, beat, 100)
        assertEquals(PeerState.READY, t.state(peer))
        t.markActive(peer, true, 200)
        assertEquals(PeerState.ACTIVE, t.state(peer))
        t.onHeartbeat(peer, beat, 3_000)
        assertEquals(PeerState.ACTIVE, t.state(peer))
        t.markActive(peer, false, 3_100)
        assertEquals(PeerState.READY, t.state(peer))
    }

    @Test
    fun oneLateHeartbeatChangesNothingThenSuspectedThenLeft() {
        val t = PeerTracker(heartbeatIntervalMillis = 3_000, missedBeforeSuspect = 3, leaveAfterMillis = 20_000)
        t.onHeartbeat(peer, beat, 0)
        assertTrue(t.tick(6_000).isEmpty())
        assertEquals(PeerState.READY, t.state(peer))
        val suspected = t.tick(9_001)
        assertEquals(listOf(PeerTransition(peer, PeerState.READY, PeerState.SUSPECTED, 9_001)), suspected)
        assertTrue(t.tick(15_000).isEmpty())
        assertEquals(PeerState.LEFT, t.tick(20_001).single().to)
    }

    @Test
    fun suspectedPeerThatComesBackResumesItsRole() {
        val t = PeerTracker()
        t.onHeartbeat(peer, beat, 0)
        t.markActive(peer, true, 0)
        t.tick(10_000)
        assertEquals(PeerState.SUSPECTED, t.state(peer))
        t.onHeartbeat(peer, beat, 11_000)
        assertEquals(PeerState.ACTIVE, t.state(peer))
    }

    @Test
    fun peerThatLeftStartsOverAsReady() {
        val t = PeerTracker()
        t.onHeartbeat(peer, beat, 0)
        t.markActive(peer, true, 0)
        t.tick(30_000)
        assertEquals(PeerState.LEFT, t.state(peer))
        t.onHeartbeat(peer, beat, 31_000)
        assertEquals(PeerState.READY, t.state(peer))
    }

    @Test
    fun drainingHeartbeatAndCleanDisconnect() {
        val t = PeerTracker()
        t.onHeartbeat(peer, beat, 0)
        t.onHeartbeat(peer, beat.copy(draining = true), 3_000)
        assertEquals(PeerState.DRAINING, t.state(peer))
        t.onDisconnected(peer, 4_000)
        assertEquals(PeerState.LEFT, t.state(peer))
    }

    @Test
    fun linkWithoutAnyHeartbeatAlsoTimesOut() {
        val t = PeerTracker()
        t.onConnected(peer, 0)
        t.tick(10_000)
        assertEquals(PeerState.SUSPECTED, t.state(peer))
        t.tick(21_000)
        assertEquals(PeerState.LEFT, t.state(peer))
    }

    @Test
    fun heartbeatReadersIgnoreUnknownFields() {
        val json = Json { ignoreUnknownKeys = true }
        val text = """{"protocolVersion":1,"engineVersion":"x","models":[{"id":"m","kind":"chat","placement":"local","future":1}],"somethingNew":true}"""
        val decoded = json.decodeFromString(Heartbeat.serializer(), text)
        assertEquals("m", decoded.models.single().id)
    }
}
