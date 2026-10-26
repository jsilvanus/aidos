package dev.aidos.providers

import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.ToolCallOutput
import dev.aidos.kernel.ToolChoice
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.Turn
import dev.aidos.modelruntime.GlobalModelRuntime
import dev.aidos.modelruntime.InferenceBackend
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import fi.italeino.aidos.engine.http.EngineHttpServer
import fi.italeino.aidos.engine.http.TokenManager
import fi.italeino.aidos.engine.inference.Locality
import fi.italeino.aidos.engine.inference.LOCALITY_HEADER
import fi.italeino.aidos.engine.inference.PLACEMENT_HEADER
import fi.italeino.aidos.engine.inference.Placement
import fi.italeino.aidos.engine.inference.placementOf
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val chatReply = """
    {"id":"c","choices":[{"index":0,"message":{"role":"assistant","content":"Hello from the provider!"},"finish_reason":"stop"}],
     "usage":{"prompt_tokens":10,"completion_tokens":5}}
""".trimIndent()

private val toolReply = """
    {"id":"c2","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[
      {"id":"call_abc123","type":"function","function":{"name":"check_calendar","arguments":"{\"time\":\"17:00\"}"}}]},
      "finish_reason":"tool_calls"}]}
""".trimIndent()

private fun userTurn(text: String) = ModelRequest(
    messages = listOf(Turn.User(listOf(ContentBlock.Text(text)), TrustLevel.UNTRUSTED)),
    tools = emptyList(),
    toolChoice = ToolChoice.None,
    maxOutputTokens = 100,
)

/** The wire conversion moved here from the old `OpenAiHttpModelAdapter`, plus locality as a fact, not a label. */
class OpenAiCompatibleModelAdapterTest {
    private fun adapter(reply: String, local: Boolean = false) = OpenAiCompatibleModelAdapter(
        baseUrl = "http://127.0.0.1:11434", modelId = "llama3", providerId = "ollama", isLocal = local,
        transport = HttpTransport { _, _, _ -> reply },
    )

    @Test
    fun chatCompletionIsParsed() = runBlocking {
        val response = adapter(chatReply).invoke(userTurn("Hi")).getOrThrow()
        assertEquals(StopReason.END_TURN, response.stopReason)
        assertEquals("Hello from the provider!", (response.outputs.single() as TextOutput).text)
        assertEquals(15, response.usage?.totalTokens)
    }

    @Test
    fun toolCallsAreParsed() = runBlocking {
        val response = adapter(toolReply).invoke(userTurn("Am I free?")).getOrThrow()
        assertEquals(StopReason.TOOL_USE, response.stopReason)
        val call = response.outputs.filterIsInstance<ToolCallOutput>().single().call
        assertEquals("check_calendar", call.toolName)
        assertEquals("call_abc123", call.callId)
    }

    @Test
    fun requestGoesToTheProvidersChatEndpointWithKeyAndProviderModelName() = runBlocking {
        var url = ""; var auth: String? = null; var body = ""
        val a = OpenAiCompatibleModelAdapter(
            baseUrl = "http://127.0.0.1:11434/", modelId = "llama3", providerId = "ollama", isLocal = true, apiKey = "k",
            transport = HttpTransport { u, h, b -> url = u; auth = h["Authorization"]; body = b; chatReply },
        )
        a.invoke(userTurn("Hi")).getOrThrow()
        assertEquals("http://127.0.0.1:11434/v1/chat/completions", url)
        assertEquals("Bearer k", auth)
        assertTrue(body.contains("\"model\":\"llama3\""))
    }

    @Test
    fun isLocalIsWhatTheCreatorSaid() {
        assertFalse(adapter(chatReply, local = false).isLocal)
        assertTrue(adapter(chatReply, local = true).isLocal)
    }
}

class ProviderConfigTest {
    @Test
    fun loopbackHostsOnly() {
        listOf("http://localhost:11434", "http://127.0.0.1:11434", "http://127.1.2.3", "http://[::1]:11434")
            .forEach { assertTrue(isLoopbackHost(it), it) }
        listOf("http://192.168.1.5:11434", "https://api.example.com", "http://localhost.evil.com", "not a url", "")
            .forEach { assertFalse(isLoopbackHost(it), it) }
    }

    @Test
    fun parsesTheConfigFile() {
        val providers = ProviderConfigs.parse(
            """{"providers":[{"id":"ollama","baseUrl":"http://127.0.0.1:11434","treatAsLocal":true,
                "models":[{"id":"llama3","contextWindow":4096},{"id":"qwen2.5:7b"}]}]}"""
        )
        val p = providers.single()
        assertEquals(listOf("ollama:llama3", "ollama:qwen2.5:7b"), p.models.map { p.publicId(it) })
        assertTrue(p.servedLocally)
    }

    @Test
    fun toggleOnANonLoopbackHostIsIgnored() {
        assertFalse(ProviderConfig("lan", "http://192.168.1.5:11434", treatAsLocal = true).servedLocally)
        assertFalse(ProviderConfig("ollama", "http://127.0.0.1:11434", treatAsLocal = false).servedLocally)
    }
}

private object EmptyBackend : InferenceBackend {
    override suspend fun catalog() = emptyList<ModelDescriptor>()
    override suspend fun installed() = emptyList<ModelDescriptor>()
    override suspend fun computeDigest(modelId: String) = ""
    override suspend fun delete(modelId: String) = Unit
    override suspend fun load(modelId: String): Result<ModelAdapter> = Result.failure(IllegalStateException("Model $modelId is not installed"))
    override suspend fun unload(modelId: String) = Unit
}

class ProviderBackendTest {
    private fun backend(local: Boolean, host: String = "127.0.0.1") = ProviderBackend(
        EmptyBackend,
        { listOf(ProviderConfig("ollama", "http://$host:11434", apiKey = "secret", treatAsLocal = local, models = listOf(ProviderModel("llama3")))) },
        HttpTransport { _, _, _ -> chatReply },
    )

    @Test
    fun providerModelsAreInstalledAndRemoteByDefault() = runBlocking {
        val d = backend(local = false).installed().single()
        assertEquals("ollama:llama3", d.id)
        assertFalse(d.isLocal)
        assertEquals(Placement.REMOTE, placementOf(d))
        assertFalse(d.metadata.values.any { it.contains("secret") }, "the API key must never reach model metadata")
    }

    @Test
    fun theDevToggleMakesALoopbackProviderLocalOnBothClaims() = runBlocking {
        val b = backend(local = true)
        val d = b.installed().single()
        assertEquals(Placement.LOCAL, placementOf(d, b.load(d.id).getOrThrow()))
    }

    @Test
    fun theDevToggleCannotRelabelAnotherMachine() = runBlocking {
        val b = backend(local = true, host = "192.168.1.5")
        val d = b.installed().single()
        assertEquals(Placement.REMOTE, placementOf(d, b.load(d.id).getOrThrow()))
        assertEquals("ignored: host is not loopback", d.metadata["treat_as_local"])
    }

    @Test
    fun otherIdsAreDelegated() = runBlocking {
        assertTrue(backend(false).load("something-else").isFailure)
    }

    // --- through the real Engine HTTP server and GlobalModelRuntime -----------------------------

    private val body = """{"model":"ollama:llama3","messages":[{"role":"user","content":"Hi"}]}"""

    /** POSTs a chat request to a started Engine on loopback; returns status and the placement header. */
    private fun chat(local: Boolean, locality: String? = null): Pair<Int, String?> = runBlocking {
        val tokens = TokenManager()
        val server = EngineHttpServer(tokens, GlobalModelRuntime(backend(local)))
        server.start()
        try {
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.getBoundPort()}/v1/chat/completions"))
                .header("Authorization", "Bearer ${tokens.generateNewToken().token}")
                .header("Content-Type", "application/json")
                .apply { if (locality != null) header(LOCALITY_HEADER, locality) }
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() to response.headers().firstValue(PLACEMENT_HEADER).orElse(null)
        } finally {
            server.stop()
        }
    }

    @Test
    fun localOnlyDefault_refusesAnUntoggledProvider() = assertEquals(403, chat(local = false).first)

    @Test
    fun localOnlyDefault_servesAToggledLoopbackProvider() = assertEquals(200 to "local", chat(local = true))

    @Test
    fun explicitAnyReachesAnUntoggledProviderAndReportsRemote() = assertEquals(200 to "remote", chat(local = false, locality = "any"))
}
