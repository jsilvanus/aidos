package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.sdk.client.AidosEngineClient
import fi.italeino.aidos.sdk.client.ChatCompletionRequest
import fi.italeino.aidos.sdk.client.ChatMessage
import fi.italeino.aidos.sdk.client.DecisionQuestionSpec
import fi.italeino.aidos.sdk.client.DecisionRequest
import fi.italeino.aidos.sdk.client.EmbeddingsRequest
import fi.italeino.aidos.sdk.client.TranscriptionRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Loaded-model tests, one per modality, driven the way an external app drives the Engine: through
 * the real runtime and loopback HTTP server, over the Aidos SDK client (RFC-0022, RFC-0103).
 *
 * Fixtures versus real models. Chat runs everywhere against the checked-in `models/echo-gguf`
 * fixture, whose output is known exactly. The other modalities need a real model, which is far
 * too big to check in, so each reads its location from an environment variable and is skipped
 * (reported as skipped, not passed) when it is unset:
 *
 * | Modality   | Variable(s)                                                            |
 * |------------|------------------------------------------------------------------------|
 * | embeddings | `AIDOS_TEST_EMBED_GGUF` (e.g. nomic-embed-text-v1.5 GGUF)              |
 * | decisions  | `AIDOS_LAYA_DIR` + `AIDOS_LAYA_ONNX` (see OnnxDecisionBackendTest)     |
 * | vision     | `AIDOS_TEST_VISION_GGUF`, `AIDOS_TEST_VISION_IMAGE`, `_EXPECTED`       |
 * | stt        | `AIDOS_TEST_STT_MODEL`, `AIDOS_TEST_STT_AUDIO`, `AIDOS_TEST_STT_EXPECTED` |
 *
 * Vision and STT have no Engine backend yet (the llama.cpp adapters render images and audio as
 * placeholder text), so their tests are written first and will start failing, rather than
 * skipping, the day someone sets the variables; that is the cue to wire the backend.
 */
class LoadedModelTest {
    private val root = Files.createTempDirectory("aidos-loaded-model").toFile()
    private val modelsDir = root.resolve("models").apply { mkdirs() }
    private val host = DesktopEngineHost(modelsDir = modelsDir, stateDir = root)
    private var client: AidosEngineClient? = null

    @BeforeTest
    fun startEngine() = runBlocking {
        host.start()
        assertEquals(fi.italeino.aidos.engine.EngineState.READY, host.state.value, "start failed: ${host.lastError.value}")
        client = assertNotNull(host.createEngineClient(), "SDK handshake with the in-process Engine failed")
    }

    @AfterTest
    fun cleanUp() {
        client?.close()
        runBlocking { host.close() }
        root.deleteRecursively()
    }

    // --- chat --------------------------------------------------------------------------------

    @Test
    fun chatAnswersFromALoadedModel() = runBlocking {
        // The echo fixture is a real llama-architecture model whose greedy output is its last
        // input byte, repeated. Any other text means the runtime, template or sampler is wrong.
        install(repoFile("models/echo-gguf/echo.gguf"), "echo.gguf")
        val response = assertNotNull(
            client!!.chatCompletion(
                ChatCompletionRequest(
                    model = "echo",
                    messages = listOf(ChatMessage("user", "abcx")),
                    temperature = 0f,
                    max_tokens = 4,
                )
            ),
            "chat completion returned no response",
        )
        val answer = response.choices.single().message.content
        assertEquals("xxxx", answer)
        assertTrue(response.usage.completion_tokens > 0, "usage should report generated tokens")
    }

    @Test
    fun chatWithAnUninstalledModelFailsCleanly() = runBlocking {
        val response = client!!.chatCompletion(
            ChatCompletionRequest("not-installed", listOf(ChatMessage("user", "hi")), max_tokens = 4)
        )
        assertEquals(null, response, "an unknown model must not produce an answer")
    }

    // --- embeddings --------------------------------------------------------------------------

    @Test
    fun embeddingsReturnRealVectors() = runBlocking {
        val gguf = optionalFile("AIDOS_TEST_EMBED_GGUF")
        install(gguf, "test-embed.gguf")
        val texts = listOf(
            "The congregation gathers for the Sunday service.",
            "Parishioners come together for worship on Sunday.",
            "Quarterly tax filing deadline for small businesses.",
        )
        val response = assertNotNull(
            client!!.embeddings(EmbeddingsRequest("test-embed", texts)),
            "embeddings returned no response",
        )
        assertEquals(texts.size, response.data.size)
        assertEquals(texts.indices.toList(), response.data.map { it.index }, "results must keep input order")

        val vectors = response.data.map { it.embedding }
        val dims = vectors.first().size
        assertTrue(dims >= 32, "implausibly small vector: $dims dimensions")
        vectors.forEach { v ->
            assertEquals(dims, v.size, "all vectors share one dimension")
            assertTrue(v.all { it.isFinite() }, "vector contains NaN or infinity")
            assertTrue(v.any { it != 0f }, "all-zero vector is the old placeholder")
        }

        // Meaning, not just shape: paraphrases must sit closer than an unrelated sentence.
        val related = cosine(vectors[0], vectors[1])
        val unrelated = cosine(vectors[0], vectors[2])
        assertTrue(related > unrelated, "paraphrase similarity $related should beat unrelated $unrelated")

        // The same text twice gives the same vector, so callers can cache.
        val again = client!!.embeddings(EmbeddingsRequest("test-embed", texts.take(1)))!!.data.single().embedding
        assertTrue(cosine(vectors[0], again) > 0.999, "embedding is not deterministic")
    }

    // --- decisions ---------------------------------------------------------------------------

    @Test
    fun decisionsAnswerNoulChoiceAndScore() = runBlocking<Unit> {
        val layaDir = optionalFile("AIDOS_LAYA_DIR")
        val onnx = optionalFile("AIDOS_LAYA_ONNX")
        val pkg = modelsDir.resolve("laya-multilingual").apply { mkdirs() }
        link(onnx, File(pkg, "model.onnx"))
        File(layaDir, "tokenizer/tokenizer.json").copyTo(File(pkg, "tokenizer.json"))
        File(layaDir, "rl_agent_config.json").copyTo(File(pkg, "rl_agent_config.json"))
        assertTrue(
            host.modelRuntime!!.load("laya-multilingual").isSuccess,
            "Laya package did not load as a decision model",
        )

        val response = assertNotNull(
            client!!.decide(
                DecisionRequest(
                    model = "laya-multilingual",
                    state = "poista edellinen lause",
                    questions = mapOf(
                        "is_command" to DecisionQuestionSpec.noul(
                            "The speaker gives an instruction to the editor instead of dictating content."
                        ),
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
            ),
            "decision request returned no response",
        )
        // Shape is asserted hard. Which way a base Laya leans is not: PIPELINE notes it is too weak
        // for routing without fine-tuning, so asserting the verdict would make this test flaky for
        // the wrong reason. The answers are printed so a human can eyeball them.
        println("decisions: ${response.answers}")
        assertEquals(listOf("is_command", "kind", "urgency"), response.answers.keys.toList())
        val noul = response.answers.getValue("is_command")
        assertEquals("noul", noul.type)
        assertTrue(noul.noul!! in 0.0..1.0, "noul must be a probability, was ${noul.noul}")
        val choice = response.answers.getValue("kind")
        assertTrue(choice.choice in setOf("DICTATION", "COMMAND"), "choice must be one of the given labels, was ${choice.choice}")
        val score = response.answers.getValue("urgency")
        assertEquals("score", score.type)
        assertNotNull(score.score, "score answer carries a score")
    }

    // --- vision ------------------------------------------------------------------------------

    @Test
    fun visionAnswersAboutAPicture() {
        optionalFile("AIDOS_TEST_VISION_GGUF")
        optionalFile("AIDOS_TEST_VISION_IMAGE")
        // Not wirable yet: the SDK's ChatMessage carries only a string, and the JVM/Android
        // adapters turn ContentBlock.Image into "[Image: mime]". Fail loudly once someone supplies
        // a model so the missing backend is noticed instead of silently untested.
        error(
            "Vision has no Engine backend yet: add image content parts to /v1/chat/completions and " +
                "a multimodal (mmproj) path to LlamaCppAdapter, then send AIDOS_TEST_VISION_IMAGE " +
                "with a question and assert the answer contains AIDOS_TEST_VISION_EXPECTED."
        )
    }

    // --- speech to text ----------------------------------------------------------------------

    @Test
    fun sttTranscribesAudio() = runBlocking {
        val model = System.getenv("AIDOS_TEST_STT_MODEL")
        assumeTrue(!model.isNullOrBlank(), "AIDOS_TEST_STT_MODEL not set")
        val audio = optionalFile("AIDOS_TEST_STT_AUDIO")
        val expected = System.getenv("AIDOS_TEST_STT_EXPECTED")
        assumeTrue(!expected.isNullOrBlank(), "AIDOS_TEST_STT_EXPECTED not set")

        val response = assertNotNull(
            client!!.transcribe(
                TranscriptionRequest(
                    file = Base64.getEncoder().encodeToString(audio.readBytes()),
                    model = model,
                )
            ),
            "transcription returned no response",
        )
        println("stt: ${response.text}")
        // Word-level containment, case-insensitive: transcribers differ on punctuation and casing.
        val said = response.text.lowercase()
        val missing = expected.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() && it !in said }
        assertTrue(missing.isEmpty(), "transcript \"${response.text}\" is missing: $missing")
    }

    // --- helpers -----------------------------------------------------------------------------

    private fun install(source: File, name: String) = link(source, File(modelsDir, name))

    /** Hard link where possible: real models are gigabytes. */
    private fun link(from: File, to: File) {
        try {
            Files.createLink(to.toPath(), from.toPath())
        } catch (e: Exception) {
            from.copyTo(to, overwrite = true)
        }
    }

    /** The file named by [variable], or the test is skipped. */
    private fun optionalFile(variable: String): File {
        val path = System.getenv(variable)
        assumeTrue(!path.isNullOrBlank(), "$variable not set")
        val file = File(path!!)
        assertTrue(file.exists(), "$variable points at a missing path: $path")
        return file
    }

    /** Walk up from the working directory: the engine is its own Gradle build. */
    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("$relative not found above ${System.getProperty("user.dir")}")
    }

    private fun cosine(a: List<Float>, b: List<Float>): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return dot / (sqrt(na) * sqrt(nb))
    }
}
