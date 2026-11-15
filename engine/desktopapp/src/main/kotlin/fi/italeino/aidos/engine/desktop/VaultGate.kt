package fi.italeino.aidos.engine.desktop

import dev.aidos.vault.DesktopVault
import dev.aidos.vault.SecretsVault
import fi.italeino.aidos.engine.control.VaultControl
import fi.italeino.aidos.engine.control.VaultStatus

/**
 * Where the Engine gets its vault (RFC-0115, D56). In `os` mode every call opens the user's vault through the OS
 * keystore, as before, and it is never locked. In `passphrase` mode (a machine-wide service, which has no login
 * session) the vault is unavailable until [unlock] succeeds; the passphrase is used to open the vault and is not
 * kept. [open] receives the passphrase source, or null in `os` mode.
 */
class VaultGate(
    private val passphraseMode: Boolean,
    private val open: (passphrase: (() -> CharArray?)?) -> SecretsVault = { p ->
        DesktopVault.open(passphrase = p ?: { System.getenv("AIDOS_VAULT_PASSPHRASE")?.toCharArray() }, forcePassphrase = p != null).vault
    },
) : VaultControl {
    @Volatile private var unlocked: SecretsVault? = null

    override suspend fun status() = VaultStatus(if (passphraseMode) "passphrase" else "os", locked = passphraseMode && unlocked == null)

    override suspend fun unlock(passphrase: CharArray) {
        require(passphraseMode) { "This vault uses the operating system keystore; there is nothing to unlock." }
        require(passphrase.isNotEmpty()) { "Enter the vault passphrase." }
        try {
            unlocked = open { passphrase.copyOf() }
        } finally {
            passphrase.fill('\u0000')
        }
    }

    override suspend fun lock() { unlocked = null }

    /** The vault for tunnel keys. Throws while locked, which the pane shows as the reason a tunnel cannot start. */
    fun vault(): SecretsVault =
        if (passphraseMode) unlocked ?: throw IllegalStateException("The vault is locked. Unlock it from the tray icon, then try again.")
        else open(null)

    companion object {
        /** `AIDOS_VAULT_MODE=passphrase` is set in the service definition of a machine-wide install. */
        fun fromEnvironment(env: (String) -> String? = System::getenv): VaultGate = VaultGate(env("AIDOS_VAULT_MODE")?.lowercase() == "passphrase")
    }
}
