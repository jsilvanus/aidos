package dev.aidos.tunnel

import dev.aidos.vault.ConsumerKind
import dev.aidos.vault.ConsumerRef
import dev.aidos.vault.SecretAccess
import dev.aidos.vault.SecretKind
import dev.aidos.vault.SecretRequest
import dev.aidos.vault.SecretsVault
import dev.aidos.vault.SqliteSecretsVault
import fi.italeino.sshtunnel.HostKeyPolicy
import fi.italeino.sshtunnel.KeyGen
import fi.italeino.sshtunnel.SshSession
import fi.italeino.sshtunnel.SshTransport
import fi.italeino.sshtunnel.TunnelException
import fi.italeino.sshtunnel.TunnelSpec
import fi.italeino.sshtunnel.TunnelState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TunnelManagerTest {
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tempDirs = mutableListOf<File>()
    private val openVaults = mutableListOf<SqliteSecretsVault>()

    @AfterTest
    fun tearDown(): Unit {
        testScope.cancel()
        openVaults.forEach { runCatching { it.close() } }
        openVaults.clear()
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun newVault(accessSink: ((SecretAccess) -> Unit)? = null): SqliteSecretsVault {
        val dir = Files.createTempDirectory("tunnel-manager-test").toFile()
        tempDirs += dir
        val vault = if (accessSink != null) {
            SqliteSecretsVault(
                dbFile = File(dir, "vault.db"),
                encryptionKey = SqliteSecretsVault.generateKey(),
                accessSink = { access -> accessSink(access) },
            )
        } else {
            SqliteSecretsVault(File(dir, "vault.db"))
        }
        openVaults += vault
        return vault
    }

    private fun sampleConfig(
        id: String = "home-desktop",
        vaultKeyId: String = "tunnel-key-$id",
        remoteAccessAllowed: Boolean = false,
    ): StoredTunnelConfig = StoredTunnelConfig(
        id = id,
        name = "Tunnel $id",
        host = "home.example.com",
        user = "ubuntu",
        forward = StoredForward.Reverse(serverPort = 47100, targetPort = 47100),
        vaultKeyId = vaultKeyId,
        remoteAccessAllowed = remoteAccessAllowed,
    )

    @Test
    fun vaultKeyProviderResolvesByNameAndIdAndZeroesIntermediateBuffers(): Unit = runBlocking {
        val vault = newVault()
        val integration = SecretsVaultIntegration(vault)
        val generated = KeyGen.ed25519("aidos-tunnel-test")
        val expectedBytes = generated.privateKey.copyOf()

        integration.storeKey("tunnel-key-home", generated.privateKey, "SSH_TUNNEL_KEY")
        generated.privateKey.fill(0)

        // Resolve by secret name (typical StoredTunnelConfig.vaultKeyId).
        val byNameProvider = VaultKeyProvider(vault, "tunnel-key-home")
        val resolvedByName = byNameProvider.privateKey()
        assertNotNull(resolvedByName)
        assertContentEquals(expectedBytes, resolvedByName)
        assertFalse(resolvedByName.all { it == 0.toByte() })

        // Resolve by generated SecretId UUID.
        val entry = vault.getByName("tunnel-key-home").getOrThrow()
        val byIdProvider = VaultKeyProvider(vault, entry.id)
        val resolvedById = byIdProvider.privateKey()
        assertNotNull(resolvedById)
        assertContentEquals(expectedBytes, resolvedById)

        // Missing or blank keys return null cleanly.
        assertNull(VaultKeyProvider(vault, "missing-key").privateKey())
        assertNull(VaultKeyProvider(vault, "   ").privateKey())

        // Closed/unavailable vault returns null instead of throwing.
        vault.close()
        assertNull(byNameProvider.privateKey())
    }

    @Test
    fun vaultKeyProviderUsesResolveForAndEnforcesAllowListTaintAndExpiry(): Unit = runBlocking {
        val accesses = CopyOnWriteArrayList<SecretAccess>()
        val vault = newVault { accesses += it }
        val allowedConsumer = ConsumerRef(ConsumerKind.MCP_SERVER, "tunnel-manager")
        val otherConsumer = ConsumerRef(ConsumerKind.PROVIDER, "anthropic")

        val generated = KeyGen.ed25519("aidos-tunnel-scoped")
        val expectedBytes = generated.privateKey.copyOf()
        val secretId = vault.store("tunnel-key-scoped", SecretKind.SSH_KEY, utf8BytesToChars(generated.privateKey))
        generated.privateKey.fill(0)
        vault.setAllowedConsumers(secretId, listOf(allowedConsumer))

        // Anonymous resolve is denied when an allow-list is configured.
        assertNull(VaultKeyProvider(vault, "tunnel-key-scoped").privateKey())

        // Disallowed consumer is denied.
        assertNull(VaultKeyProvider(vault, "tunnel-key-scoped", SecretRequest(otherConsumer)).privateKey())

        // Tainted run is denied.
        assertNull(
            VaultKeyProvider(
                vault,
                "tunnel-key-scoped",
                SecretRequest(allowedConsumer, tainted = true),
            ).privateKey(),
        )

        // Allowed, untainted consumer resolves and records access audit without leaking value.
        val resolved = VaultKeyProvider(
            vault,
            "tunnel-key-scoped",
            SecretRequest(allowedConsumer, capabilityId = "cap-1"),
        ).privateKey()
        assertNotNull(resolved)
        assertContentEquals(expectedBytes, resolved)
        assertEquals(1, accesses.size)
        assertEquals("cap-1", accesses.single().capabilityId)

        // Expired secret is denied; future expiry succeeds.
        vault.setExpiry(secretId, Instant.parse("2000-01-01T00:00:00Z"))
        assertNull(VaultKeyProvider(vault, secretId, SecretRequest(allowedConsumer)).privateKey())

        vault.setExpiry(secretId, Instant.parse("2099-01-01T00:00:00Z"))
        assertNotNull(VaultKeyProvider(vault, secretId, SecretRequest(allowedConsumer)).privateKey())
    }

    @Test
    fun hostKeyPinningPersistsTofuAcrossRestartsAndRejectsMismatch(): Unit = runBlocking {
        val storage = FakeTunnelStorage()
        val audit = FakeAuditLog()

        val firstSession = HostKeyPinningPolicy("t1", storage, audit, HostKeyTrustMode.TOFU)
        assertTrue(firstSession.accept("host.example", 22, "SHA256:fp-one"))
        assertEquals("SHA256:fp-one", storage.loadPinnedHostKey("t1"))
        assertTrue(firstSession.accept("host.example", 22, "SHA256:fp-one"))
        assertTrue(audit.logs.any { it.first == "t1" && it.second.contains("Host key pinned") })

        // Simulate process restart with a fresh policy instance reading from storage.
        val restartedSession = HostKeyPinningPolicy("t1", storage, audit, HostKeyTrustMode.TOFU)
        restartedSession.loadPinnedKey()
        assertTrue(restartedSession.accept("host.example", 22, "SHA256:fp-one"))
        assertFalse(restartedSession.accept("host.example", 22, "SHA256:fp-evil"))
        assertEquals("SHA256:fp-one", storage.loadPinnedHostKey("t1"))
        assertTrue(audit.logs.any { it.first == "t1" && it.second.contains("Host key mismatch") })
    }

    @Test
    fun hostKeyPinningStrictPrePinnedModeRejectsUnpinnedHost(): Unit = runBlocking {
        val storage = FakeTunnelStorage()
        val audit = FakeAuditLog()

        val strictPolicy = HostKeyPinningPolicy("t-strict", storage, audit, HostKeyTrustMode.STRICT_PRE_PINNED)
        assertFalse(strictPolicy.accept("host.example", 22, "SHA256:unknown"))
        assertNull(storage.loadPinnedHostKey("t-strict"))
        assertTrue(audit.logs.any { it.first == "t-strict" && it.second.contains("no pre-pinned key") })

        storage.pinHostKey("t-strict", "SHA256:trusted")
        strictPolicy.loadPinnedKey()
        assertTrue(strictPolicy.accept("host.example", 22, "SHA256:trusted"))
        assertFalse(strictPolicy.accept("host.example", 22, "SHA256:changed"))
    }

    @Test
    fun managedTunnelUpdatesStatusTextConfigAndLoadsPinnedKeyOnStart(): Unit = runBlocking {
        val storage = FakeTunnelStorage()
        val vault = SecretsVaultIntegration(newVault())
        val audit = FakeAuditLog()
        val sessions = CopyOnWriteArrayList<FakeSshSession>()
        val transport = ScriptedSshTransport { spec, _, hostKeys ->
            val fp = "SHA256:server-key"
            if (!hostKeys.accept(spec.host, spec.port, fp)) {
                throw TunnelException.HostKeyRejected(fp)
            }
            FakeSshSession(boundPort = 47100).also { sessions += it }
        }

        val manager = TunnelManager(storage, vault, audit, testScope, transport)
        val tunnel = manager.createTunnel(sampleConfig("home-desktop"))

        assertEquals("Stopped", tunnel.statusText.value)
        assertFalse(tunnel.config.remoteAccessAllowed)

        tunnel.allowRemoteAccess(true)
        assertTrue(tunnel.config.remoteAccessAllowed)
        assertTrue(tunnel.configState.value.remoteAccessAllowed)
        assertEquals(true, storage.lastSavedConfig?.remoteAccessAllowed)

        tunnel.start()
        val upState = withTimeout(5_000) { tunnel.state.first { it is TunnelState.Up } }
        assertEquals(TunnelState.Up(47100), upState)
        assertEquals("Up (port 47100)", withTimeout(5_000) { tunnel.statusText.first { it.startsWith("Up") } })
        assertEquals("SHA256:server-key", storage.loadPinnedHostKey("home-desktop"))

        tunnel.stop()
        assertEquals(TunnelState.Stopped, tunnel.state.value)
        assertEquals("Stopped", withTimeout(5_000) { tunnel.statusText.first { it == "Stopped" } })

        // Pin a different fingerprint in storage and verify start() loads it and rejects server's key.
        storage.pinHostKey("home-desktop", "SHA256:other-expected-key")
        tunnel.start()
        val rejected = withTimeout(5_000) { tunnel.state.first { it is TunnelState.HostKeyChanged } }
        assertEquals(TunnelState.HostKeyChanged("SHA256:server-key"), rejected)
        assertEquals(1, sessions.size)
        assertTrue(
            withTimeout(5_000) { tunnel.statusText.first { it.contains("Host key changed") } }
                .contains("SHA256:server-key"),
        )
    }

    @Test
    fun tunnelsSummaryStateReflectsAllTunnelLifecycles(): Unit = runBlocking {
        val storage = FakeTunnelStorage()
        val vault = SecretsVaultIntegration(newVault())
        val audit = FakeAuditLog()
        val transport = ScriptedSshTransport { spec, _, _ ->
            when (spec.host) {
                "up.example.com" -> FakeSshSession(boundPort = 47101)
                "retry.example.com" -> throw TunnelException.Network("connection timed out")
                "fail.example.com" -> throw TunnelException.AuthFailed("key rejected")
                else -> error("unexpected host: ${spec.host}")
            }
        }

        val manager = TunnelManager(storage, vault, audit, testScope, transport)
        assertEquals(TunnelsSummary(0, 0, 0, 0), manager.tunnelsSummaryState().value)

        manager.createTunnel(sampleConfig("t-up").copy(host = "up.example.com"))
        manager.createTunnel(sampleConfig("t-retry").copy(host = "retry.example.com"))
        manager.createTunnel(sampleConfig("t-fail").copy(host = "fail.example.com"))

        assertEquals(TunnelsSummary(3, 0, 0, 0), manager.tunnelsSummaryState().value)

        manager.startTunnel("t-up")
        manager.startTunnel("t-retry")
        manager.startTunnel("t-fail")

        val summary = withTimeout(5_000) {
            manager.tunnelsSummaryState().first {
                it == TunnelsSummary(totalCount = 3, upCount = 1, connectingCount = 1, failedCount = 1)
            }
        }
        assertEquals(TunnelsSummary(3, 1, 1, 1), summary)

        manager.deleteTunnel("t-fail")
        assertEquals(
            TunnelsSummary(totalCount = 2, upCount = 1, connectingCount = 1, failedCount = 0),
            withTimeout(5_000) {
                manager.tunnelsSummaryState().first { it.totalCount == 2 && it.failedCount == 0 }
            },
        )

        manager.stopTunnel("t-up")
        assertEquals(0, manager.tunnelsSummaryState().value.upCount)
    }

    @Test
    fun loadAndStartStartsStoredTunnels(): Unit = runBlocking {
        val storage = FakeTunnelStorage()
        val realVault = newVault()
        val vault = SecretsVaultIntegration(realVault)
        val audit = FakeAuditLog()

        val cfg1 = sampleConfig("alpha")
        val cfg2 = sampleConfig("beta").copy(
            forward = StoredForward.Local(remoteHost = "db.internal", remotePort = 5432),
        )
        storage.saveTunnelConfig(cfg1.id, cfg1)
        storage.saveTunnelConfig(cfg2.id, cfg2)
        vault.storeKey(cfg1.vaultKeyId, KeyGen.ed25519("aidos-tunnel-alpha").privateKey, "SSH_TUNNEL_KEY")
        vault.storeKey(cfg2.vaultKeyId, KeyGen.ed25519("aidos-tunnel-beta").privateKey, "SSH_TUNNEL_KEY")

        val transport = ScriptedSshTransport { spec, _, hostKeys ->
            assertTrue(hostKeys.accept(spec.host, spec.port, "SHA256:${spec.user}-fp"))
            FakeSshSession(boundPort = 48000)
        }

        val manager = TunnelManager(storage, vault, audit, testScope, transport)
        manager.loadAndStart()

        assertEquals(setOf("alpha", "beta"), manager.allTunnels.keys)
        val summary = withTimeout(5_000) {
            manager.tunnelsSummaryState().first { it.totalCount == 2 && it.upCount == 2 }
        }
        assertEquals(TunnelsSummary(totalCount = 2, upCount = 2, connectingCount = 0, failedCount = 0), summary)
        assertTrue(transport.connectCount.get() >= 2)
    }

    @Test
    fun createTunnelAndRotateKeySurfacePublicKeyAndWipePrivateKeyBuffer(): Unit = runBlocking {
        val storage = FakeTunnelStorage()
        val realVault = newVault()
        val realIntegration = SecretsVaultIntegration(realVault)
        val capturedPrivateKeyBuffers = CopyOnWriteArrayList<ByteArray>()
        var failNextStore = false

        val spyingVault = object : VaultIntegration {
            override val vault: SecretsVault = realVault

            override suspend fun hasKey(keyId: String): Boolean = realIntegration.hasKey(keyId)

            override suspend fun storeKey(keyId: String, privateKey: ByteArray, kind: String) {
                capturedPrivateKeyBuffers += privateKey
                if (failNextStore) throw IllegalStateException("simulated vault failure")
                realIntegration.storeKey(keyId, privateKey.copyOf(), kind)
            }

            override suspend fun deleteKey(keyId: String) = realIntegration.deleteKey(keyId)
        }

        val audit = FakeAuditLog()
        val transport = ScriptedSshTransport { _, _, _ -> FakeSshSession(boundPort = 47100) }
        val manager = TunnelManager(storage, spyingVault, audit, testScope, transport)

        val config = sampleConfig("keyed-tunnel")
        val tunnel = manager.createTunnel(config)
        val firstPubKey = tunnel.publicKey
        assertNotNull(firstPubKey)
        assertTrue(firstPubKey.startsWith("ssh-ed25519 "))
        assertTrue(firstPubKey.endsWith(" aidos-tunnel-keyed-tunnel"))
        val authLine = tunnel.authorizedKeysLine()
        assertNotNull(authLine)
        assertTrue(authLine.startsWith("restrict,port-forwarding,permitlisten=\"127.0.0.1:47100\",command=\"/bin/false\" "))

        // Private key buffer generated during createTunnel must be wiped immediately after storing.
        assertEquals(1, capturedPrivateKeyBuffers.size)
        assertTrue(capturedPrivateKeyBuffers[0].all { it == 0.toByte() })

        val resolvedBeforeRotate = VaultKeyProvider(realVault, config.vaultKeyId).privateKey()
        assertNotNull(resolvedBeforeRotate)

        val rotatedPubKey = manager.rotateKey(config.id)
        assertTrue(rotatedPubKey.startsWith("ssh-ed25519 "))
        assertNotEquals(firstPubKey, rotatedPubKey)
        assertEquals(rotatedPubKey, tunnel.publicKey)
        assertEquals(rotatedPubKey, tunnel.publicKeyState.value)

        // Private key buffer generated during rotateKey must also be wiped.
        assertEquals(2, capturedPrivateKeyBuffers.size)
        assertTrue(capturedPrivateKeyBuffers[1].all { it == 0.toByte() })

        // Vault now resolves the rotated key.
        val resolvedAfterRotate = VaultKeyProvider(realVault, config.vaultKeyId).privateKey()
        assertNotNull(resolvedAfterRotate)
        assertFalse(resolvedBeforeRotate.contentEquals(resolvedAfterRotate))

        // Even when storeKey fails, private key material must still be wiped in finally.
        failNextStore = true
        assertFailsWith<IllegalStateException> {
            manager.rotateKey(config.id)
        }
        assertEquals(3, capturedPrivateKeyBuffers.size)
        assertTrue(capturedPrivateKeyBuffers[2].all { it == 0.toByte() })
    }

    @Test
    fun concurrentTunnelManagerOperationsRemainConsistent(): Unit = runBlocking {
        val storage = FakeTunnelStorage()
        val vault = SecretsVaultIntegration(newVault())
        val audit = FakeAuditLog()
        val transport = ScriptedSshTransport { _, _, _ -> FakeSshSession(boundPort = 47500) }
        val manager = TunnelManager(storage, vault, audit, testScope, transport)

        // Create 12 tunnels concurrently.
        (1..12).map { i ->
            async(Dispatchers.Default) {
                manager.createTunnel(sampleConfig("t-$i"))
            }
        }.awaitAll()

        assertEquals(12, manager.allTunnels.size)
        assertEquals(12, manager.tunnelsSummaryState().value.totalCount)

        // Concurrently start, toggle remote access, rotate keys on even tunnels, and delete odd tunnels.
        val ops = (1..12).map { i ->
            async(Dispatchers.Default) {
                val id = "t-$i"
                manager.startTunnel(id)
                manager.allowRemoteAccess(id, i % 2 == 0)
                if (i % 2 == 1) {
                    manager.deleteTunnel(id)
                } else {
                    val newKey = manager.rotateKey(id)
                    assertTrue(newKey.startsWith("ssh-ed25519 "))
                    manager.startTunnel(id)
                }
            }
        }
        ops.awaitAll()

        val expectedIds = (2..12 step 2).map { "t-$it" }.toSet()
        assertEquals(expectedIds, manager.allTunnels.keys)
        assertEquals(expectedIds.size, storage.loadConfigs().size)
        val finalSummary = withTimeout(5_000) {
            manager.tunnelsSummaryState().first { it.totalCount == 6 && it.upCount == 6 }
        }
        assertEquals(TunnelsSummary(totalCount = 6, upCount = 6, connectingCount = 0, failedCount = 0), finalSummary)
        assertTrue(expectedIds.all { manager.getTunnel(it)?.config?.remoteAccessAllowed == true })
    }
}

private class FakeTunnelStorage : TunnelStorage {
    private val configs = ConcurrentHashMap<String, StoredTunnelConfig>()
    private val pinnedKeys = ConcurrentHashMap<String, String>()
    private val states = ConcurrentHashMap<String, TunnelState>()

    @Volatile
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

private class FakeAuditLog : TunnelAuditLog {
    val logs = CopyOnWriteArrayList<Pair<String, String>>()

    override suspend fun log(tunnelId: String, message: String) {
        logs.add(tunnelId to message)
    }
}

private class FakeSshSession(
    override val boundPort: Int = 47100,
) : SshSession {
    private val closed = CompletableDeferred<Unit>()

    override suspend fun awaitClosed() {
        closed.await()
    }

    override fun close() {
        closed.complete(Unit)
    }
}

private class ScriptedSshTransport(
    private val handler: suspend (spec: TunnelSpec, privateKey: ByteArray, hostKeys: HostKeyPolicy) -> SshSession,
) : SshTransport {
    val connectCount = AtomicInteger(0)

    override suspend fun connect(
        spec: TunnelSpec,
        privateKey: ByteArray,
        hostKeys: HostKeyPolicy,
    ): SshSession {
        connectCount.incrementAndGet()
        return handler(spec, privateKey, hostKeys)
    }
}
