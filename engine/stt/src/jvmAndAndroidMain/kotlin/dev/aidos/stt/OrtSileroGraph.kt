package dev.aidos.stt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * ONNX Runtime binding for the Silero VAD v5 graph (`silero_vad.onnx`, or `onnx/model.onnx` of
 * `onnx-community/silero-vad`): inputs `input` (1 x (64 context + 512) floats), `state` (2 x 1 x 128) and `sr`
 * (int64 scalar); outputs `output` (speech probability) and `stateN`. Runs on the CPU; the graph is about 2 MB
 * and a frame takes microseconds. The v4 graph (`h`/`c` inputs) is refused rather than fed wrongly.
 */
class OrtSileroGraph(modelFile: File) : VadGraph {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private var state = FloatArray(STATE_SIZE)
    private var context = FloatArray(CONTEXT)

    init {
        require(modelFile.isFile) { "Silero VAD model not found: ${modelFile.path}" }
        session = OrtSession.SessionOptions().use { opts ->
            opts.setIntraOpNumThreads(1) // tiny graph; extra threads cost more than they save
            env.createSession(modelFile.path, opts)
        }
        val missing = listOf("input", "state", "sr").filter { it !in session.inputNames }
        if (missing.isNotEmpty()) {
            session.close()
            throw IllegalArgumentException("not a Silero VAD v5 graph (no input named ${missing.joinToString()}): ${modelFile.path}")
        }
    }

    override fun reset() {
        state = FloatArray(STATE_SIZE)
        context = FloatArray(CONTEXT)
    }

    override fun probability(frame: FloatArray): Float {
        require(frame.size == SileroVad.FRAME_SAMPLES) { "frame must be ${SileroVad.FRAME_SAMPLES} samples, got ${frame.size}" }
        val input = FloatArray(CONTEXT + frame.size)
        context.copyInto(input)
        frame.copyInto(input, CONTEXT)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, input.size.toLong())).use { x ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { s ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(SileroVad.SAMPLE_RATE.toLong())), longArrayOf()).use { sr ->
                    session.run(mapOf("input" to x, "state" to s, "sr" to sr)).use { out ->
                        val next = (out.get("stateN").get() as OnnxTensor).floatBuffer
                        state = FloatArray(STATE_SIZE).also { next.get(it) }
                        context = input.copyOfRange(input.size - CONTEXT, input.size)
                        return (out.get("output").get() as OnnxTensor).floatBuffer.get(0)
                    }
                }
            }
        }
    }

    override fun close() = session.close()

    private companion object {
        const val CONTEXT = 64
        const val STATE_SIZE = 2 * 128
    }
}
