package dev.aidos.daemon

import dev.aidos.api.MockRuntimeClient
import dev.aidos.api.socket.SocketPaths
import dev.aidos.cli.SocketConnectionException
import dev.aidos.cli.SocketRuntimeClient
import dev.aidos.vault.ConsumerRef
import dev.aidos.vault.DesktopVault
import dev.aidos.vault.Redactor
import dev.aidos.vault.SecretKind
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RFC-0115 / D56: a machine-wide Agent daemon starts with its passphrase vault locked and is unlocked over the socket. */
class DaemonVaultGateTest {
    private val home = Files.createTempDirectory("aidos-daemon-vault").toFile()
    private val tempDir = Files.createTempDirectory("aidos-daemon-vault-sock")

    @AfterTest
    fun cleanup() { home.deleteRecursively(); tempDir.toFile().deleteRecursively() }

    private fun passphraseGate() = DaemonVaultGate(passphraseMode = true, home = home.path)

    /** A vault that already holds a secret under [pass], as an earlier unlocked run would have left it. */
    private fun seed(pass: String, name: String, value: String) = runBlocking {
        DesktopVault.open(home.path, passphrase = { pass.toCharArray() }, forcePassphrase = true).vault
            .store(name, SecretKind.API_KEY, value.toCharArray())
    }

    @Test
    fun `starts locked, refuses a wrong passphrase, and unlocking registers the secrets with the redactor`() {
        seed("correct horse", "provider-key", "sk-very-secret-value-123")
        val gate = passphraseGate()
        val redactor = Redactor()
        gate.bind(redactor)

        assertNull(gate.openInitial(), "locked: no vault at start")
        assertTrue(gate.locked())
        assertEquals("passphrase", gate.mode())
        assertFalse(redactor.redact("the key is sk-very-secret-value-123").contains("[REDACTED"), "nothing is registered while locked")

        assertFailsWith<IllegalStateException> { gate.unlock("battery staple".toCharArray()) }
        assertTrue(gate.locked(), "a wrong passphrase leaves it locked")

        gate.unlock("correct horse".toCharArray())
        assertFalse(gate.locked())
        assertNotNull(gate.vault())
        assertFalse(redactor.redact("the key is sk-very-secret-value-123").contains("sk-very-secret-value-123"), "the unlocked secret is now redacted from output")

        gate.lock()
        assertTrue(gate.locked())
    }

    @Test
    fun `an OS keystore daemon is never locked and has nothing to unlock`() {
        val gate = DaemonVaultGate(passphraseMode = false, home = home.path) { _, _ -> error("keystore refused in this test") }
        assertNull(gate.openInitial(), "a refusing keystore is reported and the daemon carries on without a vault")
        assertFalse(gate.locked())
        assertFailsWith<IllegalArgumentException> { gate.unlock("x".toCharArray()) }
    }

    @Test
    fun `the vault is unlocked over the daemon socket`(): Unit = runBlocking {
        seed("right", "k", "v-secret-material-xyz")
        val gate = passphraseGate().also { it.bind(Redactor()); it.openInitial() }
        val socketPath = tempDir.resolve("runtime.sock")
        val tokenPath = SocketPaths.defaultTokenPath(socketPath)
        val server = RuntimeSocketServer(MockRuntimeClient(), socketPath, tokenPath, vaultGate = gate)
        server.start()
        try {
            // Not interactive: a tray is not a TTY, and unlocking does not need one.
            val client = SocketRuntimeClient(socketPath, tokenPath, interactive = false)
            assertEquals("passphrase" to true, client.vaultStatus())
            val bad = assertFailsWith<SocketConnectionException> { client.vaultUnlock("wrong".toCharArray()) }
            assertTrue(bad.message!!.contains("wrong"), bad.message)
            assertEquals("passphrase" to true, client.vaultStatus())
            assertEquals("passphrase" to false, client.vaultUnlock("right".toCharArray()))
            assertFalse(gate.locked())
            assertEquals("passphrase" to true, client.vaultLock())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a daemon without a vault gate does not wire the vault methods`(): Unit = runBlocking {
        val socketPath = tempDir.resolve("r2.sock")
        val tokenPath = SocketPaths.defaultTokenPath(socketPath)
        val server = RuntimeSocketServer(MockRuntimeClient(), socketPath, tokenPath)
        server.start()
        try {
            assertFailsWith<SocketConnectionException> { SocketRuntimeClient(socketPath, tokenPath, interactive = false).vaultStatus() }
        } finally {
            server.stop()
        }
    }
}
