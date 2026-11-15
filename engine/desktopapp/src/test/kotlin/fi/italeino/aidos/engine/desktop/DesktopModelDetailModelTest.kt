package fi.italeino.aidos.engine.desktop

import dev.aidos.kernel.ModelKind
import dev.aidos.models.CatalogEntry
import fi.italeino.aidos.engine.ui.ModelLoadingStatus
import fi.italeino.aidos.engine.ui.RunSettingsUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The model detail panel's presenter over the control, through the admin API of a running Engine, on the echo fixture. */
class DesktopModelDetailModelTest {
    private val root = Files.createTempDirectory("aidos-detail-model").toFile()
    private val modelsDir = root.resolve("models").apply { mkdirs() }
    private val prefs = DesktopPreferences(root.resolve("preferences.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = DesktopEngineHost(
        modelsDir = modelsDir, stateDir = root, serverPort = { 0 }, discoveryFile = root.resolve("engine.json"),
        runSettings = prefs.runSettingsSource, preferences = prefs,
    )

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        runBlocking { host.close() }
        root.deleteRecursively()
    }

    private fun eventually(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(20) }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) { File(dir, relative).takeIf { it.isFile }?.let { return it }; dir = dir.parentFile }
        error("$relative not found")
    }

    @Test
    fun `detail, load, self test, settings and unload through the admin API`(): Unit = runBlocking {
        val artifact = repoFile("models/echo-gguf/echo.gguf").copyTo(File(modelsDir, "echo.gguf"), overwrite = true)
        host.start()
        host.catalogManager.addToCatalog(
            CatalogEntry("echo", "Echo", ModelKind.LLM, "local", null, """{"format":"gguf"}""", "2026-11-15T00:00:00Z"),
        ).getOrThrow()
        host.catalogManager.markInstalled("echo", "deadbeef", artifact.absolutePath, artifact.length(), "F32").getOrThrow()
        val running = assertNotNull(EngineAttach.runningEngine(root.resolve("engine.json"), selfPid = -1))

        val model = DesktopModelDetailModel("echo", scope, { EngineAttach.modelDetailControl(host, running) }, { EngineAttach.modelsControl(host, running) }, pollMillis = 50)
        eventually("detail shown") { model.state.value.model?.id == "echo" && !model.state.value.isLoading }
        assertEquals(ModelKind.LLM, model.state.value.model!!.kind)
        eventually("installed") { model.state.value.isInstalled }

        model.toggleLoad()
        eventually("loaded") { model.loading.value.status == ModelLoadingStatus.LOADED }
        model.runSelfTest()
        eventually("self test passed") { model.state.value.selfTestOutcome?.passed == true }

        model.setRunSettings(RunSettingsUi(contextLength = 2048))
        eventually("settings saved") { model.state.value.runSettings.contextLength == 2048 }
        assertEquals(2048, prefs.runSettingsFor("echo").contextLength, "the Engine's preferences changed")

        model.toggleLoad()
        eventually("unloaded") { model.loading.value.status == ModelLoadingStatus.NOT_LOADED }
        model.dispose()
        assertTrue(true)
    }
}
