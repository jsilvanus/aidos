package dev.aidos.tunnel

import fi.italeino.sshtunnel.Forward
import fi.italeino.sshtunnel.KeyProvider
import fi.italeino.sshtunnel.SshTunnel
import fi.italeino.sshtunnel.SshTransport
import fi.italeino.sshtunnel.TunnelSpec
import fi.italeino.sshtunnel.TunnelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * RFC-0108 Phase 3: Manages one SSH reverse tunnel with vault integration.
 * Wraps [SshTunnel] with:
 * - Vault-backed key provider (private key never stored by TunnelManager)
 * - Host key pinning (fingerprint checked at setup, rejected if changed)
 * - Audit logging (connection attempts, state changes)
 * - Remote-access switch (peer must be explicitly allowed)
 * - Automatic restart on terminal failures that the user can fix (key rotation, etc.)
 */
class ManagedTunnel(
    val config: StoredTunnelConfig,
    private val keyProvider: KeyProvider,
    private val storage: TunnelStorage,
    private val audit: TunnelAuditLog,
    private val transport: SshTransport,
    scope: CoroutineScope,
) {
    private val spec = TunnelSpec(
        host = config.host,
        user = config.user,
        forward = config.forward.toSshTunnelForward(),
        port = config.port,
        keepAliveSeconds = config.keepAliveSeconds,
        connectTimeoutMillis = config.connectTimeoutMillis,
    )

    // Host key fingerprint is checked at first connection and pinned; a change is reported as HostKeyChanged.
    private val hostKeyPolicy = HostKeyPinningPolicy(config.id, storage, audit)

    private val tunnel = SshTunnel(spec, keyProvider, hostKeyPolicy, transport, scope)

    /** Observable tunnel state: Connecting, Up, Backoff, HostKeyChanged, AuthRefused, ForwardRefused. */
    val state: StateFlow<TunnelState> = tunnel.state

    /** Human-readable tunnel status: "up", "connecting", "host key changed", etc. */
    val statusText: StateFlow<String> = tunnel.state.map { state ->
        when (state) {
            TunnelState.Stopped -> "Stopped"
            TunnelState.Connecting -> "Connecting…"
            is TunnelState.Up -> "Up (port ${state.boundPort})"
            is TunnelState.Backoff -> "Reconnecting in ${state.delayMillis}ms (attempt ${state.attempt}): ${state.cause}"
            is TunnelState.HostKeyChanged -> "⚠ Host key changed: ${state.seenFingerprint}"
            is TunnelState.AuthRefused -> "⚠ Authentication failed: ${state.reason}"
            is TunnelState.ForwardRefused -> "⚠ Forward refused: ${state.reason}"
        }
    }.asStateFlow()

    /** Starts the tunnel (or restarts after a terminal failure). */
    suspend fun start() {
        audit.log(config.id, "Starting tunnel to ${config.host}")
        tunnel.start()
    }

    /** Stops the tunnel. */
    suspend fun stop() {
        tunnel.stop()
        audit.log(config.id, "Stopped tunnel")
    }

    /** Mark this tunnel as allowed to carry remote peer connections (for UI/permission tracking). */
    suspend fun allowRemoteAccess(allowed: Boolean) {
        val updated = config.copy(remoteAccessAllowed = allowed)
        storage.saveTunnelConfig(config.id, updated)
        audit.log(config.id, if (allowed) "Remote access allowed" else "Remote access denied")
    }
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
