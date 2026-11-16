package fi.italeino.aidos.engine.inference

import de.kherud.llama.InferenceParameters
import de.kherud.llama.LlamaIterator
import de.kherud.llama.LlamaModel
import de.kherud.llama.ModelParameters
import dev.aidos.kernel.CancellableModelAdapter
import dev.aidos.kernel.EmbeddingModelAdapter
import dev.aidos.modelruntime.LlamaChatPrompt
import dev.aidos.modelruntime.ModelRunSettings
import dev.aidos.modelruntime.ToolCallParser
import dev.aidos.kernel.ModelRef
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.ModelStreamEvent
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.ToolCallOutput
import dev.aidos.kernel.Usage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Real on-device llama.cpp adapter used by the Android engine. */
class AndroidLlamaCppAdapter(
    override val modelId: String,
    private val modelFile: File,
    override val contextWindow: Int,
    private val threads: Int = 4,
    private val embeddingMode: Boolean = false,
    private val settings: ModelRunSettings = ModelRunSettings.DEFAULT,
    /** The chat template the GGUF declares, or null for a model with no notion of chat roles. */
    private val chatTemplate: String? = null,
) : CancellableModelAdapter, EmbeddingModelAdapter, AutoCloseable {
    override val providerId: String = "llama.cpp.android"
    override val modelVersion: String = "java-llama.cpp-4.2.0"
    override val isLocal: Boolean = true

    private val model: LlamaModel
    private val inferenceLock = Any()
    private val generationMutex = Mutex()
    @Volatile private var activeIterator: LlamaIterator? = null
    @Volatile private var closed = false

    init {
        fun parameters(): ModelParameters {
            var parameters = ModelParameters()
                .setModel(modelFile.absolutePath)
                .setCtxSize(contextWindow)
                .setThreads(threads)
                .setThreadsBatch(threads)
                .setBatchSize(settings.batchSize ?: ModelRunSettings.DEFAULT_BATCH)
                .setUbatchSize(settings.batchSize ?: ModelRunSettings.DEFAULT_BATCH)
                .setGpuLayers(settings.gpuLayers ?: ModelRunSettings.DEFAULT_GPU_LAYERS)
            if (settings.useMmap == false) parameters = parameters.disableMmap()
            if (embeddingMode) parameters = parameters.enableEmbedding()
            return parameters
        }
        // llama.cpp only evaluates a model's real chat template with its Jinja engine on (see
        // LlamaChatPrompt). A template the engine cannot parse must not stop the model loading.
        val withJinja = if (chatTemplate != null && !embeddingMode) {
            runCatching { LlamaModel(parameters().enableJinja()) }.getOrNull()
        } else {
            null
        }
        model = withJinja ?: LlamaModel(parameters())
    }

    override fun supportsNativeToolCalls(): Boolean = false

    override suspend fun embed(text: String): Result<FloatArray> = try {
        if (closed) Result.failure(IllegalStateException("Model $modelId is unloaded"))
        else if (!embeddingMode) Result.failure(
            UnsupportedOperationException("Model $modelId is not configured for embeddings")
        )
        else {
            val vector = model.embed(text)
            if (vector.isEmpty()) Result.failure(IllegalStateException("Embedding model returned an empty vector"))
            else Result.success(vector)
        }
    } catch (e: Throwable) {
        Result.failure(e)
    }

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        var response: ModelResponse? = null
        return try {
            invokeStreaming(request).collect { event ->
                when (event) {
                    is ModelStreamEvent.Done -> response = event.response
                    is ModelStreamEvent.Failed -> throw event.error
                    is ModelStreamEvent.Delta -> Unit
                }
            }
            val completed = response
            if (completed != null) Result.success(completed) else Result.failure(
                IllegalStateException("Inference ended without a response")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun invokeStreaming(request: ModelRequest): Flow<ModelStreamEvent> = flow {
        if (closed) {
            emit(ModelStreamEvent.Failed(IllegalStateException("Model $modelId is unloaded")))
            return@flow
        }
        if (embeddingMode) {
            emit(ModelStreamEvent.Failed(UnsupportedOperationException("Embedding model cannot perform chat generation")))
            return@flow
        }
        generationMutex.withLock {
            if (closed) {
                emit(ModelStreamEvent.Failed(IllegalStateException("Model $modelId is unloaded")))
                return@withLock
            }

            try {
                // RFC-0021: the model's own chat template, tools described and parsed as the
                // desktop adapter does it. Both hosts share LlamaChatPrompt so they cannot drift.
                val prompt = LlamaChatPrompt.render(model, chatTemplate, request)
                val offeredTools = LlamaChatPrompt.offeredTools(request)
                val grammar = LlamaChatPrompt.grammar(request)
                var parameters = InferenceParameters(prompt)
                    .setNPredict(request.maxOutputTokens)
                    .setTemperature(settings.temperature ?: ModelRunSettings.DEFAULT_TEMPERATURE)
                    .setTopP(settings.topP ?: ModelRunSettings.DEFAULT_TOP_P)
                    .setTopK(settings.topK ?: ModelRunSettings.DEFAULT_TOP_K)
                    // A conversation repeats its earlier turns: keep the evaluated prefix.
                    .setCachePrompt(true)
                if (grammar != null) parameters = parameters.setGrammar(grammar)
                val output = StringBuilder()
                var tokenCount = 0
                // `generate` is lazy: emitting as the iterator advances keeps the UI and the
                // loopback SSE endpoint genuinely token-streaming rather than buffering a native
                // completion first. `maxOutputTokens` remains the authoritative output bound.
                val iterator = model.generate(parameters).iterator()
                synchronized(inferenceLock) { activeIterator = iterator }
                try {
                    while (iterator.hasNext()) {
                        if (tokenCount++ >= request.maxOutputTokens) break
                        val token = iterator.next()
                        output.append(token.text)
                        emit(ModelStreamEvent.Delta(token.text))
                        if (request.stopConditions.any(output::contains)) break
                    }
                } finally {
                    synchronized(inferenceLock) {
                        if (activeIterator === iterator) activeIterator = null
                        // The iterator only releases its native task when it reads the model's
                        // own stop. Leaving the loop any other way (token cap, stop sequence,
                        // the collector going away) would otherwise keep llama.cpp generating
                        // up to n_predict for nobody, on a phone, and leak the task.
                        if (iterator.hasNext()) runCatching { iterator.cancel() }
                    }
                }

                val generated = output.toString()
                val toolCalls = if (offeredTools.isEmpty()) emptyList()
                else ToolCallParser.parse(generated, offeredTools, isConstrained = grammar != null)
                val text = LlamaChatPrompt.textWithoutCalls(generated, toolCalls, constrained = grammar != null)
                val inputTokens = model.encode(prompt).size
                val outputTokens = model.encode(generated).size
                emit(ModelStreamEvent.Done(
                    ModelResponse(
                        outputs = buildList {
                            if (text.isNotEmpty()) add(TextOutput(text))
                            toolCalls.forEach { add(ToolCallOutput(it)) }
                        },
                        stopReason = when {
                            outputTokens >= request.maxOutputTokens -> StopReason.MAX_TOKENS
                            request.stopConditions.any(generated::contains) -> StopReason.STOP_SEQUENCE
                            toolCalls.isNotEmpty() -> StopReason.TOOL_USE
                            else -> StopReason.END_TURN
                        },
                        usage = Usage(inputTokens, outputTokens, inputTokens + outputTokens),
                        model = ModelRef(modelId, modelVersion),
                    )
                ))
            } catch (e: CancellationException) {
                // Flow cancellation is the stop-generation signal. Do not convert it into a terminal
                // failure event: the caller owns the generation Job and expects cancellation to stop
                // the stream without replacing it with an error bubble.
                throw e
            } catch (e: Throwable) {
                emit(ModelStreamEvent.Failed(e))
            }
        }
    }

    override fun cancelCurrentInference() {
        synchronized(inferenceLock) {
            activeIterator?.cancel()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelCurrentInference()
        model.close()
    }
}
