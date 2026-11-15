package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.agent.AgentRoutingConfigDto
import fi.italeino.aidos.engine.agent.AgentRunLaunchConfigDto
import fi.italeino.aidos.engine.agent.AgentTurnRequest
import fi.italeino.aidos.engine.agent.AgentsControl
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** RFC-0115 agents group: the runtime's overview, routing and cancel, in-process and through the admin API, on the echo fixture. */
class AgentsControlTest {
    private val root = Files.createTempDirectory("aidos-agents-control").toFile()
    private val modelsDir = root.resolve("models").apply { mkdirs() }
    private val host = DesktopEngineHost(modelsDir = modelsDir, stateDir = root, serverPort = { 0 }, discoveryFile = root.resolve("engine.json"))

    @AfterTest
    fun cleanUp() {
        runBlocking { host.close() }
        root.deleteRecursively()
    }

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) { File(dir, relative).takeIf { it.isFile }?.let { return it }; dir = dir.parentFile }
        error("$relative not found")
    }

    private fun eventually(what: String, check: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) { if (runBlocking { check() }) return; Thread.sleep(50) }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun check(agents: AgentsControl): Unit = runBlocking {
        val before = agents.overview()
        assertTrue(before.available)
        assertEquals(0, before.stats.totalRetainedRuns)

        agents.setRouting(AgentRoutingConfigDto(mainModelId = "echo", defaultRunModelId = "echo", enforcedLocality = "local_only", guardianPolicy = "MANUAL"))
        agents.setRunLaunch(AgentRunLaunchConfigDto(runModelOverride = "echo"))

        val runtime = assertNotNull(host.agentRuntime)
        runtime.start("test.app", AgentTurnRequest(model = "echo", message = "abcx", async = true, maxSteps = 2))
        eventually("the run is listed as finished") {
            val o = agents.overview()
            o.runs.singleOrNull()?.let { it.owner == "test.app" && it.status == "completed" } == true
        }
        val after = agents.overview()
        assertEquals(1, after.stats.totalRetainedRuns)
        assertEquals(mapOf("test.app" to 1), after.stats.runsByApp)
        val run = after.runs.single()
        assertEquals("echo", run.model)

        assertFalse(agents.cancel("no-such-run"))
        assertTrue(agents.cancel(run.runId), "supervisory cancel reaches a run of another app")
    }

    @Test
    fun `in-process`() {
        runBlocking {
            repoFile("models/echo-gguf/echo.gguf").copyTo(File(modelsDir, "echo.gguf"), overwrite = true)
            host.start()
        }
        check(host.agentsControl)
    }

    @Test
    fun `through the loopback admin API`() {
        runBlocking {
            repoFile("models/echo-gguf/echo.gguf").copyTo(File(modelsDir, "echo.gguf"), overwrite = true)
            host.start()
        }
        val running = assertNotNull(EngineAttach.runningEngine(root.resolve("engine.json"), selfPid = -1))
        check(EngineAttach.agentsControl(host, running))
    }

    @Test
    fun `an Engine without a runtime reports it is not available`(): Unit = runBlocking {
        // Not started: there is no agent runtime yet.
        assertFalse(host.agentsControl.overview().available)
        assertFalse(host.agentsControl.cancel("x"))
    }

    @Test
    fun `the Agents presenter shows a real run and saves routing through the admin API`(): Unit = runBlocking {
        repoFile("models/echo-gguf/echo.gguf").copyTo(File(modelsDir, "echo.gguf"), overwrite = true)
        host.start()
        val running = assertNotNull(EngineAttach.runningEngine(root.resolve("engine.json"), selfPid = -1))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val prefs = DesktopPreferences(root.resolve("preferences.json"))
            val model = DesktopAgentsModel(host, scope, prefs, { EngineAttach.agentsControl(host, running) }, { EngineAttach.modelsControl(host, running) }, pollMillis = 100)
            eventually("running with the sample runs, since no real run exists yet") { model.state.value.isEngineRunning }
            val runtime = assertNotNull(host.agentRuntime)
            runtime.start("test.app", AgentTurnRequest(model = "echo", message = "abcx", async = true, maxSteps = 2))
            eventually("the real run is shown") { model.state.value.runs.any { it.ownerApp == "test.app" } }
            model.setMainModel("echo")
            eventually("choice kept") { model.state.value.projectConfig?.mainModelId == "echo" }
        } finally {
            scope.cancel()
        }
    }
}
