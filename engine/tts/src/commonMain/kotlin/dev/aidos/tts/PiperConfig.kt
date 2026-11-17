package dev.aidos.tts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The part of a Piper voice's `<voice>.onnx.json` that the Engine needs: sample rate, the inference scales the
 * graph takes as its `scales` input, and the phoneme-to-id map. The map is the voice's own vocabulary, so a voice
 * never needs a hard-coded symbol table.
 */
class PiperConfig(
    val sampleRate: Int,
    val noiseScale: Float,
    val lengthScale: Float,
    val noiseW: Float,
    val numSpeakers: Int,
    val phonemeIdMap: Map<String, List<Long>>,
) {
    /** The ids for one phoneme symbol, or null when this voice does not know it. */
    fun idsFor(symbol: String): List<Long>? = phonemeIdMap[symbol]

    companion object {
        /** Parses a Piper config; fails with [IllegalArgumentException] when a required key is missing. */
        fun parse(text: String): PiperConfig {
            val root = Json.parseToJsonElement(text).jsonObject
            val audio = root.obj("audio")
            val inference = root.obj("inference")
            val map = root["phoneme_id_map"]?.jsonObject
                ?: throw IllegalArgumentException("Piper config has no phoneme_id_map")
            val ids = map.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.int.toLong() } }
            require(ids.isNotEmpty()) { "Piper phoneme_id_map is empty" }
            return PiperConfig(
                sampleRate = audio?.get("sample_rate")?.jsonPrimitive?.int
                    ?: throw IllegalArgumentException("Piper config has no audio.sample_rate"),
                noiseScale = inference?.float("noise_scale", 0.667f) ?: 0.667f,
                lengthScale = inference?.float("length_scale", 1.0f) ?: 1.0f,
                noiseW = inference?.float("noise_w", 0.8f) ?: 0.8f,
                numSpeakers = root["num_speakers"]?.jsonPrimitive?.int ?: 1,
                phonemeIdMap = ids,
            )
        }

        private fun JsonObject.obj(key: String): JsonObject? = this[key]?.jsonObject

        private fun JsonObject.float(key: String, default: Float): Float =
            this[key]?.jsonPrimitive?.double?.toFloat() ?: default
    }
}

/**
 * Turns phoneme symbols into the id sequence a Piper VITS graph takes. Piper interleaves a pad id after every
 * phoneme and wraps the sequence in BOS and EOS markers; symbols the voice does not know are skipped, which is how
 * Piper itself treats them.
 */
object PiperPhonemeEncoder {
    const val BOS = "^"
    const val EOS = "\$"
    const val PAD = "_"

    fun encode(phonemes: List<String>, config: PiperConfig): LongArray {
        val pad = config.idsFor(PAD)?.firstOrNull() ?: 0L
        val out = ArrayList<Long>()
        config.idsFor(BOS)?.let { out += it }
        for (p in phonemes) {
            val ids = config.idsFor(p) ?: continue
            out += ids
            out += pad
        }
        config.idsFor(EOS)?.let { out += it }
        return out.toLongArray()
    }
}
