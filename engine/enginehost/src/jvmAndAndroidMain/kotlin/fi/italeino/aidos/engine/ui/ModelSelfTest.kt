package fi.italeino.aidos.engine.ui

import dev.aidos.kernel.ModelKind
import fi.italeino.aidos.sdk.client.AidosEngineClient
import fi.italeino.aidos.sdk.client.ChatCompletionRequest
import fi.italeino.aidos.sdk.client.ChatMessage
import fi.italeino.aidos.sdk.client.DecisionQuestionSpec
import fi.italeino.aidos.sdk.client.DecisionRequest
import fi.italeino.aidos.sdk.client.TranscriptionRequest
import java.io.File
import java.util.Base64
import kotlin.time.TimeSource

/**
 * The test a model card offers, chosen by what the model is (RFC-0103, RFC-0022). Embeddings keep
 * their own button and vector view, so [ModelKind.EMBEDDING] maps to nothing here.
 */
enum class ModelTestKind(
    val buttonLabel: String,
    val inputLabel: String,
    val defaultText: String,
    /** The test needs a file on disk (audio or image) in addition to the text. */
    val needsFile: Boolean = false,
    val fileLabel: String = "",
) {
    CHAT("Test Chat", "Prompt", "Say hello in one short sentence."),
    DECISION("Test Decisions", "Text to classify", "poista edellinen lause"),
    TRANSCRIPTION("Test Transcription", "Language (optional, e.g. fi)", "", needsFile = true, fileLabel = "Path to a WAV file"),
    VISION("Test Vision", "Question about the picture", "What is in this picture?", needsFile = true, fileLabel = "Path to an image file"),
}

fun ModelKind.selfTestKind(): ModelTestKind? = when (this) {
    ModelKind.LLM -> ModelTestKind.CHAT
    ModelKind.DECISION -> ModelTestKind.DECISION
    ModelKind.STT -> ModelTestKind.TRANSCRIPTION
    ModelKind.VISION -> ModelTestKind.VISION
    else -> null
}

/** What the card shows after a run: a verdict line and the evidence behind it. */
data class SelfTestOutcome(
    val passed: Boolean,
    val title: String,
    val lines: List<String> = emptyList(),
    /** Wall time of the whole request, filled in by [runModelSelfTest]. */
    val durationMs: Long = 0,
    /** Tokens the model generated, chat only. */
    val generatedTokens: Int? = null,
) {
    /**
     * Speed over the whole request, so a cold model's load time is included and the first run reads
     * slow. Null unless the test generates tokens.
     */
    val tokensPerSecond: Double?
        get() = generatedTokens?.takeIf { it > 0 && durationMs > 0 }?.let { it * 1000.0 / durationMs }
}

/** The Engine `/v1/` endpoint that serves a model of this kind, as the handshake names it; null when none does. */
fun ModelKind.engineEndpoint(): String? = when (this) {
    ModelKind.LLM -> "/v1/chat/completions"
    ModelKind.EMBEDDING -> "/v1/embeddings"
    ModelKind.DECISION -> "/v1/decisions"
    ModelKind.STT -> "/v1/audio/transcriptions"
    else -> null
}

/** Short human name for the kind, for the card's badge. */
fun ModelKind.displayName(): String = when (this) {
    ModelKind.LLM -> "Chat"
    ModelKind.EMBEDDING -> "Embeddings"
    ModelKind.DECISION -> "Decisions"
    ModelKind.STT -> "Speech to text"
    ModelKind.TTS -> "Text to speech"
    ModelKind.VISION -> "Vision"
    ModelKind.OCR -> "OCR"
    ModelKind.RERANKER -> "Reranker"
    ModelKind.TRANSLATION -> "Translation"
}

/**
 * Runs the test for [kind] through the Engine's own `/v1/` endpoint with the Aidos SDK client, the
 * way an external app would, so a pass means the whole path works, not just the adapter.
 * "Passed" means the model produced a well-formed answer; whether the answer is *good* is for
 * the person reading the lines.
 */
suspend fun runModelSelfTest(
    client: AidosEngineClient,
    kind: ModelTestKind,
    modelId: String,
    text: String,
    filePath: String,
): SelfTestOutcome {
    val started = TimeSource.Monotonic.markNow()
    val outcome = when (kind) {
        ModelTestKind.CHAT -> chatTest(client, modelId, text.ifBlank { kind.defaultText })
        ModelTestKind.DECISION -> decisionTest(client, modelId, text.ifBlank { kind.defaultText })
        ModelTestKind.TRANSCRIPTION -> transcriptionTest(client, modelId, text.trim(), filePath.trim())
        ModelTestKind.VISION -> visionTest(client, modelId, text.ifBlank { kind.defaultText }, filePath.trim())
    }
    return outcome.copy(durationMs = started.elapsedNow().inWholeMilliseconds)
}

private suspend fun chatTest(client: AidosEngineClient, modelId: String, prompt: String): SelfTestOutcome {
    val response = client.chatCompletion(
        ChatCompletionRequest(modelId, listOf(ChatMessage("user", prompt)), temperature = 0.2f, max_tokens = 64)
    ) ?: return SelfTestOutcome(false, "Engine returned no answer")
    val reply = response.choices.firstOrNull()?.message?.content.orEmpty().trim()
    return if (reply.isEmpty()) {
        SelfTestOutcome(false, "The model answered with nothing")
    } else {
        SelfTestOutcome(true, "Chat works", listOf(reply), generatedTokens = response.usage.completion_tokens)
    }
}

private suspend fun decisionTest(client: AidosEngineClient, modelId: String, state: String): SelfTestOutcome {
    val response = client.decide(
        DecisionRequest(
            model = modelId,
            state = state,
            questions = linkedMapOf(
                "is_command" to DecisionQuestionSpec.noul("The speaker gives an instruction to the editor instead of dictating content."),
                "kind" to DecisionQuestionSpec.choice(
                    "What is the speaker doing?",
                    mapOf("DICTATION" to "dictating text to be written", "COMMAND" to "giving an editing command"),
                ),
                "urgency" to DecisionQuestionSpec.score(
                    "How urgent is the request?",
                    listOf("not urgent", "somewhat urgent", "very urgent"),
                ),
            ),
        )
    ) ?: return SelfTestOutcome(false, "Engine returned no answer (is this a decision model?)")
    val noul = response.answers["is_command"]?.noul
    val choice = response.answers["kind"]?.choice
    val score = response.answers["urgency"]?.score
    val lines = listOf(
        "Command? noul = ${noul?.format() ?: "missing"}",
        "Kind: ${choice ?: "missing"}",
        "Urgency score: ${score?.format() ?: "missing"}",
    )
    val wellFormed = noul != null && noul in 0.0..1.0 && choice in setOf("DICTATION", "COMMAND") && score != null
    return if (wellFormed) SelfTestOutcome(true, "Decisions work", lines)
    else SelfTestOutcome(false, "Answers were incomplete or out of range", lines)
}

private suspend fun transcriptionTest(client: AidosEngineClient, modelId: String, language: String, path: String): SelfTestOutcome {
    val file = readInput(path, "audio") ?: return SelfTestOutcome(false, inputProblem(path, "a WAV file"))
    val response = client.transcribe(
        TranscriptionRequest(
            file = Base64.getEncoder().encodeToString(file.readBytes()),
            model = modelId,
            language = language.ifBlank { null },
        )
    ) ?: return SelfTestOutcome(false, "Engine returned no transcript (is the file a WAV?)")
    val transcript = response.text.trim()
    return if (transcript.isEmpty()) SelfTestOutcome(false, "Transcript is empty (silence, or the wrong language?)")
    else SelfTestOutcome(true, "Transcription works", listOf(transcript))
}

private suspend fun visionTest(client: AidosEngineClient, modelId: String, question: String, path: String): SelfTestOutcome {
    val image = readInput(path, "image") ?: return SelfTestOutcome(false, inputProblem(path, "an image file"))
    val response = client.chatCompletion(
        ChatCompletionRequest(
            modelId,
            listOf(ChatMessage("user", question, images = listOf(Base64.getEncoder().encodeToString(image.readBytes())))),
            temperature = 0f,
            max_tokens = 96,
        )
    ) ?: return SelfTestOutcome(false, "Engine returned no answer (is the file a PNG or JPEG?)")
    val answer = response.choices.firstOrNull()?.message?.content.orEmpty().trim()
    return if (answer.isEmpty()) SelfTestOutcome(false, "The model answered with nothing")
    else SelfTestOutcome(true, "Vision works", listOf(answer))
}

private fun readInput(path: String, @Suppress("UNUSED_PARAMETER") what: String): File? =
    path.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }

private fun inputProblem(path: String, wanted: String) =
    if (path.isBlank()) "Enter the path to $wanted." else "No such file: $path"

private fun Double.format() = "%.3f".format(this)
