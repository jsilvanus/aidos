package dev.aidos.stt

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Whisper model package is the directory layout of a Hugging Face `onnx-community/whisper-*` (or an
 * Optimum export, e.g. of a fine-tune) repo, graphs and tokenizer side by side:
 *
 *     <modelId>/
 *         encoder_model.onnx                 (or encoder_model_quantized / _int8 / _fp16 ...)
 *         decoder_model_merged.onnx          (or decoder_model.onnx, slower: no KV cache)
 *         tokenizer.json
 *         generation_config.json             (suppress_tokens; optional)
 *         preprocessor_config.json           (feature_size 80 or 128; optional)
 *
 * When several precisions sit in the directory the plain fp32 pair wins, then the alphabetically first,
 * so installing one variant is enough and a mixed directory still loads.
 */
object WhisperPackage {
    const val TOKENIZER = "tokenizer.json"

    fun encoderFile(dir: File): File? = pick(dir, "encoder_model")

    fun decoderFile(dir: File): File? = pick(dir, "decoder_model_merged") ?: pick(dir, "decoder_model")

    fun isPackage(dir: File): Boolean =
        dir.isDirectory && File(dir, TOKENIZER).isFile && encoderFile(dir) != null && decoderFile(dir) != null

    fun sizeBytes(dir: File): Long = (encoderFile(dir)?.length() ?: 0) + (decoderFile(dir)?.length() ?: 0)

    private fun pick(dir: File, stem: String): File? {
        val all = dir.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }.orEmpty()
        return all.firstOrNull { it.name == "$stem.onnx" }
            ?: all.filter { it.name.startsWith("${stem}_") && it.name != "decoder_model_merged.onnx" && !(stem == "decoder_model" && it.name.startsWith("decoder_model_merged")) }
                .minByOrNull { it.name }
    }

    fun readGenerationConfig(dir: File): WhisperGenerationConfig {
        val gen = json(File(dir, "generation_config.json"))
        val model = json(File(dir, "config.json"))
        fun ids(key: String) = gen?.get(key)?.jsonArray?.map { it.jsonPrimitive.int }?.toSet().orEmpty()
        return WhisperGenerationConfig(
            suppressTokens = ids("suppress_tokens"),
            beginSuppressTokens = ids("begin_suppress_tokens"),
            maxTargetPositions = model?.get("max_target_positions")?.jsonPrimitive?.int ?: 448,
        )
    }

    /** 80 mel bins, or 128 for large-v3 and its fine-tunes. */
    fun melBins(dir: File): Int =
        json(File(dir, "preprocessor_config.json"))?.get("feature_size")?.jsonPrimitive?.int
            ?: json(File(dir, "config.json"))?.get("num_mel_bins")?.jsonPrimitive?.int
            ?: 80

    private fun json(f: File): JsonObject? =
        if (f.isFile) runCatching { Json.parseToJsonElement(f.readText()).jsonObject }.getOrNull() else null

    /** Opens the package at [dir] on ONNX Runtime. The caller owns (and must close) the returned model. */
    fun open(modelId: String, dir: File): WhisperSttModel {
        require(isPackage(dir)) { "not a Whisper model package: ${dir.path} (needs encoder_model*.onnx, decoder_model*.onnx, $TOKENIZER)" }
        val tokenizer = WhisperTokenizer.fromJson(File(dir, TOKENIZER).readText())
        val graphs = OrtWhisperGraphs(encoderFile(dir)!!, decoderFile(dir)!!, melBins(dir))
        return try {
            WhisperSttModel(modelId, tokenizer, graphs, readGenerationConfig(dir))
        } catch (e: Throwable) {
            graphs.close()
            throw e
        }
    }
}
