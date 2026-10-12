package dev.aidos.decisions

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse

/** A loaded decision model as the ModelRuntime sees it: an adapter that answers [DecisionRequest]s, not chat turns. */
interface DecisionModelAdapter : ModelAdapter {
    suspend fun decide(request: DecisionRequest): Result<DecisionResult>
}

/** Bridges a [DecisionModel] onto the kernel's [ModelAdapter] so GlobalModelRuntime can load/unload it. */
class DecisionModelAdapterImpl(
    private val model: DecisionModel,
    override val contextWindow: Int,
    override val modelVersion: String = "onnx",
) : DecisionModelAdapter, AutoCloseable {
    override val providerId: String = "local"
    override val modelId: String get() = model.modelId
    override val isLocal: Boolean = true

    override fun supportsNativeToolCalls(): Boolean = false

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> =
        Result.failure(UnsupportedOperationException("Model $modelId is a decision model; use /v1/systemone"))

    override suspend fun decide(request: DecisionRequest): Result<DecisionResult> =
        runCatching { model.decide(request) }

    override fun close() = model.close()
}
