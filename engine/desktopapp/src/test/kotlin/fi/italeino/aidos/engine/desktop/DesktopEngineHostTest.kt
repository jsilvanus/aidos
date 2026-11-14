package fi.italeino.aidos.engine.desktop

import dev.aidos.aidin.AidinNode
import dev.aidos.aidin.FileAidinStateStore
import dev.aidos.aidin.FileNodeKeyStore
import dev.aidos.kernel.ModelKind
import dev.aidos.models.CatalogEntry
import fi.italeino.aidos.engine.EngineState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.MessageDigest
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

    @Test
    fun splittableModelsUseInstalledCatalogPathsInsteadOfModelIdFilenameGuess() = runBlocking {
        val artifact = root.resolve("models").resolve("qwen2_5_3b_instruct_q4.gguf").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46))
        }
        host.catalogManager.addToCatalog(
            CatalogEntry(
                id = "qwen2.5-3b-instruct",
                name = "Qwen2.5 3B Instruct",
                kind = ModelKind.LLM,
                provider = "huggingface",
                remoteUrl = null,
                propertiesJson = """{"format":"gguf"}""",
                discoveredAt = "2026-11-13T00:00:00Z",
            )
        ).getOrThrow()
        host.catalogManager.markInstalled(
            modelId = "qwen2.5-3b-instruct",
            digest = "deadbeef",
            path = artifact.absolutePath,
            sizeBytes = artifact.length(),
            quantization = "Q4_K_M",
        ).getOrThrow()

        val models = host.splittableModels()
        assertEquals(listOf("qwen2.5-3b-instruct"), models)
    }

    @Test
    fun splittableModelsUnionsCatalogAndManualGgufFilesWithoutDuplicates() = runBlocking {
        val modelsDir = root.resolve("models").apply { mkdirs() }
        val catalogArtifact = modelsDir.resolve("qwen2_5_3b_instruct_q4.gguf").apply {
            writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46))
        }
        modelsDir.resolve("manual-local.gguf").writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46, 0x01))

        host.catalogManager.addToCatalog(
            CatalogEntry(
                id = "qwen2.5-3b-instruct",
                name = "Qwen2.5 3B Instruct",
                kind = ModelKind.LLM,
                provider = "huggingface",
                remoteUrl = null,
                propertiesJson = """{"format":"gguf"}""",
                discoveredAt = "2026-11-14T00:00:00Z",
            )
        ).getOrThrow()
        host.catalogManager.markInstalled(
            modelId = "qwen2.5-3b-instruct",
            digest = "deadbeef",
            path = catalogArtifact.absolutePath,
            sizeBytes = catalogArtifact.length(),
            quantization = "Q4_K_M",
        ).getOrThrow()

        assertEquals(listOf("manual-local", "qwen2.5-3b-instruct"), host.splittableModels())
    }

    @Test
    fun resolveSplitModelUsesRealCatalogDigestAndComputesSha256Fallback() = runBlocking {
        val modelsDir = root.resolve("models").apply { mkdirs() }
        val catalogArtifact = modelsDir.resolve("qwen_q4.gguf").apply {
            writeBytes("catalog-model-bytes".toByteArray())
        }
        val manualArtifact = modelsDir.resolve("manual-local.gguf").apply {
            writeBytes("manual-model-bytes".toByteArray())
        }

        val catalogDigest = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        host.catalogManager.addToCatalog(
            CatalogEntry(
                id = "qwen-catalog",
                name = "Qwen Catalog",
                kind = ModelKind.LLM,
                provider = "huggingface",
                remoteUrl = null,
                propertiesJson = """{"format":"gguf","sha256":"$catalogDigest"}""",
                discoveredAt = "2026-11-14T00:00:00Z",
            )
        ).getOrThrow()
        host.catalogManager.markInstalled(
            modelId = "qwen-catalog",
            digest = catalogDigest,
            path = catalogArtifact.absolutePath,
            sizeBytes = catalogArtifact.length(),
            quantization = "Q4_K_M",
        ).getOrThrow()

        val resolvedCatalog = assertNotNull(host.resolveSplitModel("qwen-catalog"))
        assertEquals(catalogArtifact.canonicalFile, resolvedCatalog.first.canonicalFile)
        assertEquals(catalogDigest, resolvedCatalog.second)

        val resolvedManual = assertNotNull(host.resolveSplitModel("manual-local"))
        val expectedManualSha = sha256Hex(manualArtifact)
        assertEquals(manualArtifact.canonicalFile, resolvedManual.first.canonicalFile)
        assertEquals(expectedManualSha, resolvedManual.second)
        assertFalse(resolvedManual.second.startsWith("gguf-bytes:"))
    }

    @Test
    fun openModelAndInferenceEnforceLicenseAcceptanceAndPersistAcrossRestart() = runBlocking {
        root.resolve("providers.json").writeText(
            """
            {
              "providers": [
                {
                  "id": "mock",
                  "baseUrl": "http://127.0.0.1:1",
                  "treatAsLocal": true,
                  "models": [{"id": "licensed-chat"}]
                }
              ]
            }
            """.trimIndent()
        )
        host.catalogManager.addToCatalog(
            CatalogEntry(
                id = "mock:licensed-chat",
                name = "Licensed Chat",
                kind = ModelKind.LLM,
                provider = "mock",
                remoteUrl = null,
                propertiesJson = """{"version":"1.0","license_text":"Accept me","license_digest":"digest-1"}""",
                discoveredAt = "2026-11-14T00:00:00Z",
            )
        ).getOrThrow()

        host.start()
        assertEquals(EngineState.READY, host.state.value, "start failed: ${host.lastError.value}")
        val port = assertNotNull(host.boundPort.value)
        val token = Regex("\"token\":\\s*\"([^\"]+)\"").find(discoveryFile.readText())!!.groupValues[1]
        val client = HttpClient.newHttpClient()

        val openDenied = client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/models/mock:licensed-chat/open"))
                .header("Authorization", "Bearer $token")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(451, openDenied.statusCode(), "Expected HTTP 451 before acceptance: ${openDenied.body()}")
        assertTrue(openDenied.body().contains("license_not_accepted"))

        val chatDenied = client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/chat/completions"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"model":"mock:licensed-chat","messages":[{"role":"user","content":"hi"}]}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(451, chatDenied.statusCode(), "Expected HTTP 451 for chat before acceptance: ${chatDenied.body()}")

        host.licenseAcceptanceStore.accept("mock:licensed-chat", "1.0", "digest-1")

        val openAllowed = client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/models/mock:licensed-chat/open"))
                .header("Authorization", "Bearer $token")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, openAllowed.statusCode(), "Expected HTTP 200 after acceptance: ${openAllowed.body()}")

        host.stop()

        val restarted = DesktopEngineHost(
            modelsDir = root.resolve("models"),
            stateDir = root,
            serverPort = { 0 },
            discoveryFile = null,
        )
        try {
            assertTrue(restarted.licenseAcceptanceStore.isAccepted("mock:licensed-chat", "1.0", "digest-1"))
            val watched = restarted.licenseAcceptanceStore.watchAcceptances().first()
            assertEquals(1, watched.size)
            assertEquals("mock:licensed-chat", watched.first().modelId)
        } finally {
            restarted.close()
        }
    }

    @Test
    fun localityPoliciesEnforceEngineAndSubjectLimitsAndRecordRefusals() = runBlocking {
        root.resolve("providers.json").writeText(
            """
            {
              "providers": [
                {
                  "id": "cloud",
                  "baseUrl": "https://example.invalid",
                  "treatAsLocal": false,
                  "models": [{"id": "remote-chat"}]
                }
              ]
            }
            """.trimIndent()
        )
        val prefs = DesktopPreferences(root.resolve("preferences.json"))
        prefs.setEngineLocalityLimit("local_only")

        host.start()
        assertEquals(EngineState.READY, host.state.value, "start failed: ${host.lastError.value}")
        val port = assertNotNull(host.boundPort.value)
        val token = Regex("\"token\":\\s*\"([^\"]+)\"").find(discoveryFile.readText())!!.groupValues[1]
        val client = HttpClient.newHttpClient()

        // Engine-wide limit ("local_only") overrides request's "any" header.
        val engineDenied = client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/chat/completions"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .header("X-Aidos-Locality", "any")
                .POST(HttpRequest.BodyPublishers.ofString("""{"model":"cloud:remote-chat","messages":[{"role":"user","content":"hi"}]}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(403, engineDenied.statusCode(), "Expected HTTP 403 for engine locality limit: ${engineDenied.body()}")
        assertTrue(engineDenied.body().contains("locality_violation"))
        val afterEngineRefusal = assertNotNull(host.approvalStore.getApproval(DesktopEngineHost.LOCALHOST_SUBJECT))
        assertEquals(1, afterEngineRefusal.localityRefusalCount)
        assertEquals("required_local_only|placement_remote", afterEngineRefusal.lastLocalityRefusalCode)

        // Lifting engine-wide limit while tightening per-app subject limit still enforces and records refusal.
        prefs.setEngineLocalityLimit("any")
        host.approvalStore.setLocalityLimit(DesktopEngineHost.LOCALHOST_SUBJECT, "local_only")
        val subjectDenied = client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/chat/completions"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .header("X-Aidos-Locality", "any")
                .POST(HttpRequest.BodyPublishers.ofString("""{"model":"cloud:remote-chat","messages":[{"role":"user","content":"hi"}]}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(403, subjectDenied.statusCode(), "Expected HTTP 403 for subject locality limit: ${subjectDenied.body()}")
        val afterSubjectRefusal = assertNotNull(host.approvalStore.getApproval(DesktopEngineHost.LOCALHOST_SUBJECT))
        assertEquals(2, afterSubjectRefusal.localityRefusalCount)
    }

    private fun sha256Hex(file: File): String =
        MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
}
