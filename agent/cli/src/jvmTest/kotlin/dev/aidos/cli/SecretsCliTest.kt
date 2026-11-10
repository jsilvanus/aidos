package dev.aidos.cli

import dev.aidos.portability.CliIo
import dev.aidos.vault.KeyProtection
import dev.aidos.vault.OpenedVault
import dev.aidos.vault.SqliteSecretsVault
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RFC-0035 `aidos secrets` command surface. */
class SecretsCliTest {
    private val tmp = Files.createTempDirectory("aidos-secrets-cli").toFile()
    private val key = SqliteSecretsVault.generateKey()
    private val db = File(tmp, "vault.db")

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private class Io(val value: String? = null, val pw: String? = null) : CliIo {
        val out = mutableListOf<String>(); val err = mutableListOf<String>()
        override fun out(line: String) { out += line }
        override fun err(line: String) { err += line }
        override fun password(prompt: String) = pw?.toCharArray()
        override fun env(name: String) = if (name == "AIDOS_SECRET_VALUE") value else null
    }

    private fun cli(io: Io, stdin: String? = null, protection: KeyProtection = KeyProtection.PLATFORM_KEYSTORE) =
        SecretsCli({ OpenedVault(SqliteSecretsVault(db, key), protection) }, io, { stdin })

    private fun run(vararg a: String, io: Io = Io(), stdin: String? = null, protection: KeyProtection = KeyProtection.PLATFORM_KEYSTORE) =
        cli(io, stdin, protection).run(a.toList()) to io

    @Test fun `set stores a secret that list shows without its value, and it survives reopening`() {
        val (c, io) = run("set", "github_token", "--kind", "token", io = Io(value = "ghx-very-secret-value-1"))
        assertEquals(0, c, io.err.toString())
        val (c2, io2) = run("list")
        assertEquals(0, c2)
        assertTrue(io2.out.single().startsWith("github_token  TOKEN"))
        assertFalse((io.out + io.err + io2.out + io2.err).any { it.contains("ghx-very-secret-value-1") }, "a value is never printed")
    }

    @Test fun `value can come from a prompt or piped stdin, never argv`() {
        assertEquals(0, run("set", "a", io = Io(pw = "prompted-value-1")).first)
        assertEquals(0, run("set", "b", stdin = "piped-value-12\n").first)
        val (c, io) = run("set", "c")
        assertEquals(2, c); assertTrue(io.err.any { it.contains("no value") })
    }

    @Test fun `set refuses an existing name, rotate replaces, delete removes`() {
        run("set", "k", io = Io(value = "first-value-1234"))
        val (c, io) = run("set", "k", io = Io(value = "other-value-1234"))
        assertEquals(1, c); assertTrue(io.err.single().contains("rotate"))
        assertEquals(0, run("rotate", "k", io = Io(value = "second-value-1234")).first)
        assertEquals(0, run("delete", "k").first)
        assertTrue(run("list").second.out.single().contains("no secrets"))
        val (c3, io3) = run("delete", "k")
        assertEquals(1, c3); assertTrue(io3.err.single().contains("secrets.not_found"))
    }

    @Test fun `expire and allow are shown in the list and validated`() {
        run("set", "k", io = Io(value = "first-value-1234"))
        assertEquals(0, run("expire", "k", "2099-01-01T00:00:00Z").first)
        assertEquals(0, run("allow", "k", "MCP_SERVER:github", "PROVIDER:anthropic").first)
        val line = run("list").second.out.single()
        assertTrue(line.contains("expires 2099-01-01T00:00:00Z")); assertTrue(line.contains("MCP_SERVER:github,PROVIDER:anthropic"))
        assertEquals(2, run("expire", "k", "tomorrow").first)
        assertEquals(2, run("allow", "k", "BOGUS:x").first)
        assertEquals(0, run("allow", "k").first)
        assertFalse(run("list").second.out.single().contains("consumers"))
    }

    @Test fun `status reports protection and a weak keystore is called out when storing`() {
        val (c, io) = run("status")
        assertEquals(0, c); assertTrue(io.out.first().contains("PLATFORM_KEYSTORE"))
        val (_, weak) = run("set", "k", io = Io(value = "first-value-1234"), protection = KeyProtection.OWNER_ONLY_FILE)
        assertTrue(weak.err.single().contains("weaker", ignoreCase = true) || weak.err.single().contains("weak", ignoreCase = true))
        val (_, strong) = run("set", "k2", io = Io(value = "first-value-1234"))
        assertTrue(strong.err.isEmpty())
    }

    @Test fun `usage errors exit 2`() {
        assertEquals(2, run().first); assertEquals(2, run("bogus").first); assertEquals(2, run("set").first)
    }

    @Test fun `vault signing keys round trip and refuse duplicates`() {
        val keys = VaultSigningKeys { OpenedVault(SqliteSecretsVault(db, key), KeyProtection.PLATFORM_KEYSTORE) }
        keys.save("release", "priv\npub\n")
        assertEquals("priv\npub\n", keys.load("release"))
        assertNull(keys.load("other"))
        assertTrue(runCatching { keys.save("release", "x") }.isFailure)
        // They are ordinary vault entries, so they appear in `secrets list` and can be deleted there.
        assertTrue(run("list").second.out.single().startsWith("export-signing-key:release"))
    }
}
