package dev.aidos.vault

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** RFC-0035 requirement tests; the map is in docs/rfcs/STATUS.md. */
class Rfc0035RequirementsTest {

    private fun dir(): File = Files.createTempDirectory("rfc0035").toFile()

    // ── Vault key persistence (MVP 1) ──────────────────────────────────────────

    @Test
    fun `a secret stored in one process resolves in the next with a persisted key`() = runTest {
        val d = dir()
        val provider = OwnerOnlyFileKeyProvider(File(d, "vault.key"))
        val v1 = SqliteSecretsVault(File(d, "vault.db"), provider)
        val id = v1.store("github_token", SecretKind.TOKEN, "ghx-secret-value-123".toCharArray())
        v1.close()
        val v2 = SqliteSecretsVault(File(d, "vault.db"), OwnerOnlyFileKeyProvider(File(d, "vault.key")))
        assertEquals("ghx-secret-value-123", String(v2.resolve(id).getOrThrow()))
    }

    @Test
    fun `the database file never contains the plaintext or the key`() = runTest {
        val d = dir()
        val v = SqliteSecretsVault(File(d, "vault.db"), OwnerOnlyFileKeyProvider(File(d, "vault.key")))
        v.store("k", SecretKind.API_KEY, "plaintext-needle-value".toCharArray())
        v.close()
        val bytes = File(d, "vault.db").readBytes() + (File(d, "vault.db-wal").takeIf { it.exists() }?.readBytes() ?: ByteArray(0))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("plaintext-needle-value"))
    }

    @Test
    fun `a ciphertext copied onto another secret fails to decrypt`() = runTest {
        val d = dir()
        val key = SqliteSecretsVault.generateKey()
        val v = SqliteSecretsVault(File(d, "vault.db"), key)
        val a = v.store("a", SecretKind.GENERIC, "value-of-a-1234".toCharArray())
        val b = v.store("b", SecretKind.GENERIC, "value-of-b-1234".toCharArray())
        java.sql.DriverManager.getConnection("jdbc:sqlite:${File(d, "vault.db").absolutePath}").use { c ->
            c.createStatement().execute("UPDATE secrets SET ciphertext=(SELECT ciphertext FROM secrets WHERE id='$a'), nonce=(SELECT nonce FROM secrets WHERE id='$a') WHERE id='$b'")
        }
        assertTrue(v.resolve(b).isFailure, "AAD binds the ciphertext to its secret id")
    }

    @Test
    fun `passphrase provider derives the same key, rejects a wrong passphrase, and stores no key`() {
        val d = dir()
        val salt = File(d, "vault.salt")
        val k1 = PassphraseKeyProvider(salt, { "correct horse".toCharArray() }, iterations = 1000).loadOrCreate()
        val k2 = PassphraseKeyProvider(salt, { "correct horse".toCharArray() }, iterations = 1000).loadOrCreate()
        assertContentEquals(k1.encoded, k2.encoded)
        assertFailsWith<IllegalStateException> { PassphraseKeyProvider(salt, { "wrong".toCharArray() }, iterations = 1000).loadOrCreate() }
        assertFalse(salt.readText().contains(java.util.Base64.getEncoder().encodeToString(k1.encoded)))
        assertEquals(KeyProtection.PASSPHRASE, PassphraseKeyProvider(salt, { CharArray(1) }).protection)
    }

    @Test
    fun `owner-only file provider keeps one key and says its protection is weak`() {
        val d = dir()
        val p = OwnerOnlyFileKeyProvider(File(d, "k"))
        assertContentEquals(p.loadOrCreate().encoded, OwnerOnlyFileKeyProvider(File(d, "k")).loadOrCreate().encoded)
        assertTrue(p.protection.plainDescription.contains("weaker", ignoreCase = true) || p.protection.plainDescription.contains("weak", ignoreCase = true))
    }

    // ── OS keystores: driven through a fake runner (no keystore needed in CI) ──

    private class FakeKeystore : CommandRunner {
        var stored: ByteArray? = null
        val calls = mutableListOf<List<String>>()
        var stdins = mutableListOf<String?>()
        override fun run(command: List<String>, stdin: ByteArray?): CommandResult {
            calls += command; stdins += stdin?.toString(Charsets.UTF_8)
            return when {
                "lookup" in command -> stored?.let { CommandResult(0, it, "") } ?: CommandResult(1, ByteArray(0), "")
                "store" in command -> { stored = stdin; CommandResult(0, ByteArray(0), "") }
                "find-generic-password" in command -> stored?.let { CommandResult(0, it, "") } ?: CommandResult(44, ByteArray(0), "")
                "add-generic-password" in command -> { stored = command[command.indexOf("-w") + 1].toByteArray(); CommandResult(0, ByteArray(0), "") }
                else -> CommandResult(1, ByteArray(0), "")
            }
        }
    }

    @Test
    fun `secret service provider creates then reuses one key and sends it over stdin`() {
        val r = FakeKeystore()
        val a = SecretServiceKeyProvider(r).loadOrCreate()
        val b = SecretServiceKeyProvider(r).loadOrCreate()
        assertContentEquals(a.encoded, b.encoded)
        assertTrue(r.calls.none { c -> c.any { it.length > 30 } }, "the key must not appear in argv")
        assertEquals(KeyProtection.PLATFORM_KEYSTORE, SecretServiceKeyProvider(r).protection)
    }

    @Test
    fun `keychain provider creates then reuses one key`() {
        val r = FakeKeystore()
        assertContentEquals(KeychainKeyProvider(r).loadOrCreate().encoded, KeychainKeyProvider(r).loadOrCreate().encoded)
    }

    @Test
    fun `dpapi provider stores only the wrapped blob`() {
        val d = dir()
        val wrapped = ByteArray(0)
        var plain: String? = null
        val runner = CommandRunner { cmd, stdin ->
            val script = cmd.last()
            if ("Unprotect" in script) CommandResult(0, plain!!.toByteArray(), "")
            else { plain = stdin!!.toString(Charsets.UTF_8); CommandResult(0, ("WRAPPED" + wrapped.size).toByteArray(), "") }
        }
        val k = DpapiKeyProvider(File(d, "blob"), runner).loadOrCreate()
        assertEquals("WRAPPED0", File(d, "blob").readText())
        assertContentEquals(k.encoded, DpapiKeyProvider(File(d, "blob"), runner).loadOrCreate().encoded)
    }

    @Test
    fun `platformDefault picks the keystore per OS and falls back to passphrase then file`() {
        val d = dir()
        val none = CommandRunner { _, _ -> CommandResult(127, ByteArray(0), "not found") }
        assertTrue(VaultKeyProviders.platformDefault(d, "Mac OS X", none) is KeychainKeyProvider)
        assertTrue(VaultKeyProviders.platformDefault(d, "Windows 11", none) is DpapiKeyProvider)
        assertTrue(VaultKeyProviders.platformDefault(d, "Linux", FakeKeystore()) is SecretServiceKeyProvider)
        assertTrue(VaultKeyProviders.platformDefault(d, "Linux", none, passphrase = { "pw".toCharArray() }) is PassphraseKeyProvider)
        val last = VaultKeyProviders.platformDefault(d, "Linux", none)
        assertTrue(last is OwnerOnlyFileKeyProvider)
        assertNotEquals(KeyProtection.PLATFORM_KEYSTORE, last.protection)
    }

    // ── Resolution rules ───────────────────────────────────────────────────────

    private fun vault(sink: SecretAccessSink? = null, redactor: Redactor? = null) =
        SqliteSecretsVault(File(dir(), "v.db"), SqliteSecretsVault.generateKey(), redactor, sink)

    private val mcp = ConsumerRef(ConsumerKind.MCP_SERVER, "github")

    @Test
    fun `resolveFor audits the access without the value`() = runTest {
        val seen = mutableListOf<SecretAccess>()
        val v = vault(sink = { seen += it })
        val id = v.store("t", SecretKind.TOKEN, "tok-value-123456".toCharArray())
        v.resolveFor(id, SecretRequest(mcp, capabilityId = "cap1", attemptId = "att1")).getOrThrow()
        assertEquals(1, seen.size)
        assertEquals("cap1", seen[0].capabilityId)
        assertFalse(seen[0].toString().contains("tok-value-123456"))
    }

    @Test
    fun `a tainted run cannot resolve a secret`() = runTest {
        val v = vault()
        val id = v.store("t", SecretKind.TOKEN, "tok-value-123456".toCharArray())
        val r = v.resolveFor(id, SecretRequest(mcp, tainted = true))
        assertTrue(r.exceptionOrNull()!!.message!!.startsWith("secrets.denied_tainted"))
    }

    @Test
    fun `an allow-list limits consumers and blocks anonymous resolve`() = runTest {
        val v = vault()
        val id = v.store("t", SecretKind.TOKEN, "tok-value-123456".toCharArray())
        v.setAllowedConsumers(id, listOf(mcp))
        v.resolveFor(id, SecretRequest(mcp)).getOrThrow()
        val other = v.resolveFor(id, SecretRequest(ConsumerRef(ConsumerKind.PROVIDER, "anthropic")))
        assertTrue(other.exceptionOrNull()!!.message!!.startsWith("secrets.consumer_not_allowed"))
        assertTrue(v.resolve(id).exceptionOrNull()!!.message!!.startsWith("secrets.consumer_required"))
        assertEquals(listOf("MCP_SERVER:github"), v.get(id).getOrThrow().allowedConsumers)
    }

    @Test
    fun `an expired secret fails to resolve and a future expiry does not`() = runTest {
        val v = vault()
        val id = v.store("t", SecretKind.TOKEN, "tok-value-123456".toCharArray())
        v.setExpiry(id, Clock.System.now() - 1.hours)
        assertTrue(v.resolve(id).exceptionOrNull()!!.message!!.startsWith("secrets.expired"))
        v.setExpiry(id, Clock.System.now() + 1.hours)
        assertTrue(v.resolve(id).isSuccess)
    }

    @Test
    fun `missing secret reports secrets not_found`() = runTest {
        assertTrue(vault().resolve("nope").exceptionOrNull()!!.message!!.startsWith("secrets.not_found"))
    }

    @Test
    fun `opening a vault registers every stored value with the redactor`() = runTest {
        val d = dir()
        val key = SqliteSecretsVault.generateKey()
        SqliteSecretsVault(File(d, "v.db"), key).apply { store("anthropic_api_key", SecretKind.API_KEY, "plain.anthropic.value.1".toCharArray()); close() }
        val r = Redactor()
        SqliteSecretsVault(File(d, "v.db"), key, r)
        assertTrue(r.redact("error: bad key plain.anthropic.value.1").contains("«redacted:anthropic_api_key»"))
    }

    @Test
    fun `rotation replaces the value, and the old value stays filtered for the grace period only`() = runTest {
        var now = 0L
        val r = Redactor(nowMillis = { now })
        val v = SqliteSecretsVault(File(dir(), "v.db"), SqliteSecretsVault.generateKey(), r)
        val id = v.store("k", SecretKind.TOKEN, "old.value.123456".toCharArray())
        v.rotate(id, "new.value.123456".toCharArray())
        assertEquals("new.value.123456", String(v.resolve(id).getOrThrow()))
        assertTrue(r.detect("old.value.123456"), "old value filtered during grace")
        assertTrue(r.detect("new.value.123456"))
        now += 25L * 60 * 60 * 1000
        assertFalse(r.detect("old.value.123456"), "old value dropped after the grace period")
        assertNotEquals(null, v.get(id).getOrThrow().lastRotatedAt)
    }

    // ── Redactor ───────────────────────────────────────────────────────────────

    @Test
    fun `pattern detection covers common provider keys, JWTs, PEM blocks and connection strings`() {
        val r = Redactor()
        val samples = mapOf(
            "pattern:anthropic-api-key" to "sk-ant-api03-abcdefghijklmnopqrstuvwxyz",
            "pattern:github-token" to "ghp_abcdefghijklmnopqrstuvwxyz0123456789",
            "pattern:huggingface-token" to ("hf" + "_" + "abcdefghijklmnopqrstuvwxyzABCDEFGH"),
            "pattern:aws-access-key" to "AKIAABCDEFGHIJKLMNOP",
            "pattern:jwt" to "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnop",
            "pattern:pem-private-key" to "-----BEGIN PRIVATE KEY-----\nMIIabc\n-----END PRIVATE KEY-----",
            "pattern:connection-string" to "postgres://user:hunter2pw@db.example.com/app",
            "pattern:credential-assignment" to "API_KEY=abcdef0123456789abcdef",
        )
        for ((kind, text) in samples) {
            assertTrue(kind in r.detections("x $text y"), "expected $kind for $text")
            assertTrue(r.redact("x $text y").contains("«redacted:"), "redact $kind")
        }
        assertFalse(r.detect("The quick brown fox jumps over the lazy dog, version 1.2.3"))
    }

    @Test
    fun `detections are reported by kind and boundary, never by value`() {
        val seen = mutableListOf<Pair<RedactionBoundary, String>>()
        val r = Redactor(sink = { b, k -> seen += b to k })
        r.register("1", "tok", "super-secret-value".toCharArray())
        val out = r.redact("a super-secret-value b ghp_abcdefghijklmnopqrstuvwxyz0123456789", RedactionBoundary.EVENT)
        assertFalse(out.contains("super-secret-value"))
        assertEquals(setOf(RedactionBoundary.EVENT to "known_value", RedactionBoundary.EVENT to "pattern:github-token"), seen.toSet())
        assertTrue(seen.none { it.second.contains("super-secret") })
    }

    @Test
    fun `short values are not registered because they would corrupt ordinary text`() {
        val r = Redactor()
        r.register("1", "pin", "ab".toCharArray())
        assertEquals("abab", r.redact("abab"))
    }

    @Test
    fun `unchanged input is returned as the same object`() {
        val r = Redactor()
        val s = "nothing to see"
        assertTrue(r.redact(s) === s)
    }
}
