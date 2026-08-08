package dev.aidos.huggingface

import kotlin.test.Test
import kotlin.test.assertEquals
import dev.aidos.kernel.ModelKind

class HuggingFaceClientTest {

    private val client = HuggingFaceClient()

    @Test
    fun testInferModelKindFromTags() {
        assertEquals(
            ModelKind.LLM,
            client.inferModelKind(
                tags = listOf("text-generation", "llm", "instruct"),
                pipeline = null,
            )
        )

        assertEquals(
            ModelKind.EMBEDDING,
            client.inferModelKind(
                tags = listOf("embedding", "sentence-transformers"),
                pipeline = null,
            )
        )

        assertEquals(
            ModelKind.STT,
            client.inferModelKind(
                tags = listOf("speech-recognition"),
                pipeline = "automatic-speech-recognition",
            )
        )

        assertEquals(
            ModelKind.VISION,
            client.inferModelKind(
                tags = listOf("image-to-text", "multimodal"),
                pipeline = null,
            )
        )
    }

    @Test
    fun testDefaultsToLLMWhenKindUnclear() {
        assertEquals(
            ModelKind.LLM,
            client.inferModelKind(
                tags = listOf("unknown", "tags"),
                pipeline = null,
            )
        )
    }
}
