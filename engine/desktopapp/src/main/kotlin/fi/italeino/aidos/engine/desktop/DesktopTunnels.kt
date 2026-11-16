package fi.italeino.aidos.engine.desktop

import dev.aidos.aidin.AidinNode
import dev.aidos.tunnel.HostKeyTrustMode
import dev.aidos.tunnel.SecretsVaultIntegration
import dev.aidos.tunnel.StoredForward
import dev.aidos.tunnel.StoredTunnelConfig
import dev.aidos.tunnel.TunnelAuditLog
import dev.aidos.tunnel.TunnelManager
import dev.aidos.tunnel.TunnelStorage
import dev.aidos.vault.SecretsVault
import fi.italeino.sshtunnel.AuthorizedKeys
import fi.italeino.sshtunnel.Forward
import fi.italeino.sshtunnel.HostKeyPolicy
import fi.italeino.sshtunnel.SshTransport
import fi.italeino.sshtunnel.SshjTransport
import fi.italeino.sshtunnel.TunnelException
import fi.italeino.sshtunnel.TunnelSpec
import fi.italeino.sshtunnel.TunnelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What the Tunnels screen needs to know about one tunnel. Never holds a private key. */
data class TunnelView(
    val id: String,
    val name: String,
    val serverHost: String,
    val serverPort: Int,
    val user: String,
    /** The port on the server that the tunnel publishes this Engine's remote-ingress listener on. */
    val publishedPort: Int,
    val state: TunnelState,
    val publicKey: String?,
    /** The restricted line to paste into the server's `authorized_keys`. */
    val authorizedKeysLine: String?,
    /** The pinned server host key fingerprint, or null until the user has confirmed one. */
    val pinnedHostKey: String?,
    /** A fingerprint seen by [DesktopTunnels.probeHostKey] that is waiting for the user's confirmation. */
    val pendingHostKey: String?,
    /** The `host:port` the user granted egress to when confirming the host key (RFC-0042), or null before that. */
    val egressGrantedTo: String? = null,
)

/**
 * RFC-0108 phase 3 on the desktop: the user's SSH reverse tunnels, which publish this Engine's
 * remote-ingress listener on a server the user owns so a peer away from home can dial in.
 *
 * - **Keys.** A tunnel key is an Ed25519 pair; the private half goes to the vault and never leaves it, and only
 *   the restricted `authorized_keys` line (`restrict,port-forwarding,permitlisten=...,command="/bin/false"`) is
 *   ever shown. The public half is kept with the config so the line can be shown again after a restart.
 * - **Host keys are confirmed, never trusted on first use.** The user probes the server, compares the
 *   fingerprint with the one they know, and confirms; only then is it pinned and the tunnel allowed to start.
 *   A change later stops the tunnel in `HostKeyChanged`.
 * - **What it carries.** The forward's target is [AidinNode.startRemoteIngress]: connections arriving
 *   through a tunnel are classed `SSH_TUNNEL` and need the per-peer remote-access switch (RFC-0108).
 *
 * The vault is opened on first use ([vaultProvider]), so a machine with no usable key protection only sees
 * an error here, not at startup.
 */
class DesktopTunnels(
    private val stateDir: File,
    private val node: () -> AidinNode?,
    private val scope: CoroutineScope,
    private val vaultProvider: () -> SecretsVault,
    private val transport: SshTransport = SshjTransport(),
    private val onAuditLine: (String) -> Unit = {},
) {
    @Serializable
    private data class StoredTunnel(
        val id: String,
        val name: String,
        val host: String,
        val port: Int,
        val user: String,
        val serverPort: Int,
        val targetPort: Int,
        val vaultKeyId: String,
        val publicKey: String? = null,
        val pinnedHostKey: String? = null,
        /** RFC-0042: the one `host:port` this tunnel may dial, recorded when the user confirms the host key. */
        val egressGrantedTo: String? = null,
    )

    @Serializable
    private data class Stored(val tunnels: List<StoredTunnel> = emptyList())

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(stateDir, "tunnels.json")
    private val lock = Any()
    private var stored: Stored = runCatching { json.decodeFromString(Stored.serializer(), file.readText()) }.getOrDefault(Stored())
    private val pending = ConcurrentHashMap<String, String>()

    private fun save(s: Stored) = synchronized(lock) {
        stateDir.mkdirs()
        file.writeText(json.encodeToString(Stored.serializer(), s))
        stored = s
    }

    private val storage = object : TunnelStorage {
        override suspend fun loadConfigs() = stored.tunnels.map { it.toConfig() }
        override suspend fun saveTunnelConfig(id: String, config: StoredTunnelConfig) = Unit
        override suspend fun deleteTunnelConfig(id: String) = Unit
        override suspend fun loadPinnedHostKey(tunnelId: String) = stored.tunnels.firstOrNull { it.id == tunnelId }?.pinnedHostKey
        override suspend fun pinHostKey(tunnelId: String, fingerprint: String) {
            save(stored.copy(tunnels = stored.tunnels.map { if (it.id == tunnelId) it.copy(pinnedHostKey = fingerprint) else it }))
        }
        override suspend fun loadTunnelState(tunnelId: String): TunnelState? = null
        override suspend fun saveTunnelState(tunnelId: String, state: TunnelState) = Unit
    }

    private val audit = object : TunnelAuditLog {
        override suspend fun log(tunnelId: String, message: String) {
            val name = stored.tunnels.firstOrNull { it.id == tunnelId }?.name ?: tunnelId.take(8)
            onAuditLine("tunnel $name: $message")
            runCatching {
                stateDir.mkdirs()
                File(stateDir, "tunnels-audit.log").appendText("${java.time.Instant.now()} $name $message\n")
            }
        }
    }

    private val managerLazy = lazy {
        TunnelManager(
            storage = storage,
            vault = SecretsVaultIntegration(vaultProvider()),
            audit = audit,
            scope = scope,
            transport = transport,
            // RFC-0108: no silent trust on first use. A tunnel cannot start until the user has pinned a key.
            hostKeyTrustMode = HostKeyTrustMode.STRICT_PRE_PINNED,
        )
    }
    private val manager: TunnelManager get() = managerLazy.value

    /**
     * The server `host:port` is a scoped egress destination the user grants once (RFC-0042). Confirming its host key is
     * that grant. A tunnel saved before grants were recorded counts as granted when it already has a pinned key, since
     * the user confirmed that same server; anything else must be confirmed again.
     */
    private fun StoredTunnel.egressGranted(): Boolean =
        egressGrantedTo?.let { it == "$host:$port" } ?: (pinnedHostKey != null)

    private fun StoredTunnel.toConfig() = StoredTunnelConfig(
        id = id, name = name, host = host, port = port, user = user,
        forward = StoredForward.Reverse(serverPort = serverPort, targetPort = targetPort),
        vaultKeyId = vaultKeyId,
    )

    /** Starts the tunnels saved earlier; call once when the AIDIN node is up. Tunnels with no pinned host key stay stopped. */
    fun startSaved() {
        runBlocking {
            for (t in stored.tunnels) {
                if (t.pinnedHostKey == null) continue
                if (!t.egressGranted()) {
                    onAuditLine("tunnel ${t.name}: not started, egress to ${t.host}:${t.port} is not granted")
                    continue
                }
                runCatching { manager.createTunnel(t.toConfig()).also { it.start() } }
                    .onFailure { onAuditLine("tunnel ${t.name}: could not start (${it.message})") }
            }
        }
    }

    /** The loopback port the tunnels' forwards point at, starting the remote-ingress listener if needed. */
    private fun ingressPort(): Int {
        val n = node() ?: error("Group sharing is off, so there is no peer listener to publish. Turn it on first.")
        return n.remoteIngressPort ?: n.startRemoteIngress()
    }

    /** Creates a tunnel and its key. It stays stopped until the host key is confirmed. */
    fun create(name: String, host: String, port: Int, user: String, serverPort: Int): String = runBlocking {
        require(name.isNotBlank()) { "a name is required" }
        require(serverPort in 1..65535) { "invalid server port" }
        TunnelSpec(host = host, user = user, forward = Forward.Reverse(serverPort, 1), port = port) // validates host, user, port
        val id = UUID.randomUUID().toString()
        val cfg = StoredTunnelConfig(
            id = id, name = name.trim(), host = host.trim(), port = port, user = user.trim(),
            forward = StoredForward.Reverse(serverPort = serverPort, targetPort = ingressPort()),
            vaultKeyId = "ssh-tunnel-$id",
        )
        val managed = manager.createTunnel(cfg)
        save(stored.copy(tunnels = stored.tunnels + StoredTunnel(id, cfg.name, cfg.host, port, cfg.user, serverPort, (cfg.forward as StoredForward.Reverse).targetPort, cfg.vaultKeyId, publicKey = managed.publicKey)))
        audit.log(id, "created")
        id
    }

    /**
     * Connects just far enough to learn the server's host key fingerprint (no login, no forward) and holds it
     * for the user to confirm. Returns the fingerprint to show.
     */
    fun probeHostKey(id: String): String = runBlocking {
        val t = stored.tunnels.firstOrNull { it.id == id } ?: error("no such tunnel")
        val seen = java.util.concurrent.atomic.AtomicReference<String?>()
        try {
            // A rejecting policy ends the connection right after key exchange, before any authentication.
            transport.connect(
                TunnelSpec(host = t.host, user = t.user, forward = Forward.Reverse(t.serverPort, t.targetPort), port = t.port),
                ByteArray(0),
                HostKeyPolicy { _, _, fp -> seen.set(fp); false },
            ).close()
        } catch (e: TunnelException.HostKeyRejected) {
            seen.compareAndSet(null, e.fingerprint)
        } catch (e: TunnelException.Network) {
            if (seen.get() == null) throw IllegalStateException("could not reach ${t.host}:${t.port}: ${e.message}")
        } catch (e: TunnelException) {
            // Auth or forward errors still mean the key exchange happened.
        }
        val fp = seen.get() ?: error("the server did not present a host key")
        pending[id] = fp
        audit.log(id, "host key $fp seen; waiting for confirmation")
        fp
    }

    /** Pins the fingerprint the user was shown and compared. Only a fingerprint that [probeHostKey] returned can be confirmed. */
    fun confirmHostKey(id: String): Unit = runBlocking {
        val fp = pending.remove(id) ?: error("probe the server first, then confirm what it showed")
        storage.pinHostKey(id, fp)
        val t = stored.tunnels.first { it.id == id }
        save(stored.copy(tunnels = stored.tunnels.map { if (it.id == id) it.copy(egressGrantedTo = "${t.host}:${t.port}") else it }))
        audit.log(id, "host key pinned: $fp")
        audit.log(id, "egress granted to ${t.host}:${t.port}")
    }

    fun start(id: String): Unit = runBlocking {
        val t = stored.tunnels.firstOrNull { it.id == id } ?: error("no such tunnel")
        check(t.pinnedHostKey != null) { "Confirm the server's host key first." }
        check(t.egressGranted()) { "Egress to ${t.host}:${t.port} is not granted. Check the server and confirm its host key again." }
        val existing = manager.getTunnel(id) ?: manager.createTunnel(t.toConfig())
        existing.start()
    }

    fun stop(id: String): Unit = runBlocking { manager.getTunnel(id)?.stop() }

    /** New key; the old one stops working as soon as the server line is replaced. Returns the new `authorized_keys` line. */
    fun rotateKey(id: String): String = runBlocking {
        val t = stored.tunnels.firstOrNull { it.id == id } ?: error("no such tunnel")
        if (manager.getTunnel(id) == null) manager.createTunnel(t.toConfig())
        val pub = manager.rotateKey(id)
        save(stored.copy(tunnels = stored.tunnels.map { if (it.id == id) it.copy(publicKey = pub) else it }))
        line(t, pub)
    }

    fun delete(id: String): Unit = runBlocking {
        manager.getTunnel(id)?.let { manager.deleteTunnel(id) }
        save(stored.copy(tunnels = stored.tunnels.filterNot { it.id == id }))
        pending.remove(id)
        audit.log(id, "deleted")
    }

    /** Current tunnels with their live state. */
    fun views(): List<TunnelView> = stored.tunnels.map { t ->
        // Listing must not open the vault: only look at live tunnels once a manager exists.
        val managed = if (managerLazy.isInitialized()) managerLazy.value.getTunnel(t.id) else null
        TunnelView(
            id = t.id, name = t.name, serverHost = t.host, serverPort = t.port, user = t.user, publishedPort = t.serverPort,
            state = managed?.state?.value ?: TunnelState.Stopped,
            publicKey = t.publicKey,
            authorizedKeysLine = t.publicKey?.let { line(t, it) },
            pinnedHostKey = t.pinnedHostKey,
            pendingHostKey = pending[t.id],
            egressGrantedTo = t.egressGrantedTo?.takeIf { t.egressGranted() },
        )
    }

    private fun line(t: StoredTunnel, publicKey: String) = AuthorizedKeys.forReverse(publicKey, Forward.Reverse(t.serverPort, t.targetPort))
}
