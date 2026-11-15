package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.control.EngineControlException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** RFC-0115 models group (installed, install, delete) in-process and through the loopback admin API. */
class ModelsControlTest {
    private val root = Files.createTempDirectory("aidos-models-control").toFile()
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
    fun `installed models, installs and bad requests through the admin API`(): Unit = runBlocking {
        host.start()
        val running = assertNotNull(EngineAttach.runningEngine(root.resolve("engine.json"), selfPid = -1))
        val models = EngineAttach.modelsControl(host, running)

        assertTrue(models.installed().isEmpty())
        assertTrue(models.installs().isEmpty())
        val bad = assertFailsWith<EngineControlException> { models.startInstall("no-such-suggestion") }
        assertEquals(400, bad.httpStatus)
        assertFailsWith<EngineControlException> { models.searchRemote(null, "NOT_A_KIND", null, fi.italeino.aidos.engine.control.ModelSearchFiltersInfo()) }
        // Deleting a model that is not installed is a no-op; the slash in the id reaches the Engine intact.
        models.delete("org/never-installed")
    }

    @Test
    fun `the Models presenter works over the admin API`(): Unit = runBlocking {
        host.start()
        val running = assertNotNull(EngineAttach.runningEngine(root.resolve("engine.json"), selfPid = -1))
        val prefs = DesktopPreferences(root.resolve("preferences.json"))
        val model = DesktopModelsModel(host, prefs, scope, control = { EngineAttach.modelsControl(host, running) }, installPollMillis = 50)
        eventually("loaded") { !model.state.value.isLoading }
        assertTrue(model.state.value.localModels.isEmpty())

        model.deleteModel("org/never-installed")
        model.refresh()
        eventually("still loaded and empty, with no error") { !model.state.value.isLoading && model.state.value.localModels.isEmpty() }
        assertEquals(null, model.errorMessage.value)
    }
}
