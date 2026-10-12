package dev.aidos.stt

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Mono float samples in [-1, 1] at [sampleRate]. */
class PcmAudio(val samples: FloatArray, val sampleRate: Int) {
    val seconds: Double get() = samples.size.toDouble() / sampleRate
}

/**
 * RIFF/WAVE to the 16 kHz mono floats Whisper wants. Takes PCM 8/16/24/32-bit and IEEE float, any
 * channel count (averaged) and any sample rate (resampled). Compressed WAV (ADPCM, a-law) and other
 * containers are refused with a message the caller can show, rather than decoded into noise.
 */
object WavDecoder {
    const val TARGET_RATE = 16_000

    fun decode16k(bytes: ByteArray): PcmAudio = resample(decode(bytes), TARGET_RATE)

    fun decode(bytes: ByteArray): PcmAudio {
        if (bytes.size < 12 || tag(bytes, 0) != "RIFF" || tag(bytes, 8) != "WAVE") {
            throw SttRequestException("audio must be a RIFF/WAV file (16-bit PCM recommended)")
        }
        var fmt: IntArray? = null // [format, channels, rate, bits]
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = tag(bytes, pos)
            val declared = le32(bytes, pos + 4).toLong() and 0xFFFFFFFFL
            val bodyStart = pos + 8
            // A streamed WAV may declare 0 or 0xFFFFFFFF for data; take what is there.
            val size = if (id == "data" && (declared == 0L || declared > bytes.size - bodyStart)) (bytes.size - bodyStart).toLong() else declared
            if (id == "fmt " && bodyStart + 16 <= bytes.size) {
                var format = le16(bytes, bodyStart)
                // WAVE_FORMAT_EXTENSIBLE carries the real format in the sub-format GUID's first two bytes.
                if (format == 0xFFFE && size >= 26 && bodyStart + 26 <= bytes.size) format = le16(bytes, bodyStart + 24)
                fmt = intArrayOf(format, le16(bytes, bodyStart + 2), le32(bytes, bodyStart + 4), le16(bytes, bodyStart + 14))
            } else if (id == "data") {
                val f = fmt ?: throw SttRequestException("WAV has no fmt chunk before its data")
                val end = min(bytes.size.toLong(), bodyStart + size).toInt()
                return PcmAudio(toMono(bytes, bodyStart, end, f[0], f[1], f[3]), f[2])
            }
            pos = (bodyStart + size + (size and 1)).let { if (it > Int.MAX_VALUE) bytes.size else it.toInt() }
        }
        throw SttRequestException("WAV has no data chunk")
    }

    private fun toMono(b: ByteArray, from: Int, to: Int, format: Int, channels: Int, bits: Int): FloatArray {
        if (channels < 1) throw SttRequestException("WAV declares $channels channels")
        val bytesPer = bits / 8
        val isFloat = format == 3
        if (!(format == 1 || isFloat) || bytesPer !in 1..4 || (isFloat && bits != 32 && bits != 64)) {
            throw SttRequestException("unsupported WAV encoding (format $format, $bits-bit); send 16-bit PCM")
        }
        val frame = bytesPer * channels
        val frames = (to - from) / frame
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += sample(b, from + i * frame + c * bytesPer, bytesPer, isFloat)
            out[i] = sum / channels
        }
        return out
    }

    private fun sample(b: ByteArray, p: Int, bytes: Int, isFloat: Boolean): Float = when {
        isFloat && bytes == 4 -> Float.fromBits(le32(b, p))
        isFloat -> Double.fromBits((le32(b, p).toLong() and 0xFFFFFFFFL) or (le32(b, p + 4).toLong() shl 32)).toFloat()
        bytes == 1 -> ((b[p].toInt() and 0xFF) - 128) / 128f
        bytes == 2 -> ((b[p].toInt() and 0xFF) or (b[p + 1].toInt() shl 8)) / 32768f
        bytes == 3 -> (((b[p].toInt() and 0xFF) shl 8) or ((b[p + 1].toInt() and 0xFF) shl 16) or (b[p + 2].toInt() shl 24)) / 2147483648f
        else -> le32(b, p) / 2147483648f
    }

    /**
     * Area-average resampling: each output sample is the mean of the (linearly interpolated) input over
     * its own window, which low-passes when downsampling (48 kHz phone audio) without a filter design.
     */
    fun resample(audio: PcmAudio, rate: Int): PcmAudio {
        if (audio.sampleRate == rate) return audio
        val src = audio.samples
        val ratio = audio.sampleRate.toDouble() / rate
        val n = floor(src.size / ratio).toInt()
        val out = FloatArray(n)
        for (i in 0 until n) {
            val a = i * ratio
            val b = a + ratio
            if (ratio <= 1.0) {
                // Upsampling: plain linear interpolation.
                val i0 = floor(a).toInt()
                val frac = (a - i0).toFloat()
                val s0 = src[min(i0, src.size - 1)]
                val s1 = src[min(i0 + 1, src.size - 1)]
                out[i] = s0 + (s1 - s0) * frac
            } else {
                var acc = 0.0
                var p = a
                while (p < b) {
                    val i0 = floor(p).toInt()
                    val next = min(b, (i0 + 1).toDouble())
                    acc += src[min(i0, src.size - 1)] * (next - p)
                    p = next
                }
                out[i] = (acc / (b - a)).toFloat()
            }
        }
        return PcmAudio(out, rate)
    }

    private fun tag(b: ByteArray, p: Int) = String(CharArray(4) { (b[p + it].toInt() and 0xFF).toChar() })
    private fun le16(b: ByteArray, p: Int) = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, p: Int) = le16(b, p) or (le16(b, p + 2) shl 16)

}
