package dev.aidos.stt

/** Builds in-memory WAV files for tests. */
object Wavs {
    fun pcm16(samples: ShortArray, rate: Int, channels: Int = 1): ByteArray {
        val data = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s -> data[2 * i] = s.toByte(); data[2 * i + 1] = (s.toInt() shr 8).toByte() }
        fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        return "RIFF".encodeToByteArray() + le32(36 + data.size) + "WAVE".encodeToByteArray() +
            "fmt ".encodeToByteArray() + le32(16) + le16(1) + le16(channels) + le32(rate) +
            le32(rate * channels * 2) + le16(channels * 2) + le16(16) +
            "data".encodeToByteArray() + le32(data.size) + data
    }
}
