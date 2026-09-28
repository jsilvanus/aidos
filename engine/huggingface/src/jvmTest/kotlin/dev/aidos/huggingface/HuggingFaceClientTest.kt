package dev.aidos.huggingface

import dev.aidos.kernel.BasicResourceHandle
import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.EffectBroker
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.Preview
import dev.aidos.kernel.Tool
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolCallResult
import dev.aidos.kernel.ToolDescriptor
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrustLevel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HuggingFaceClientTest {

    @Test
    fun testInferModelKindFromTags() {
        // Create a dummy client just for testing inferModelKind which is a pure function
        // The function doesn't depend on broker, so we can test the logic directly
        
        assertEquals(
            ModelKind.LLM,
            HuggingFaceClient.inferModelKind(
                tags = listOf("text-generation", "llm", "instruct"),
                pipeline = null,
            )
        )

        assertEquals(
            ModelKind.EMBEDDING,
            HuggingFaceClient.inferModelKind(
                tags = listOf("embedding", "sentence-transformers"),
                pipeline = null,
            )
        )

        assertEquals(
            ModelKind.STT,
            HuggingFaceClient.inferModelKind(
                tags = listOf("speech-recognition"),
                pipeline = "automatic-speech-recognition",
            )
        )

        assertEquals(
            ModelKind.VISION,
            HuggingFaceClient.inferModelKind(
                tags = listOf("image-to-text", "multimodal"),
                pipeline = null,
            )
        )
    }

    @Test
    fun search_sendsEncodedQueryAndOneFilterParamPerTag() = runBlocking {
        val broker = RecordingBroker("HTTP 200\n\n[]")
        client(broker).search(query = "qwen coder & more", filter = "gguf,text-generation").getOrThrow()

        val url = broker.urls.single()
        assertTrue("search=qwen%20coder%20%26%20more" in url, url)
        assertTrue("filter=gguf&" in url || url.endsWith("filter=gguf"), url)
        assertTrue("filter=text-generation" in url, url)
        assertTrue("gguf,text-generation" !in url, url)
    }

    @Test
    fun search_failsOnNonSuccessStatusInsteadOfReturningNoModels() = runBlocking {
        val broker = RecordingBroker("HTTP 429\n\n{\"error\":\"rate limited\"}")
        val result = client(broker).search(query = null, filter = "gguf")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("HTTP 429"))
    }

    @Test
    fun getModel_readsLicenseFromTags() = runBlocking {
        val broker = RecordingBroker(
            "HTTP 200\n\n{\"id\":\"jsilvanus/echo-gguf\",\"tags\":[\"gguf\",\"license:eupl-1.2\"]," +
                "\"siblings\":[{\"rfilename\":\"echo.gguf\"}]}"
        )
        val model = client(broker).getModel("jsilvanus/echo-gguf").getOrThrow()

        assertEquals("eupl-1.2", model.license)
        assertEquals(listOf("echo"), model.quantizations.map { it.name })
    }

    private fun client(broker: EffectBroker) =
        HuggingFaceClient(broker, BasicResourceHandle(CapabilityId("huggingface")))

    /** Answers every http:get with [body] and records the requested URLs. */
    private class RecordingBroker(private val body: String) : EffectBroker {
        val urls = mutableListOf<String>()

        override fun register(tool: Tool) = Unit

        override fun descriptorsFor(
            subjectId: String,
            profile: PlatformProfile,
            networkAvailable: Boolean,
        ): List<ToolDescriptor> = emptyList()

        override suspend fun invoke(subjectId: String, call: ToolCall, runTaint: TrustLevel): ToolCallResult {
            urls += call.arguments["url"]!!.jsonPrimitive.content
            return ToolCallResult(
                callId = call.callId,
                outcome = ToolOutcome.Ok,
                content = listOf(ContentBlock.Text(body)),
                trustLevel = TrustLevel.UNTRUSTED,
            )
        }

        override suspend fun preview(subjectId: String, call: ToolCall): Result<Preview> =
            Result.failure(UnsupportedOperationException())

        override suspend fun cancel(callId: String) = Unit
    }

    @Test
    fun testDefaultsToLLMWhenKindUnclear() {
        assertEquals(
            ModelKind.LLM,
            HuggingFaceClient.inferModelKind(
                tags = listOf("unknown", "tags"),
                pipeline = null,
            )
        )
    }
}
