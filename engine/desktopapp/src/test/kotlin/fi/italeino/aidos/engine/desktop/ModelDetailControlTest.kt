package fi.italeino.aidos.engine.desktop

import dev.aidos.kernel.ModelKind
import dev.aidos.models.CatalogEntry
import fi.italeino.aidos.engine.control.EngineControlException
import fi.italeino.aidos.engine.control.ModelDetailControl
import fi.italeino.aidos.engine.control.RunSettingsInfo
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RFC-0115 model detail group, against the checked-in `models/echo-gguf` fixture, the same checks in-process and
 * through the loopback admin API of a running Engine. Written before the presenter moved onto the interface.
 */
class ModelDetailControlTest {
    private val root = Files.createTempDirectory("aidos-model-detail").toFile()
    private val modelsDir = root.resolve("models").apply { mkdirs() }
    private val prefs = DesktopPreferences(root.resolve("preferences.json"))
    private val host = DesktopEngineHost(
        modelsDir = modelsDir, stateDir = root, serverPort = { 0 },
        discoveryFile = root.resolve("engine.json"),
        runSettings = prefs.runSettingsSource, preferences = prefs,
    )

    @AfterTest
    fun cleanUp() {
        runBlocking { host.close() }
        root.deleteRecursively()
    }

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("$relative not found above ${System.getProperty("user.dir")}")
    }

    private fun startWithEcho() = runBlocking {
        val artifact = repoFile("models/echo-gguf/echo.gguf").copyTo(File(modelsDir, "echo.gguf"), overwrite = true)
        host.start()
        assertEquals(fi.italeino.aidos.engine.EngineState.READY, host.state.value, "start failed: ${host.lastError.value}")
        // A catalog row and an install record, as the installer writes them (a provider that is not Hugging Face, so no network).
        host.catalogManager.addToCatalog(
            CatalogEntry(id = "echo", name = "Echo", kind = ModelKind.LLM, provider = "local", remoteUrl = null, propertiesJson = """{"format":"gguf"}""", discoveredAt = "2026-11-15T00:00:00Z"),
        ).getOrThrow()
        host.catalogManager.markInstalled("echo", "deadbeef", artifact.absolutePath, artifact.length(), "F32").getOrThrow()
    }

    private fun check(control: ModelDetailControl): Unit = runBlocking {
        val detail = control.detail("echo")
        assertEquals("echo", detail.id)
        assertEquals("LLM", detail.kind)
        assertTrue(detail.contextFitTable.isNotEmpty(), "the fit table is built from this machine's memory")
        assertTrue(detail.deviceTotalRamMB > 0)

        val installed = control.installedState("echo")
        assertTrue(installed.installed)
        assertNotNull(installed.path)
        assertFalse(control.installedState("not-installed").installed)

        assertTrue(control.licenseAccepted("echo"), "a local file declares no license, so nothing to accept")
        assertNull(control.downloadStatus("echo"))

        assertFalse(control.residency("echo").loaded)
        control.load("echo")
        val resident = control.residency("echo")
        assertTrue(resident.loaded)
        assertNotNull(resident.loadedSettings)

        val outcome = control.selfTest("echo", "abcx", "")
        assertTrue(outcome.passed, "self test: ${outcome.title} ${outcome.lines}")

        control.unload("echo")
        assertFalse(control.residency("echo").loaded)

        val settings = RunSettingsInfo(contextLength = 2048, threads = 2, useMmap = false)
        control.setRunSettings("echo", settings)
        assertEquals(settings, control.runSettings("echo"))
        control.setRunSettings("echo", RunSettingsInfo())
        assertEquals(RunSettingsInfo(), control.runSettings("echo"), "default settings read back as default")

        assertFailsWith<Exception> { control.load("not-installed") }
        assertFailsWith<Exception> { control.detail("not-installed") }
    }

    @Test
    fun `in-process`() {
        startWithEcho()
        check(host.modelDetailControl)
    }

    @Test
    fun `through the loopback admin API`() {
        startWithEcho()
        val running = assertNotNull(EngineAttach.runningEngine(root.resolve("engine.json"), selfPid = -1))
        val http = EngineAttach.modelDetailControl(host, running)
        check(http)
        val bad = assertFailsWith<EngineControlException> { runBlocking { http.load("not-installed") } }
        assertTrue(bad.httpStatus in 400..499, "a refusal, not a crash: ${bad.httpStatus}")
    }
}
