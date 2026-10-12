package dev.aidos.vision

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A vision model package is a directory holding SmolVLM's three exported graphs and the files that
 * describe them, installed flat under their repo base names:
 *
 *     <modelId>/
 *         vision_encoder<suffix>.onnx
 *         embed_tokens<suffix>.onnx
 *         decoder_model_merged<suffix>.onnx
 *         tokenizer.json  config.json  processor_config.json  generation_config.json
 *
 * `<suffix>` is the quantization the exporter chose (`_uint8` is what Aidos installs: ~260 MB in all
 * against 1 GB for fp32). The three graphs must share one suffix; a directory mixing variants is
 * not a package, so a half-finished or hand-assembled install is refused at load, not mid-answer.
 */
object VisionPackage {
    const val TOKENIZER = "tokenizer.json"
    const val CONFIG = "config.json"
    const val PROCESSOR_CONFIG = "processor_config.json"
    const val GENERATION_CONFIG = "generation_config.json"

    private const val ENCODER = "vision_encoder"
    private const val EMBED = "embed_tokens"
    private const val DECODER = "decoder_model_merged"

    /** The three graphs of the one variant [dir] holds, or null when it does not hold a complete set. */
    fun graphs(dir: File): Graphs? {
        val names = dir.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }?.map { it.name }.orEmpty()
        fun suffixes(stem: String) = names.filter { it.startsWith(stem) }.map { it.removePrefix(stem).removeSuffix(".onnx") }.toSet()
        val common = suffixes(ENCODER) intersect suffixes(EMBED) intersect suffixes(DECODER)
        // Prefer uint8 (what the installer fetches and the loaded-model test exercises), then the shortest name.
        // Not "_int8": that export uses signed ConvInteger, which ONNX Runtime's CPU provider has no kernel for.
        val suffix = common.sortedWith(compareBy({ it != "_uint8" }, { it != "_quantized" }, { it.length }, { it })).firstOrNull() ?: return null
        return Graphs(File(dir, "$ENCODER$suffix.onnx"), File(dir, "$EMBED$suffix.onnx"), File(dir, "$DECODER$suffix.onnx"))
    }

    class Graphs(val encoder: File, val embed: File, val decoder: File) {
        val sizeBytes: Long get() = encoder.length() + embed.length() + decoder.length()
    }

    fun isPackage(dir: File): Boolean =
        dir.isDirectory && graphs(dir) != null &&
            File(dir, TOKENIZER).isFile && File(dir, CONFIG).isFile && File(dir, PROCESSOR_CONFIG).isFile

    fun readConfig(dir: File): SmolVlmConfig {
        val config = Json.parseToJsonElement(File(dir, CONFIG).readText()).jsonObject
        require(config["model_type"]?.jsonPrimitive?.content == "idefics3") {
            "${dir.name}: model_type is not idefics3; only SmolVLM-style vision models are supported"
        }
        val text = config.getValue("text_config").jsonObject
        val processor = Json.parseToJsonElement(File(dir, PROCESSOR_CONFIG).readText()).jsonObject
        val eos = buildSet {
            File(dir, GENERATION_CONFIG).takeIf { it.isFile }?.let { f ->
                val g = Json.parseToJsonElement(f.readText()).jsonObject["eos_token_id"]
                when (g) {
                    null -> Unit
                    is kotlinx.serialization.json.JsonArray -> g.forEach { add(it.jsonPrimitive.int) }
                    else -> add(g.jsonPrimitive.int)
                }
            }
        }
        return SmolVlmConfig(
            hiddenSize = text.getValue("hidden_size").jsonPrimitive.int,
            imageTokenId = config.getValue("image_token_id").jsonPrimitive.int,
            imageSeqLen = processor.getValue("image_seq_len").jsonPrimitive.int,
            eosTokenIds = eos,
            // The graph accepts 8k positions; a phone's KV cache for that is the real limit, so cap the window.
            contextWindow = minOf(text["max_position_embeddings"]?.jsonPrimitive?.int ?: 4096, 4096),
        )
    }

    /** Opens the package at [dir] on ONNX Runtime. The caller owns (and must close) the returned model. */
    fun open(modelId: String, dir: File, images: ImageDecoder): SmolVlmModel {
        require(isPackage(dir)) { "not a vision model package: ${dir.path} (needs the three SmolVLM graphs, $TOKENIZER, $CONFIG, $PROCESSOR_CONFIG)" }
        val tokenizer = ByteLevelBpeTokenizer.fromJson(File(dir, TOKENIZER).readText())
        val cfg = readConfig(dir)
        val eos = cfg.eosTokenIds + listOfNotNull(tokenizer.tokenId(SmolVlmModel.END_OF_UTTERANCE))
        val graphs = graphs(dir)!!
        val runner = OrtVisionRunner(graphs.encoder, graphs.embed, graphs.decoder, cfg.hiddenSize, cfg.imageSeqLen)
        return try {
            SmolVlmModel(modelId, tokenizer, runner, images, cfg.copy(eosTokenIds = eos))
        } catch (e: Throwable) {
            runner.close()
            throw e
        }
    }
}
