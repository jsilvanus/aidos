package dev.aidos.daemon

import dev.aidos.vault.DesktopVault
import dev.aidos.vault.OpenedVault
import dev.aidos.vault.Redactor
import dev.aidos.vault.SecretsVault

/**
 * The Agent daemon's vault (RFC-0035, RFC-0115 / D56). In `os` mode it is opened once at start through the OS keystore,
 * as it always was. In `passphrase` mode (a machine-wide service has no login session, so no keystore) the daemon starts
 * locked and carries on without a vault, like when the keystore refuses, until the owner unlocks it; unlocking opens the
 * vault with the passphrase (not kept) and registers its secrets with the redactor so they never reach a log.
 */
class DaemonVaultGate(
    val passphraseMode: Boolean,
    private val home: String = System.getProperty("user.home"),
    private val open: (home: String, passphrase: (() -> CharArray?)?) -> OpenedVault = { h, p ->
        if (p == null) DesktopVault.open(h) else DesktopVault.open(h, passphrase = p, forcePassphrase = true)
    },
) {
    @Volatile private var opened: OpenedVault? = null
    @Volatile private var redactor: Redactor? = null

    /** Connects the redactor the daemon's logging uses; secrets of an unlocked vault are registered with it. */
    fun bind(redactor: Redactor) { this.redactor = redactor }

    /** The vault at start: opened now in `os` mode, absent (locked) in `passphrase` mode. Null also when the keystore refuses. */
    fun openInitial(): OpenedVault? {
        if (passphraseMode) return null
        return runCatching { open(home, null) }
            .onFailure { DaemonLog.err("aidos: secrets vault unavailable: ${it.message}") }
            .getOrNull()
            .also { opened = it }
    }

    fun mode(): String = if (passphraseMode) "passphrase" else "os"
    fun locked(): Boolean = passphraseMode && opened == null

    /** Opens the vault with [passphrase]. A wrong one throws and leaves the daemon locked. */
    fun unlock(passphrase: CharArray) {
        require(passphraseMode) { "This daemon's vault uses the operating system keystore; there is nothing to unlock." }
        require(passphrase.isNotEmpty()) { "Enter the vault passphrase." }
        try {
            val v = open(home) { passphrase.copyOf() }
            redactor?.let { r -> v.vault.registerAllWith(r) }
            opened = v
        } finally {
            passphrase.fill('\u0000')
        }
    }

    fun lock() { if (passphraseMode) opened = null }

    fun vault(): SecretsVault? = opened?.vault

    companion object {
        fun fromEnvironment(home: String = System.getProperty("user.home"), env: (String) -> String? = System::getenv) =
            DaemonVaultGate(env("AIDOS_VAULT_MODE")?.lowercase() == "passphrase", home)
    }
}
