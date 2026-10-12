package dev.aidos.vision

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * ONNX Runtime binding for SmolVLM's exported graphs (RFC-0022). CPU execution provider, like the
 * decision models; the same code runs on the JVM and on Android (`onnxruntime-android`).
 *
 * The decoder is the "merged" graph: one session handles both the prompt (empty past) and every
 * following token (one position, full past), taking and returning `past_key_values.N.{key,value}` /
 * `present.N.{key,value}` tensors. Layer count and head geometry are read from the graph, not assumed.
 */
class OrtVisionRunner(
    encoderFile: File,
    embedFile: File,
    decoderFile: File,
    override val hiddenSize: Int,
    override val tokensPerImage: Int,
    intraOpThreads: Int = 0,
) : VisionRunner {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val encoder: OrtSession
    private val embedder: OrtSession
    private val decoder: OrtSession
    private val pastNames: List<String>
    private val kvHeads: Long
    private val headDim: Long

    init {
        for (f in listOf(encoderFile, embedFile, decoderFile)) require(f.isFile) { "ONNX model not found: ${f.path}" }
        val opened = ArrayList<OrtSession>()
        try {
            fun open(f: File): OrtSession = OrtSession.SessionOptions().use { opts ->
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                if (intraOpThreads > 0) opts.setIntraOpNumThreads(intraOpThreads)
                env.createSession(f.path, opts).also { opened += it }
            }
            encoder = open(encoderFile)
            embedder = open(embedFile)
            decoder = open(decoderFile)
        } catch (e: Throwable) {
            opened.forEach { runCatching { it.close() } }
            throw e
        }
        pastNames = decoder.inputNames.filter { it.startsWith("past_key_values.") }
        require(pastNames.isNotEmpty()) { "decoder graph has no past_key_values inputs: not a merged decoder" }
        val shape = (decoder.inputInfo.getValue(pastNames.first()).info as TensorInfo).shape
        kvHeads = shape[1]
        headDim = shape[3]
    }

    override fun encodeImage(tile: ImageTile): FloatArray {
        val mask = ByteBuffer.allocateDirect(tile.mask.size)
        tile.mask.forEach { mask.put(if (it) 1 else 0) }
        rewind(mask)
        val pixels = direct(tile.pixels)
        OnnxTensor.createTensor(env, pixels, longArrayOf(1, 1, 3, ImagePreprocessor.TILE.toLong(), ImagePreprocessor.TILE.toLong())).use { pv ->
            OnnxTensor.createTensor(
                env, mask, longArrayOf(1, 1, ImagePreprocessor.TILE.toLong(), ImagePreprocessor.TILE.toLong()), OnnxJavaType.BOOL,
            ).use { pm ->
                encoder.run(mapOf("pixel_values" to pv, "pixel_attention_mask" to pm)).use { out ->
                    return floats(out.get(0) as OnnxTensor)
                }
            }
        }
    }

    override fun embed(ids: IntArray): FloatArray {
        val buf = ByteBuffer.allocateDirect(ids.size * 8).order(ByteOrder.nativeOrder()).asLongBuffer()
        ids.forEach { buf.put(it.toLong()) }
        rewind(buf)
        OnnxTensor.createTensor(env, buf, longArrayOf(1, ids.size.toLong())).use { t ->
            embedder.run(mapOf("input_ids" to t)).use { out -> return floats(out.get(0) as OnnxTensor) }
        }
    }

    override fun newDecoder(): DecoderSession = OrtDecoderSession()

    override fun close() {
        encoder.close()
        embedder.close()
        decoder.close()
    }

    private inner class OrtDecoderSession : DecoderSession {
        private var cache: OrtSession.Result? = null
        private var length = 0L

        override fun step(embeds: FloatArray, length: Int): FloatArray {
            require(embeds.size == length * hiddenSize) { "embeds hold ${embeds.size} floats, expected ${length * hiddenSize}" }
            val total = this.length + length
            val owned = ArrayList<OnnxTensor>()
            try {
                fun own(t: OnnxTensor) = t.also { owned += it }
                val inputs = HashMap<String, OnnxTensor>()
                inputs["inputs_embeds"] = own(OnnxTensor.createTensor(env, direct(embeds), longArrayOf(1, length.toLong(), hiddenSize.toLong())))
                inputs["attention_mask"] = own(OnnxTensor.createTensor(env, longs(LongArray(total.toInt()) { 1L }), longArrayOf(1, total)))
                inputs["position_ids"] = own(OnnxTensor.createTensor(env, longs(LongArray(length) { this.length + it }), longArrayOf(1, length.toLong())))

                val previous = cache
                if (previous == null) {
                    // Nothing cached yet: zero-length past tensors. They live as long as this decoder, as
                    // the first step is their only use, but closing them with the step keeps ownership simple.
                    for (name in pastNames) {
                        inputs[name] = own(OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, kvHeads, 0, headDim)))
                    }
                } else {
                    for (name in pastNames) {
                        // The previous step's present.* output is this step's past.* input, unconverted.
                        inputs[name] = previous.get("present." + name.removePrefix("past_key_values.")).get() as OnnxTensor
                    }
                }

                val result = decoder.run(inputs)
                try {
                    val logits = result.get("logits").get() as OnnxTensor
                    val vocab = (logits.info.shape[2]).toInt()
                    val all = logits.floatBuffer
                    val last = FloatArray(vocab)
                    seek(all, (length - 1) * vocab)
                    all.get(last)
                    previous?.close()
                    cache = result
                    this.length = total
                    return last
                } catch (e: Throwable) {
                    result.close()
                    throw e
                }
            } finally {
                owned.forEach { it.close() }
            }
        }

        override fun close() {
            cache?.close()
            cache = null
        }
    }

    private fun direct(values: FloatArray): FloatBuffer {
        val buf = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(values)
        rewind(buf)
        return buf
    }

    private fun longs(values: LongArray): LongBuffer {
        val buf = ByteBuffer.allocateDirect(values.size * 8).order(ByteOrder.nativeOrder()).asLongBuffer()
        buf.put(values)
        rewind(buf)
        return buf
    }

    // Buffer.rewind()/position(int) return Buffer on Android before API 33 and the subclass on JDK 9+.
    // Calling them through the Buffer type keeps the compiled reference valid on both.
    private fun rewind(b: Buffer) { b.rewind() }

    private fun seek(b: Buffer, position: Int) { b.position(position) }

    private fun floats(t: OnnxTensor): FloatArray {
        val fb = t.floatBuffer
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }
}
