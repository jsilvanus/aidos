package fi.italeino.sshtunnel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackoffTest {
    @Test
    fun growsAndCaps() {
        val b = Backoff(1_000, 10_000, 2.0)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L), (1..6).map { b.delayMillis(it) })
    }

    @Test
    fun rejectsBadInput() {
        assertFailsWith<IllegalArgumentException> { Backoff(0) }
        assertFailsWith<IllegalArgumentException> { Backoff().delayMillis(0) }
    }
}

class AuthorizedKeysTest {
    private val pk = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFake aidos-tunnel-x"

    @Test
    fun reverseLineIsRestricted() {
        val l = AuthorizedKeys.forReverse(pk, Forward.Reverse(serverPort = 47100, targetPort = 47100))
        assertEquals(
            "restrict,port-forwarding,permitlisten=\"127.0.0.1:47100\",command=\"/bin/false\" $pk",
            l,
        )
    }

    @Test
    fun localLineUsesPermitOpen() {
        val l = AuthorizedKeys.forLocal(pk, Forward.Local("127.0.0.1", 47100))
        assertTrue(l.contains("permitopen=\"127.0.0.1:47100\""))
    }

    @Test
    fun refusesInjection() {
        assertFailsWith<IllegalArgumentException> {
            AuthorizedKeys.forReverse(pk, Forward.Reverse(1, 1, serverBindHost = "a\"b"))
        }
        assertFailsWith<IllegalArgumentException> {
            AuthorizedKeys.forReverse("$pk\nssh-rsa AAAA evil", Forward.Reverse(1, 1))
        }
        assertFailsWith<IllegalArgumentException> {
            AuthorizedKeys.forReverse(pk, Forward.Reverse(1, 1), forcedCommand = "x\" y")
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SshTunnelTest {
    private class FakeSession(override val boundPort: Int = 4711) : SshSession {
        val closed = CompletableDeferred<Unit>()
        override suspend fun awaitClosed() = closed.await()
        override fun close() { closed.complete(Unit) }
    }

    private val spec = TunnelSpec("example.org", "tun", Forward.Reverse(47100, 47100), backoff = Backoff(1_000, 4_000))
    private val allow = HostKeyPolicy { _, _, _ -> true }

    private fun TestScope.tunnel(keys: KeyProvider, transport: SshTransport) =
        SshTunnel(spec, keys, allow, transport, this.backgroundScope)

    @Test
    fun comesUpAndZeroesKey() = runTest(StandardTestDispatcher()) {
        var seen: ByteArray? = null
        val t = tunnel({ byteArrayOf(1, 2, 3) }, { _, key, _ -> seen = key; FakeSession() })
        t.start(); runCurrent()
        assertEquals(TunnelState.Up(4711), t.state.value)
        assertTrue(seen!!.all { it == 0.toByte() }, "key bytes are wiped after connect")
        t.stop()
        assertEquals(TunnelState.Stopped, t.state.value)
    }

    @Test
    fun reconnectsWithBackoffAfterNetworkErrors() = runTest(StandardTestDispatcher()) {
        var calls = 0
        val t = tunnel({ byteArrayOf(1) }, { _, _, _ ->
            calls++
            if (calls <= 2) throw TunnelException.Network("down") else FakeSession()
        })
        t.start(); runCurrent()
        assertEquals(TunnelState.Backoff(1, 1_000, "down"), t.state.value)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(TunnelState.Backoff(2, 2_000, "down"), t.state.value)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(TunnelState.Up(4711), t.state.value)
        assertEquals(3, calls)
        t.stop()
    }

    @Test
    fun reconnectsWhenSessionDrops() = runTest(StandardTestDispatcher()) {
        val sessions = mutableListOf<FakeSession>()
        val t = tunnel({ byteArrayOf(1) }, { _, _, _ -> FakeSession().also { sessions += it } })
        t.start(); runCurrent()
        sessions[0].closed.complete(Unit); runCurrent()
        assertTrue(t.state.value is TunnelState.Backoff)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(TunnelState.Up(4711), t.state.value)
        assertEquals(2, sessions.size)
        t.stop()
    }

    @Test
    fun hostKeyChangeIsTerminal() = runTest(StandardTestDispatcher()) {
        var calls = 0
        val t = tunnel({ byteArrayOf(1) }, { _, _, _ -> calls++; throw TunnelException.HostKeyRejected("SHA256:other") })
        t.start(); runCurrent(); advanceTimeBy(120_000); runCurrent()
        assertEquals(TunnelState.HostKeyChanged("SHA256:other"), t.state.value)
        assertEquals(1, calls, "a changed host key is never retried")
    }

    @Test
    fun authAndForwardRefusalAreTerminal() = runTest(StandardTestDispatcher()) {
        val a = tunnel({ byteArrayOf(1) }, { _, _, _ -> throw TunnelException.AuthFailed("no") })
        a.start(); runCurrent()
        assertEquals(TunnelState.AuthRefused("no"), a.state.value)
        val f = tunnel({ byteArrayOf(1) }, { _, _, _ -> throw TunnelException.ForwardRefused("denied") })
        f.start(); runCurrent()
        assertEquals(TunnelState.ForwardRefused("denied"), f.state.value)
    }

    @Test
    fun missingKeyStopsTunnel() = runTest(StandardTestDispatcher()) {
        var connected = false
        val t = tunnel({ null }, { _, _, _ -> connected = true; FakeSession() })
        t.start(); runCurrent()
        assertEquals(TunnelState.AuthRefused("no key available"), t.state.value)
        assertTrue(!connected)
    }
}
