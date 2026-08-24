package fi.italeino.aidos.sdk

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AidosEngineClientTest {

    @Test
    fun parseCapabilitiesJson_decodesEngineWireShape() {
        // Mirrors fi.italeino.aidos.engine.http.Capabilities/ModelInfo's actual JSON shape,
        // including fields (object, owned_by) the SDK doesn't model — ignoreUnknownKeys must
        // tolerate those (RFC-0103's independent-versioning rationale for the Bundle switch).
        val json = """
            {"endpoints":["chat.completions","embeddings"],"models":[
                {"id":"llama-3-8b","object":"model","owned_by":"aidos-local","kind":"llm","context_window":4096,"quantization":"q4_k_m"}
            ]}
        """.trimIndent()

        val result = parseCapabilitiesJson(json)

        assertEquals(listOf("chat.completions", "embeddings"), result.endpoints)
        assertEquals(1, result.models.size)
        assertEquals("llama-3-8b", result.models[0].id)
        assertEquals("llm", result.models[0].kind)
        assertEquals(4096, result.models[0].context_window)
        assertEquals("q4_k_m", result.models[0].quantization)
    }

    @Test
    fun initialize_withSuccessfulHandshake_reportsAvailable() = runTest {
        val response = HandshakeResponse(
            port = 8080,
            token = "test-token",
            apiVersion = 1,
            capabilities = CapabilitiesResponse(
                endpoints = listOf("chat.completions"),
                models = listOf(ModelInfoResponse(id = "m1", kind = "llm"))
            )
        )
        val client = EngineClientImpl(HandshakePerformer { response })

        assertTrue(client.initialize())
        assertTrue(client.isAvailable())
        assertEquals(1, client.apiVersion())

        val caps = client.capabilities()
        assertEquals(listOf("chat.completions"), caps.endpoints)
        assertEquals("m1", caps.models.single().id)
    }

    @Test
    fun initialize_whenPerformerReturnsNull_reportsUnavailable() = runTest {
        val client = EngineClientImpl(HandshakePerformer { null })

        assertFalse(client.initialize())
        assertFalse(client.isAvailable())
    }

    @Test
    fun initialize_whenPerformerThrows_reportsUnavailableInsteadOfPropagating() = runTest {
        val client = EngineClientImpl(HandshakePerformer { throw IllegalStateException("bind failed") })

        assertFalse(client.initialize())
        assertFalse(client.isAvailable())
    }

    @Test
    fun defaultFactoryClient_neverFindsEngine() = runTest {
        // No Binder on the jvm() target this factory also builds for (see its KDoc).
        val client = AidosEngineClientFactory.createClient()

        assertFalse(client.initialize())
        assertNull(client.request("chat/completions"))
    }
}
