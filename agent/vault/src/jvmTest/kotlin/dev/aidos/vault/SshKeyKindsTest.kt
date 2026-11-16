package dev.aidos.vault

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RFC-0108 / RFC-0035 amendment: tunnel, command and setup SSH keys are distinct vault kinds. */
class SshKeyKindsTest {
    private fun vault(): SqliteSecretsVault {
        val d = Files.createTempDirectory("sshkinds").toFile()
        return SqliteSecretsVault(File(d, "vault.db"), OwnerOnlyFileKeyProvider(File(d, "vault.key")))
    }

    @Test
    fun eachSshKindRoundTripsAndKeepsItsKind() = runTest {
        val v = vault()
        val kinds = listOf(SecretKind.SSH_KEY, SecretKind.SSH_TUNNEL_KEY, SecretKind.SSH_COMMAND_KEY, SecretKind.SSH_SETUP_KEY)
        val ids = kinds.associateWith { v.store("key-${it.name}", it, "pem-${it.name}".toCharArray()) }
        for ((kind, id) in ids) {
            assertEquals(kind, v.get(id).getOrThrow().kind)
            assertEquals("pem-${kind.name}", String(v.resolve(id).getOrThrow()))
        }
        assertEquals(kinds.toSet(), v.list().map { it.kind }.toSet())
        v.close()
    }

    @Test
    fun aTunnelKeyCanBeHeldToTheTunnelManagerOnly() = runTest {
        val v = vault()
        val id = v.store("tunnel-home", SecretKind.SSH_TUNNEL_KEY, "pem".toCharArray())
        val manager = ConsumerRef(ConsumerKind.PROVIDER, "tunnel-manager")
        v.setAllowedConsumers(id, listOf(manager))
        assertTrue(v.resolveFor(id, SecretRequest(manager)).isSuccess)
        val other = v.resolveFor(id, SecretRequest(ConsumerRef(ConsumerKind.MCP_SERVER, "some-server")))
        assertTrue(other.isFailure)
        assertTrue(other.exceptionOrNull()!!.message!!.contains("consumer_not_allowed"))
        // An unrestricted resolve is refused once an allow-list exists.
        assertTrue(v.resolve(id).isFailure)
        v.close()
    }
}
