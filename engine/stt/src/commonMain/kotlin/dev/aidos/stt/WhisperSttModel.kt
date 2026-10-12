package dev.aidos.stt

import kotlin.math.max
import kotlin.math.sqrt

/** The two ONNX graphs behind [WhisperSttModel]; the only backend-facing seam, so the loop is testable without a runtime. */
interface WhisperGraphs : AutoCloseable {
    val melBins: Int

    /** Encodes one 30 s window: [WhisperFeatures.logMel] output, `melBins * 3000` floats. */
    fun encode(features: FloatArray): WhisperEncoded
}

interface WhisperEncoded : AutoCloseable {
    /** A fresh autoregressive decoding over this encoding. */
    fun decoding(): WhisperDecoding
}

interface WhisperDecoding : AutoCloseable {
    /** Appends [tokens] to the sequence so far and returns the next-token logits (vocabulary-sized). */
    fun step(tokens: IntArray): FloatArray
}

class WhisperGenerationConfig(
    /** Ids never emitted (`suppress_tokens` in generation_config.json). */
    val suppressTokens: Set<Int> = emptySet(),
    /** Ids not allowed as the first text token (`begin_suppress_tokens`). */
    val beginSuppressTokens: Set<Int> = emptySet(),
    /** Decoder positions (`max_target_positions`); 448 for every released Whisper. */
    val maxTargetPositions: Int = 448,
)

/**
 * Whisper transcription: 30 s windows to log-mel, encoder once per window, greedy decoder with the
 * forced `<|startoftranscript|><|lang|><|transcribe|><|notimestamps|>` prefix. Audio over 30 s is cut at
 * the quietest point near the end of each window; there are no timestamps and no overlap, so a word
 * straddling a cut can still be split (long dictation should be sent in utterance-sized pieces).
 */
class WhisperSttModel(
    override val modelId: String,
    private val tokenizer: WhisperTokenizer,
    private val graphs: WhisperGraphs,
    private val config: WhisperGenerationConfig = WhisperGenerationConfig(),
) : SttModel {
    private val features = WhisperFeatures(graphs.melBins)

    /** Every id from `<|endoftext|>` up except EOT itself: markers, languages, tasks, timestamps. */
    private val alwaysSuppressedFrom = tokenizer.eot + 1

    override fun transcribe(request: SttRequest): SttResult {
        val audio = WavDecoder.decode16k(request.audio)
        val samples = audio.samples
        if (samples.isEmpty()) throw SttRequestException("audio contains no samples")
        val explicit = request.language?.takeIf { it.isNotBlank() }?.let { tokenizer.languageToken(it) }
        val promptTokens = request.prompt?.trim()?.takeIf { it.isNotEmpty() }?.let { tokenizer.encode(" $it") }.orEmpty()

        var language = explicit
        val parts = ArrayList<String>()
        for ((from, to) in windows(samples)) {
            if (to - from < WhisperFeatures.SAMPLE_RATE / 10 || rms(samples, from, to) < SILENCE_RMS) continue
            graphs.encode(features.logMel(samples, from, to)).use { encoded ->
                val lang = language ?: detectLanguage(encoded).also { language = it }
                val text = decodeWindow(encoded, lang, promptTokens)
                if (text.isNotEmpty()) parts += text
            }
        }
        val code = tokenizer.languages.entries.firstOrNull { it.value == language }?.key ?: ""
        return SttResult(parts.joinToString(" "), code, audio.seconds)
    }

    private fun detectLanguage(encoded: WhisperEncoded): Int =
        encoded.decoding().use { dec ->
            val logits = dec.step(intArrayOf(tokenizer.sot))
            tokenizer.languages.values.maxByOrNull { logits[it] } ?: error("tokenizer has no language tokens")
        }

    private fun decodeWindow(encoded: WhisperEncoded, language: Int, promptTokens: List<Int>): String {
        val forced = intArrayOf(tokenizer.sot, language, tokenizer.transcribe, tokenizer.noTimestamps)
        val room = config.maxTargetPositions / 2 - 1
        val prompt = tokenizer.startOfPrev?.let { sop ->
            if (promptTokens.isEmpty()) IntArray(0) else intArrayOf(sop) + promptTokens.takeLast(room - 1)
        } ?: IntArray(0)
        val prefix = prompt + forced
        val maxNew = minOf(MAX_NEW_TOKENS, config.maxTargetPositions - prefix.size)
        val out = ArrayList<Int>()
        encoded.decoding().use { dec ->
            var logits = dec.step(prefix)
            for (i in 0 until maxNew) {
                val next = pick(logits, first = i == 0)
                if (next == tokenizer.eot) break
                out += next
                loopStart(out)?.let { cut -> while (out.size > cut) out.removeAt(out.size - 1); return@use }
                logits = dec.step(intArrayOf(next))
            }
        }
        return tokenizer.decode(out).trim()
    }

    /** Argmax over the logits with Whisper's suppression rules applied. */
    private fun pick(logits: FloatArray, first: Boolean): Int {
        var best = -1
        var bestV = Float.NEGATIVE_INFINITY
        for (id in logits.indices) {
            if (id >= alwaysSuppressedFrom || id in config.suppressTokens) continue
            if (first && id in config.beginSuppressTokens) continue
            if (logits[id] > bestV) { bestV = logits[id]; best = id }
        }
        // Everything else suppressed (only possible with a broken head): end the segment instead of looping.
        return if (best < 0) tokenizer.eot else if (logits[tokenizer.eot] > bestV) tokenizer.eot else best
    }

    /**
     * Whisper occasionally falls into a loop ("and the and the and the ..."). When the tail is one pattern
     * repeated many times, returns the length to cut back to (one copy of the pattern kept), else null.
     */
    private fun loopStart(t: List<Int>): Int? {
        val n = t.size
        for (p in 1..16) {
            val reps = if (p <= 2) 8 else if (p <= 5) 5 else 3
            if (n < p * reps) continue
            if ((1..p * (reps - 1)).all { t[n - it] == t[n - it - p] }) return n - p * (reps - 1)
        }
        return null
    }

    /** `[from, to)` sample windows of at most 30 s; a cut goes to the quietest 100 ms in the last 5 s of a full window. */
    private fun windows(s: FloatArray): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var from = 0
        val max = WhisperFeatures.CHUNK_SAMPLES
        while (from < s.size) {
            if (s.size - from <= max) { out += from to s.size; break }
            val lo = from + max - 5 * WhisperFeatures.SAMPLE_RATE
            val step = WhisperFeatures.SAMPLE_RATE / 10
            var cut = from + max
            var quiet = Float.MAX_VALUE
            var p = lo
            while (p + step <= from + max) {
                val e = rms(s, p, p + step)
                if (e < quiet) { quiet = e; cut = p + step / 2 }
                p += step
            }
            out += from to cut
            from = cut
        }
        return out
    }

    private fun rms(s: FloatArray, from: Int, to: Int): Float {
        var acc = 0.0
        for (i in from until to) acc += s[i] * s[i]
        return sqrt(acc / max(1, to - from)).toFloat()
    }

    override fun close() = graphs.close()

    private companion object {
        /** Below this a window is treated as silence; Whisper invents text ("Thank you.") for pure silence. */
        const val SILENCE_RMS = 1e-4f
        const val MAX_NEW_TOKENS = 224
    }

}
