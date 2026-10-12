package dev.aidos.vision

/**
 * The three SmolVLM graphs as plain tensor operations, so [SmolVlmModel] (prompt, image splicing,
 * sampling) is testable without a model file. [OrtVisionRunner] is the ONNX Runtime binding.
 */
interface VisionRunner : AutoCloseable {
    /** Width of the language model's embeddings (576 for SmolVLM-256M). */
    val hiddenSize: Int

    /** Image tokens produced per tile ([hiddenSize] floats each). */
    val tokensPerImage: Int

    /** Runs the vision encoder over [tile]; returns `tokensPerImage * hiddenSize` floats. */
    fun encodeImage(tile: ImageTile): FloatArray

    /** Token embeddings for [ids]; returns `ids.size * hiddenSize` floats. */
    fun embed(ids: IntArray): FloatArray

    /** A fresh decoder with an empty KV cache. One per generation; the caller closes it. */
    fun newDecoder(): DecoderSession
}

interface DecoderSession : AutoCloseable {
    /**
     * Feeds [length] more positions ([embeds] is `length * hiddenSize` floats) after everything fed so
     * far, and returns the logits of the last one.
     */
    fun step(embeds: FloatArray, length: Int): FloatArray
}
