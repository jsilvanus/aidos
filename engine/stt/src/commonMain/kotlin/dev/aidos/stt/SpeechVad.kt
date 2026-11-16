package dev.aidos.stt

/** A half-open sample range `[start, end)` of 16 kHz mono audio that contains speech. */
data class SpeechSegment(val start: Int, val end: Int)

/** Finds where speech is in 16 kHz mono audio. [WhisperSttModel] uses it to cut utterances at silences. */
interface SpeechVad {
    /** Speech ranges in order, non-overlapping, clamped to the audio. Empty for silence and noise. */
    fun segments(samples: FloatArray): List<SpeechSegment>
}

/**
 * The one backend-facing seam of [SileroVad]: a recurrent speech classifier fed fixed-size frames. Implementations
 * carry the recurrent state between [probability] calls; [reset] starts a new recording.
 */
interface VadGraph : AutoCloseable {
    fun reset()

    /** Probability (0..1) that the [SileroVad.FRAME_SAMPLES] samples in [frame] contain speech. */
    fun probability(frame: FloatArray): Float
}

/**
 * Tuning for [SileroVad]. The defaults suit dictation: a short pause is not an utterance boundary
 * (Finnish compounds and breaths), a click is not speech, and the edges keep some room so Whisper does not hear
 * a clipped first or last consonant.
 */
data class VadParams(
    /** Frame probability at or above which speech starts. */
    val threshold: Float = 0.5f,
    /** Probability below which speech may end (hysteresis, so a dip inside a word does not end it). */
    val negativeThreshold: Float = maxOf(threshold - 0.15f, 0.01f),
    val minSpeechMs: Int = 250,
    /** Silence shorter than this does not end a segment. */
    val minSilenceMs: Int = 500,
    /** Added before and after every segment (neighbours split the gap between them). */
    val speechPadMs: Int = 200,
)

/**
 * Silero VAD (v5 ONNX graph) speech segmentation: 512-sample frames at 16 kHz, a probability per frame, then the
 * hysteresis and padding rules of silero's `get_speech_timestamps`. Pure Kotlin around a [VadGraph], so it is
 * tested without the runtime.
 */
class SileroVad(private val graph: VadGraph, private val params: VadParams = VadParams()) : SpeechVad, AutoCloseable {
    @Synchronized
    override fun segments(samples: FloatArray): List<SpeechSegment> {
        if (samples.isEmpty()) return emptyList()
        graph.reset()
        val minSpeech = params.minSpeechMs * MS
        val minSilence = params.minSilenceMs * MS
        val pad = params.speechPadMs * MS

        val raw = ArrayList<SpeechSegment>()
        var start = -1
        var silenceFrom = -1
        val frame = FloatArray(FRAME_SAMPLES)
        var pos = 0
        while (pos < samples.size) {
            val n = minOf(FRAME_SAMPLES, samples.size - pos)
            java.util.Arrays.fill(frame, 0f)
            samples.copyInto(frame, 0, pos, pos + n)
            val p = graph.probability(frame)
            if (p >= params.threshold) {
                silenceFrom = -1
                if (start < 0) start = pos
            } else if (start >= 0 && p < params.negativeThreshold) {
                if (silenceFrom < 0) silenceFrom = pos
                if (pos + n - silenceFrom >= minSilence) {
                    if (silenceFrom - start >= minSpeech) raw += SpeechSegment(start, silenceFrom)
                    start = -1
                    silenceFrom = -1
                }
            }
            pos += FRAME_SAMPLES
        }
        if (start >= 0 && samples.size - start >= minSpeech) raw += SpeechSegment(start, samples.size)
        return pad(raw, pad, samples.size)
    }

    private fun pad(segments: List<SpeechSegment>, pad: Int, total: Int): List<SpeechSegment> {
        if (segments.isEmpty()) return segments
        val starts = IntArray(segments.size) { segments[it].start }
        val ends = IntArray(segments.size) { segments[it].end }
        for (i in segments.indices) {
            starts[i] = if (i == 0) maxOf(0, starts[i] - pad) else starts[i]
            ends[i] = if (i == segments.lastIndex) minOf(total, ends[i] + pad) else ends[i]
            if (i < segments.lastIndex) {
                val gap = segments[i + 1].start - ends[i]
                if (gap < 2 * pad) {
                    // Neighbours too close for full padding: split the gap between them.
                    ends[i] += gap / 2
                    starts[i + 1] = segments[i + 1].start - gap / 2
                } else {
                    ends[i] += pad
                    starts[i + 1] = segments[i + 1].start - pad
                }
            }
        }
        return segments.indices.map { SpeechSegment(starts[it], ends[it]) }
    }

    override fun close() = graph.close()

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 512
        private const val MS = SAMPLE_RATE / 1000
    }
}
