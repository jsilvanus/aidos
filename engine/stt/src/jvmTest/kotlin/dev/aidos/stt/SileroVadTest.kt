package dev.aidos.stt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Segmentation rules against a scripted per-frame probability, so no runtime or weights are needed. */
class SileroVadTest {
    private val frame = SileroVad.FRAME_SAMPLES

    /** Returns [probs] one per frame in order; counts resets. */
    private class Scripted(val probs: List<Float>) : VadGraph {
        var resets = 0
        var calls = 0
        override fun reset() { resets++; calls = 0 }
        override fun close() = Unit
        override fun probability(frame: FloatArray): Float = probs.getOrElse(calls++) { 0f }
    }

    private fun frames(vararg runs: Pair<Float, Int>): List<Float> = runs.flatMap { (p, n) -> List(n) { p } }

    private fun vad(graph: VadGraph, pad: Int = 0, minSilence: Int = 100, minSpeech: Int = 100) =
        SileroVad(graph, VadParams(minSpeechMs = minSpeech, minSilenceMs = minSilence, speechPadMs = pad))

    // 31.25 frames per second of 512 samples: 16 frames = 8192 samples = 512 ms.
    private fun audio(nFrames: Int) = FloatArray(nFrames * frame)

    @Test
    fun silenceHasNoSegments() {
        assertEquals(emptyList(), vad(Scripted(frames(0.01f to 50))).segments(audio(50)))
    }

    @Test
    fun emptyAudioHasNoSegmentsAndNeverTouchesTheGraph() {
        val g = Scripted(emptyList())
        assertEquals(emptyList(), vad(g).segments(FloatArray(0)))
        assertEquals(0, g.resets)
    }

    @Test
    fun aSpeechRunBecomesASegmentAtFrameBoundaries() {
        val g = Scripted(frames(0.0f to 10, 0.9f to 20, 0.0f to 30))
        assertEquals(listOf(SpeechSegment(10 * frame, 30 * frame)), vad(g).segments(audio(60)))
        assertEquals(1, g.resets, "each recording starts from a fresh recurrent state")
    }

    @Test
    fun aClickShorterThanMinSpeechIsDropped() {
        val g = Scripted(frames(0.0f to 10, 0.9f to 2, 0.0f to 30))
        assertEquals(emptyList(), vad(g, minSpeech = 250).segments(audio(42)))
    }

    @Test
    fun aShortPauseDoesNotSplitButALongOneDoes() {
        // 400 ms minimum silence = 12.5 frames. A 5-frame dip stays inside one segment; a 20-frame gap splits.
        val g = Scripted(frames(0.9f to 10, 0.0f to 5, 0.9f to 10, 0.0f to 20, 0.9f to 10, 0.0f to 20))
        val segs = vad(g, minSilence = 400).segments(audio(75))
        assertEquals(listOf(SpeechSegment(0, 25 * frame), SpeechSegment(45 * frame, 55 * frame)), segs)
    }

    @Test
    fun aProbabilityBetweenTheThresholdsKeepsSpeechGoing() {
        // 0.4 is below the 0.5 start threshold but above the 0.35 end threshold: hysteresis, no silence counted.
        val g = Scripted(frames(0.9f to 10, 0.4f to 40, 0.9f to 10, 0.0f to 30))
        assertEquals(listOf(SpeechSegment(0, 60 * frame)), vad(g, minSilence = 300).segments(audio(90)))
    }

    @Test
    fun speechRunningToTheEndIsClosedAtTheEndOfTheAudio() {
        val g = Scripted(frames(0.0f to 5, 0.9f to 20))
        assertEquals(listOf(SpeechSegment(5 * frame, 25 * frame)), vad(g).segments(audio(25)))
    }

    @Test
    fun aTrailingPartialFrameIsZeroPaddedAndCovered() {
        val g = Scripted(frames(0.9f to 4))
        val samples = FloatArray(3 * frame + 100)
        assertEquals(listOf(SpeechSegment(0, samples.size)), vad(g).segments(samples))
        assertEquals(4, g.calls)
    }

    @Test
    fun paddingIsClampedToTheAudioAndSharedBetweenCloseNeighbours() {
        val pad = 200 * 16 // 200 ms in samples
        val framesFor = pad / frame // 6.25: use frame counts that make the gap < 2 * pad
        val g = Scripted(frames(0.9f to 10, 0.0f to 8, 0.9f to 10))
        val segs = vad(g, pad = 200, minSilence = 100).segments(audio(28))
        // 8 silent frames = 4096 samples is under 2 * pad (6400): the gap is split, no overlap.
        assertEquals(2, segs.size)
        assertEquals(0, segs[0].start)
        assertEquals(28 * frame, segs[1].end)
        assertTrue(segs[0].end <= segs[1].start, "padded neighbours must not overlap: $segs")
        assertEquals(10 * frame + 4096 / 2, segs[0].end)
        assertTrue(framesFor > 0)
    }

    @Test
    fun farApartSegmentsGetFullPadding() {
        val g = Scripted(frames(0.0f to 40, 0.9f to 10, 0.0f to 60, 0.9f to 10, 0.0f to 40))
        val pad = 200 * 16
        val segs = vad(g, pad = 200, minSilence = 100).segments(audio(160))
        assertEquals(SpeechSegment(40 * frame - pad, 50 * frame + pad), segs[0])
        assertEquals(SpeechSegment(110 * frame - pad, 120 * frame + pad), segs[1])
    }
}
