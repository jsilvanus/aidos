package fi.italeino.aidos.engine.agent

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import fi.italeino.aidos.engine.inference.InferenceRequestManager
import fi.italeino.aidos.engine.inference.Locality

/**
 * A model of this Engine, called in-process through [InferenceRequestManager] (admission, load
 * lifecycle and the locality check of RFC-0104) rather than over the Engine's own HTTP API. Replaces
 * the loopback `OpenAiHttpModelAdapter`, which hard-coded `isLocal = true` and so could not tell a
 * local model from a provider's.
 *
 * Each call is checked against [locality], so an agent turn cannot reach a wider placement than its
 * caller allowed. [isLocal] is false because this adapter itself does not know where the model
 * runs; the manager does, per call.
 */
class ManagedModelAdapter(
    private val manager: InferenceRequestManager,
    override val modelId: String,
    private val locality: Locality,
    override val contextWindow: Int = 8192,
) : ModelAdapter {
    override val providerId: String = "aidos-engine"
    override val modelVersion: String = "1.0"
    override val isLocal: Boolean = false

    override fun supportsNativeToolCalls(): Boolean = true

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> =
        manager.execute(modelId, locality) { adapter, _ -> adapter.invoke(request).getOrThrow() }
}
