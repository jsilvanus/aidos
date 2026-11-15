package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.desktop.tray.TrayActions
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RFC-0115 end to end across a process boundary: a real headless Engine is started as its own JVM, the window's
 * controls attach to it through `engine.json` and the loopback admin API, and the tray's stop asks it to exit.
 */
class HeadlessEngineAttachTest {
    private val root = Files.createTempDirectory("aidos-headless").toFile()
    private val discovery = root.resolve("engine.json")
    private var process: Process? = null

    @AfterTest
    fun cleanUp() {
        process?.destroyForcibly()
        process?.waitFor(10, TimeUnit.SECONDS)
        root.deleteRecursively()
    }

    private fun startHeadless(): Process {
        // The class path of a test run can exceed the command-line limit on Windows, so it goes in an argument file.
        val argFile = root.resolve("java.args").apply { writeText("-cp\n\"" + System.getProperty("java.class.path").replace("\\", "\\\\") + "\"\n") }
        val java = File(System.getProperty("java.home"), "bin/java").path
        val p = ProcessBuilder(
            java,
            "-Daidos.dir=${root.resolve("aidos").path}", "-Daidos.models.dir=${root.resolve("models").path}",
            "-Daidos.state.dir=${root.resolve("state").path}", "-Daidos.discovery.file=${discovery.path}",
            "@${argFile.path}", "fi.italeino.aidos.engine.desktop.MainKt", "--daemon", "--port", "0",
        ).redirectErrorStream(true).redirectOutput(root.resolve("engine.log")).start()
        process = p
        return p
    }

    private fun waitFor(what: String, seconds: Long = 90, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(100) }
        throw AssertionError("timed out waiting for: $what\n" + root.resolve("engine.log").takeIf { it.isFile }?.readText().orEmpty().takeLast(2000))
    }

    @Test
    fun `a window attaches to a real headless Engine and the tray stops it`(): Unit = runBlocking {
        val p = startHeadless()
        waitFor("the Engine to publish engine.json") { EngineAttach.runningEngine(discovery, selfPid = -1) != null }
        val running = assertNotNull(EngineAttach.runningEngine(discovery, selfPid = -1))
        assertEquals(p.pid(), running.pid)

        // The window would not start a second Engine, and its controls reach the real one.
        assertFalse(EngineAttach.shouldStartOwnEngine(autoStart = true, running = running))
        val host = DesktopEngineHost(modelsDir = root.resolve("unused-models").apply { mkdirs() }, stateDir = root.resolve("unused-state"), serverPort = { 0 }, discoveryFile = null)
        try {
            val status = EngineAttach.statusControl(host, running).status()
            assertTrue(status.running, "the other process says it is running")
            assertTrue(status.stateName.contains("127.0.0.1:"), status.stateName)
            assertTrue(EngineAttach.appsControl(host, running).list().isNotEmpty(), "the Engine's own local client is a known app")
            assertEquals("os", EngineAttach.vaultControl(host, running).status().mode)
            assertTrue(EngineAttach.agentsControl(host, running).overview().available)
            assertTrue(EngineAttach.lanControl(host, running).status().devices.isEmpty())
        } finally {
            host.close()
        }

        // The tray stops it through the admin API; the process exits and engine.json no longer names a live Engine.
        val tray = TrayActions(running = { EngineAttach.runningEngine(discovery, selfPid = -1) }, launch = {})
        assertTrue(tray.stopEngine())
        waitFor("the Engine process to exit", seconds = 30) { !p.isAlive }
        assertNull(EngineAttach.runningEngine(discovery, selfPid = -1))
    }
}
