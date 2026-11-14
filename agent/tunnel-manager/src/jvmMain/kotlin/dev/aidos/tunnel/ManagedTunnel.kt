package dev.aidos.tunnel

import fi.italeino.sshtunnel.AuthorizedKeys
import fi.italeino.sshtunnel.Forward
import fi.italeino.sshtunnel.KeyProvider
import fi.italeino.sshtunnel.SshTransport
import fi.italeino.sshtunnel.SshTunnel
import fi.italeino.sshtunnel.TunnelSpec
import fi.italeino.sshtunnel.TunnelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * RFC-0108 Phase 3: Manages one SSH reverse or local tunnel with vault integration.
 * Wraps [SshTunnel] with:
 * - Vault-backed key provider (private key never stored by TunnelManager)
 * - Host key pinning (fingerprint checked at setup, rejected if changed)
 * - Audit logging (connection attempts, state changes)
 * - Remote-access switch (peer must be explicitly allowed)
 */
class ManagedTunnel(
    config: StoredTunnelConfig,
    keyProvider: KeyProvider,
    private val storage: TunnelStorage,
    private val audit: TunnelAuditLog,
    transport: SshTransport,
    scope: CoroutineScope,
    hostKeyTrustMode: HostKeyTrustMode = HostKeyTrustMode.TOFU,
    initialPublicKey: String? = null,
) {
    private val configMutex = Mutex()
    private val _config = MutableStateFlow(config)

    /** Observable tunnel configuration. */
    val configState: StateFlow<StoredTunnelConfig> = _config.asStateFlow()

    /** Current tunnel configuration (updated when [allowRemoteAccess] changes). */
    val config: StoredTunnelConfig get() = _config.value

    private val _publicKey = MutableStateFlow(initialPublicKey)

    /** Observable SSH public key for this tunnel when generated or rotated in the current session. */
    val publicKeyState: StateFlow<String?> = _publicKey.asStateFlow()

    /** SSH public key for this tunnel when generated or rotated in the current session. */
    val publicKey: String? get() = _publicKey.value

    private val spec = TunnelSpec(
        host = config.host,
        user = config.user,
        forward = config.forward.toSshTunnelForward(),
        port = config.port,
        keepAliveSeconds = config.keepAliveSeconds,
        connectTimeoutMillis = config.connectTimeoutMillis,
    )

    // Host key fingerprint is checked at first connection and pinned; a change is reported as HostKeyChanged.
    private val hostKeyPolicy = HostKeyPinningPolicy(config.id, storage, audit, hostKeyTrustMode)

    private val tunnel = SshTunnel(spec, keyProvider, hostKeyPolicy, transport, scope)

    /** Observable tunnel state: Connecting, Up, Backoff, HostKeyChanged, AuthRefused, ForwardRefused. */
    val state: StateFlow<TunnelState> = tunnel.state

    /** Human-readable tunnel status: "Stopped", "Connecting…", "Up (port …)", etc. */
    val statusText: StateFlow<String> = tunnel.state
        .map(::formatStatusText)
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = formatStatusText(tunnel.state.value),
        )

    /** Build a restricted server `authorized_keys` line if [publicKey] is available. */
    fun authorizedKeysLine(forcedCommand: String = "/bin/false"): String? {
        val pk = publicKey ?: return null
        return when (val forward = config.forward.toSshTunnelForward()) {
            is Forward.Reverse -> AuthorizedKeys.forReverse(pk, forward, forcedCommand)
            is Forward.Local -> AuthorizedKeys.forLocal(pk, forward, forcedCommand)
        }
    }

    internal fun updatePublicKey(newPublicKey: String) {
        _publicKey.value = newPublicKey
    }

    /** Loads the pinned host key from storage and starts (or restarts) the tunnel. */
    suspend fun start() {
        val current = config
        hostKeyPolicy.loadPinnedKey()
        audit.log(current.id, "Starting tunnel to ${current.host}")
        tunnel.start()
    }

    /** Stops the tunnel. */
    suspend fun stop() {
        tunnel.stop()
        audit.log(config.id, "Stopped tunnel")
    }

    /** Mark this tunnel as allowed to carry remote peer connections (for UI/permission tracking). */
    suspend fun allowRemoteAccess(allowed: Boolean) {
        val updated = configMutex.withLock {
            val next = _config.value.copy(remoteAccessAllowed = allowed)
            storage.saveTunnelConfig(next.id, next)
            _config.value = next
            next
        }
        audit.log(updated.id, if (allowed) "Remote access allowed" else "Remote access denied")
    }
}

private fun formatStatusText(state: TunnelState): String = when (state) {
    TunnelState.Stopped -> "Stopped"
    TunnelState.Connecting -> "Connecting…"
    is TunnelState.Up -> "Up (port ${state.boundPort})"
    is TunnelState.Backoff -> "Reconnecting in ${state.delayMillis}ms (attempt ${state.attempt}): ${state.cause}"
    is TunnelState.HostKeyChanged -> "⚠ Host key changed: ${state.seenFingerprint}"
    is TunnelState.AuthRefused -> "⚠ Authentication failed: ${state.reason}"
    is TunnelState.ForwardRefused -> "⚠ Forward refused: ${state.reason}"
}

private fun StoredForward.toSshTunnelForward(): Forward = when (this) {
    is StoredForward.Reverse -> Forward.Reverse(
        serverPort = serverPort,
        targetPort = targetPort,
        serverBindHost = serverBindHost,
        targetHost = targetHost,
    )
    is StoredForward.Local -> Forward.Local(
        remoteHost = remoteHost,
        remotePort = remotePort,
        localPort = localPort,
        localBindHost = localBindHost,
    )
}
