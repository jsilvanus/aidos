package dev.aidos.modelruntime

import java.io.File

/**
 * GGUF format detection and header parsing.
 *
 * GGUF is a self-describing binary format for quantized language models.
 * This loader validates file format and extracts metadata needed for model selection
 * (context window, model kind, quantization).
 *
 * See: https://github.com/ggerganov/ggml/blob/master/docs/gguf.md
 */
object GgufLoader {
    private const val GGUF_MAGIC = 0x46554747u // "GGUF" in little-endian

    /**
     * Validates that a file is a valid GGUF model.
     * Returns the model metadata if valid; null if file is not a GGUF or is corrupt.
     */
    fun loadMetadata(file: File): GgufMetadata? {
        if (!file.exists() || !file.isFile) return null
        if (file.length() < 28) return null // GGUF header is at least 28 bytes

        return try {
            file.inputStream().buffered().use { stream ->
                // Read magic number (4 bytes, little-endian)
                val magic = readU32LE(stream)
                if (magic != GGUF_MAGIC) return null

                // Read version (4 bytes, little-endian)
                val version = readU32LE(stream)
                if (version < 1u || version > 3u) return null // Support GGUF v1-v3

                // Read tensor count (8 bytes, little-endian)
                val tensorCount = readU64LE(stream)

                // Read metadata KV count (8 bytes, little-endian)
                val kvCount = readU64LE(stream)

                // Parse key-value metadata
                val metadata = mutableMapOf<String, String>()
                repeat(minOf(kvCount.toInt(), 10000)) { // Limit to 10k KV pairs
                    val key = readString(stream) ?: return null
                    val valType = readU32LE(stream) // value type enum
                    val value = readValue(stream, valType.toInt()) ?: return@repeat
                    metadata[key] = value
                }

                GgufMetadata(
                    version = version.toInt(),
                    tensorCount = tensorCount.toLong(),
                    kvCount = kvCount.toLong(),
                    contextWindow = metadata["general.context_length"]?.toIntOrNull() ?: 4096,
                    modelName = metadata["general.name"] ?: "unknown",
                    quantization = metadata["general.quantization_version"] ?: "unknown",
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readU32LE(stream: java.io.InputStream): UInt {
        val bytes = ByteArray(4)
        if (stream.read(bytes) != 4) throw Exception("EOF reading u32")
        return (bytes[0].toUInt() and 0xFFu) or
                ((bytes[1].toUInt() and 0xFFu) shl 8) or
                ((bytes[2].toUInt() and 0xFFu) shl 16) or
                ((bytes[3].toUInt() and 0xFFu) shl 24)
    }

    private fun readU64LE(stream: java.io.InputStream): ULong {
        val bytes = ByteArray(8)
        if (stream.read(bytes) != 8) throw Exception("EOF reading u64")
        return (bytes[0].toULong() and 0xFFu) or
                ((bytes[1].toULong() and 0xFFu) shl 8) or
                ((bytes[2].toULong() and 0xFFu) shl 16) or
                ((bytes[3].toULong() and 0xFFu) shl 24) or
                ((bytes[4].toULong() and 0xFFu) shl 32) or
                ((bytes[5].toULong() and 0xFFu) shl 40) or
                ((bytes[6].toULong() and 0xFFu) shl 48) or
                ((bytes[7].toULong() and 0xFFu) shl 56)
    }

    private fun readString(stream: java.io.InputStream): String? {
        val lenBytes = ByteArray(4)
        if (stream.read(lenBytes) != 4) return null
        val len = (lenBytes[0].toInt() and 0xFF) or
                ((lenBytes[1].toInt() and 0xFF) shl 8) or
                ((lenBytes[2].toInt() and 0xFF) shl 16) or
                ((lenBytes[3].toInt() and 0xFF) shl 24)
        if (len < 0 || len > 10000) return null // Sanity check
        val bytes = ByteArray(len)
        if (stream.read(bytes) != len) return null
        return String(bytes, Charsets.UTF_8)
    }

    private fun readValue(stream: java.io.InputStream, type: Int): String? {
        return try {
            when (type) {
                0 -> { // uint8
                    val b = ByteArray(1)
                    stream.read(b)
                    b[0].toString()
                }
                1 -> { // int8
                    val b = ByteArray(1)
                    stream.read(b)
                    b[0].toString()
                }
                2 -> { // uint16
                    val b = ByteArray(2)
                    stream.read(b)
                    ((b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)).toString()
                }
                3 -> { // int16
                    val b = ByteArray(2)
                    stream.read(b)
                    ((b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)).toString()
                }
                4 -> { // uint32
                    val b = ByteArray(4)
                    stream.read(b)
                    readU32LE(java.io.ByteArrayInputStream(b)).toString()
                }
                5 -> { // int32
                    val b = ByteArray(4)
                    stream.read(b)
                    readU32LE(java.io.ByteArrayInputStream(b)).toInt().toString()
                }
                6 -> { // float32
                    val b = ByteArray(4)
                    stream.read(b)
                    java.nio.ByteBuffer.wrap(b).float.toString()
                }
                7 -> { // bool
                    val b = ByteArray(1)
                    stream.read(b)
                    (b[0].toInt() != 0).toString()
                }
                8 -> { // string
                    readString(stream)
                }
                9 -> { // array - we skip this for simplicity
                    stream.skip(8) // skip count and type
                    null
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}

data class GgufMetadata(
    val version: Int,
    val tensorCount: Long,
    val kvCount: Long,
    val contextWindow: Int,
    val modelName: String,
    val quantization: String,
)
