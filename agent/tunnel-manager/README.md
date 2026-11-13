# tunnel-manager (`dev.aidos.tunnel`)

RFC-0108 Phase 3: Engine adapter layer for the standalone `sshtunnel` library.

Provides Aidos-specific wiring:
- **Vault integration** (`VaultKeyProvider`): Private keys are stored encrypted in the vault and resolved only when needed for connection.
- **Host key pinning** (`HostKeyPinningPolicy`): Server fingerprint is saved on first connection and verified on reconnects; a mismatch is terminal.
- **Audit logging** (`TunnelAuditLog`): All tunnel events (connect, disconnect, key rotation, remote-access changes) are logged for RFC-0042 audit trail.
- **Remote-access switch** (`allowRemoteAccess`): Per-peer gate that must be explicitly enabled before the peer listener accepts connections over this tunnel.
- **Tunnel manager** (`TunnelManager`): Orchestrates multiple tunnels, each with its own stored config, vault key, and audit trail.

## Usage

```kotlin
val tunnelManager = TunnelManager(storage, vault, auditLog, coroutineScope)

// Load stored tunnels at startup
tunnelManager.loadAndStart()

// Create a new tunnel (user provides config)
val config = StoredTunnelConfig(
    id = "home-desktop",
    name = "Home Desktop",
    host = "home.example.com",
    user = "ubuntu",
    forward = StoredForward.Reverse(serverPort = 47100, targetPort = 47100),
    vaultKeyId = "tunnel-key-home",
)
val tunnel = tunnelManager.createTunnel(config)
tunnel.start()

// Enable remote access for a peer (after user confirmation in UI)
tunnel.allowRemoteAccess(true)

// Rotate key
tunnelManager.rotateKey("home-desktop")

// Delete
tunnelManager.deleteTunnel("home-desktop")
```

## Integration points

- **Vault** (`dev.aidos.vault.SecretsVault`): Stores SSH_TUNNEL_KEY secrets, accessed only by tunnel-manager.
- **Audit** (RFC-0042): Logs tunnel lifecycle events to the Engine audit trail.
- **Storage**: Persists tunnel configurations and pinned host keys. Implementation depends on Engine storage layer.

## Testing

The module includes unit tests for tunnel lifecycle (create, start, allow remote access, delete) using fake storage and vault. Real SSH connection tests are in the `sshtunnel` module.

```bash
cd agent && gradle :tunnel-manager:jvmTest
```

## Future work

- Phase 4: Android Keystore-backed `KeyProvider` for phone-side SSH.
- Phase 5: RFC-0107 bounded continue-then-cancel behavior.
- RFC-0035 amendments: Update vault to include SSH_TUNNEL_KEY, SSH_COMMAND_KEY, SSH_SETUP_KEY kinds with appropriate metadata.
- Setup-key installer: Allow user to provide an SSH admin key to automatically append the restricted tunnel key to `authorized_keys` on the server.
