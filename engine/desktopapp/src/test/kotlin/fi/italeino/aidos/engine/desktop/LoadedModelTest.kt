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
 * | vision     | `AIDOS_VISION_MODEL_DIR` (SmolVLM package); optional `AIDOS_TEST_VISION_IMAGE`/`_QUESTION`/`_EXPECTED` |
 * | stt        | `AIDOS_STT_MODEL_DIR` (Whisper package); optional `AIDOS_TEST_STT_AUDIO`/`_EXPECTED`/`_LANGUAGE` |
 *
 * Vision runs SmolVLM-256M on ONNX Runtime (the llama.cpp Java binding is built without
 * multimodal support); images travel in the chat message's `images` field. STT needs the Whisper backend from aidos#72 in the
 * desktop host; without it the request falls through to the chat model and the assertion fails.
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
    fun visionAnswersAboutAPicture() = runBlocking<Unit> {
        // A SmolVLM package directory (`node scripts/vision/fetch-smolvlm.mjs <dir>`). The picture is
        // generated unless AIDOS_TEST_VISION_IMAGE brings one; the question and the word the answer
        // must contain default to a red rectangle on white.
        val packageDir = optionalFile("AIDOS_VISION_MODEL_DIR")
        val image = System.getenv("AIDOS_TEST_VISION_IMAGE")?.takeIf { it.isNotBlank() }?.let(::File)?.readBytes()
            ?: redRectanglePng()
        val question = System.getenv("AIDOS_TEST_VISION_QUESTION")?.takeIf { it.isNotBlank() } ?: "What color is the rectangle?"
        val expected = System.getenv("AIDOS_TEST_VISION_EXPECTED")?.takeIf { it.isNotBlank() } ?: "red"

        val target = File(modelsDir, "smolvlm").apply { mkdirs() }
        packageDir.listFiles { f -> f.isFile }!!.forEach { link(it, File(target, it.name)) }
        val response = assertNotNull(
            client!!.chatCompletion(
                ChatCompletionRequest(
                    model = "smolvlm",
                    messages = listOf(
                        ChatMessage("user", question, images = listOf(Base64.getEncoder().encodeToString(image)))
                    ),
                    max_tokens = 32,
                )
            ),
            "vision chat returned no response",
        )
        val answer = response.choices.first().message.content.orEmpty()
        println("vision: $answer")
        assertTrue(expected.lowercase() in answer.lowercase(), "answer \"$answer\" does not mention \"$expected\"")
    }

    /** 320x240 white picture with a red rectangle, as PNG. */
    private fun redRectanglePng(): ByteArray {
        val img = java.awt.image.BufferedImage(320, 240, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color.WHITE; g.fillRect(0, 0, 320, 240)
        g.color = java.awt.Color(220, 20, 20); g.fillRect(60, 40, 200, 160)
        g.dispose()
        return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "png", it) }.toByteArray()
    }

    // --- speech to text ----------------------------------------------------------------------

    @Test
    fun sttTranscribesAudio() = runBlocking<Unit> {
        // Same variable as the stt module's own tests: a Whisper ONNX package directory
        // (`node scripts/stt/fetch-whisper.mjs <dir>`). Finnish needs whisper-small or larger.
        val packageDir = optionalFile("AIDOS_STT_MODEL_DIR")
        // Audio and the words it must contain: bring your own, or fall back to the public-domain
        // JFK clip bundled with the stt module.
        val audio = System.getenv("AIDOS_TEST_STT_AUDIO")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: repoFileOrNull("engine/stt/src/jvmTest/resources/jfk.wav")
        assumeTrue(audio != null, "no audio: set AIDOS_TEST_STT_AUDIO or build with the stt module's jfk.wav")
        val expected = System.getenv("AIDOS_TEST_STT_EXPECTED")?.takeIf { it.isNotBlank() } ?: "country"
        val language = System.getenv("AIDOS_TEST_STT_LANGUAGE")?.takeIf { it.isNotBlank() } ?: "en"

        packageDir.copyRecursively(File(modelsDir, "whisper"))
        val response = assertNotNull(
            client!!.transcribe(
                TranscriptionRequest(
                    file = Base64.getEncoder().encodeToString(audio!!.readBytes()),
                    model = "whisper",
                    language = language,
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
    private fun repoFileOrNull(relative: String): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        return null
    }

    private fun repoFile(relative: String): File =
        repoFileOrNull(relative) ?: error("$relative not found above ${System.getProperty("user.dir")}")

    private fun cosine(a: List<Float>, b: List<Float>): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return dot / (sqrt(na) * sqrt(nb))
    }
}
