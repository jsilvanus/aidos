package dev.aidos.stt

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WhisperFeaturesTest {
    @Test
    fun fftMatchesNaiveDft() {
        val n = 400
        val rnd = java.util.Random(7)
        val re = DoubleArray(n) { rnd.nextGaussian() }
        val im = DoubleArray(n)
        val outRe = DoubleArray(n)
        val outIm = DoubleArray(n)
        Fft(n).transform(re, im, outRe, outIm)
        for (k in listOf(0, 1, 7, 100, 199, 200, 201, 399)) {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until n) { sr += re[t] * cos(2 * PI * k * t / n); si -= re[t] * sin(2 * PI * k * t / n) }
            assertTrue(abs(sr - outRe[k]) < 1e-9 && abs(si - outIm[k]) < 1e-9, "bin $k: ($sr, $si) vs (${outRe[k]}, ${outIm[k]})")
        }
    }

    /** Values from transformers' WhisperFeatureExtractor on the same file (see resources/jfk-logmel-golden.txt). */
    @Test
    fun logMelMatchesTransformersOnJfk() {
        val wav = javaClass.getResourceAsStream("/jfk.wav")!!.readBytes()
        val mel = WhisperFeatures(80).logMel(WavDecoder.decode16k(wav).samples)
        assertEquals(80 * WhisperFeatures.FRAMES, mel.size)
        val golden = javaClass.getResourceAsStream("/jfk-logmel-golden.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        assertTrue(golden.size >= 30)
        for (line in golden) {
            val (m, t, v) = line.split(' ')
            val got = mel[m.toInt() * WhisperFeatures.FRAMES + t.toInt()]
            assertTrue(abs(got - v.toFloat()) < 2e-3f, "mel $m frame $t: expected $v, got $got")
        }
    }

    @Test
    fun aToneLightsTheRightMelBin() {
        val tone = FloatArray(16_000 * 2) { (0.5 * sin(2 * PI * 1000.0 * it / 16_000)).toFloat() }
        val mel = WhisperFeatures(80).logMel(tone)
        val frame = 50
        val peak = (0 until 80).maxByOrNull { mel[it * WhisperFeatures.FRAMES + frame] }!!
        // 1 kHz is mel 15 of the 45.3 mel spanning 0 to 8 kHz on the Slaney scale: bin ~26.8 of 81 steps.
        assertTrue(peak in 25..28, "peak bin $peak")
    }
}
