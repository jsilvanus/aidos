package dev.aidos.vision

import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.Turn

/** The few numbers of a SmolVLM package the pipeline needs, read from its own config files. */
data class SmolVlmConfig(
    val hiddenSize: Int,
    val imageTokenId: Int,
    /** Image tokens per tile, `image_seq_len` in `processor_config.json`. */
    val imageSeqLen: Int,
    val eosTokenIds: Set<Int>,
    val contextWindow: Int,
)

/** One increment of a running generation. */
sealed interface VisionStep {
    data class Text(val delta: String) : VisionStep
    data class Finished(val promptTokens: Int, val newTokens: Int, val reason: FinishReason) : VisionStep
}

enum class FinishReason { END_OF_TURN, MAX_TOKENS, STOP_SEQUENCE, CONTEXT_FULL }

/**
 * SmolVLM-256M-Instruct (Idefics3 architecture): chat turns with images in, text out, greedy decoding.
 *
 * The chat template is Idefics3's: `<|im_start|>` once, then `Role: text<end_of_utterance>\n` per
 * turn and a trailing `Assistant:`. An image becomes `<fake_token_around_image><global-img>` + N x
 * `<image>` + `<fake_token_around_image>`, and the N `<image>` embeddings are replaced by the vision
 * encoder's features in order. Nothing is sampled: the same picture and question give the same
 * answer, which is what a test (and a visual question) wants.
 */
class SmolVlmModel(
    val modelId: String,
    private val tokenizer: ByteLevelBpeTokenizer,
    private val runner: VisionRunner,
    private val images: ImageDecoder,
    val config: SmolVlmConfig,
) : AutoCloseable {

    init {
        require(runner.hiddenSize == config.hiddenSize) { "runner hidden size ${runner.hiddenSize} != config ${config.hiddenSize}" }
        require(runner.tokensPerImage == config.imageSeqLen) { "runner emits ${runner.tokensPerImage} tokens per image, config says ${config.imageSeqLen}" }
    }

    /** The prompt text for [turns] and the images it refers to, in order. */
    internal fun buildPrompt(turns: List<Turn>): Pair<String, List<ByteArray>> {
        val prompt = StringBuilder("<|im_start|>")
        val pictures = ArrayList<ByteArray>()
        // The template has no system role. System text rides at the front of the first user turn, where
        // an instruction-tuned model of this size follows it best.
        val system = turns.filterIsInstance<Turn.System>().joinToString("\n\n") { it.content }.takeIf { it.isNotBlank() }
        var systemPending = system

        fun appendTurn(role: String, blocks: List<ContentBlock>, lead: String? = null) {
            val firstIsImage = lead == null && blocks.firstOrNull() is ContentBlock.Image
            prompt.append(role).append(if (firstIsImage) ":" else ": ")
            lead?.let { prompt.append(it).append("\n\n") }
            for (block in blocks) {
                when (block) {
                    is ContentBlock.Text -> prompt.append(block.text)
                    is ContentBlock.Image -> {
                        pictures += block.data
                        prompt.append(IMAGE_OPEN)
                        repeat(config.imageSeqLen) { prompt.append(IMAGE_TOKEN) }
                        prompt.append(IMAGE_CLOSE)
                    }
                    is ContentBlock.Audio -> throw UnsupportedOperationException("Model $modelId does not take audio")
                    is ContentBlock.ResourceRef -> prompt.append("[Resource: ${block.nodeId}]")
                }
            }
            prompt.append(END_OF_UTTERANCE).append('\n')
        }

        for (turn in turns) {
            when (turn) {
                is Turn.System -> Unit
                is Turn.User -> {
                    appendTurn("User", turn.content, systemPending)
                    systemPending = null
                }
                is Turn.Assistant -> turn.text?.let { appendTurn("Assistant", listOf(ContentBlock.Text(it))) }
                is Turn.ToolResult -> appendTurn("User", turn.result.content)
            }
        }
        // A system prompt with no user turn still has to reach the model.
        systemPending?.let { appendTurn("User", listOf(ContentBlock.Text(it))) }
        prompt.append("Assistant:")
        return prompt.toString() to pictures
    }

    /**
     * Runs the model over [turns], lazily: each `next()` produces at most one token's worth of work,
     * so a collector that stops (a cancelled HTTP stream) stops the computation.
     */
    fun generate(turns: List<Turn>, maxNewTokens: Int, stop: List<String> = emptyList()): Sequence<VisionStep> = sequence {
        val (prompt, pictures) = buildPrompt(turns)
        val ids = tokenizer.encode(prompt)
        require(ids.size < config.contextWindow) { "prompt is ${ids.size} tokens, the model window is ${config.contextWindow}" }

        val embeds = runner.embed(ids)
        val hidden = config.hiddenSize
        var next = 0
        for (picture in pictures) {
            val tile = ImagePreprocessor.prepare(images.decode(picture))
            val features = runner.encodeImage(tile)
            val slots = (0 until ids.size).filter { ids[it] == config.imageTokenId }.drop(next * config.imageSeqLen).take(config.imageSeqLen)
            check(slots.size == config.imageSeqLen && features.size == slots.size * hidden) { "image features do not fit the prompt" }
            slots.forEachIndexed { i, pos -> System.arraycopy(features, i * hidden, embeds, pos * hidden, hidden) }
            next++
        }

        val decoder = runner.newDecoder()
        try {
            var logits = decoder.step(embeds, ids.size)
            val bytes = java.io.ByteArrayOutputStream()
            var emitted = 0
            var generated = 0
            var reason = FinishReason.MAX_TOKENS
            while (true) {
                val token = argmax(logits)
                if (token in config.eosTokenIds) { reason = FinishReason.END_OF_TURN; break }
                generated++
                bytes.write(tokenizer.tokenBytes(token))
                // Hold back a trailing replacement char: it is a UTF-8 sequence still being assembled.
                // The first word comes back with the tokenizer's leading-space marker; the answer starts at the word.
                val text = bytes.toByteArray().decodeToString().trimEnd('\uFFFD').trimStart()
                if (text.length > emitted) {
                    yield(VisionStep.Text(text.substring(emitted)))
                    emitted = text.length
                }
                if (stop.any { text.contains(it) }) { reason = FinishReason.STOP_SEQUENCE; break }
                if (generated >= maxNewTokens) break
                if (ids.size + generated >= config.contextWindow) { reason = FinishReason.CONTEXT_FULL; break }
                logits = decoder.step(runner.embed(intArrayOf(token)), 1)
            }
            yield(VisionStep.Finished(ids.size, generated, reason))
        } finally {
            decoder.close()
        }
    }

    private fun argmax(values: FloatArray): Int {
        var best = 0
        for (i in 1 until values.size) if (values[i] > values[best]) best = i
        return best
    }

    override fun close() = runner.close()

    companion object {
        const val IMAGE_TOKEN = "<image>"
        const val IMAGE_OPEN = "<fake_token_around_image><global-img>"
        const val IMAGE_CLOSE = "<fake_token_around_image>"
        const val END_OF_UTTERANCE = "<end_of_utterance>"
    }
}
