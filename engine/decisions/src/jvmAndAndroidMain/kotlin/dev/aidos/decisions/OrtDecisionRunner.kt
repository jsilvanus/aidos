package dev.aidos.decisions

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/**
 * ONNX Runtime binding for the Laya graph (RFC-0022 D44). Generic tensor execution only: nothing
 * here knows what the logits mean. CPU execution provider; accelerator providers are a host choice.
 */
class OrtDecisionRunner(modelFile: File, intraOpThreads: Int = 0) : DecisionTensorRunner {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        require(modelFile.isFile) { "ONNX model not found: ${modelFile.path}" }
        OrtSession.SessionOptions().use { opts ->
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (intraOpThreads > 0) opts.setIntraOpNumThreads(intraOpThreads)
            session = env.createSession(modelFile.path, opts)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun run(batch: TensorBatch): TensorResult {
        val tensors = ArrayList<OnnxTensor>()
        try {
            fun <T : Any> add(v: T): OnnxTensor = OnnxTensor.createTensor(env, v).also { tensors += it }
            val inputs = mapOf(
                "input_ids" to add(batch.inputIds),
                "attention_mask" to add(batch.attentionMask),
                "marker_pos" to add(batch.markerPos),
                "marker_mask" to add(batch.markerMask),
                "qtype" to add(batch.qtype),
            )
            session.run(inputs).use { out ->
                return TensorResult(
                    logits = out.get("logits").get().value as Array<FloatArray>,
                    actLogits = out.get("act_logits").get().value as Array<FloatArray>,
                )
            }
        } finally {
            tensors.forEach { it.close() }
        }
    }

    override fun close() = session.close()
}
