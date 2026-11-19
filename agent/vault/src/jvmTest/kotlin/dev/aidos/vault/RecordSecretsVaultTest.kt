package dev.aidos.vault

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * RFC-0035 amendment 2026-11-19: the record-store vault the Android host runs. Same rules as
 * [SqliteSecretsVault]; the file store holds ciphertext only and refuses to replace a file it
 * cannot read.
 */
class RecordSecretsVaultTest {

    private val keyProvider = EphemeralKeyProvider()
    private fun tempFile() = File(Files.createTempDirectory("record-vault").toFile(), "secrets/vault.json")

    @Test
    fun `values round-trip and the file holds no plaintext`() = runTest {
        val file = tempFile()
        val vault = RecordSecretsVault(FileSecretRecordStore(file), keyProvider)
        val id = vault.store("hf_token", SecretKind.TOKEN, "hf-plaintext-value-123456".toCharArray())

        assertEquals("hf-plaintext-value-123456", String(vault.resolve(id).getOrThrow()))
        assertFalse(file.readText().contains("hf-plaintext-value-123456"))
    }

    @Test
    fun `a vault reopens on its own file with the same key, and a different key cannot read it`() {
        val file = tempFile()
        RecordSecretsVault(FileSecretRecordStore(file), keyProvider).putValue("k", SecretKind.API_KEY, "value-abcdef-123".toCharArray())

        val again = RecordSecretsVault(FileSecretRecordStore(file), keyProvider)
        assertEquals("value-abcdef-123", String(again.valueOf("k")!!))

        val other = RecordSecretsVault(FileSecretRecordStore(file), EphemeralKeyProvider())
        assertFailsWith<java.security.GeneralSecurityException> { other.valueOf("k") }
    }

    @Test
    fun `a sealed value moved onto another record does not open`() {
        val store = InMemorySecretRecordStore()
        val vault = RecordSecretsVault(store, keyProvider)
        vault.putValue("a", SecretKind.TOKEN, "value-aaaa-1111".toCharArray())
        vault.putValue("b", SecretKind.TOKEN, "value-bbbb-2222".toCharArray())
        val a = store.all().first { it.name == "a" }
        val b = store.all().first { it.name == "b" }
        store.put(b.copy(sealed = a.sealed))
        assertFailsWith<java.security.GeneralSecurityException> { vault.valueOf("b") }
    }

    @Test
    fun `putValue rotates an existing name, and the old value stays redacted for the grace period`() {
        val redactor = Redactor()
        val vault = RecordSecretsVault(InMemorySecretRecordStore(), keyProvider, redactor)
        val id1 = vault.putValue("provider:openai:api_key", SecretKind.API_KEY, "old-secret-value-1".toCharArray())
        val id2 = vault.putValue("provider:openai:api_key", SecretKind.API_KEY, "new-secret-value-2".toCharArray())

        assertEquals(id1, id2)
        assertEquals("new-secret-value-2", String(vault.valueOf("provider:openai:api_key")!!))
        val out = redactor.redact("a old-secret-value-1 b new-secret-value-2")
        assertFalse(out.contains("old-secret-value-1"))
        assertFalse(out.contains("new-secret-value-2"))
    }

    @Test
    fun `stored values are registered with the redactor when the vault opens`() {
        val file = tempFile()
        RecordSecretsVault(FileSecretRecordStore(file), keyProvider).putValue("hf_token", SecretKind.TOKEN, "plain-looking-value-42".toCharArray())

        val redactor = Redactor()
        RecordSecretsVault(FileSecretRecordStore(file), keyProvider, redactor)
        assertFalse(redactor.redact("leak: plain-looking-value-42").contains("plain-looking-value-42"))
    }

    @Test
    fun `delete and removeByName unregister the value`() {
        val redactor = Redactor()
        val vault = RecordSecretsVault(InMemorySecretRecordStore(), keyProvider, redactor)
        vault.putValue("n", SecretKind.TOKEN, "plain-looking-value-77".toCharArray())
        assertTrue(vault.removeByName("n"))
        assertFalse(vault.removeByName("n"))
        assertNull(vault.valueOf("n"))
        assertEquals(0, redactor.registeredCount())
    }

    @Test
    fun `names are unique`() = runTest {
        val vault = RecordSecretsVault(InMemorySecretRecordStore(), keyProvider)
        vault.store("n", SecretKind.TOKEN, "value-1234-abcd".toCharArray())
        val e = assertFailsWith<IllegalStateException> { vault.store("n", SecretKind.TOKEN, "value-5678-efgh".toCharArray()) }
        assertTrue(e.message!!.startsWith("secrets.name_taken"))
    }

    @Test
    fun `allow-lists, taint and expiry are enforced, and successful resolveFor is audited`() = runTest {
        val accesses = mutableListOf<SecretAccess>()
        var now = Instant.parse("2026-11-19T08:00:00Z")
        val clock = object : Clock { override fun now() = now }
        val vault = RecordSecretsVault(InMemorySecretRecordStore(), keyProvider, accessSink = { accesses += it }, clock = clock)
        val id = vault.store("mcp-token", SecretKind.TOKEN, "token-value-123456".toCharArray())
        val allowed = ConsumerRef(ConsumerKind.MCP_SERVER, "docs")
        vault.setAllowedConsumers(id, listOf(allowed))

        assertTrue(vault.resolve(id).exceptionOrNull()!!.message!!.startsWith("secrets.consumer_required"))
        assertTrue(vault.resolveFor(id, SecretRequest(ConsumerRef(ConsumerKind.MCP_SERVER, "other"))).exceptionOrNull()!!.message!!.startsWith("secrets.consumer_not_allowed"))
        assertTrue(vault.resolveFor(id, SecretRequest(allowed, tainted = true)).exceptionOrNull()!!.message!!.startsWith("secrets.denied_tainted"))
        assertEquals("token-value-123456", String(vault.resolveFor(id, SecretRequest(allowed)).getOrThrow()))
        assertEquals(1, accesses.size)
        assertEquals(now, vault.get(id).getOrThrow().lastUsedAt)

        vault.setExpiry(id, now + 1.hours)
        now += 2.hours
        assertTrue(vault.resolveFor(id, SecretRequest(allowed)).exceptionOrNull()!!.message!!.startsWith("secrets.expired"))
        assertTrue(vault.resolve("missing").exceptionOrNull()!!.message!!.startsWith("secrets.not_found"))
    }

    @Test
    fun `a vault file that does not parse is left alone and the vault does not open`() {
        val file = tempFile()
        file.parentFile.mkdirs()
        file.writeText("{ not json")
        assertFailsWith<IOException> { RecordSecretsVault(FileSecretRecordStore(file), keyProvider, Redactor()) }
        assertEquals("{ not json", file.readText())
    }

    @Test
    fun `the record store keeps metadata across reopen`() = runTest {
        val file = tempFile()
        val vault = RecordSecretsVault(FileSecretRecordStore(file), keyProvider)
        val id = vault.store("n", SecretKind.SSH_TUNNEL_KEY, "value-0000-1111".toCharArray())
        vault.setAllowedConsumers(id, listOf(ConsumerRef(ConsumerKind.GIT_REMOTE, "github.com")))

        val entry = RecordSecretsVault(FileSecretRecordStore(file), keyProvider).get(id).getOrThrow()
        assertEquals(SecretKind.SSH_TUNNEL_KEY, entry.kind)
        assertEquals(listOf("GIT_REMOTE:github.com"), entry.allowedConsumers)
    }

    @Test
    fun `a plaintext value is adopted once and an existing vault value wins`() {
        val vault = RecordSecretsVault(InMemorySecretRecordStore(), keyProvider)
        assertFalse(vault.adoptPlaintext("hf_token", SecretKind.TOKEN, null))
        assertTrue(vault.adoptPlaintext("hf_token", SecretKind.TOKEN, "first-value-123".toCharArray()))
        assertTrue(vault.adoptPlaintext("hf_token", SecretKind.TOKEN, "stale-value-456".toCharArray()))
        assertEquals("first-value-123", String(vault.valueOf("hf_token")!!))
    }
}
