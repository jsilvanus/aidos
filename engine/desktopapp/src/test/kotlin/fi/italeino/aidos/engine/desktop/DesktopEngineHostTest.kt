package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.EngineState
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop host's start/stop lifecycle against the real JVM runtime and loopback HTTP server
 * (RFC-0103), in temporary directories so the test never touches ~/.aidos.
 */
class DesktopEngineHostTest {
    private val root = Files.createTempDirectory("aidos-desktop-host").toFile()
    private val host = DesktopEngineHost(modelsDir = root.resolve("models").apply { mkdirs() }, stateDir = root)

    @AfterTest
    fun cleanUp() {
        runBlocking { host.close() }
        root.deleteRecursively()
    }

    @Test
    fun startServesHealthOnLoopbackAndStopTearsDown() = runBlocking {
        host.start()
        assertEquals(EngineState.READY, host.state.value, "start failed: ${host.lastError.value}")
        assertTrue(host.isRunning.value)
        val port = assertNotNull(host.boundPort.value)

        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode())
        assertTrue("ok" in response.body())

        host.stop()
        assertFalse(host.isRunning.value)
        assertNull(host.boundPort.value)
        assertNull(host.modelRuntime)
    }

    @Test
    fun catalogWorksWithTheEngineOff() = runBlocking {
        // Browsing installed models must not need the runtime, as on Android.
        assertTrue(host.modelBrowser.browse(onlyInstalled = true).getOrThrow().isEmpty())
        assertFalse(host.isRunning.value)
    }
}
