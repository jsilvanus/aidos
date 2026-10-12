package dev.aidos.stt

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Whisper's log-mel front end, matching openai-whisper / transformers' WhisperFeatureExtractor:
 * 400-point Hann window, hop 160, reflect-padded centred STFT, power spectrum, Slaney mel filters,
 * log10 clamped to 8 below the maximum, scaled to (x + 4) / 4. Output is [melBins][FRAMES] flattened
 * row-major (the encoder's `input_features` layout with batch 1).
 */
class WhisperFeatures(val melBins: Int = 80) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val N_FFT = 400
        const val HOP = 160
        const val CHUNK_SECONDS = 30
        const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_SECONDS
        const val FRAMES = CHUNK_SAMPLES / HOP // 3000
        private const val BINS = N_FFT / 2 + 1
    }

    private val window = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2.0 * PI * it / N_FFT)).toFloat() }
    private val filters: Array<FloatArray> = melFilters()
    private val fft = Fft(N_FFT)

    /** [samples] (at most [CHUNK_SAMPLES]) are zero-padded to 30 s, as the encoder expects. */
    fun logMel(samples: FloatArray, from: Int = 0, to: Int = samples.size): FloatArray {
        val n = min(to - from, CHUNK_SAMPLES)
        // Reflect-pad N_FFT/2 on both sides of the 30 s buffer (torch.stft center=True).
        val pad = N_FFT / 2
        val total = CHUNK_SAMPLES
        fun at(i: Int): Float {
            // i indexes the unpadded 30 s buffer; reflect outside [0, total).
            var j = i
            if (j < 0) j = -j
            if (j >= total) j = 2 * (total - 1) - j
            return if (j < n) samples[from + j] else 0f
        }
        val mel = FloatArray(melBins * FRAMES)
        val re = DoubleArray(N_FFT)
        val im = DoubleArray(N_FFT)
        val outRe = DoubleArray(N_FFT)
        val outIm = DoubleArray(N_FFT)
        val power = FloatArray(BINS)
        var maxLog = -Float.MAX_VALUE
        for (t in 0 until FRAMES) {
            val start = t * HOP - pad
            for (k in 0 until N_FFT) { re[k] = (at(start + k) * window[k]).toDouble(); im[k] = 0.0 }
            fft.transform(re, im, outRe, outIm)
            for (k in 0 until BINS) power[k] = (outRe[k] * outRe[k] + outIm[k] * outIm[k]).toFloat()
            for (m in 0 until melBins) {
                val f = filters[m]
                var acc = 0f
                for (k in 0 until BINS) acc += f[k] * power[k]
                val l = log10(max(acc, 1e-10f))
                mel[m * FRAMES + t] = l
                if (l > maxLog) maxLog = l
            }
        }
        val floor = maxLog - 8f
        for (i in mel.indices) mel[i] = (max(mel[i], floor) + 4f) / 4f
        return mel
    }

    private fun melFilters(): Array<FloatArray> {
        fun hzToMel(f: Double) = if (f < 1000.0) f / (200.0 / 3) else 15.0 + ln(f / 1000.0) / (ln(6.4) / 27.0)
        fun melToHz(m: Double) = if (m < 15.0) m * (200.0 / 3) else 1000.0 * kotlin.math.exp((ln(6.4) / 27.0) * (m - 15.0))
        val lo = hzToMel(0.0)
        val hi = hzToMel(SAMPLE_RATE / 2.0)
        val pts = DoubleArray(melBins + 2) { melToHz(lo + (hi - lo) * it / (melBins + 1)) }
        val freqs = DoubleArray(BINS) { it * SAMPLE_RATE.toDouble() / N_FFT }
        return Array(melBins) { m ->
            val enorm = 2.0 / (pts[m + 2] - pts[m])
            FloatArray(BINS) { k ->
                val lower = (freqs[k] - pts[m]) / (pts[m + 1] - pts[m])
                val upper = (pts[m + 2] - freqs[k]) / (pts[m + 2] - pts[m + 1])
                (max(0.0, min(lower, upper)) * enorm).toFloat()
            }
        }
    }
}

/** Mixed-radix complex FFT for any length (400 = 2^4 * 5^2 here); O(N * sum of prime factors). */
internal class Fft(private val n: Int) {
    private val cos = DoubleArray(n) { cos(2.0 * PI * it / n) }
    private val sin = DoubleArray(n) { -sin(2.0 * PI * it / n) }
    private val tmpRe = DoubleArray(n)
    private val tmpIm = DoubleArray(n)

    fun transform(inRe: DoubleArray, inIm: DoubleArray, outRe: DoubleArray, outIm: DoubleArray) {
        rec(inRe, inIm, 0, 1, n, outRe, outIm, 0)
    }

    private fun rec(inRe: DoubleArray, inIm: DoubleArray, inOff: Int, stride: Int, len: Int, outRe: DoubleArray, outIm: DoubleArray, outOff: Int) {
        if (len == 1) { outRe[outOff] = inRe[inOff]; outIm[outOff] = inIm[inOff]; return }
        var p = 2
        while (len % p != 0) p++
        val m = len / p
        for (r in 0 until p) rec(inRe, inIm, inOff + r * stride, stride * p, m, outRe, outIm, outOff + r * m)
        val step = n / len
        for (k in 0 until m) {
            for (q in 0 until p) {
                val idx = k + q * m
                var sr = 0.0
                var si = 0.0
                for (r in 0 until p) {
                    val w = (r * idx * step) % n
                    val yr = outRe[outOff + r * m + k]
                    val yi = outIm[outOff + r * m + k]
                    sr += yr * cos[w] - yi * sin[w]
                    si += yr * sin[w] + yi * cos[w]
                }
                tmpRe[idx] = sr
                tmpIm[idx] = si
            }
            // Results for this k land in tmp at k + q*m; they cannot overwrite out yet (other k still read it).
            // Copy back only after all k are done.
            if (k == m - 1) {
                for (i in 0 until len) { outRe[outOff + i] = tmpRe[i]; outIm[outOff + i] = tmpIm[i] }
            }
        }
    }
}
