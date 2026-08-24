package dev.aidos.modelruntime

import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelStreamEvent
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.ToolChoice
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.Turn
import de.kherud.llama.InferenceParameters
import de.kherud.llama.LlamaModel
import de.kherud.llama.ModelParameters
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Smoke tests against the checked-in ROT13 GGUF fixture (`models/rot13-gguf/`).
 *
 * The fixture is a hand-built llama-architecture model whose correct output is
 * known exactly for all 256 byte values, which is what lets these assert on real
 * values instead of "something came back". See the fixture's own README.
 *
 * These lock in two GGUF parsing bugs that made [GgufLoader] unusable against
 * real models, both found by pointing it at this fixture:
 *
 *  1. Array fields were read as (count, type) instead of the spec's (type, count),
 *     so any file containing an array was rejected outright — which is every
 *     model with a tokenizer.
 *  2. The context window was read from `general.context_length`, a key GGUF does
 *     not define. The real key is namespaced per architecture, so every model
 *     silently reported the 4096 fallback.
 */
class GgufRot13FixtureTest {

    private fun fixture(): File? {
        // Walk up from the working directory: the engine is its own Gradle build,
        // so the fixture sits outside this project's tree.
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "models/rot13-gguf/rot13.gguf")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }

    private fun requireFixture(): File =
        fixture() ?: error("ROT13 GGUF fixture not found; expected models/rot13-gguf/rot13.gguf")

    @Test
    fun `metadata parses, including arrays and the namespaced context length`() {
        val metadata = GgufLoader.loadMetadata(requireFixture())

        assertNotNull(metadata, "GgufLoader rejected a valid GGUF file")
        assertEquals("llama", metadata.architecture)
        assertEquals("rot13", metadata.modelName)
        assertEquals(3, metadata.version)
        assertEquals(12L, metadata.tensorCount)
        assertEquals("F32", metadata.quantization)
        assertEquals(0, metadata.fileType)

        // The fixture declares llama.context_length = 512. Reading 4096 here means
        // the fallback was used and the namespaced key was missed again.
        assertEquals(512, metadata.contextWindow, "context window came from the fallback, not the file")

        // token_embd + output are 256x256 each, and the block tensors bring the
        // total to 590,592 — a real count, not a zero from skipped descriptors.
        assertEquals(590_592L, metadata.parameterCount)

        // A completion model with no roles. This is what selects the raw prompt
        // path in LlamaCppAdapter.
        assertEquals(null, metadata.chatTemplate)
    }

    @Test
    fun `non-GGUF input is still rejected`() {
        val notGguf = File.createTempFile("not-a-model", ".gguf").apply {
            deleteOnExit()
            writeBytes(ByteArray(64) { 0x7F })
        }
        assertEquals(null, GgufLoader.loadMetadata(notGguf))
    }

    /**
     * End-to-end through the same llama.cpp binding the engine pins, proving the
     * fixture is loadable and runnable by this stack — not merely parseable.
     */
    @Test
    fun `llama_cpp binding loads the fixture and predicts ROT13`() {
        val model = try {
            LlamaModel(
                requireFixture().absolutePath,
                ModelParameters().setNCtx(512).setNThreads(2).setNGpuLayers(0)
                    .setLogitsAll(false).setUseMmap(true).setUseMLock(false),
            )
        } catch (e: UnsatisfiedLinkError) {
            // No native binary for this platform; nothing to assert about inference.
            println("skipping inference check: ${e.message}")
            return
        }

        model.use {
            // The fixture's vocabulary is one token per byte, with id == byte.
            assertTrue(
                it.encode("Hello").toList() == listOf(72, 101, 108, 108, 111),
                "tokenizer is not 1 token per byte: ${it.encode("Hello").toList()}",
            )

            // Greedy-equivalent: the winning logit leads by 16, so the fixture is
            // effectively deterministic even under the binding's default sampling.
            val first = it.generate("Hello", InferenceParameters()).first()
            assertEquals("b", first.text, "expected rot13('o') = 'b'")
        }
    }

    /**
     * The whole ModelAdapter path, which is what the engine actually calls.
     *
     * This is the check that the prompt reaches the model unaltered. The adapter
     * used to frame every request as `User: ...\nAssistant:`; against a model with
     * no chat template that appended a colon the caller never sent, and since
     * rot13(':') is ':', the fixture answered ':' forever — a green-looking
     * liveness check that proved nothing about the prompt getting through.
     */
    @Test
    fun `adapter passes the prompt through unaltered for a model with no chat template`() = runTest {
        val file = requireFixture()
        val metadata = assertNotNull(GgufLoader.loadMetadata(file))

        val adapter = try {
            LlamaCppAdapter("rot13", file, metadata, contextSize = 512, threads = 2)
        } catch (e: UnsatisfiedLinkError) {
            println("skipping adapter check: ${e.message}")
            return@runTest
        }

        try {
            val response = adapter.invoke(
                ModelRequest(
                    messages = listOf(
                        Turn.User(
                            content = listOf(ContentBlock.Text("Hello")),
                            trustLevel = TrustLevel.TRUSTED,
                        )
                    ),
                    tools = emptyList(),
                    toolChoice = ToolChoice.Auto,
                    maxOutputTokens = 1,
                )
            ).getOrThrow()

            val text = response.outputs.filterIsInstance<TextOutput>().single().text
            assertEquals("b", text, "expected rot13 of the prompt's last byte 'o', not a wrapper artifact")
        } finally {
            adapter.close()
        }
    }

    /**
     * [LlamaCppAdapter.invoke] is now implemented in terms of [LlamaCppAdapter.invokeStreaming]
     * (RFC-0021 "Streaming"; Dictator plan S4) — this exercises that streaming path directly,
     * against the same real llama.cpp binding and fixture the previous test proves `invoke()`
     * against, so the refactor is verified on the actual token-emitting path, not only through
     * the non-streaming wrapper.
     */
    @Test
    fun `invokeStreaming emits a real delta before the terminal Done event`() = runTest {
        val file = requireFixture()
        val metadata = assertNotNull(GgufLoader.loadMetadata(file))

        val adapter = try {
            LlamaCppAdapter("rot13", file, metadata, contextSize = 512, threads = 2)
        } catch (e: UnsatisfiedLinkError) {
            println("skipping streaming adapter check: ${e.message}")
            return@runTest
        }

        try {
            val events = adapter.invokeStreaming(
                ModelRequest(
                    messages = listOf(
                        Turn.User(
                            content = listOf(ContentBlock.Text("Hello")),
                            trustLevel = TrustLevel.TRUSTED,
                        )
                    ),
                    tools = emptyList(),
                    toolChoice = ToolChoice.Auto,
                    maxOutputTokens = 1,
                )
            ).toList()

            val delta = assertIs<ModelStreamEvent.Delta>(events.first(), "expected a real token delta first")
            assertEquals("b", delta.text, "expected rot13 of the prompt's last byte 'o'")

            val done = assertIs<ModelStreamEvent.Done>(events.last(), "stream must end with the final response")
            val text = done.response.outputs.filterIsInstance<TextOutput>().single().text
            assertEquals("b", text, "Done's response must match what was streamed, not a wrapper artifact")
        } finally {
            adapter.close()
        }
    }
}
