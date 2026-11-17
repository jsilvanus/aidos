package dev.aidos.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * ONNX Runtime binding for a Piper VITS graph. The graph takes `input` (int64 [1, n] phoneme ids), `input_lengths`
 * (int64 [1]), `scales` (float [3]: noise_scale, length_scale, noise_w) and, for multi-speaker voices, `sid`
 * (int64 [1]). It returns float audio at the voice's sample rate. Runs on the CPU, one utterance at a time.
 */
class OrtPiperGraph(modelFile: File) : SpeechGraph {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        require(modelFile.isFile) { "Piper voice model not found: ${modelFile.path}" }
        session = OrtSession.SessionOptions().use { opts ->
            opts.setIntraOpNumThreads(2)
            env.createSession(modelFile.path, opts)
        }
        val missing = listOf("input", "input_lengths", "scales").filter { it !in session.inputNames }
        if (missing.isNotEmpty()) {
            session.close()
            throw IllegalArgumentException("not a Piper VITS graph (no input named ${missing.joinToString()}): ${modelFile.path}")
        }
    }

    /** True when the graph takes a speaker id (multi-speaker voices). */
    override val takesSpeaker: Boolean = "sid" in session.inputNames

    /** Runs one utterance and returns its samples in [-1, 1]. */
    override fun run(ids: LongArray, scales: FloatArray, speaker: Long): FloatArray {
        require(ids.isNotEmpty()) { "no phoneme ids to speak" }
        require(scales.size == 3) { "scales must be noise_scale, length_scale, noise_w" }
        val tensors = ArrayList<OnnxTensor>()
        try {
            val input = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).also { tensors += it }
            val lengths = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(ids.size.toLong())), longArrayOf(1)).also { tensors += it }
            val sc = OnnxTensor.createTensor(env, FloatBuffer.wrap(scales), longArrayOf(3)).also { tensors += it }
            val feeds = linkedMapOf<String, OnnxTensor>("input" to input, "input_lengths" to lengths, "scales" to sc)
            if (takesSpeaker) {
                feeds["sid"] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(speaker)), longArrayOf(1)).also { tensors += it }
            }
            session.run(feeds).use { out ->
                val audio = out.get(0) as OnnxTensor
                val buf = audio.floatBuffer
                return FloatArray(buf.remaining()).also { buf.get(it) }
            }
        } finally {
            tensors.forEach { it.close() }
        }
    }

    override fun close() = session.close()
}
