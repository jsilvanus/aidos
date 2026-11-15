package fi.italeino.aidos.engine.control

import kotlinx.serialization.Serializable

/**
 * The vault group of `EngineControl` (RFC-0115, D56): a machine-wide Engine service has no login session, so its
 * vault is protected by a passphrase and the service starts locked until the owner unlocks it. A per-user Engine uses
 * the OS keystore and is never locked. The passphrase travels only on the loopback admin API and is never stored.
 */
interface VaultControl {
    suspend fun status(): VaultStatus

    /** Unlocks a passphrase vault. A wrong passphrase is an `IllegalStateException`; a vault that is not passphrase protected is an `IllegalArgumentException`. */
    suspend fun unlock(passphrase: CharArray)

    /** Forgets the unlocked vault; tunnels that need a key stop being startable until it is unlocked again. */
    suspend fun lock()
}

/** [mode] is `os` (the OS keystore, never locked) or `passphrase`. */
@Serializable
data class VaultStatus(val mode: String, val locked: Boolean)

@Serializable
data class VaultUnlockRequest(val passphrase: String)
