package fi.italeino.aidos.engine.ui

import dev.aidos.kernel.ModelKind
import fi.italeino.aidos.sdk.client.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which test a model card offers per kind, and how each one judges the Engine's answer. */
class ModelSelfTestTest {
    @Test
    fun theCardOffersATestMatchingTheModelKind() {
        assertEquals(ModelTestKind.CHAT, ModelKind.LLM.selfTestKind())
        assertEquals(ModelTestKind.DECISION, ModelKind.DECISION.selfTestKind())
        assertEquals(ModelTestKind.TRANSCRIPTION, ModelKind.STT.selfTestKind())
        assertEquals(ModelTestKind.VISION, ModelKind.VISION.selfTestKind())
        // Embeddings keep their own button; kinds with no test offer none rather than a wrong one.
        assertNull(ModelKind.EMBEDDING.selfTestKind())
        assertNull(ModelKind.TTS.selfTestKind())
    }

    @Test
    fun everyKindHasANameAndServedKindsHaveAnEndpoint() {
        ModelKind.values().forEach { assertTrue(it.displayName().isNotBlank()) }
        assertEquals("/v1/chat/completions", ModelKind.LLM.engineEndpoint())
        assertEquals("/v1/embeddings", ModelKind.EMBEDDING.engineEndpoint())
        assertEquals("/v1/decisions", ModelKind.DECISION.engineEndpoint())
        assertEquals("/v1/audio/transcriptions", ModelKind.STT.engineEndpoint())
        assertNull(ModelKind.TTS.engineEndpoint())
    }

    @Test
    fun speedIsTokensOverTheWholeRequestAndOnlyForChat() = runBlocking {
        val outcome = SelfTestOutcome(true, "ok", generatedTokens = 20, durationMs = 2000)
        assertEquals(10.0, outcome.tokensPerSecond)
        assertNull(SelfTestOutcome(true, "ok", durationMs = 2000).tokensPerSecond)
        assertNull(SelfTestOutcome(true, "ok", generatedTokens = 0, durationMs = 2000).tokensPerSecond)
        assertNull(SelfTestOutcome(true, "ok", generatedTokens = 5, durationMs = 0).tokensPerSecond)
        // The runner records generated tokens and a duration for chat, none for decisions.
        val chat = run(ModelTestKind.CHAT, FakeClient(chat = "Hi"))
        assertEquals(2, chat.generatedTokens)
        assertNull(run(ModelTestKind.DECISION, FakeClient(decision = null)).generatedTokens)
    }

    @Test
    fun chatPassesOnAReplyAndFailsOnSilenceOrNoAnswer() = runBlocking {
        val ok = run(ModelTestKind.CHAT, FakeClient(chat = "Hello there."))
        assertTrue(ok.passed); assertTrue("Hello there." in ok.lines)
        assertFalse(run(ModelTestKind.CHAT, FakeClient(chat = "  ")).passed)
        assertFalse(run(ModelTestKind.CHAT, FakeClient(chat = null)).passed)
    }

    @Test
    fun decisionsPassOnlyWhenAllThreeAnswerTypesAreWellFormed() = runBlocking {
        val good = FakeClient(decision = mapOf(
            "is_command" to DecisionAnswerWire("noul", noul = 0.9),
            "kind" to DecisionAnswerWire("choice", choice = "COMMAND"),
            "urgency" to DecisionAnswerWire("score", score = 1.2),
        ))
        assertTrue(run(ModelTestKind.DECISION, good).passed)
        val badChoice = FakeClient(decision = good.decision!! + ("kind" to DecisionAnswerWire("choice", choice = "OTHER")))
        assertFalse(run(ModelTestKind.DECISION, badChoice).passed)
        val outOfRange = FakeClient(decision = good.decision + ("is_command" to DecisionAnswerWire("noul", noul = 1.5)))
        assertFalse(run(ModelTestKind.DECISION, outOfRange).passed)
        assertFalse(run(ModelTestKind.DECISION, FakeClient(decision = null)).passed)
    }

    @Test
    fun transcriptionNeedsAnExistingFileAndANonEmptyTranscript() = runBlocking {
        val wav = File.createTempFile("selftest", ".wav").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
        val ok = run(ModelTestKind.TRANSCRIPTION, FakeClient(transcript = "hello world"), file = wav.path, text = "en")
        assertTrue(ok.passed); assertEquals(listOf("hello world"), ok.lines)
        assertFalse(run(ModelTestKind.TRANSCRIPTION, FakeClient(transcript = "hi"), file = "").passed)
        assertTrue("No such file" in run(ModelTestKind.TRANSCRIPTION, FakeClient(transcript = "hi"), file = "/nope.wav").title)
        assertFalse(run(ModelTestKind.TRANSCRIPTION, FakeClient(transcript = ""), file = wav.path).passed)
    }

    @Test
    fun visionSaysItIsUnsupportedUntilTheEngineCanDoIt() = runBlocking {
        val png = File.createTempFile("selftest", ".png").apply { writeBytes(byteArrayOf(1)); deleteOnExit() }
        val outcome = run(ModelTestKind.VISION, FakeClient(), file = png.path)
        assertFalse(outcome.passed)
        assertTrue("not supported" in outcome.title)
        assertTrue("Enter the path" in run(ModelTestKind.VISION, FakeClient(), file = "").title)
    }

    private suspend fun run(kind: ModelTestKind, client: AidosEngineClient, text: String = "", file: String = "") =
        runModelSelfTest(client, kind, "m", text, file)

    private class FakeClient(
        val chat: String? = null,
        val decision: Map<String, DecisionAnswerWire>? = null,
        val transcript: String? = null,
    ) : AidosEngineClient {
        override suspend fun initialize() = true
        override fun isAvailable() = true
        override fun availability() = EngineAvailability.Available
        override fun apiVersion() = 1
        override suspend fun capabilities() = EngineCapabilities(emptyList(), emptyList())
        override suspend fun request(endpoint: String, method: String, body: String?): String? = null
        override suspend fun supportsEndpoint(endpoint: String) = true
        override suspend fun chatCompletion(request: ChatCompletionRequest) = chat?.let {
            ChatCompletionResponse("id", 0, request.model, listOf(ChatChoice(0, ChatMessage("assistant", it))), TokenUsage(1, 2, 3))
        }
        override fun streamChatCompletion(request: ChatCompletionRequest): Flow<ChatCompletionChunk> = emptyFlow()
        override suspend fun embeddings(request: EmbeddingsRequest): EmbeddingsResponse? = null
        override suspend fun transcribe(request: TranscriptionRequest) = transcript?.let { TranscriptionResponse(it) }
        override suspend fun decide(request: DecisionRequest) = decision?.let { DecisionResponse(request.model, it) }
        override fun close() = Unit
    }
}
