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
 * 3. [providerRetentionJson] is present and non-null — not an assumed default.
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
    fun `delete removes the secret`() = runTest {
        val vault = tempVault()
        val id = vault.store("temp_key", SecretKind.GENERIC, "value".toCharArray())
        vault.delete(id)
        val result = vault.resolve(id)
        assertTrue(result.isFailure)
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
    fun `anthropic adapter has provider retention json`() {
        val adapter = AnthropicAdapter("placeholder".toCharArray())
        assertNotNull(adapter.providerRetentionJson)
        assertTrue(adapter.providerRetentionJson.contains("anthropic"))
        // Must not be an assumed default — must be an explicit statement.
        assertFalse(adapter.providerRetentionJson.contains("UNKNOWN"))
    }

    @Test
    fun `anthropic adapter is not local`() {
        val adapter = AnthropicAdapter("placeholder".toCharArray())
        assertFalse(adapter.isLocal)
        assertEquals("anthropic", adapter.providerId)
        assertTrue(adapter.supportsNativeToolCalls())
    }
}
