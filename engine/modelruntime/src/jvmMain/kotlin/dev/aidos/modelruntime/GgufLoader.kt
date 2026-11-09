package dev.aidos.modelruntime

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GGUF header and metadata parser used by the JVM tooling.
 *
 * This intentionally parses metadata and tensor descriptors only; it never reads
 * the model tensor data. That makes inspection fast even for multi-GB models.
 */
object GgufLoader {
    private const val GGUF_MAGIC = 0x46554747L // "GGUF" as little-endian UInt32
    // ULong, not Long: these bound values read by readU64LE(), which returns ULong.
    // Comparing ULong against a Long literal does not compile.
    private const val MAX_KV_ENTRIES = 100_000uL
    private const val MAX_STRING_BYTES = 1_048_576uL
    private const val MAX_ARRAY_ELEMENTS = 1_000_000uL

    private val LAYER_TENSOR_REGEX = Regex("""^blk\.(\d+)\.""")

    fun loadMetadata(file: File): GgufMetadata? {
        if (!file.exists() || !file.isFile || file.length() < 24) return null

        return try {
            file.inputStream().buffered().use { stream ->
                val magic = readU32LE(stream)
                if (magic != GGUF_MAGIC) return null

                val version = readU32LE(stream).toInt()
                if (version !in 1..3) return null

                val tensorCount = readU64LE(stream)
                val kvCount = readU64LE(stream)
                if (kvCount > MAX_KV_ENTRIES) return null

                var architecture: String? = null
                var modelName: String? = null
                var fileType: Long? = null
                var sizeLabel: String? = null
                // Whether the model carries its own chat template decides how a
                // prompt may be framed: a model without one has no notion of
                // roles, so wrapping its input in invented role labels corrupts it.
                var chatTemplate: String? = null
                // GGUF namespaces this key per architecture (`llama.context_length`,
                // `qwen2.context_length`, ...). There is no `general.context_length`,
                // so looking for one always missed and every model silently reported
                // the 4096 fallback as its context window. Collect the namespaced
                // keys and resolve against the architecture once it is known, since
                // key order within the metadata block is not guaranteed.
                val contextLengths = mutableMapOf<String, Long>()
                val blockCounts = mutableMapOf<String, Long>()
                val embeddingLengths = mutableMapOf<String, Long>()
                val headCounts = mutableMapOf<String, Long>()
                val headCountsKv = mutableMapOf<String, Long>()

                repeat(kvCount.toInt()) {
                    val key = readString(stream) ?: throw GgufParseException("Invalid metadata key")
                    val type = readU32LE(stream).toInt()
                    val value = readValue(stream, type)
                    when {
                        key == "general.architecture" -> architecture = value as? String
                        key == "general.name" -> modelName = value as? String
                        key == "general.file_type" -> fileType = value.asLong()
                        key == "general.size_label" -> sizeLabel = value as? String
                        key == "tokenizer.chat_template" -> chatTemplate = value as? String
                        key.endsWith(".context_length") ->
                            value.asLong()?.let { contextLengths[key] = it }
                        key.endsWith(".block_count") ->
                            value.asLong()?.let { blockCounts[key] = it }
                        key.endsWith(".embedding_length") ->
                            value.asLong()?.let { embeddingLengths[key] = it }
                        key.endsWith(".attention.head_count") ->
                            value.asLong()?.let { headCounts[key] = it }
                        key.endsWith(".attention.head_count_kv") ->
                            value.asLong()?.let { headCountsKv[key] = it }
                    }
                }

                val contextWindow = architecture?.let { contextLengths["$it.context_length"] }
                    ?: contextLengths.values.firstOrNull()
                val declaredBlocks = (architecture?.let { blockCounts["$it.block_count"] }
                    ?: blockCounts.values.firstOrNull())?.toIntSafely() ?: 0
                val embeddingLength = (architecture?.let { embeddingLengths["$it.embedding_length"] }
                    ?: embeddingLengths.values.firstOrNull())?.toIntSafely() ?: 0
                val headCount = (architecture?.let { headCounts["$it.attention.head_count"] }
                    ?: headCounts.values.firstOrNull())?.toIntSafely() ?: 0
                val headCountKv = (architecture?.let { headCountsKv["$it.attention.head_count_kv"] }
                    ?: headCountsKv.values.firstOrNull())?.toIntSafely() ?: headCount

                // Tensor descriptors follow the KV metadata. Summing tensor element
                // counts gives a useful parameter-count value without touching tensor data,
                // and grouping `blk.<N>.*` descriptors gives per-layer weight distribution
                // for RFC-0105 layer-split planning.
                var parameterCount: ULong = 0u
                val elementsByLayer = mutableMapOf<Int, ULong>()
                var nonLayerElements: ULong = 0u
                var maxLayerIndex = -1

                repeat(tensorCount.toInt()) {
                    val tensorName = readString(stream) ?: throw GgufParseException("Invalid tensor name")
                    val dimensions = readU32LE(stream).toInt()
                    if (dimensions < 0 || dimensions > 64) throw GgufParseException("Invalid tensor rank")
                    var elements = 1uL
                    repeat(dimensions) {
                        val dimension = readU64LE(stream)
                        if (dimension == 0uL) elements = 0uL
                        else elements = elements.saturatingMultiply(dimension)
                    }
                    readU64LE(stream) // tensor data offset
                    readU32LE(stream) // GGML tensor type
                    parameterCount = parameterCount.saturatingAdd(elements)

                    val layerMatch = LAYER_TENSOR_REGEX.find(tensorName)
                    if (layerMatch != null) {
                        val idx = layerMatch.groupValues[1].toIntOrNull() ?: -1
                        if (idx >= 0) {
                            maxLayerIndex = maxOf(maxLayerIndex, idx)
                            elementsByLayer[idx] = (elementsByLayer[idx] ?: 0uL).saturatingAdd(elements)
                        } else {
                            nonLayerElements = nonLayerElements.saturatingAdd(elements)
                        }
                    } else {
                        nonLayerElements = nonLayerElements.saturatingAdd(elements)
                    }
                }

                val totalParams = parameterCount.toLongSafely()
                val effectiveBlockCount = maxOf(declaredBlocks, if (maxLayerIndex >= 0) maxLayerIndex + 1 else 0)
                val fileBytes = file.length().coerceAtLeast(1L)
                val layerBytes = if (effectiveBlockCount > 0 && totalParams > 0L) {
                    List(effectiveBlockCount) { layerIdx ->
                        val layerEls = (elementsByLayer[layerIdx] ?: 0uL).toLongSafely()
                        ((fileBytes.toDouble() * layerEls.toDouble()) / totalParams.toDouble()).toLong().coerceAtLeast(1L)
                    }
                } else {
                    emptyList()
                }
                val nonLayerBytes = if (totalParams > 0L) {
                    ((fileBytes.toDouble() * nonLayerElements.toLongSafely().toDouble()) / totalParams.toDouble()).toLong()
                } else {
                    0L
                }

                GgufMetadata(
                    version = version,
                    tensorCount = tensorCount.toLongSafely(),
                    kvCount = kvCount.toLongSafely(),
                    contextWindow = (contextWindow ?: 4096L).coerceIn(1, Int.MAX_VALUE.toLong()).toInt(),
                    modelName = modelName ?: "unknown",
                    architecture = architecture ?: "unknown",
                    fileType = fileType?.toIntSafely(),
                    quantization = fileType?.let(::quantizationName) ?: "unknown",
                    sizeLabel = sizeLabel ?: "unknown",
                    chatTemplate = chatTemplate,
                    parameterCount = totalParams,
                    blockCount = effectiveBlockCount,
                    embeddingLength = embeddingLength,
                    headCount = headCount,
                    headCountKv = headCountKv,
                    layerTensorBytes = layerBytes,
                    nonLayerBytes = nonLayerBytes,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readValue(stream: InputStream, type: Int): Any? = when (type) {
        0 -> readU8(stream)
        1 -> readI8(stream)
        2 -> readU16LE(stream)
        3 -> readI16LE(stream)
        4 -> readU32LE(stream)
        5 -> readI32LE(stream)
        6 -> readFloat32LE(stream)
        7 -> readU8(stream) != 0L
        8 -> readString(stream)
        9 -> readArray(stream)
        10 -> readU64LE(stream)
        11 -> readI64LE(stream)
        12 -> readFloat64LE(stream)
        else -> throw GgufParseException("Unsupported GGUF value type: $type")
    }

    /**
     * Reads and discards an array while validating its structure.
     *
     * Field order is element type (u32) *then* element count (u64), per the GGUF
     * spec. Reading them the other way round consumes the type plus the low half
     * of the count as a bogus 64-bit length, which overflows [MAX_ARRAY_ELEMENTS]
     * and rejects the file — and since every real model carries at least the
     * `tokenizer.ggml.tokens` array, that rejected every GGUF with a tokenizer.
     */
    private fun readArray(stream: InputStream): List<Any?>? {
        val elementType = readU32LE(stream).toInt()
        val count = readU64LE(stream)
        if (count > MAX_ARRAY_ELEMENTS) throw GgufParseException("GGUF array is too large")
        return List(count.toInt()) { readValue(stream, elementType) }
    }

    private fun readString(stream: InputStream): String? {
        val length = readU64LE(stream)
        if (length > MAX_STRING_BYTES) throw GgufParseException("GGUF string is too large")
        val bytes = ByteArray(length.toInt())
        readFully(stream, bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun readU8(stream: InputStream): Long {
        val b = stream.read()
        if (b < 0) throw GgufParseException("Unexpected EOF")
        return b.toLong()
    }

    private fun readI8(stream: InputStream): Long = readU8(stream).let { if (it >= 128) it - 256 else it }

    private fun readU16LE(stream: InputStream): Long = readByteBuffer(stream, 2).short.toInt().toLong() and 0xFFFFL

    private fun readI16LE(stream: InputStream): Long = readByteBuffer(stream, 2).short.toLong()

    private fun readI32LE(stream: InputStream): Long = readByteBuffer(stream, 4).int.toLong()

    private fun readFloat32LE(stream: InputStream): Double = readByteBuffer(stream, 4).float.toDouble()

    private fun readFloat64LE(stream: InputStream): Double = readByteBuffer(stream, 8).double

    private fun readU32LE(stream: InputStream): Long = readByteBuffer(stream, 4).int.toLong() and 0xFFFF_FFFFL

    private fun readU64LE(stream: InputStream): ULong {
        val buffer = ByteArray(8)
        readFully(stream, buffer)
        return ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).long.toULong()
    }

    private fun readI64LE(stream: InputStream): Long = readU64LE(stream).toLong()

    private fun readByteBuffer(stream: InputStream, size: Int): ByteBuffer {
        val bytes = ByteArray(size)
        readFully(stream, bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun readFully(stream: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = stream.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw GgufParseException("Unexpected EOF")
            offset += read
        }
    }

    private fun Any?.asLong(): Long? = when (this) {
        is Long -> this
        is ULong -> this.toLong()
        is Int -> this.toLong()
        is UInt -> this.toLong()
        is Double -> this.toLong()
        else -> null
    }

    private fun quantizationName(fileType: Long): String = when (fileType) {
        0L -> "F32"
        1L -> "F16"
        2L -> "Q4_0"
        3L -> "Q4_1"
        4L -> "Q4_1 (some F16)"
        5L -> "Q4_2"
        6L -> "Q4_3"
        7L -> "Q8_0"
        8L -> "Q5_0"
        9L -> "Q5_1"
        10L -> "Q2_K"
        11L -> "Q3_K_S"
        12L -> "Q3_K_M"
        13L -> "Q3_K_L"
        14L -> "Q4_K_S"
        15L -> "Q4_K_M"
        16L -> "Q5_K_S"
        17L -> "Q5_K_M"
        18L -> "Q6_K"
        19L -> "IQ2_XXS"
        20L -> "IQ2_XS"
        21L -> "IQ3_XXS"
        22L -> "IQ1_S"
        23L -> "IQ4_NL"
        24L -> "IQ3_S"
        25L -> "IQ2_S"
        26L -> "IQ4_XS"
        27L -> "I8"
        28L -> "IQ1_M"
        29L -> "BF16"
        30L -> "Q4_0_4_4"
        31L -> "Q4_0_4_8"
        32L -> "Q4_0_8_8"
        33L -> "TQ1_0"
        34L -> "TQ2_0"
        35L -> "MXFP4"
        else -> "UNKNOWN($fileType)"
    }

    private fun ULong.saturatingMultiply(other: ULong): ULong =
        if (other != 0uL && this > ULong.MAX_VALUE / other) ULong.MAX_VALUE else this * other

    private fun ULong.saturatingAdd(other: ULong): ULong =
        if (ULong.MAX_VALUE - this < other) ULong.MAX_VALUE else this + other

    private fun ULong.toLongSafely(): Long = minOf(this, Long.MAX_VALUE.toULong()).toLong()
    private fun Long.toIntSafely(): Int = coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    private class GgufParseException(message: String) : Exception(message)
}

data class GgufMetadata(
    val version: Int,
    val tensorCount: Long,
    val kvCount: Long,
    val contextWindow: Int,
    val modelName: String,
    val architecture: String,
    val fileType: Int?,
    val quantization: String,
    val sizeLabel: String,
    val parameterCount: Long,
    /** The model's own chat template, or null if it declares none. */
    val chatTemplate: String? = null,
    /** Number of transformer blocks/layers (`<arch>.block_count`), used by RFC-0105 layer-split planner. */
    val blockCount: Int = 0,
    /** Embedding dimension (`<arch>.embedding_length`), used to size KV cache per token. */
    val embeddingLength: Int = 0,
    /** Attention head count (`<arch>.attention.head_count`). */
    val headCount: Int = 0,
    /** KV head count (`<arch>.attention.head_count_kv`). */
    val headCountKv: Int = 0,
    /** Estimated byte footprint per layer `0 until blockCount`. */
    val layerTensorBytes: List<Long> = emptyList(),
    /** Estimated byte footprint of non-block tensors (token embeddings, output projection, norms). */
    val nonLayerBytes: Long = 0L,
) {
    /**
     * Estimates FP16 KV cache byte footprint across all layers for [contextTokens]:
     * `2 (K + V) * 2 bytes (FP16) * blockCount * kvDim * contextTokens`.
     */
    fun estimateKvCacheBytes(contextTokens: Int = contextWindow): Long {
        if (blockCount <= 0 || contextTokens <= 0) return 0L
        val headDim = if (headCount > 0 && embeddingLength > 0) embeddingLength / headCount else 128
        val kvHeads = if (headCountKv > 0) headCountKv else if (headCount > 0) headCount else 8
        val kvDim = (kvHeads * headDim).coerceAtLeast(64)
        return 4L * blockCount.toLong() * kvDim.toLong() * contextTokens.toLong()
    }
}
