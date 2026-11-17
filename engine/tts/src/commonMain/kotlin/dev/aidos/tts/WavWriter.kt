package dev.aidos.tts

/** Writes mono 16-bit PCM as a RIFF/WAVE file. Only the canonical 44-byte header is produced. */
object WavWriter {
    fun pcm16Wav(pcm16: ByteArray, sampleRate: Int): ByteArray {
        val dataSize = pcm16.size
        val out = ByteArray(44 + dataSize)
        fun ascii(offset: Int, s: String) = s.forEachIndexed { i, c -> out[offset + i] = c.code.toByte() }
        fun u32(offset: Int, v: Int) {
            out[offset] = v.toByte(); out[offset + 1] = (v shr 8).toByte()
            out[offset + 2] = (v shr 16).toByte(); out[offset + 3] = (v shr 24).toByte()
        }
        fun u16(offset: Int, v: Int) {
            out[offset] = v.toByte(); out[offset + 1] = (v shr 8).toByte()
        }
        ascii(0, "RIFF")
        u32(4, 36 + dataSize)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        u32(16, 16)            // PCM fmt chunk size
        u16(20, 1)             // PCM
        u16(22, 1)             // mono
        u32(24, sampleRate)
        u32(28, sampleRate * 2) // byte rate: mono, 16-bit
        u16(32, 2)             // block align
        u16(34, 16)            // bits per sample
        ascii(36, "data")
        u32(40, dataSize)
        pcm16.copyInto(out, 44)
        return out
    }

    /** Converts float samples in [-1, 1] to little-endian 16-bit PCM, clipping anything outside the range. */
    fun floatsToPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            out[2 * i] = v.toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }
}
