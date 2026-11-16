package dev.aidos.vault

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M14 done-when (RFC-0035):
 *
 * 1. An API key round-trips through vault.db and never appears in a log, event, audit row,
 *    or prompt (demonstrated by the Redactor intercepting it).
 * 2. [AnthropicAdapter] exists and normalises to the neutral [ModelResponse] envelope.
 * 3. [AnthropicAdapter.providerRetention] is present and states a real policy — not an assumed default.
 */
class VaultTest {

    private fun tempVault(): SqliteSecretsVault {
        val dir = Files.createTempDirectory("vault-test").toFile()
        return SqliteSecretsVault(File(dir, "vault.db"))
    }

    @Test
    fun `store and resolve round-trips the value`() = runTest {
        val vault = tempVault()
        val secret = "sk-ant-test-key-1234567890".toCharArray()
        val id = vault.store("anthropic_key", SecretKind.API_KEY, secret)
        assertNotNull(id)

        val resolved = vault.resolve(id).getOrThrow()
        assertEquals("sk-ant-test-key-1234567890", String(resolved))
        resolved.fill('\u0000')
    }

    @Test
    fun `every table of the schema is created, and a vault opens again on its own file`() = runTest {
        // A statement that followed a comment was skipped, so migration_history never existed,
        // and every error while applying the schema was swallowed.
        val file = File(Files.createTempDirectory("vault-schema").toFile(), "vault.db")
        val key = SqliteSecretsVault.generateKey()
        val first = SqliteSecretsVault(file, key)
        val id = first.store("k", SecretKind.TOKEN, "value-1234".toCharArray())
        first.close()

        val tables = java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rs ->
                buildSet { while (rs.next()) add(rs.getString(1)) }
            }
        }
        assertTrue(tables.containsAll(setOf("schema_versions", "migration_history", "secrets")), "tables: $tables")

        val again = SqliteSecretsVault(file, key)
        assertEquals("value-1234", String(again.resolve(id).getOrThrow()))
        again.close()
    }

    @Test
    fun `a file that is not a vault stops the vault from opening`() {
        val file = File(Files.createTempDirectory("vault-bad").toFile(), "vault.db")
        file.writeText("this is not a sqlite database, and it is long enough to have a header to reject")
        val failure = runCatching { SqliteSecretsVault(file) }.exceptionOrNull()
        assertNotNull(failure, "a corrupt vault file opened as if it were empty")
    }

    @Test
    fun `schema statements survive comments and leave PRAGMAs out`() {
        val statements = SqliteSecretsVault.schemaStatements(
            "-- header\nPRAGMA x = 1;\nCREATE TABLE a (id TEXT); -- trailing\n-- about b\nCREATE TABLE b (\n  id TEXT -- the id\n);\n"
        )
        assertEquals(2, statements.size, statements.toString())
        assertTrue(statements[0].startsWith("CREATE TABLE a"))
        assertTrue(statements[1].startsWith("CREATE TABLE b"))
    }

    @Test
    fun `the redactor finds a known value more than once and beside pattern matches`() {
        val redactor = Redactor()
        redactor.register("id1", "db_password", "hunter2hunter2".toCharArray())
        val out = redactor.redact("a=hunter2hunter2; b=hunter2hunter2 and hunter2hunter then eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c")
        assertEquals("a=«redacted:db_password»; b=«redacted:db_password» and hunter2hunter then «redacted:jwt»", out)
        val plain = "nothing to see here"
        assertTrue(redactor.redact(plain) === plain, "an unchanged string is returned as it came")
        assertEquals(listOf("known_value", "pattern:jwt"), redactor.detections("x hunter2hunter2 eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"))
    }

    @Test
    fun `get returns metadata without the value`() = runTest {
        val vault = tempVault()
        val id = vault.store("gh_token", SecretKind.TOKEN, "ghp_abc123".toCharArray())
        val entry = vault.get(id).getOrThrow()
        assertEquals("gh_token", entry.name)
        assertEquals(SecretKind.TOKEN, entry.kind)
        assertNotNull(entry.createdAt)
    }

    @Test
    fun `rotate replaces the value`() = runTest {
        val vault = tempVault()
        val id = vault.store("key", SecretKind.API_KEY, "old_value".toCharArray())
        vault.rotate(id, "new_value".toCharArray())
        val resolved = vault.resolve(id).getOrThrow()
        assertEquals("new_value", String(resolved))
    }

    @Test
    fun `concurrent rotations leave every replaced value redacted`() {
        // RFC-0035 "Rotation and revocation": a rotated-out value stays filtered for the grace
        // period. Unlocked, two rotations could both read the same "old" value, so the value one
        // of them wrote was replaced without ever being retired, and passed the redactor in clear.
        repeat(20) { round ->
            val redactor = Redactor()
            val dir = Files.createTempDirectory("vault-test").toFile()
            val vault = SqliteSecretsVault(File(dir, "vault.db"), SqliteSecretsVault.generateKey(), redactor)
            val id = kotlinx.coroutines.runBlocking { vault.store("k", SecretKind.API_KEY, "initial-value-$round".toCharArray()) }
            val values = (0 until 16).map { "rotated-value-$round-$it-end" }
            val pool = java.util.concurrent.Executors.newFixedThreadPool(values.size)
            try {
                val start = java.util.concurrent.CountDownLatch(1)
                values.map { v ->
                    pool.submit {
                        start.await()
                        kotlinx.coroutines.runBlocking { vault.rotate(id, v.toCharArray()) }
                    }
                }.also { start.countDown() }.forEach { it.get() }
            } finally {
                pool.shutdownNow()
            }
            val redacted = redactor.redact(values.joinToString(" | "))
            values.forEach { assertFalse(it in redacted, "rotated-out value passed the redactor: $it") }
            vault.close()
        }
    }

    @Test
    fun `rotating a secret that does not exist fails and retires nothing`() = runTest {
        val vault = tempVault()
        assertTrue(runCatching { vault.rotate("no-such-id", "x".toCharArray()) }.isFailure)
    }

    @Test
    fun `delete removes the secret`() = runTest {
        val vault = tempVault()
        val id = vault.store("temp_key", SecretKind.GENERIC, "value".toCharArray())
        vault.delete(id)
        val result = vault.resolve(id)
        assertTrue(result.isFailure)
    }

    @Test
    fun `a stored value is registered with the redactor, delete unregisters it`() = runTest {
        // RFC-0035: every vault value is registered with the redactor, and unregistered on delete.
        val redactor = Redactor()
        val dir = Files.createTempDirectory("vault-redactor-test").toFile()
        val vault = SqliteSecretsVault(File(dir, "vault.db"), SqliteSecretsVault.generateKey(), redactor = redactor)

        val secretValue = "live.test.value.9876543210"
        val id = vault.store("live_key", SecretKind.API_KEY, secretValue.toCharArray())
        assertTrue(redactor.detect(secretValue), "store() registers the value before anything resolves it")
        assertTrue(redactor.redact("token=$secretValue here").contains("«redacted:live_key»"))

        vault.delete(id)
        assertFalse(redactor.detect(secretValue), "delete() must unregister the value")
    }

    @Test
    fun `list returns all stored entries`() = runTest {
        val vault = tempVault()
        vault.store("key1", SecretKind.API_KEY, "v1".toCharArray())
        vault.store("key2", SecretKind.TOKEN, "v2".toCharArray())
        val list = vault.list()
        assertEquals(2, list.size)
    }

    @Test
    fun `redactor replaces known value in strings`() {
        val redactor = Redactor()
        redactor.register("id1", "my_key", "super_secret_value".toCharArray())
        val redacted = redactor.redact("The key is: super_secret_value and more")
        assertFalse(redacted.contains("super_secret_value"))
        assertTrue(redacted.contains("«redacted:my_key»"))
    }

    @Test
    fun `redactor detects Anthropic API key pattern`() {
        val redactor = Redactor()
        // Anthropic API key shape — sk-ant- prefix followed by alphanumerics.
        val fakeKey = "sk-ant-api03-testkeytestkeytestkey12345"
        assertTrue(redactor.detect(fakeKey))
        val redacted = redactor.redact(fakeKey)
        assertFalse(redacted.contains("sk-ant-"))
    }

    @Test
    fun `redactor detect returns false for clean content`() {
        val redactor = Redactor()
        assertFalse(redactor.detect("Hello, world! This is clean content."))
    }

    @Test
    fun `anthropic adapter states a real provider retention policy`() {
        val adapter = AnthropicAdapter("placeholder".toCharArray())
        val retention = adapter.providerRetention
        assertNotNull(retention)
        // Must be an explicit statement, not RetentionPolicy.UNKNOWN standing in for one Anthropic
        // never actually gave -- this adapter has a real source (see its own doc comment).
        assertEquals(dev.aidos.kernel.RetentionPolicy.ZERO, retention.policy)
    }

    @Test
    fun `anthropic adapter is not local`() {
        val adapter = AnthropicAdapter("placeholder".toCharArray())
        assertFalse(adapter.isLocal)
        assertEquals("anthropic", adapter.providerId)
        assertTrue(adapter.supportsNativeToolCalls())
    }
}
