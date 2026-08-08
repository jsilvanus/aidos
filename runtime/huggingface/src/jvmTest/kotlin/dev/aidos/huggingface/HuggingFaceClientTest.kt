package dev.aidos.huggingface

import kotlin.test.Test
import kotlin.test.assertEquals
import dev.aidos.kernel.ModelKind

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
