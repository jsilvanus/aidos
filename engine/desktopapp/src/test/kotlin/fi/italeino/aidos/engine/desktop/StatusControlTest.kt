package fi.italeino.aidos.engine.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** RFC-0115 status group: the Home pane over a running Engine's loopback admin API. */
class StatusControlTest {
    private val root = Files.createTempDirectory("aidos-status-control").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = DesktopEngineHost(
        modelsDir = root.resolve("models").apply { mkdirs() },
        stateDir = root,
        serverPort = { 0 },
        discoveryFile = root.resolve("engine.json"),
    )

    @AfterTest
    fun tearDown() {
        runBlocking { host.close() }
        scope.cancel()
        root.deleteRecursively()
    }

    private fun eventually(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(20) }
        throw AssertionError("timed out waiting for: $what")
    }

    @Test
    fun `the status comes through the admin API and the presenter shows it`(): Unit = runBlocking {
        host.start()
        val running = assertNotNull(EngineAttach.runningEngine(root.resolve("engine.json"), selfPid = -1))
        val info = EngineAttach.statusControl(host, running).status()
        assertTrue(info.running)
        assertTrue(info.stateName.contains("127.0.0.1:"), "the serving port is part of the state name: ${info.stateName}")

        host.approvalStore.recordFirstHandshake("dev.test.app", "Test app")
        val model = DesktopStatusModel(host, scope, status = { EngineAttach.statusControl(host, running) }, apps = { EngineAttach.appsControl(host, running) })
        eventually("shown as running with a pending app") { model.state.value.isEngineRunning && model.state.value.pendingAppCount == 1 }
        assertFalse("Test app" in model.state.value.approvedApps, "a pending app is not listed as approved")
    }

    @Test
    fun `an Engine that stops answering shows as off`(): Unit = runBlocking {
        host.start()
        val running = assertNotNull(EngineAttach.runningEngine(root.resolve("engine.json"), selfPid = -1))
        val model = DesktopStatusModel(host, scope, status = { EngineAttach.statusControl(host, running) }, apps = { EngineAttach.appsControl(host, running) })
        eventually("running") { model.state.value.isEngineRunning }
        host.close()
        eventually("off after the Engine went away") { !model.state.value.isEngineRunning && model.state.value.engineStateName == "OFF" }
        assertFalse(model.state.value.isEngineRunning)
    }
}
