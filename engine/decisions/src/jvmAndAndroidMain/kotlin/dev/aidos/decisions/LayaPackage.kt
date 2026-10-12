package dev.aidos.decisions

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Laya model package is a directory holding the exported graph and the files the Python runtime
 * reads beside it:
 *
 *     <modelId>/
 *         model.onnx            (any single *.onnx; produced by scripts/laya/export_onnx.py)
 *         tokenizer.json        (from the checkpoint's tokenizer/ folder)
 *         rl_agent_config.json  (max_len, head_max_len, temperature, ...)
 *
 * Single-file catalog installs cannot express this yet; installing a package is a manual or
 * scripted step (docs/system-one.md).
 */
object LayaPackage {
    const val TOKENIZER = "tokenizer.json"
    const val CONFIG = "rl_agent_config.json"

    fun onnxFile(dir: File): File? =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }?.sortedBy { it.name }?.firstOrNull()

    fun isPackage(dir: File): Boolean =
        dir.isDirectory && File(dir, TOKENIZER).isFile && File(dir, CONFIG).isFile && onnxFile(dir) != null

    fun readConfig(file: File): LayaConfig {
        val o = Json.parseToJsonElement(file.readText()).jsonObject
        val temps = o["temperature"]?.jsonArray?.map { it.jsonPrimitive.double }?.takeIf { it.size == 3 }
        val byOptions = (o["temperature_by_options"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.double } ?: emptyMap()
        return LayaConfig(
            maxLen = o["max_len"]?.jsonPrimitive?.int ?: 512,
            headMaxLen = o["head_max_len"]?.jsonPrimitive?.int ?: 192,
            temperatures = LayaTemperatures(temps?.toDoubleArray() ?: doubleArrayOf(1.0, 1.0, 1.0), byOptions),
        )
    }

    /** Opens the package at [dir] on ONNX Runtime. The caller owns (and must close) the returned model. */
    fun open(modelId: String, dir: File): LayaDecisionModel {
        require(isPackage(dir)) { "not a Laya model package: ${dir.path} (needs *.onnx, $TOKENIZER, $CONFIG)" }
        val tokenizer = GemmaBpeTokenizer.fromJson(File(dir, TOKENIZER).readText())
        val runner = OrtDecisionRunner(onnxFile(dir)!!)
        return try {
            LayaDecisionModel(modelId, tokenizer, runner, readConfig(File(dir, CONFIG)))
        } catch (e: Throwable) {
            runner.close()
            throw e
        }
    }
}
