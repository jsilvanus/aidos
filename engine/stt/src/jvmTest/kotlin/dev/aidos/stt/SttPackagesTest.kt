package dev.aidos.stt

import dev.aidos.kernel.ModelKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SttPackagesTest {
    private fun dir(): File = kotlin.io.path.createTempDirectory("stt-pkgs").toFile().also { it.deleteOnExit() }

    private fun install(root: File, id: String, vararg files: String): File {
        val d = File(root, id).also { it.mkdirs() }
        files.forEach { File(d, it).writeText(if (it == "tokenizer.json") TINY_TOKENIZER_JSON else "x") }
        return d
    }

    @Test
    fun recognisesAWhisperPackageAndNotOtherModels() {
        val root = dir()
        install(root, "whisper-tiny", "tokenizer.json", "encoder_model_quantized.onnx", "decoder_model_merged_quantized.onnx")
        install(root, "laya", "tokenizer.json", "rl_agent_config.json", "model.onnx")
        install(root, "half", "tokenizer.json", "encoder_model.onnx")
        val pk = SttPackages(root)
        assertTrue(pk.contains("whisper-tiny"))
        assertFalse(pk.contains("laya"))
        assertFalse(pk.contains("half"))
        assertFalse(pk.contains("../whisper-tiny"))
        val d = pk.descriptors().single()
        assertEquals("whisper-tiny", d.id)
        assertEquals(ModelKind.STT, d.kind)
        assertEquals("onnx", d.format)
        assertEquals(2L, d.sizeBytes)
    }

    @Test
    fun prefersTheMergedDecoderAndTheFp32PairWhenPresent() {
        val root = dir()
        val d = install(root, "w", "tokenizer.json", "encoder_model.onnx", "encoder_model_int8.onnx", "decoder_model.onnx", "decoder_model_merged_int8.onnx")
        assertEquals("encoder_model.onnx", WhisperPackage.encoderFile(d)!!.name)
        assertEquals("decoder_model_merged_int8.onnx", WhisperPackage.decoderFile(d)!!.name)
        val plain = install(root, "p", "tokenizer.json", "encoder_model_fp16.onnx", "decoder_model_fp16.onnx")
        assertEquals("decoder_model_fp16.onnx", WhisperPackage.decoderFile(plain)!!.name)
    }

    @Test
    fun readsMelBinsAndGenerationConfig() {
        val d = install(dir(), "w", "tokenizer.json", "encoder_model.onnx", "decoder_model.onnx")
        assertEquals(80, WhisperPackage.melBins(d))
        File(d, "preprocessor_config.json").writeText("""{"feature_size": 128}""")
        File(d, "generation_config.json").writeText("""{"suppress_tokens": [1, 2], "begin_suppress_tokens": [220]}""")
        assertEquals(128, WhisperPackage.melBins(d))
        val cfg = WhisperPackage.readGenerationConfig(d)
        assertEquals(setOf(1, 2), cfg.suppressTokens)
        assertEquals(setOf(220), cfg.beginSuppressTokens)
    }

    @Test
    fun deleteRemovesThePackageAndUnloadReportsUnknown() {
        val root = dir()
        install(root, "w", "tokenizer.json", "encoder_model.onnx", "decoder_model.onnx")
        val pk = SttPackages(root)
        assertFalse(pk.unload("w"))
        assertTrue(pk.delete("w"))
        assertFalse(File(root, "w").exists())
        assertNull(pk.digest("w"))
        assertFalse(pk.delete("w"))
    }
}
