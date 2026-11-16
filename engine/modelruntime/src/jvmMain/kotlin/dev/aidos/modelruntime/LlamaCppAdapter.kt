package dev.aidos.modelruntime

import de.kherud.llama.InferenceParameters
import de.kherud.llama.LlamaIterator
import de.kherud.llama.LlamaModel
import de.kherud.llama.ModelParameters
import dev.aidos.kernel.CancellableModelAdapter
import dev.aidos.kernel.EmbeddingModelAdapter
import dev.aidos.kernel.ModelRef
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.ModelStreamEvent
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.ToolCallOutput
import dev.aidos.kernel.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Real llama.cpp-based inference adapter (RFC-0022, M21/M22).
 *
 * Uses llama-cpp-java JNI bindings to load and run GGUF models locally.
 * Prompts are rendered with the model's own chat template, and tools are offered and parsed as
 * [LlamaChatPrompt] describes (RFC-0021 constrained decoding, RFC-0008 agent loop).
 */
class LlamaCppAdapter(
    override val modelId: String,
    private val modelFile: File,
    private val metadata: GgufMetadata,
    contextSize: Int = 2048,
    threads: Int = 4,
    /** Embedding models are loaded with llama.cpp's embedding mode on and cannot generate text. */
    private val embeddingMode: Boolean = false,
    /** Batch size, GPU layers and sampling come from here; context and threads arrive resolved above. */
    private val settings: ModelRunSettings = ModelRunSettings.DEFAULT,
    /**
     * A chat template to use instead of the one in the GGUF, for a model whose file carries none
     * or a wrong one. Null (the default) uses the model's own.
     */
    private val chatTemplateOverride: String? = null,
) : CancellableModelAdapter, EmbeddingModelAdapter {
    override val providerId = "llama.cpp"
    override val modelVersion = "java-llama.cpp-4.2.0"
    override val contextWindow = contextSize
    override val isLocal = true

    /** The template prompts are rendered with; null means the model has no notion of chat roles. */
    private val chatTemplate: String? = chatTemplateOverride ?: metadata.chatTemplate

    private val loadStartNanos: Long = System.nanoTime()
    private val model: LlamaModel = loadModel(modelFile, contextSize, threads)
    val coldStartMillis: Long = (System.nanoTime() - loadStartNanos) / 1_000_000

    @Volatile
    private var closed: Boolean = false

    /** The generation in progress, so [cancelCurrentInference] (timeout, model close) can stop the native work. */
    @Volatile
    private var activeIterator: LlamaIterator? = null

    private fun loadModel(
        modelPath: File,
        contextSize: Int,
        threads: Int,
    ): LlamaModel {
        fun parameters() = ModelParameters()
            .setModel(modelPath.absolutePath)
            .setCtxSize(contextSize)
            .setThreads(threads)
            .setThreadsBatch(threads)
            .setBatchSize(settings.batchSize ?: ModelRunSettings.DEFAULT_BATCH)
            .setUbatchSize(settings.batchSize ?: ModelRunSettings.DEFAULT_BATCH)
            .setGpuLayers(settings.gpuLayers ?: ModelRunSettings.DEFAULT_GPU_LAYERS)
            .let { if (settings.useMmap == false) it.disableMmap() else it }
            .let { if (embeddingMode) it.enableEmbedding() else it }
            .let { if (chatTemplateOverride != null) it.setChatTemplate(chatTemplateOverride) else it }

        // llama.cpp only evaluates a model's real chat template with its Jinja engine on; without
        // it, it guesses among a few built-in formats and falls back to ChatML for the rest.
        if (chatTemplate != null && !embeddingMode) {
            try {
                return LlamaModel(parameters().enableJinja())
            } catch (e: Exception) {
                // A template the engine cannot parse must not stop the model from loading.
                System.err.println("Model $modelId: chat template not usable with Jinja (${e.message}); loading without")
            }
        }
        return try {
            LlamaModel(parameters())
        } catch (e: Exception) {
            throw IllegalStateException("Failed to load model $modelId from ${modelPath.absolutePath}", e)
        }
    }

    override fun supportsNativeToolCalls(): Boolean = false

    /** Mirrors AndroidLlamaCppAdapter.embed so both hosts serve /v1/embeddings (RFC-0022). */
    override suspend fun embed(text: String): Result<FloatArray> = try {
        when {
            closed -> Result.failure(IllegalStateException("Model $modelId was unloaded; reload before invoking again"))
            !embeddingMode -> Result.failure(UnsupportedOperationException("Model $modelId is not configured for embeddings"))
            else -> model.embed(text).let {
                if (it.isEmpty()) Result.failure(IllegalStateException("Embedding model returned an empty vector"))
                else Result.success(it)
            }
        }
    } catch (e: Throwable) {
        Result.failure(e)
    }

    /**
     * Run inference and return the generalized RFC-0125 response shape.
     * Text and tool calls are separate ordered ModelOutput values; model identity and usage
     * remain attached to the response rather than being encoded in output types.
     *
     * Implemented in terms of [invokeStreaming] rather than its own generation loop, so there is
     * exactly one place that walks `model.generate()`'s token iterator (RFC-0021 "Streaming";
     * Dictator plan S4). Before this, [invoke] buffered every token into one string before
     * returning, which is where the *actual* streaming problem lived: Engine's SSE endpoint could
     * only ever start emitting after generation had already finished, whatever the transport did.
     */
    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        var finalResponse: ModelResponse? = null
        try {
            invokeStreaming(request).collect { event ->
                if (event is ModelStreamEvent.Done) finalResponse = event.response
                if (event is ModelStreamEvent.Failed) throw event.error
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val response = finalResponse
            ?: return Result.failure(
                IllegalStateException("Generation stream for $modelId completed without a result")
            )
        return Result.success(response)
    }

    /** Real token-by-token streaming — see [invoke]'s KDoc for why it now delegates here. */
    override suspend fun invokeStreaming(request: ModelRequest): Flow<ModelStreamEvent> = flow {
        if (closed) {
            emit(ModelStreamEvent.Failed(
                IllegalStateException("Model $modelId was unloaded; reload before invoking again")
            ))
            return@flow
        }
        if (embeddingMode) {
            emit(ModelStreamEvent.Failed(UnsupportedOperationException("Embedding model cannot perform chat generation")))
            return@flow
        }

        try {
            val prompt = renderPrompt(request)
            val offeredTools = LlamaChatPrompt.offeredTools(request)
            // Non-null only when the caller requires a call (RFC-0021 constrained decoding).
            val grammar = LlamaChatPrompt.grammar(request)

            val output = StringBuilder()
            val parameters = InferenceParameters(prompt)
                .setNPredict(request.maxOutputTokens)
                .setTemperature(settings.temperature ?: ModelRunSettings.DEFAULT_TEMPERATURE)
                .setTopP(settings.topP ?: ModelRunSettings.DEFAULT_TOP_P)
                .setTopK(settings.topK ?: ModelRunSettings.DEFAULT_TOP_K)
                // Each turn of a conversation repeats the turns before it. Keeping the evaluated
                // prefix means only the new text is processed, not the whole history again.
                .setCachePrompt(true)
                .let { if (grammar != null) it.setGrammar(grammar) else it }
            val tokens = model.generate(parameters).iterator()
            var tokenCount = 0
            var stoppedOnSequence = false
            val longestStop = request.stopConditions.maxOfOrNull { it.length } ?: 0

            activeIterator = tokens
            try {
                while (tokens.hasNext() && tokenCount < request.maxOutputTokens) {
                    val token = tokens.next()
                    output.append(token.text)
                    tokenCount++
                    emit(ModelStreamEvent.Delta(token.text))

                    if (longestStop > 0 && endsWithStop(output, token.text.length, longestStop, request.stopConditions)) {
                        stoppedOnSequence = true
                        break
                    }
                }
            } finally {
                if (activeIterator === tokens) activeIterator = null
                // The iterator only releases its native task when it reads the model's own stop.
                // Leaving the loop any other way (token cap, stop sequence, the collector going
                // away) would otherwise keep llama.cpp generating up to n_predict for nobody,
                // holding the model's only slot, and leak the task.
                if (tokens.hasNext()) runCatching { tokens.cancel() }
            }

            val generated = output.toString()
            val toolCalls = if (offeredTools.isNotEmpty()) {
                ToolCallParser.parse(generated, offeredTools, isConstrained = grammar != null)
            } else {
                emptyList()
            }
            val finalText = LlamaChatPrompt.textWithoutCalls(generated, toolCalls, constrained = grammar != null)

            val stopReason = when {
                tokenCount >= request.maxOutputTokens -> StopReason.MAX_TOKENS
                stoppedOnSequence -> StopReason.STOP_SEQUENCE
                toolCalls.isNotEmpty() -> StopReason.TOOL_USE
                else -> StopReason.END_TURN
            }

            // Counted, not estimated: a word-count estimate reported 1 token for "oooooooo"
            // and made the self-test's tokens/s meaningless for anything without spaces.
            val inputTokens = runCatching { model.encode(prompt).size }.getOrElse { estimateTokens(prompt) }
            val outputTokens = tokenCount
            val usage = Usage(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                totalTokens = inputTokens + outputTokens,
            )

            val outputs = buildList {
                if (finalText.isNotEmpty()) add(TextOutput(finalText))
                toolCalls.forEach { add(ToolCallOutput(it)) }
            }

            emit(ModelStreamEvent.Done(
                ModelResponse(
                    outputs = outputs,
                    stopReason = stopReason,
                    usage = usage,
                    model = ModelRef(modelId, modelVersion),
                )
            ))
        } catch (e: Exception) {
            emit(ModelStreamEvent.Failed(e))
        }
    }

    /** The exact prompt text [request] is run with: the model's chat template applied, tools described. */
    internal fun renderPrompt(request: ModelRequest): String = LlamaChatPrompt.render(model, chatTemplate, request)

    /**
     * Whether a stop sequence was completed by the token just appended. Only the tail that the
     * new token can have changed is searched, so a long generation does not rescan (or copy) the
     * whole output for every token.
     */
    private fun endsWithStop(output: StringBuilder, appended: Int, longestStop: Int, stopSequences: List<String>): Boolean {
        val from = (output.length - appended - longestStop + 1).coerceAtLeast(0)
        return stopSequences.any { it.isNotEmpty() && output.indexOf(it, from) >= 0 }
    }

    override fun cancelCurrentInference() {
        activeIterator?.let { runCatching { it.cancel() } }
    }

    private fun estimateTokens(text: String): Int =
        (text.split("\\s+".toRegex()).size * 1.3).toInt()

    fun close() {
        if (closed) return
        closed = true
        cancelCurrentInference()
        try {
            model.close()
        } catch (e: Exception) {
            System.err.println("Error closing llama.cpp model: ${e.message}")
        }
    }
}
