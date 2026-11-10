package dev.aidos.vault

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** How well the vault key is protected (RFC-0035 "Where no keystore exists ... told plainly"). */
enum class KeyProtection(val plainDescription: String) {
    /** Held by the operating system's credential store; the vault database alone cannot decrypt. */
    PLATFORM_KEYSTORE("The vault key is held by the operating system's credential store."),
    /** Derived from a passphrase the user supplies; protection is only as strong as the passphrase. */
    PASSPHRASE("The vault key is derived from your passphrase. Protection is weaker than the system keystore: it is only as strong as the passphrase."),
    /** No keystore and no passphrase: the key sits in a file only the owner can read. */
    OWNER_ONLY_FILE("No system keystore is available and no passphrase was given, so the vault key is stored in a file readable only by you. Anyone who can read your files can read your secrets. Protection is much weaker."),
}

/**
 * Supplies the AES-256 key that encrypts the vault's values. The key is never written to the
 * vault database. [protection] is what the host tells the user.
 */
interface VaultKeyProvider {
    val protection: KeyProtection
    fun loadOrCreate(): SecretKey
}

/** Key lives only in this process: tests, and callers that explicitly want a throwaway vault. */
class EphemeralKeyProvider(private var key: SecretKey? = null) : VaultKeyProvider {
    override val protection = KeyProtection.OWNER_ONLY_FILE
    override fun loadOrCreate(): SecretKey = key ?: SqliteSecretsVault.generateKey().also { key = it }
}

/** Result of running an OS helper program. */
data class CommandResult(val exitCode: Int, val stdout: ByteArray, val stderr: String)

/** Seam so the OS keystore providers are testable without a keystore. [stdin] is how secrets travel. */
fun interface CommandRunner {
    fun run(command: List<String>, stdin: ByteArray?): CommandResult
}

object SystemCommandRunner : CommandRunner {
    override fun run(command: List<String>, stdin: ByteArray?): CommandResult {
        val p = ProcessBuilder(command).redirectErrorStream(false).start()
        // stdin first, and closed, so a helper waiting for end-of-input proceeds.
        p.outputStream.use { if (stdin != null) it.write(stdin) }
        val err = StringBuilder()
        val t = Thread { err.append(p.errorStream.readBytes().toString(Charsets.UTF_8)) }.also { it.start() }
        val out = p.inputStream.readBytes()
        if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); return CommandResult(-1, ByteArray(0), "timeout") }
        t.join(1000)
        return CommandResult(p.exitValue(), out, err.toString())
    }
}

internal object KeyCodec {
    fun newKeyBytes(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    fun encode(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun decode(s: String): ByteArray = Base64.getDecoder().decode(s.trim())
    fun toKey(b: ByteArray): SecretKey = SecretKeySpec(b, "AES")
}

/**
 * Linux Secret Service (libsecret) through the `secret-tool` helper. The key travels over stdin,
 * never argv.
 */
class SecretServiceKeyProvider(
    private val runner: CommandRunner = SystemCommandRunner,
    private val account: String = "default",
) : VaultKeyProvider {
    override val protection = KeyProtection.PLATFORM_KEYSTORE
    private val attrs = listOf("service", SERVICE, "account", account)

    override fun loadOrCreate(): SecretKey {
        val got = runner.run(listOf("secret-tool", "lookup") + attrs, null)
        if (got.exitCode == 0 && got.stdout.isNotEmpty()) return KeyCodec.toKey(KeyCodec.decode(got.stdout.toString(Charsets.UTF_8)))
        val fresh = KeyCodec.newKeyBytes()
        val put = runner.run(listOf("secret-tool", "store", "--label=Aidos vault key") + attrs, KeyCodec.encode(fresh).toByteArray())
        check(put.exitCode == 0) { "secret-tool store failed: ${put.stderr.take(200)}" }
        return KeyCodec.toKey(fresh)
    }

    companion object {
        const val SERVICE = "aidos-vault"
        fun available(runner: CommandRunner = SystemCommandRunner): Boolean = runCatching {
            // `search` exits 1 on "no match" but fails to run at all (or reports a D-Bus error) without a service.
            val r = runner.run(listOf("secret-tool", "search", "service", "aidos-vault-probe"), null)
            r.exitCode == 0 || (r.exitCode == 1 && !r.stderr.contains("dbus", ignoreCase = true) && !r.stderr.contains("Cannot autolaunch", ignoreCase = true))
        }.getOrDefault(false)
    }
}

/** macOS Keychain through the `security` tool. */
class KeychainKeyProvider(
    private val runner: CommandRunner = SystemCommandRunner,
    private val account: String = "default",
) : VaultKeyProvider {
    override val protection = KeyProtection.PLATFORM_KEYSTORE

    override fun loadOrCreate(): SecretKey {
        val got = runner.run(listOf("security", "find-generic-password", "-s", SERVICE, "-a", account, "-w"), null)
        if (got.exitCode == 0 && got.stdout.isNotEmpty()) return KeyCodec.toKey(KeyCodec.decode(got.stdout.toString(Charsets.UTF_8)))
        val fresh = KeyCodec.newKeyBytes()
        // `security` has no stdin form for the password; -w on argv is visible to other local
        // processes for the instant this one runs, once per install. Accepted and documented.
        val put = runner.run(listOf("security", "add-generic-password", "-s", SERVICE, "-a", account, "-w", KeyCodec.encode(fresh), "-U"), null)
        check(put.exitCode == 0) { "security add-generic-password failed: ${put.stderr.take(200)}" }
        return KeyCodec.toKey(fresh)
    }

    companion object { const val SERVICE = "aidos-vault" }
}

/**
 * Windows DPAPI: the key is wrapped for the current user by `ProtectedData` (PowerShell) and the
 * wrapped blob is stored in [blobFile]. The blob is useless to another user or machine.
 */
class DpapiKeyProvider(
    private val blobFile: File,
    private val runner: CommandRunner = SystemCommandRunner,
) : VaultKeyProvider {
    override val protection = KeyProtection.PLATFORM_KEYSTORE

    override fun loadOrCreate(): SecretKey {
        if (blobFile.isFile) {
            val r = runner.run(ps(UNPROTECT), blobFile.readText().trim().toByteArray())
            check(r.exitCode == 0) { "DPAPI unprotect failed: ${r.stderr.take(200)}" }
            return KeyCodec.toKey(KeyCodec.decode(r.stdout.toString(Charsets.UTF_8)))
        }
        val fresh = KeyCodec.newKeyBytes()
        val r = runner.run(ps(PROTECT), KeyCodec.encode(fresh).toByteArray())
        check(r.exitCode == 0) { "DPAPI protect failed: ${r.stderr.take(200)}" }
        blobFile.parentFile?.mkdirs()
        blobFile.writeText(r.stdout.toString(Charsets.UTF_8).trim())
        return KeyCodec.toKey(fresh)
    }

    private fun ps(script: String) = listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", script)

    private companion object {
        const val PRE = "Add-Type -AssemblyName System.Security; \$in=[Console]::In.ReadToEnd().Trim(); "
        const val PROTECT = PRE + "[Convert]::ToBase64String([Security.Cryptography.ProtectedData]::Protect([Convert]::FromBase64String(\$in),\$null,'CurrentUser'))"
        const val UNPROTECT = PRE + "[Convert]::ToBase64String([Security.Cryptography.ProtectedData]::Unprotect([Convert]::FromBase64String(\$in),\$null,'CurrentUser'))"
    }
}

/**
 * Passphrase-derived key (PBKDF2-HmacSHA256). The salt and a verifier live in [saltFile]; the
 * passphrase and the key never do. A wrong passphrase is reported before any value is touched.
 */
class PassphraseKeyProvider(
    private val saltFile: File,
    private val passphrase: () -> CharArray,
    private val iterations: Int = 210_000,
) : VaultKeyProvider {
    override val protection = KeyProtection.PASSPHRASE

    override fun loadOrCreate(): SecretKey {
        val pass = passphrase()
        require(pass.isNotEmpty()) { "vault passphrase is empty" }
        try {
            if (saltFile.isFile) {
                val (salt, verifier) = saltFile.readText().trim().split(":").let { KeyCodec.decode(it[0]) to KeyCodec.decode(it[1]) }
                val key = derive(pass, salt)
                check(MessageDigest.isEqual(verifier, verifierOf(key))) { "secrets.bad_passphrase: the vault passphrase is wrong" }
                return KeyCodec.toKey(key)
            }
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val key = derive(pass, salt)
            saltFile.parentFile?.mkdirs()
            saltFile.writeText(KeyCodec.encode(salt) + ":" + KeyCodec.encode(verifierOf(key)))
            return KeyCodec.toKey(key)
        } finally {
            pass.fill('\u0000')
        }
    }

    private fun derive(pass: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pass, salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }

    /** A hash of the key under a fixed label: lets a wrong passphrase fail fast without exposing the key. */
    private fun verifierOf(key: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest("aidos-vault-check".toByteArray() + key)
}

/** Last resort: random key in a file only the owner can read. [protection] says so plainly. */
class OwnerOnlyFileKeyProvider(private val keyFile: File) : VaultKeyProvider {
    override val protection = KeyProtection.OWNER_ONLY_FILE

    override fun loadOrCreate(): SecretKey {
        if (keyFile.isFile) return KeyCodec.toKey(KeyCodec.decode(keyFile.readText()))
        val fresh = KeyCodec.newKeyBytes()
        keyFile.parentFile?.mkdirs()
        val tmp = File(keyFile.parentFile, keyFile.name + ".tmp")
        tmp.writeBytes(ByteArray(0))
        try {
            Files.setPosixFilePermissions(tmp.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        } catch (_: UnsupportedOperationException) { /* Windows: inherits the profile ACL */ }
        tmp.writeText(KeyCodec.encode(fresh))
        check(tmp.renameTo(keyFile)) { "could not store vault key in $keyFile" }
        return KeyCodec.toKey(fresh)
    }
}

/** Chooses the strongest available provider for this machine (RFC-0035 platform table). */
object VaultKeyProviders {
    /**
     * Order: the OS keystore, then a passphrase if [passphrase] yields one, then an owner-only
     * file. The caller reads [VaultKeyProvider.protection] and tells the user when it is weaker
     * than [KeyProtection.PLATFORM_KEYSTORE].
     */
    fun platformDefault(
        secretsDir: File,
        osName: String = System.getProperty("os.name") ?: "",
        runner: CommandRunner = SystemCommandRunner,
        passphrase: (() -> CharArray?)? = null,
    ): VaultKeyProvider {
        val os = osName.lowercase()
        when {
            "mac" in os || "darwin" in os -> return KeychainKeyProvider(runner)
            "win" in os -> return DpapiKeyProvider(File(secretsDir, "vault.key.dpapi"), runner)
            "linux" in os || "nux" in os -> if (SecretServiceKeyProvider.available(runner)) return SecretServiceKeyProvider(runner)
        }
        passphrase?.invoke()?.takeIf { it.isNotEmpty() }?.let { pass ->
            return PassphraseKeyProvider(File(secretsDir, "vault.salt"), { pass.copyOf() })
        }
        return OwnerOnlyFileKeyProvider(File(secretsDir, "vault.key"))
    }
}
