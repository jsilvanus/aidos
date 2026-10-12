package dev.aidos.stt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * ONNX Runtime binding for the Hugging Face / Optimum Whisper export: `encoder_model*.onnx` plus either
 * `decoder_model_merged*.onnx` (KV cache, the efficient one) or a plain `decoder_model*.onnx` (re-runs the
 * whole sequence per token). CPU execution provider; accelerators are a host choice.
 */
class OrtWhisperGraphs(encoderFile: File, decoderFile: File, override val melBins: Int = 80, intraOpThreads: Int = 0) : WhisperGraphs {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val encoder: OrtSession
    private val decoder: OrtSession
    private val cached: Boolean
    private val pastInputs: List<String>
    private val pastShapes: Map<String, LongArray>

    init {
        require(encoderFile.isFile) { "ONNX encoder not found: ${encoderFile.path}" }
        require(decoderFile.isFile) { "ONNX decoder not found: ${decoderFile.path}" }
        fun open(f: File) = OrtSession.SessionOptions().use { opts ->
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (intraOpThreads > 0) opts.setIntraOpNumThreads(intraOpThreads)
            env.createSession(f.path, opts)
        }
        val enc = open(encoderFile)
        val dec = try { open(decoderFile) } catch (e: Throwable) { enc.close(); throw e }
        encoder = enc
        decoder = dec
        cached = "use_cache_branch" in dec.inputNames
        pastInputs = dec.inputNames.filter { it.startsWith("past_key_values.") }
        pastShapes = pastInputs.associateWith { (dec.inputInfo.getValue(it).info as ai.onnxruntime.TensorInfo).shape }
    }

    override fun encode(features: FloatArray): WhisperEncoded {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(features), longArrayOf(1, melBins.toLong(), WhisperFeatures.FRAMES.toLong())).use { input ->
            return Encoded(encoder.run(mapOf(ENC_IN to input)))
        }
    }

    override fun close() {
        decoder.close()
        encoder.close()
    }

    private inner class Encoded(private val result: OrtSession.Result) : WhisperEncoded {
        private val hidden: OnnxTensor = result.get(ENC_OUT).get() as OnnxTensor
        override fun decoding(): WhisperDecoding = if (cached) CachedDecoding(hidden) else FullDecoding(hidden)
        override fun close() = result.close()
    }

    /** Merged decoder: first step feeds the whole prefix with empty caches, later steps one token with the caches. */
    private inner class CachedDecoding(private val hidden: OnnxTensor) : WhisperDecoding {
        private var first: OrtSession.Result? = null // keeps the cross-attention caches alive
        private var last: OrtSession.Result? = null

        override fun step(tokens: IntArray): FloatArray {
            val temp = ArrayList<OnnxTensor>()
            try {
                val inputs = HashMap<String, OnnxTensor>()
                inputs["input_ids"] = longTensor(tokens).also { temp += it }
                inputs["encoder_hidden_states"] = hidden
                inputs["use_cache_branch"] = OnnxTensor.createTensor(env, booleanArrayOf(last != null)).also { temp += it }
                for (name in pastInputs) {
                    val src = if (last == null) null else (if ("encoder" in name && first != null) first else last)
                    val value = src?.get(name.replace("past_key_values.", "present."))?.orElse(null) as? OnnxTensor
                    inputs[name] = value ?: emptyTensor(pastShapes.getValue(name)).also { temp += it }
                }
                val next = decoder.run(inputs)
                val logits = lastRow(next.get("logits").get() as OnnxTensor)
                if (first == null) first = next
                else { if (last !== first) last?.close(); }
                last = next
                return logits
            } finally {
                temp.forEach { it.close() }
            }
        }

        override fun close() {
            if (last !== first) last?.close()
            first?.close()
        }
    }

    /** Plain decoder: no cache, the whole token sequence goes in every step. */
    private inner class FullDecoding(private val hidden: OnnxTensor) : WhisperDecoding {
        private val seen = ArrayList<Int>()

        override fun step(tokens: IntArray): FloatArray {
            tokens.forEach { seen += it }
            longTensor(seen.toIntArray()).use { ids ->
                decoder.run(mapOf("input_ids" to ids, "encoder_hidden_states" to hidden)).use { out ->
                    return lastRow(out.get("logits").get() as OnnxTensor)
                }
            }
        }

        override fun close() = Unit
    }

    private fun longTensor(ids: IntArray): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }), longArrayOf(1, ids.size.toLong()))

    /** Cache inputs for a first step: [batch, heads, 0, headDim]. */
    private fun emptyTensor(declared: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, declared[1], 0, declared[3]))

    private fun lastRow(logits: OnnxTensor): FloatArray {
        val shape = logits.info.shape // [1, seq, vocab]
        val seq = shape[1].toInt()
        val vocab = shape[2].toInt()
        val buf = logits.floatBuffer
        return FloatArray(vocab).also { buf.position((seq - 1) * vocab); buf.get(it); buf.rewind() }
    }

    private companion object {
        const val ENC_IN = "input_features"
        const val ENC_OUT = "last_hidden_state"
    }
}
