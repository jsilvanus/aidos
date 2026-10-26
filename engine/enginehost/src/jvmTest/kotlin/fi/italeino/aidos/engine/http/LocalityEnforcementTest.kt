package fi.italeino.aidos.engine.http

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRef
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.ModelRuntime
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.Usage
import fi.italeino.aidos.engine.inference.LOCALITY_HEADER
import fi.italeino.aidos.engine.inference.Locality
import fi.italeino.aidos.engine.inference.PLACEMENT_HEADER
import fi.italeino.aidos.engine.inference.Placement
import fi.italeino.aidos.engine.inference.placementOf
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val testJson = Json { encodeDefaults = true }

private fun descriptor(id: String, isLocal: Boolean = true, placement: String? = null) = ModelDescriptor(
    id = id, name = id, kind = ModelKind.LLM, providerId = "test", isLocal = isLocal,
    contextWindow = 2048, sizeBytes = 1L, digest = "d",
    metadata = if (placement == null) emptyMap() else mapOf("placement" to placement),
)

private class FlagAdapter(override val isLocal: Boolean) : ModelAdapter {
    override val providerId = "test"
    override val modelId = "test-model"
    override val modelVersion = "1.0"
    override val contextWindow = 2048
    override fun supportsNativeToolCalls() = false
    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> = Result.success(
        ModelResponse(
            outputs = listOf(TextOutput("Mock response")),
            stopReason = StopReason.END_TURN,
            usage = Usage(inputTokens = 1, outputTokens = 1, totalTokens = 2),
            model = ModelRef(id = modelId, version = modelVersion),
        )
    )
}

/** Serves [models]; [adapterIsLocal] is what the loaded adapter claims, [loads] counts load() calls. */
private class PlacementRuntime(
    private val models: List<ModelDescriptor>,
    private val adapterIsLocal: Boolean = true,
) : ModelRuntime {
    val loads = AtomicInteger()
    override suspend fun catalog() = models
    override suspend fun installed() = models
    override suspend fun load(modelId: String): Result<ModelAdapter> {
        loads.incrementAndGet()
        return Result.success(FlagAdapter(adapterIsLocal))
    }
    override suspend fun delete(modelId: String) = Unit
    override suspend fun unload(modelId: String) = Unit
    override fun loaded() = emptyList<String>()
}

/** RFC-0104: Engine refuses to serve a request at a wider placement than the request allows. */
class LocalityEnforcementTest {

    private val local = descriptor("local-model")
    private val remote = descriptor("remote-model", isLocal = false)
    private val cluster = descriptor("cluster-model", placement = "cluster")

    private fun chatBody(model: String) =
        testJson.encodeToString(ChatCompletionRequest(model = model, messages = listOf(ChatMessage(role = "user", content = "Hi"))))

    private fun server(
        runtime: ModelRuntime,
        engineLocality: () -> Locality = { Locality.ANY },
        subjectLocality: (suspend (String) -> Locality?)? = null,
    ) = TokenManager().let { tokens ->
        EngineHttpServer(tokens, runtime, engineLocality = engineLocality, subjectLocality = subjectLocality) to tokens
    }

    // --- placementOf ---------------------------------------------------------------------------

    @Test
    fun placementOf_widestClaimWinsAndNothingNarrows() {
        assertEquals(Placement.LOCAL, placementOf(local))
        assertEquals(Placement.REMOTE, placementOf(remote))
        assertEquals(Placement.CLUSTER, placementOf(cluster))
        // A descriptor that says local cannot override an adapter that says it is not.
        assertEquals(Placement.REMOTE, placementOf(local, FlagAdapter(isLocal = false)))
        // ...and an explicit "local" cannot narrow isLocal = false.
        assertEquals(Placement.REMOTE, placementOf(descriptor("x", isLocal = false, placement = "local")))
    }

    @Test
    fun placementOf_unknownOrMissingIsRemote() {
        assertEquals(Placement.REMOTE, placementOf(descriptor("x", placement = "orbital")))
        assertEquals(Placement.REMOTE, placementOf(null, null))
    }

    @Test
    fun locality_permitsAndStrictest() {
        assertTrue(Locality.LOCAL_ONLY.permits(Placement.LOCAL))
        assertTrue(!Locality.LOCAL_ONLY.permits(Placement.CLUSTER))
        assertTrue(Locality.TRUSTED_CLUSTER.permits(Placement.CLUSTER))
        assertTrue(!Locality.TRUSTED_CLUSTER.permits(Placement.REMOTE))
        assertTrue(Locality.ANY.permits(Placement.REMOTE))
        assertEquals(Locality.LOCAL_ONLY, Locality.ANY.strictest(Locality.LOCAL_ONLY))
        assertEquals(Locality.TRUSTED_CLUSTER, Locality.TRUSTED_CLUSTER.strictest(Locality.ANY))
    }

    // --- HTTP ----------------------------------------------------------------------------------

    @Test
    fun localOnly_servesLocalModelAndReportsPlacement() = testApplication {
        val (server, tokens) = server(PlacementRuntime(listOf(local)))
        application { server.installInto(this) }
        val response = client.post("/v1/chat/completions") {
            bearerAuth(tokens.generateNewToken().token)
            header(LOCALITY_HEADER, "local_only")
            contentType(ContentType.Application.Json)
            setBody(chatBody("local-model"))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("local", response.headers[PLACEMENT_HEADER])
    }

    @Test
    fun localOnly_refusesRemoteModelWithoutLoadingIt() = testApplication {
        val runtime = PlacementRuntime(listOf(remote))
        val (server, tokens) = server(runtime)
        application { server.installInto(this) }
        val response = client.post("/v1/chat/completions") {
            bearerAuth(tokens.generateNewToken().token)
            header(LOCALITY_HEADER, "local_only")
            contentType(ContentType.Application.Json)
            setBody(chatBody("remote-model"))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"type\":\"locality_violation\""), body)
        assertTrue(body.contains("\"code\":\"placement_remote\""), body)
        assertEquals(0, runtime.loads.get(), "a refused model must not be loaded")
    }

    @Test
    fun localOnly_refusesWhenLoadedAdapterDisagreesWithDescriptor() = testApplication {
        val (server, tokens) = server(PlacementRuntime(listOf(local), adapterIsLocal = false))
        application { server.installInto(this) }
        val response = client.post("/v1/chat/completions") {
            bearerAuth(tokens.generateNewToken().token)
            header(LOCALITY_HEADER, "local_only")
            contentType(ContentType.Application.Json)
            setBody(chatBody("local-model"))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun trustedCluster_allowsClusterButNotRemote() = testApplication {
        val (server, tokens) = server(PlacementRuntime(listOf(cluster, remote)))
        application { server.installInto(this) }
        val token = tokens.generateNewToken().token
        suspend fun ask(model: String) = client.post("/v1/chat/completions") {
            bearerAuth(token)
            header(LOCALITY_HEADER, "trusted_cluster")
            contentType(ContentType.Application.Json)
            setBody(chatBody(model))
        }
        val ok = ask("cluster-model")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("cluster", ok.headers[PLACEMENT_HEADER])
        assertEquals(HttpStatusCode.Forbidden, ask("remote-model").status)
    }

    @Test
    fun localOnly_refusesClusterModel() = testApplication {
        val (server, tokens) = server(PlacementRuntime(listOf(cluster)))
        application { server.installInto(this) }
        val response = client.post("/v1/chat/completions") {
            bearerAuth(tokens.generateNewToken().token)
            header(LOCALITY_HEADER, "local_only")
            contentType(ContentType.Application.Json)
            setBody(chatBody("cluster-model"))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("placement_cluster"))
    }

    @Test
    fun noHeader_isHeldToLocalOnly() = testApplication {
        val runtime = PlacementRuntime(listOf(remote, local))
        val (server, tokens) = server(runtime)
        application { server.installInto(this) }
        val token = tokens.generateNewToken().token
        suspend fun ask(model: String) = client.post("/v1/chat/completions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(chatBody(model))
        }
        assertEquals(HttpStatusCode.Forbidden, ask("remote-model").status)
        val ok = ask("local-model")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("local", ok.headers[PLACEMENT_HEADER])
    }

    @Test
    fun explicitAny_reachesRemoteAndReportsIt() = testApplication {
        val (server, tokens) = server(PlacementRuntime(listOf(remote)))
        application { server.installInto(this) }
        val response = client.post("/v1/chat/completions") {
            bearerAuth(tokens.generateNewToken().token)
            header(LOCALITY_HEADER, "any")
            contentType(ContentType.Application.Json)
            setBody(chatBody("remote-model"))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("remote", response.headers[PLACEMENT_HEADER])
    }

    @Test
    fun hostCanChangeTheDefault() = testApplication {
        val tokens = TokenManager()
        val server = EngineHttpServer(tokens, PlacementRuntime(listOf(remote)), defaultLocality = { Locality.ANY })
        application { server.installInto(this) }
        val response = client.post("/v1/chat/completions") {
            bearerAuth(tokens.generateNewToken().token)
            contentType(ContentType.Application.Json)
            setBody(chatBody("remote-model"))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun unreadableHeader_isBadRequestNotAny() = testApplication {
        val (server, tokens) = server(PlacementRuntime(listOf(remote)))
        application { server.installInto(this) }
        val response = client.post("/v1/chat/completions") {
            bearerAuth(tokens.generateNewToken().token)
            header(LOCALITY_HEADER, "locall_only")
            contentType(ContentType.Application.Json)
            setBody(chatBody("remote-model"))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun engineWideLimit_cannotBeLoosenedByTheRequest() = testApplication {
        val (server, tokens) = server(PlacementRuntime(listOf(remote)), engineLocality = { Locality.LOCAL_ONLY })
        application { server.installInto(this) }
        val response = client.post("/v1/chat/completions") {
            bearerAuth(tokens.generateNewToken().token)
            header(LOCALITY_HEADER, "any")
            contentType(ContentType.Application.Json)
            setBody(chatBody("remote-model"))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun perAppLimit_appliesToThatAppOnly() = testApplication {
        val (server, tokens) = server(
            PlacementRuntime(listOf(remote)),
            subjectLocality = { subject -> if (subject == "org.example.dictator") Locality.LOCAL_ONLY else null },
        )
        application { server.installInto(this) }
        val limited = tokens.generateNewToken(subject = "org.example.dictator").token
        val free = tokens.generateNewToken(subject = "org.example.other").token
        suspend fun ask(token: String) = client.post("/v1/chat/completions") {
            bearerAuth(token)
            header(LOCALITY_HEADER, "any")
            contentType(ContentType.Application.Json)
            setBody(chatBody("remote-model"))
        }
        assertEquals(HttpStatusCode.Forbidden, ask(limited).status)
        assertEquals(HttpStatusCode.OK, ask(free).status)
    }

    @Test
    fun streaming_isRefusedBeforeAnyEventIsSent() = testApplication {
        val (server, tokens) = server(PlacementRuntime(listOf(remote)))
        application { server.installInto(this) }
        val response = client.post("/v1/chat/completions") {
            bearerAuth(tokens.generateNewToken().token)
            header(LOCALITY_HEADER, "local_only")
            contentType(ContentType.Application.Json)
            setBody(testJson.encodeToString(ChatCompletionRequest(
                model = "remote-model", stream = true,
                messages = listOf(ChatMessage(role = "user", content = "Hi")),
            )))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.contentType()?.match(ContentType.Application.Json) == true, "must be a JSON error, not an event stream")
    }

    @Test
    fun embeddingsAndTranscriptionsAreCheckedToo() = testApplication {
        val (server, tokens) = server(PlacementRuntime(listOf(remote)))
        application { server.installInto(this) }
        val token = tokens.generateNewToken().token
        val embeddings = client.post("/v1/embeddings") {
            bearerAuth(token)
            header(LOCALITY_HEADER, "local_only")
            contentType(ContentType.Application.Json)
            setBody(testJson.encodeToString(EmbeddingsRequest(model = "remote-model", input = listOf("a"))))
        }
        assertEquals(HttpStatusCode.Forbidden, embeddings.status)
        val transcription = client.post("/v1/audio/transcriptions") {
            bearerAuth(token)
            header(LOCALITY_HEADER, "local_only")
            contentType(ContentType.Application.Json)
            setBody(testJson.encodeToString(TranscriptionRequest(file = "AAAA", model = "remote-model")))
        }
        assertEquals(HttpStatusCode.Forbidden, transcription.status)
    }

    @Test
    fun decisionsAreCheckedToo() = testApplication {
        val (server, tokens) = server(PlacementRuntime(listOf(remote)))
        application { server.installInto(this) }
        val response = client.post("/v1/systemone") {
            bearerAuth(tokens.generateNewToken().token)
            header(LOCALITY_HEADER, "local_only")
            contentType(ContentType.Application.Json)
            setBody("""{"model":"remote-model","state":"s","questions":{"q":{"type":"noul","instructions":"i"}}}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun modelsListsPlacement() = testApplication {
        val (server, tokens) = server(PlacementRuntime(listOf(local, remote, cluster)))
        application { server.installInto(this) }
        val body = client.get("/v1/models") { bearerAuth(tokens.generateNewToken().token) }.bodyAsText()
        assertTrue(body.contains("\"placement\":\"local\""), body)
        assertTrue(body.contains("\"placement\":\"remote\""), body)
        assertTrue(body.contains("\"placement\":\"cluster\""), body)
    }
}
