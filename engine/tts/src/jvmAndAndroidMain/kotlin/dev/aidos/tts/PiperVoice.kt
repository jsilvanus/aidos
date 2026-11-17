package dev.aidos.tts

/** The graph call a voice needs, so the text path can be tested without a native runtime. [OrtPiperGraph] implements it. */
interface SpeechGraph : AutoCloseable {
    val takesSpeaker: Boolean
    fun run(ids: LongArray, scales: FloatArray, speaker: Long): FloatArray
}

/**
 * A loaded Piper voice: text in, mono 16-bit PCM out. Long input is split at sentence ends and each piece is spoken
 * on its own, with a short pause between pieces, so one graph call stays a sentence long. Speed is applied through
 * the graph's length scale, as Piper does, so pitch is kept.
 */
class PiperVoice(
    override val modelId: String,
    private val config: PiperConfig,
    private val phonemizer: Phonemizer,
    private val graph: SpeechGraph,
) : TtsModel {
    override fun synthesize(request: SpeechRequest): SpeechResult {
        val text = SpeechText.validate(request.input)
        if (!(request.speed >= MIN_SPEED && request.speed <= MAX_SPEED)) {
            throw TtsRequestException("speed must be between $MIN_SPEED and $MAX_SPEED")
        }
        val speaker = speakerFor(request.voice)
        val scales = floatArrayOf(config.noiseScale, (config.lengthScale / request.speed).toFloat(), config.noiseW)
        val silence = FloatArray(config.sampleRate * PAUSE_MS / 1000)

        val samples = ArrayList<Float>()
        var spoken = 0
        for (piece in SpeechText.split(text)) {
            val ids = PiperPhonemeEncoder.encode(phonemizer.phonemize(piece), config)
            // Only the BOS and EOS markers: nothing speakable in this piece (digits, punctuation).
            if (ids.size <= 2) continue
            if (spoken > 0) silence.forEach { samples += it }
            graph.run(ids, scales, speaker).forEach { samples += it }
            spoken++
        }
        if (spoken == 0) throw TtsRequestException("input has no speakable text")
        val pcm = WavWriter.floatsToPcm16(samples.toFloatArray())
        return SpeechResult(pcm, config.sampleRate)
    }

    private fun speakerFor(voice: String?): Long {
        if (voice == null) return 0L
        return voice.toLongOrNull()?.takeIf { it in 0 until config.numSpeakers }
            ?: throw TtsRequestException("unknown voice $voice; this voice takes ${config.numSpeakers} speaker(s), by number")
    }

    override fun close() = graph.close()

    companion object {
        const val MIN_SPEED = 0.25
        const val MAX_SPEED = 4.0
        const val PAUSE_MS = 150
    }
}
