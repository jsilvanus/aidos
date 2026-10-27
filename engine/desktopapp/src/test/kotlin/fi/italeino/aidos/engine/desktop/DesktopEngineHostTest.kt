package fi.italeino.aidos.engine.desktop

import dev.aidos.aidin.AidinNode
import dev.aidos.aidin.FileAidinStateStore
import dev.aidos.aidin.FileNodeKeyStore
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
    private val discoveryFile = root.resolve("engine.json")
    private val host = DesktopEngineHost(
        modelsDir = root.resolve("models").apply { mkdirs() },
        stateDir = root,
        serverPort = { 0 },
        discoveryFile = discoveryFile,
    )

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

        // Health endpoint (unauthenticated)
        val client = HttpClient.newHttpClient()
        val response = client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode())
        assertTrue("ok" in response.body())

        // Verify discovery file creation and content
        assertTrue(discoveryFile.exists(), "engine.json discovery file was not created")
        val discoveryContent = discoveryFile.readText()
        assertTrue(discoveryContent.contains("\"READY\""))
        assertTrue(discoveryContent.contains("\"port\": $port"))
        assertTrue(discoveryContent.contains("\"token\":"))

        // Extract token and test authenticated /v1/models endpoint
        val tokenRegex = Regex("\"token\":\\s*\"([^\"]+)\"")
        val token = tokenRegex.find(discoveryContent)?.groupValues?.get(1)
        assertNotNull(token, "Token could not be extracted from discovery file")

        val modelsResponse = client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/models"))
                .header("Authorization", "Bearer $token")
                .GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, modelsResponse.statusCode(), "Authenticated request failed: ${modelsResponse.body()}")

        host.stop()
        assertFalse(host.isRunning.value)
        assertNull(host.boundPort.value)
        assertNull(host.modelRuntime)
        assertFalse(discoveryFile.exists(), "engine.json should be removed when engine stops")
    }

    @Test
    fun catalogWorksWithTheEngineOff() = runBlocking {
        // Browsing installed models must not need the runtime, as on Android.
        assertTrue(host.modelBrowser.browse(onlyInstalled = true).getOrThrow().isEmpty())
        assertFalse(host.isRunning.value)
    }

    @Test
    fun servesAgentRuntimeBehindTheEngineToken() = runBlocking {
        host.start()
        val port = assertNotNull(host.boundPort.value)
        val token = Regex("\"token\": \"([^\"]+)\"").find(discoveryFile.readText())!!.groupValues[1]
        assertTrue(discoveryFile.readText().contains("\"agentApi\": \"/v1/agent\""))

        val client = HttpClient.newHttpClient()
        fun get(auth: Boolean): HttpResponse<String> = client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/agent/info")).apply {
                if (auth) header("Authorization", "Bearer $token")
            }.GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(401, get(auth = false).statusCode())
        val ok = get(auth = true)
        assertEquals(200, ok.statusCode())
        assertTrue("maxConcurrentRuns" in ok.body())
    }

    @Test
    fun groupSharingIsOffUnlessTurnedOn() = runBlocking {
        host.start()
        assertNull(host.aidin.value)
    }

    @Test
    fun groupSharingStartsAndStopsWithTheEngine() = runBlocking {
        val dir = root.resolve("aidin")
        val shared = DesktopEngineHost(
            modelsDir = root.resolve("models").apply { mkdirs() },
            stateDir = root,
            serverPort = { 0 },
            discoveryFile = null,
            groupSharing = { true },
            // No mDNS in tests; any free port.
            aidinFactory = { scope ->
                AidinNode(FileNodeKeyStore(dir.resolve("node-key")).loadOrCreate(), FileAidinStateStore(dir.resolve("state.json")), scope, "test")
            },
            aidinPort = 0,
        )
        try {
            shared.start()
            val node = assertNotNull(shared.aidin.value, "group sharing failed: ${shared.aidinError.value}")
            assertNotNull(node.port)
            shared.setGroupSharing(false)
            assertNull(shared.aidin.value)
            shared.setGroupSharing(true)
            val again = assertNotNull(shared.aidin.value)
            // Same identity across restarts: paired peers still recognise this machine.
            assertEquals(node.nodeId, again.nodeId)
            shared.stop()
            assertNull(shared.aidin.value)
        } finally {
            shared.close()
        }
    }
}
