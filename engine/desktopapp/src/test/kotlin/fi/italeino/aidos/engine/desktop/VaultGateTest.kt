package fi.italeino.aidos.engine.desktop

import dev.aidos.vault.DesktopVault
import fi.italeino.aidos.engine.control.EngineControlException
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** RFC-0115 / D56: a machine-wide Engine starts with its vault locked and is unlocked with a passphrase. */
class VaultGateTest {
    private val home = Files.createTempDirectory("aidos-vault-gate").toFile()
    private val root = Files.createTempDirectory("aidos-vault-gate-state").toFile()

    @AfterTest
    fun tearDown() { home.deleteRecursively(); root.deleteRecursively() }

    private fun passphraseGate() = VaultGate(passphraseMode = true) { p ->
        DesktopVault.open(home = home.path, passphrase = p, forcePassphrase = true).vault
    }

    @Test
    fun `a passphrase vault starts locked, refuses a wrong passphrase, and opens with the right one`(): Unit = runBlocking {
        val gate = passphraseGate()
        assertEquals(true, gate.status().locked)
        assertEquals("passphrase", gate.status().mode)
        assertFailsWith<IllegalStateException> { gate.vault() }

        gate.unlock("correct horse".toCharArray())
        assertFalse(gate.status().locked)
        gate.vault() // usable

        gate.lock()
        assertTrue(gate.status().locked)
        assertFailsWith<IllegalStateException> { gate.vault() }

        val wrong = assertFailsWith<IllegalStateException> { gate.unlock("battery staple".toCharArray()) }
        assertTrue(wrong.message!!.contains("wrong"), wrong.message)
        assertTrue(gate.status().locked, "a wrong passphrase leaves it locked")
        gate.unlock("correct horse".toCharArray())
        assertFalse(gate.status().locked)
    }

    @Test
    fun `an OS keystore vault is never locked and has nothing to unlock`(): Unit = runBlocking {
        val gate = VaultGate(passphraseMode = false) { error("not opened by these checks") }
        assertEquals(VaultStatusOs, gate.status().mode)
        assertFalse(gate.status().locked)
        assertFailsWith<IllegalArgumentException> { gate.unlock("x".toCharArray()) }
    }

    @Test
    fun `the vault is unlocked through the loopback admin API of a running Engine`(): Unit = runBlocking {
        val gate = passphraseGate()
        val host = DesktopEngineHost(
            modelsDir = root.resolve("models").apply { mkdirs() }, stateDir = root, serverPort = { 0 },
            discoveryFile = root.resolve("engine.json"), vaultGate = gate,
        )
        try {
            host.start()
            val running = assertNotNull(EngineAttach.runningEngine(root.resolve("engine.json"), selfPid = -1))
            val vault = EngineAttach.vaultControl(host, running)
            assertTrue(vault.status().locked)
            val bad = assertFailsWith<EngineControlException> { vault.unlock("wrong".toCharArray().also { DesktopVault.open(home = home.path, passphrase = { "right".toCharArray() }, forcePassphrase = true) }) }
            assertEquals(409, bad.httpStatus)
            vault.unlock("right".toCharArray())
            assertFalse(vault.status().locked)
            assertFalse(gate.status().locked, "the real gate in the Engine changed")
            vault.lock()
            assertTrue(gate.status().locked)
        } finally {
            host.close()
        }
    }

    private companion object { const val VaultStatusOs = "os" }
}
