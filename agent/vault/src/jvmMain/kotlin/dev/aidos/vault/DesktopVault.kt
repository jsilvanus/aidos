package dev.aidos.vault

import java.io.File

/** A vault opened at its standard user-scope location, with how its key is protected. */
class OpenedVault(val vault: SqliteSecretsVault, val protection: KeyProtection) {
    /** The sentence to show the user when protection is weaker than the system keystore; null when it is not. */
    val weakProtectionNotice: String? get() =
        if (protection == KeyProtection.PLATFORM_KEYSTORE) null else protection.plainDescription
}

/** The vault at `~/.aidos/secrets/vault.db` (RFC-0035 "The vault is user scope"). */
object DesktopVault {
    fun secretsDir(home: String = System.getProperty("user.home")): File = File(home, ".aidos/secrets")

    /**
     * Opens (creating if needed) the user's vault with the strongest key protection this machine
     * offers. [passphrase] is consulted only where there is no OS keystore.
     */
    fun open(
        home: String = System.getProperty("user.home"),
        redactor: Redactor? = null,
        accessSink: SecretAccessSink? = null,
        runner: CommandRunner = SystemCommandRunner,
        osName: String = System.getProperty("os.name") ?: "",
        passphrase: (() -> CharArray?)? = { System.getenv("AIDOS_VAULT_PASSPHRASE")?.toCharArray() },
    ): OpenedVault {
        val dir = secretsDir(home)
        dir.mkdirs()
        runCatching {
            java.nio.file.Files.setPosixFilePermissions(dir.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        }
        val provider = VaultKeyProviders.platformDefault(dir, osName, runner, passphrase)
        return OpenedVault(SqliteSecretsVault(File(dir, "vault.db"), provider, redactor, accessSink), provider.protection)
    }
}
