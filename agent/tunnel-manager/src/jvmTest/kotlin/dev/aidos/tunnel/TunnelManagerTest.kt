package dev.aidos.tunnel

import fi.italeino.sshtunnel.Forward
import fi.italeino.sshtunnel.SshSession
import fi.italeino.sshtunnel.SshTransport
import fi.italeino.sshtunnel.TunnelState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import dev.aidos.vault.SecretsVault
import kotlin.test.Test
import kotlin.test.assertEquals

class TunnelManagerTest {
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test
    fun createsAndStartsTunnel() = runBlocking {
        val storage = FakeTunnelStorage()
        val vault = FakeVault()
        val audit = FakeAuditLog()

        val config = StoredTunnelConfig(
            id = "home-desktop",
            name = "Home Desktop",
            host = "home.example.com",
            user = "ubuntu",
            forward = StoredForward.Reverse(serverPort = 47100, targetPort = 47100),
            vaultKeyId = "tunnel-key-home",
        )

        val manager = TunnelManager(storage, vault, audit, testScope)
        val tunnel = manager.createTunnel(config)

        assertEquals("Stopped", tunnel.statusText.value)
        tunnel.start()
        // Would check state changes with real transport
    }

    @Test
    fun allowsRemoteAccess() = runBlocking {
        val storage = FakeTunnelStorage()
        val vault = FakeVault()
        val audit = FakeAuditLog()

        val config = StoredTunnelConfig(
            id = "work-server",
            name = "Work Server",
            host = "work.corp",
            user = "deploy",
            forward = StoredForward.Local(remoteHost = "db.internal", remotePort = 5432),
            vaultKeyId = "tunnel-work",
            remoteAccessAllowed = false,
        )

        val manager = TunnelManager(storage, vault, audit, testScope)
        val tunnel = manager.createTunnel(config)

        assertEquals(false, tunnel.config.remoteAccessAllowed)
        tunnel.allowRemoteAccess(true)
        assertEquals(true, storage.lastSavedConfig?.remoteAccessAllowed)
    }
}

private class FakeTunnelStorage : TunnelStorage {
    private val configs = mutableMapOf<String, StoredTunnelConfig>()
    private val pinnedKeys = mutableMapOf<String, String>()
    private val states = mutableMapOf<String, TunnelState>()
    var lastSavedConfig: StoredTunnelConfig? = null

    override suspend fun loadConfigs(): List<StoredTunnelConfig> = configs.values.toList()

    override suspend fun saveTunnelConfig(id: String, config: StoredTunnelConfig) {
        configs[id] = config
        lastSavedConfig = config
    }

    override suspend fun deleteTunnelConfig(id: String) {
        configs.remove(id)
    }

    override suspend fun loadPinnedHostKey(tunnelId: String): String? = pinnedKeys[tunnelId]

    override suspend fun pinHostKey(tunnelId: String, fingerprint: String) {
        pinnedKeys[tunnelId] = fingerprint
    }

    override suspend fun loadTunnelState(tunnelId: String): TunnelState? = states[tunnelId]

    override suspend fun saveTunnelState(tunnelId: String, state: TunnelState) {
        states[tunnelId] = state
    }
}

private class FakeVault : VaultIntegration {
    private val keys = mutableMapOf<String, ByteArray>()
    override val vault = FakeSecretsVault()

    override suspend fun hasKey(keyId: String): Boolean = keyId in keys

    override suspend fun storeKey(keyId: String, privateKey: ByteArray, kind: String) {
        keys[keyId] = privateKey
    }

    override suspend fun deleteKey(keyId: String) {
        keys.remove(keyId)
    }

    private class FakeSecretsVault : SecretsVault {
        override suspend fun store(id: String, value: ByteArray, kind: String) {}
        override suspend fun resolve(id: String): ByteArray? = null
        override suspend fun delete(id: String) {}
        override fun lock() {}
        override fun unlock() {}
        override fun isLocked(): Boolean = false
    }
}

private class FakeAuditLog : TunnelAuditLog {
    val logs = mutableListOf<Pair<String, String>>()

    override suspend fun log(tunnelId: String, message: String) {
        logs.add(tunnelId to message)
    }
}

private class FakeSshTransport : SshTransport {
    override suspend fun connect(
        spec: fi.italeino.sshtunnel.TunnelSpec,
        privateKey: ByteArray,
        hostKeys: fi.italeino.sshtunnel.HostKeyPolicy,
    ): SshSession = FakeSshSession()

    private class FakeSshSession : SshSession {
        private val closed = CompletableDeferred<Unit>()
        override val boundPort = 47100

        override suspend fun awaitClosed() = closed.await()
        override fun close() { closed.complete(Unit) }
    }
}
