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
    private const val MAX_KV_ENTRIES = 100_000L
    private const val MAX_STRING_BYTES = 1_048_576L
    private const val MAX_ARRAY_ELEMENTS = 1_000_000L

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
                var contextWindow: Long? = null
                var fileType: Long? = null
                var sizeLabel: String? = null

                repeat(kvCount.toInt()) {
                    val key = readString(stream) ?: throw GgufParseException("Invalid metadata key")
                    val type = readU32LE(stream).toInt()
                    val value = readValue(stream, type)
                    when (key) {
                        "general.architecture" -> architecture = value as? String
                        "general.name" -> modelName = value as? String
                        "general.context_length" -> contextWindow = value.asLong()
                        "general.file_type" -> fileType = value.asLong()
                        "general.size_label" -> sizeLabel = value as? String
                    }
                }

                // Tensor descriptors follow the KV metadata. Summing tensor element
                // counts gives a useful parameter-count value without touching tensor data.
                var parameterCount: ULong = 0u
                repeat(tensorCount.toInt()) {
                    readString(stream) ?: throw GgufParseException("Invalid tensor name")
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
                    parameterCount = parameterCount.toLongSafely(),
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

    /** Reads and discards an array while validating its structure. */
    private fun readArray(stream: InputStream): List<Any?>? {
        val count = readU64LE(stream)
        val elementType = readU32LE(stream).toInt()
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
)
